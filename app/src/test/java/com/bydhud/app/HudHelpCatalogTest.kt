package com.bydhud.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class HudHelpCatalogTest {
    @Test
    fun everyTopicResolvesUniquelyWithContentInEveryLanguage() {
        val topics = HudHelpCatalog.topics
        assertEquals(HudHelpTopicId.values().toSet(), topics.map { it.id }.toSet())
        assertEquals(topics.size, topics.map { it.id }.distinct().size)
        topics.forEach { topic ->
            assertSame(topic, HudHelpCatalog.topic(topic.id))
            assertTrue(topic.id.toString(), topic.frames.isNotEmpty())
            Language.values().forEach { language ->
                assertTrue(topic.id.toString(), topic.title(language).isNotBlank())
                topic.frames.forEach { assertTrue(it.label(language).isNotBlank()) }
            }
        }
    }

    @Test
    fun nativeClearChoicesAndNavigationEndHelpFollowUiOrderInAllLanguages() {
        val topic = HudHelpCatalog.topic(HudHelpTopicId.SpeedLimitNativeClearMode)
        assertEquals(listOf("Ніколи", "В кінці навігації", "Немає нав. даних", "Завжди"),
            topic.frames.map { it.label(Language.Ua) })
        assertEquals(listOf("Never", "At navigation end", "No nav. data", "Always"),
            topic.frames.map { it.label(Language.En) })
        assertEquals(listOf("Никогда", "В конце навигации", "Нет нав. данных", "Всегда"),
            topic.frames.map { it.label(Language.Ru) })
        assertTrue(topic.frames[1].caption(Language.Ua).contains("після завершення навігації"))
        assertTrue(topic.frames[1].caption(Language.En).contains("when navigation ends"))
        assertTrue(topic.frames[1].caption(Language.Ru).contains("после завершения навигации"))
    }

    @Test
    fun everyReferencedLocalizedImageHasACompleteImageContainer() {
        val names = R.drawable::class.java.fields.associate { it.getInt(null) to it.name }
        val resources = listOf(File("src/main/res/drawable-nodpi"),
            File("app/src/main/res/drawable-nodpi")).first { it.isDirectory }
        val images = HudHelpCatalog.topics.flatMap { it.frames }.map { it.imageRes }.toMutableSet()
        // The ETA help computes images from field selections, rather than only the topic frames.
        for (street in listOf(false, true)) for (mask in 0..7) {
            EtaStreetFormat.values().forEach { images += HudHelpCatalog.etaImage(street, mask, it) }
        }
        for (image in images) for (language in Language.values()) {
            val id = HudHelpCatalog.localizedImage(image, language)
            val name = requireNotNull(names[id]) { "Unknown drawable $id for $language" }
            val file = requireNotNull(resources.listFiles()?.singleOrNull { it.nameWithoutExtension == name }) {
                "Missing or ambiguous image $name"
            }
            val bytes = file.readBytes()
            if (file.extension == "png") {
                // Android's compile bootclasspath excludes java.desktop; the host JVM has ImageIO.
                val decoded = requireNotNull(Class.forName("javax.imageio.ImageIO")
                    .getMethod("read", File::class.java).invoke(null, file)) { "Invalid PNG $name" }
                assertTrue(name, decoded.javaClass.getMethod("getWidth").invoke(decoded) as Int > 0)
                assertTrue(name, decoded.javaClass.getMethod("getHeight").invoke(decoded) as Int > 0)
                continue
            }
            assertEquals(name, "webp", file.extension)
            assertTrue("Truncated $name", bytes.size >= 20)
            assertEquals(name, "RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals(name, "WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
            val declaredSize = (0..3).fold(0L) { value, i ->
                value or ((bytes[4 + i].toLong() and 255) shl (8 * i))
            }
            assertEquals("Incomplete $name", bytes.size.toLong(), declaredSize + 8)
        }
    }
}
