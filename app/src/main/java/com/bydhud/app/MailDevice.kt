package com.bydhud.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidMailSql(ctx: Context): MailSql {
    private val db = SQLiteDatabase.openOrCreateDatabase(File(ctx.noBackupFilesDir,"developer-mail.db"),null)
    override fun exec(sql: String,vararg args: String) {
        if(args.isEmpty()) db.execSQL(sql) else db.execSQL(sql,args)
    }
    override fun rows(sql: String,vararg args: String) = db.rawQuery(sql,args).use { c ->
        buildList { while(c.moveToNext()) add(List(c.columnCount) { c.getString(it) }) }
    }
    override fun <T> atomic(work: ()->T): T {
        db.beginTransaction()
        try { return work().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
}

internal object MailVault {
    private const val ALIAS="bydhud.mail.v1"
    private const val FLAGS=Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    fun newSecret() = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) },FLAGS)!!
    private fun key(create: Boolean): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS,null) as? SecretKey)?.let { return it }
        check(create) { "mail credential key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun seal(text: String): String {
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE,key(true))
        return Base64.encodeToString(c.iv+c.doFinal(text.toByteArray(Charsets.UTF_8)),FLAGS)
    }
    fun open(value: String): String {
        val bytes=Base64.decode(value,FLAGS)
        val c=Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return String(c.doFinal(bytes,12,bytes.size-12),Charsets.UTF_8)
    }
}

internal object MailVehicle {
    private val props=listOf("persist.sys.cloud.last_vin","persist.sys.dms.config.vin","sys.dms.config.vin","sys.virtual.vin")
    @JvmStatic fun allows(property: String) = property in props
    fun read(ctx: Context): String {
        val evidence=mutableListOf<String>()
        fun accept(text: String): String? = text.trim().takeIf { MailStore.validText(it,256) && '\n' !in it }
        for (property in props) {
            try {
                val p=ProcessBuilder("/system/bin/getprop",property).start()
                try {
                    if (!p.waitFor(1,TimeUnit.SECONDS)) { evidence.add("app.timeout"); continue }
                    if (p.exitValue()==0) {
                        accept(p.inputStream.bufferedReader().use { it.readText() })?.let {
                            AppEventLogger.event(ctx,"developer-mail DiLink source=app property="+property+" available")
                            return it
                        }
                        val stderr=p.errorStream.bufferedReader().use { it.readText() }
                        evidence.add(if(stderr.contains("denied",true)) "app.denied" else "app.empty")
                    } else {
                        val denied=p.errorStream.bufferedReader().use { it.readText().contains("denied",true) }
                        evidence.add("app."+if(denied) "denied" else "exit_${p.exitValue()}")
                    }
                } finally { p.destroy() }
            } catch(e: Exception) { evidence.add("app.${e.javaClass.simpleName}") }
        }
        LocalAdbBridge.openConfigurationExport(ctx,4000).use { session ->
            for (property in props) {
                val result=session.readMailProperty(property)
                if (result.success()) {
                    accept(result.output)?.let {
                        AppEventLogger.event(ctx,"developer-mail DiLink source=authorized-shell property="+property+" available")
                        return it
                    }
                    evidence.add("shell.empty")
                } else {
                    evidence.add("shell.${result.status}.${result.exitCode}")
                    if (result.status=="denied" || result.status=="unavailable") break
                }
            }
        }
        val reason=when {
            "shell.empty" in evidence -> "dilink_empty"
            evidence.any { "denied" in it } -> "dilink_denied"
            else -> "dilink_unavailable"
        }
        throw MailFailure("DiLink/"+evidence.distinct().joinToString(","),reason)
    }
}
