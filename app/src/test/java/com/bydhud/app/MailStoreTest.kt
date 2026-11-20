package com.bydhud.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Executes the same SQL/transactions against an on-disk SQLite database on the host. */
internal class JdbcMailSql(file: File): MailSql, Closeable {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite:"+file.absolutePath)
    override fun close() = db.close()
    override fun exec(sql: String, vararg args: String) {
        db.prepareStatement(sql).use { s ->
            args.forEachIndexed { i,v -> s.setString(i+1,v) }
            s.executeUpdate()
        }
    }
    override fun rows(sql: String, vararg args: String) = db.prepareStatement(sql).use { s ->
        args.forEachIndexed { i,v -> s.setString(i+1,v) }
        s.executeQuery().use { r -> buildList { while(r.next()) add(List(r.metaData.columnCount) { r.getString(it+1) }) } }
    }
    override fun <T> atomic(work: () -> T): T {
        db.autoCommit=false
        try { return work().also { db.commit() } }
        catch(t: Throwable) { db.rollback(); throw t }
        finally { db.autoCommit=true }
    }
}

class MailStoreTest {
    @get:Rule val temp=TemporaryFolder()
    private fun newBox(s: MailStore) {
        s.register(UUID.randomUUID().toString(),"sealed-token","sealed-binding","driver","")
        s.remoteState(1)
    }
    @Test fun pageAndPendingAckSurviveReopenWithoutDuplicateLetterOrBanner() {
        val file=temp.newFile()
        val page=listOf(MailIncoming("57",500,"Subject","Body",false))
        JdbcMailSql(file).use { db ->
            val s=MailStore(db); newBox(s); s.banner(true)
            assertEquals(1,s.acceptPage(s.account()!!,page,"opaque"))
            val banner=s.pendingBanner().single()
            assertEquals("Subject",banner.subject)
            assertEquals("Body",banner.body)
        }
        JdbcMailSql(file).use { db ->
            val s=MailStore(db)
            assertEquals("opaque",s.account()!!.cursor)
            assertEquals(0,s.acceptPage(s.account()!!,page,"opaque"))
            assertEquals(listOf("57"),s.ackIds())
            assertEquals("57",s.pendingBanner().single().id)
            s.bannerShown(listOf("57"))
            assertTrue(s.pendingBanner().isEmpty())
            assertFalse(s.visible().single().read)
            s.acknowledged(s.ackIds())
            s.setRead(setOf("57"),true)
            assertTrue(s.visible().single().read)
            s.setRead(setOf("57"),false)
            s.folder(setOf("57"),"trash")
            s.folder(setOf("57"),"restore")
            assertFalse(s.visible().single().trash)
            s.folder(setOf("57"),"trash")
            s.folder(setOf("57"),"purge")
            assertTrue(s.visible().isEmpty())
            assertEquals(0,s.acceptPage(s.account()!!,page,"opaque"))
        }
    }
    @Test fun showingOneBatchDoesNotConsumeNewMailAndDismissalDoesNotMarkRead() {
        val file=temp.newFile()
        JdbcMailSql(file).use { db ->
            val s=MailStore(db); newBox(s); s.banner(true)
            s.acceptPage(s.account()!!,listOf(MailIncoming("1",1,"first","body",false)),"a")
            val shown=s.pendingBanner().map { it.id }
            s.acceptPage(s.account()!!,listOf(MailIncoming("2",2,"second","body",false)),"b")
            s.bannerShown(shown)
        }
        JdbcMailSql(file).use { db ->
            val s=MailStore(db)
            assertEquals(listOf("2"),s.pendingBanner().map { it.id })
            assertTrue(s.visible().none { it.read })
            s.consent(false)
            assertTrue(s.pendingBanner().isEmpty())
            assertEquals(2,s.visible().size)
        }
    }
    @Test fun aNewWithdrawalSupersedesUnsentGrantsButCannotBeErasedByALateGrantReply() {
        JdbcMailSql(temp.newFile()).use { db ->
            val s=MailStore(db)
            newBox(s)
            s.consent(true,true)
            val oldGrant=s.firstControl()!!
            s.consent(false)
            val withdrawal=s.firstControl()!!
            assertFalse(withdrawal.active)
            assertFalse(withdrawal.news)
            s.completeControl(oldGrant,2)
            assertEquals(withdrawal,s.firstControl())
            assertFalse(s.account()!!.active)
            assertTrue(s.account()!!.news)
        }
    }
    @Test fun announcementChoiceSurvivesWithdrawalReconciliationReopenAndRenewal() {
        for (choice in listOf(false,true)) {
            val file=temp.newFile()
            JdbcMailSql(file).use { db ->
                val s=MailStore(db); newBox(s)
                s.consent(true,choice)
                s.firstControl()?.let { s.completeControl(it,2) }
                s.consent(false)
                assertFalse(s.enqueue("subject","body",100))
            }
            JdbcMailSql(file).use { db ->
                val s=MailStore(db)
                val withdrawal=s.firstControl()!!
                assertFalse(withdrawal.active)
                assertFalse(withdrawal.news)
                s.completeControl(withdrawal,3)
                s.reconcile(3,false,false)
                assertFalse(s.account()!!.active)
                assertEquals(choice,s.account()!!.news)
                s.consent(true)
            }
            JdbcMailSql(file).use { db ->
                val s=MailStore(db)
                val renewal=s.firstControl()!!
                assertTrue(renewal.active)
                assertEquals(choice,renewal.news)
                s.completeControl(renewal,4)
                s.reconcile(4,true,choice)
                assertTrue(s.account()!!.active)
                assertEquals(choice,s.account()!!.news)
                s.consent(true,!choice)
                assertEquals(!choice,s.account()!!.news)
                assertEquals(!choice,s.firstControl()!!.news)
            }
        }
    }
    @Test fun bulkRestorePreservesOriginalFoldersAndReadStateAfterReopen() {
        val file=temp.newFile()
        val original=JdbcMailSql(file).use { db ->
            val s=MailStore(db); newBox(s)
            s.acceptPage(s.account()!!,listOf(
                MailIncoming("1",100,"Unread","First",false),
                MailIncoming("2",200,"Read","Second",false)),"page")
            s.setRead(setOf("2"),true)
            assertTrue(s.enqueue("Outgoing","Third",300))
            val before=s.visible()
            s.folder(before.map { it.id }.toSet(),"trash")
            assertTrue(s.visible().all { it.trash })
            before
        }
        JdbcMailSql(file).use { db ->
            val s=MailStore(db)
            s.folder(original.map { it.id }.toSet(),"restore")
        }
        JdbcMailSql(file).use { db -> assertEquals(original,MailStore(db).visible()) }
    }
    @Test fun outboundKeepsSameRequestAcrossReopenAndWithdrawalFencesPreviousWork() {
        val file=temp.newFile()
        val request=JdbcMailSql(file).use { db ->
            val s=MailStore(db); newBox(s)
            assertTrue(s.enqueue("subject","body",100))
            s.outgoing()!!
        }
        JdbcMailSql(file).use { db ->
            val s=MailStore(db)
            assertEquals(request,s.outgoing())
            val before=s.account()!!
            s.consent(false)
            s.consent(true)
            assertEquals(0,s.acceptPage(before,listOf(MailIncoming("1",1,"old","old",false)),"stale"))
            assertNull(s.outgoing())
        }
        JdbcMailSql(file).use { db ->
            val s=MailStore(db)
            val revoke=s.firstControl()!!
            assertFalse(revoke.active)
            s.completeControl(revoke,2)
            val renew=s.firstControl()!!
            assertTrue(renew.active)
            s.completeControl(renew,3)
            assertTrue(s.account()!!.active)
            assertEquals("held",s.visible().single().delivery)
            assertEquals(request.body,s.visible().single().body)
            assertNull(s.outgoing())
        }
    }
}
