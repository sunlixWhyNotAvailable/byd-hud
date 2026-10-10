package com.bydhud.app

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class NavigatorApkValidationTest {
    @Test fun actualApkIntegrityAndIdentityGatePublicationWithoutLosingReadyFile() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.cacheDir, "apk-validation").apply { mkdirs() }
        val valid = File(dir, "valid.apk").apply {
            writeBytes(NavigatorApkValidationTest::class.java.getResourceAsStream("/apk/valid.apk")!!.use { it.readBytes() })
        }
        val brokenSignature = File(dir, "broken-signature.apk")
        // Repack signed entries with changed content: catalog digest matches, cryptographic signature does not.
        ZipFile(valid).use { source -> ZipOutputStream(brokenSignature.outputStream()).use { target ->
            source.entries().asSequence().forEach { entry ->
                target.putNextEntry(ZipEntry(entry.name))
                val bytes = source.getInputStream(entry).use { it.readBytes() }
                target.write(if (entry.name == "resources.arsc") bytes + byteArrayOf(1) else bytes)
                target.closeEntry()
            }
        } }
        val garbage = File(dir, "garbage.apk").apply { writeText("not an APK") }
        val ready = File(dir, "ready.apk").apply { writeText("previous validated APK") }
        val previous = ready.readBytes()
        val part = File(dir, "ready.part")
        fun asset(file: File, pkg: String = "com.waze", signer: String = SIGNER,
                  hash: String = sha(file)) = NavigatorAssetManager.Asset(
            "fixture", "Fixture", "Fixture", "1.0", 1L, pkg, signer, hash,
            "", "fixture.apk", NavigatorPatchStore.Profile.WAZE)
        val validator = NavigatorAssetManager::class.java.getDeclaredMethod("validate",
            Context::class.java, NavigatorAssetManager.Asset::class.java, File::class.java)
            .apply { isAccessible = true }
        suspend fun prepare(file: File, expected: NavigatorAssetManager.Asset) =
            prepareUpdateApk(file, part, ready) { candidate ->
                // PackageManager is an Android boundary; manifest and signatures are parsed by production below.
                shadowOf(context.packageManager).setPackageArchiveInfo(candidate.absolutePath,
                    PackageInfo().apply { packageName = "com.waze"; versionName = "1.0"; longVersionCode = 1L })
                try { validator.invoke(null, context, expected, candidate) }
                catch (error: InvocationTargetException) { throw error.targetException }
                1L
            }
        for ((file, expected) in listOf(
            garbage to asset(garbage),
            valid to asset(valid, hash = "0".repeat(64)),
            valid to asset(valid, pkg = "foreign.app"),
            valid to asset(valid, signer = "0".repeat(64)),
            brokenSignature to asset(brokenSignature)
        )) {
            val failure = runCatching { prepare(file, expected) }.exceptionOrNull()
            assertNotNull("invalid APK must not reach publication: ${file.name}", failure)
            assertArrayEquals(previous, ready.readBytes())
            assertTrue("download must survive failed verification", file.exists())
        }
        prepare(valid, asset(valid))
        assertArrayEquals(valid.readBytes(), ready.readBytes())
    }

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02X".format(it) }
    companion object {
        private const val SIGNER = "376D61F1DAD7E5236E000BDEA4AA4FC7433D0B4C5EA1E148A62AFE97911AD6D9"
    }
}
