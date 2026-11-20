package com.bydhud.app

import java.util.UUID

internal interface MailSql {
    fun exec(sql: String, vararg args: String)
    fun rows(sql: String, vararg args: String): List<List<String>>
    fun <T> atomic(work: () -> T): T
}

internal data class MailLetter(val id: String, val subject: String, val body: String, val date: Long,
    val sent: Boolean, val read: Boolean, val trash: Boolean, val announcement: Boolean, val delivery: String)
internal data class MailAccount(val id: String, val credential: String, val binding: String,
    val name: String, val description: String, val active: Boolean, val news: Boolean,
    val banner: Boolean, val revision: Long, val epoch: Long, val cursor: String, val sendAfter: Long,
    val registerAfter: Long, val registerAttempted: Boolean)
internal data class MailControl(val sequence: String, val active: Boolean, val news: Boolean)
internal data class MailFolderOp(val sequence: String, val localId: String, val serverId: String, val action: String)
internal data class MailIncoming(val id: String, val date: Long, val subject: String, val body: String, val news: Boolean)

private fun Boolean.bit() = if (this) "1" else "0"

// One database per installation, which owns exactly one mailbox. The account primary key enforces that invariant.
internal class MailStore(private val db: MailSql) {
    init {
        db.atomic {
            db.exec("""CREATE TABLE IF NOT EXISTS account (singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                uuid TEXT UNIQUE, credential TEXT, binding TEXT, name TEXT, description TEXT, active INTEGER,
                news INTEGER DEFAULT 0, banner INTEGER DEFAULT 0, rev INTEGER DEFAULT 0, epoch INTEGER DEFAULT 0,
                cursor TEXT DEFAULT '', sendAfter INTEGER DEFAULT 0, registerAfter INTEGER DEFAULT 0, registerAttempted INTEGER DEFAULT 0)""")
            db.exec("""CREATE TABLE IF NOT EXISTS letter (id TEXT PRIMARY KEY, serverId TEXT UNIQUE, subject TEXT,
                body TEXT, stamp INTEGER, sent INTEGER, seen INTEGER, trash INTEGER DEFAULT 0, news INTEGER,
                delivery TEXT, ack INTEGER DEFAULT 0, notify INTEGER DEFAULT 0, purged INTEGER DEFAULT 0)""")
            db.exec("CREATE TABLE IF NOT EXISTS control (seq INTEGER PRIMARY KEY AUTOINCREMENT, active INTEGER, news INTEGER)")
            db.exec("CREATE TABLE IF NOT EXISTS folder (seq INTEGER PRIMARY KEY, id TEXT, action TEXT)")
        }
    }

    @Synchronized fun account(): MailAccount? = db.rows("SELECT uuid,credential,binding,name,description,active,news,banner,rev,epoch,cursor,sendAfter,registerAfter,registerAttempted FROM account").firstOrNull()?.let {
        MailAccount(it[0],it[1],it[2],it[3],it[4],it[5]=="1",it[6]=="1",it[7]=="1",it[8].toLong(),
            it[9].toLong(),it[10],it[11].toLong(),it[12].toLong(),it[13]=="1")
    }

    @Synchronized fun register(id: String, credential: String, binding: String, name: String, description: String) {
        db.exec("INSERT INTO account(singleton,uuid,credential,binding,name,description,active) VALUES(1,?,?,?,?,?,1)",
            id,credential,binding,name,description)
    }

    @Synchronized fun consent(active: Boolean, news: Boolean? = null) = db.atomic {
        val a = account() ?: return@atomic
        // Keep the saved choice while consent disables the effective server subscription.
        val preference = if (active) news ?: a.news else a.news
        if (a.active == active && a.news == preference) return@atomic
        db.exec("UPDATE account SET active=?, news=?, epoch=epoch+1",active.bit(),preference.bit())
        if (!active) db.exec("DELETE FROM control WHERE active=1")
        db.exec("INSERT INTO control(active,news) VALUES(?,?)",active.bit(),(active && preference).bit())
        if (!active) {
            db.exec("UPDATE letter SET delivery='held' WHERE delivery='queued'")
            db.exec("UPDATE letter SET notify=0")
        }
    }
    @Synchronized fun banner(value: Boolean) = db.atomic {
        db.exec("UPDATE account SET banner=?",value.bit())
        if (!value) db.exec("UPDATE letter SET notify=0")
    }
    @Synchronized fun firstControl() = db.rows("SELECT seq,active,news FROM control ORDER BY seq LIMIT 1").firstOrNull()?.let {
        MailControl(it[0],it[1]=="1",it[2]=="1")
    }
    @Synchronized fun registering() { db.exec("UPDATE account SET registerAttempted=1") }
    @Synchronized fun remoteState(revision: Long) {
        db.exec("UPDATE account SET rev=?, binding='',registerAfter=0", revision.toString())
    }
    @Synchronized fun completeControl(control: MailControl, revision: Long) = db.atomic {
        if (revision > 0) remoteState(revision)
        db.exec("DELETE FROM control WHERE seq=?", control.sequence)
        if (!control.active) db.exec("UPDATE letter SET ack=0,notify=0")
    }
    // Adopt a remote withdrawal after state reconciliation. Never silently manufacture a grant.
    @Synchronized fun reconcile(revision: Long, active: Boolean, news: Boolean) = db.atomic {
        remoteState(revision)
        if (firstControl() == null) {
            db.exec("UPDATE account SET active=?,news=CASE WHEN ?='1' THEN ? ELSE news END,epoch=epoch+1",
                active.bit(),active.bit(),news.bit())
            if (!active) db.exec("UPDATE letter SET delivery=CASE WHEN delivery='queued' THEN 'held' ELSE delivery END,ack=0,notify=0")
        }
    }
    private fun current(a: MailAccount): Boolean {
        val now = account() ?: return false
        return now.active && now.epoch==a.epoch && now.revision==a.revision && firstControl()==null
    }
    @Synchronized fun valid(a: MailAccount) = current(a)

    @Synchronized fun acceptPage(a: MailAccount, messages: List<MailIncoming>, next: String): Int = db.atomic {
        if (!current(a)) return@atomic 0
        val notify = account()!!.banner.bit()
        var count = 0
        for (m in messages) {
            if (db.rows("SELECT 1 FROM letter WHERE serverId=?", m.id).isNotEmpty()) continue
            db.exec("""INSERT INTO letter(id,serverId,subject,body,stamp,sent,seen,news,delivery,ack,notify)
                VALUES(?,?,?,?,?,0,0,?,'received',1,?)""", m.id,m.id,m.subject,m.body,m.date.toString(),m.news.bit(),notify)
            count++
        }
        if (next != a.cursor) db.exec("UPDATE account SET cursor=?", next)
        count
    }
    @Synchronized fun ackIds() = db.rows("SELECT serverId FROM letter WHERE ack=1 ORDER BY rowid LIMIT 100").map { it[0] }
    @Synchronized fun acknowledged(ids: List<String>) = db.atomic {
        for (id in ids) db.exec("UPDATE letter SET ack=0 WHERE serverId=?",id)
    }
    @Synchronized fun pendingBanner(): List<MailLetter> =
        if (account()?.let { current(it) && it.banner } == true)
            letters("notify=1 AND seen=0 AND trash=0 AND purged=0") else emptyList()

    @Synchronized fun bannerShown(ids: List<String>) = db.atomic {
        for (id in ids) db.exec("UPDATE letter SET notify=0 WHERE id=?",id)
    }
    @Synchronized fun enqueue(subject: String, body: String, now: Long): Boolean {
        val a=account() ?: return false
        if (!a.active || a.sendAfter>now || !validText(subject,200) || !validText(body,4000)) return false
        db.exec("""INSERT INTO letter(id,subject,body,stamp,sent,seen,news,delivery)
            VALUES(?,?,?,?,1,1,0,'queued')""",UUID.randomUUID().toString(),subject.trim(),body.trim(),now.toString())
        return true
    }
    private fun letters(where: String) = db.rows("""SELECT id,subject,body,stamp,sent,seen,trash,news,delivery
        FROM letter WHERE $where ORDER BY stamp,rowid""").map {
        MailLetter(it[0],it[1],it[2],it[3].toLong(),it[4]=="1",it[5]=="1",it[6]=="1",it[7]=="1",it[8])
    }
    @Synchronized fun visible() = letters("purged=0")
    @Synchronized fun outgoing() = letters("delivery='queued' AND trash=0 AND purged=0").firstOrNull()
    @Synchronized fun receipt(a: MailAccount, id: String, serverId: String, date: Long) = db.atomic {
        if (!current(a)) return@atomic
        db.exec("UPDATE letter SET serverId=?,stamp=?,delivery='sent' WHERE id=?",serverId,date.toString(),id)
        val recent = db.rows("SELECT stamp FROM letter WHERE delivery='sent' AND stamp>? ORDER BY stamp DESC LIMIT 10",
            (date-86_400_000L).toString())
        val daily = if (recent.size==10) recent.last()[0].toLong()+86_400_000L else 0L
        cooldown(false,maxOf(date+30_000L,daily))
    }
    @Synchronized fun reject(id: String) { db.exec("UPDATE letter SET delivery='held' WHERE id=?",id) }
    @Synchronized fun cooldown(register: Boolean, until: Long) {
        db.exec(if (register) "UPDATE account SET registerAfter=?" else "UPDATE account SET sendAfter=?",until.toString())
    }
    @Synchronized fun setRead(ids: Set<String>, read: Boolean) = db.atomic {
        for (id in ids) db.exec("UPDATE letter SET seen=?,notify=0 WHERE id=? AND sent=0",read.bit(),id)
    }
    @Synchronized fun folder(ids: Set<String>, action: String) = db.atomic {
        for (id in ids) {
            val row = db.rows("SELECT trash,purged,delivery FROM letter WHERE id=?",id).firstOrNull() ?: continue
            if (row[1]=="1" || (action=="trash") == (row[0]=="1")) continue
            when(action) {
                "trash" -> db.exec("UPDATE letter SET trash=1,notify=0 WHERE id=?",id)
                "restore" -> db.exec("UPDATE letter SET trash=0 WHERE id=?",id)
                "purge" -> db.exec("UPDATE letter SET purged=1,body='',subject='',notify=0 WHERE id=?",id)
                else -> error("unknown folder operation")
            }
            db.exec("INSERT INTO folder(id,action) VALUES(?,?)",id,action)
        }
    }
    @Synchronized fun folderBatch(): List<MailFolderOp> {
        val rows=db.rows("""SELECT f.seq,f.id,l.serverId,f.action FROM folder f JOIN letter l ON l.id=f.id
            WHERE l.serverId IS NOT NULL ORDER BY f.seq LIMIT 100""")
        return rows.takeWhile { it[3]==rows.first()[3] }.map { MailFolderOp(it[0],it[1],it[2],it[3]) }
    }
    @Synchronized fun finishFolder(ops: List<MailFolderOp>) = db.atomic {
        for (op in ops) db.exec("DELETE FROM folder WHERE seq=?",op.sequence)
    }
    companion object {
        fun validText(text: String, max: Int) = text.isNotBlank() && '\u0000' !in text && text.codePointCount(0,text.length)<=max
    }
}
