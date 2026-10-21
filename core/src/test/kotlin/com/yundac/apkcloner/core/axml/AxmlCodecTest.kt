package com.yundac.apkcloner.core.axml

import com.yundac.apkcloner.core.support.TestManifests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AxmlCodecTest {

    @Test
    fun `parses manifest header attributes`() {
        val editor = ManifestEditor.parse(TestManifests.standard(versionCode = 12, versionName = "3.4.5"))

        assertEquals("com.example.source", editor.packageName())

        val manifest = editor.document.root
        assertEquals("manifest", manifest.name)
        assertEquals("3.4.5", editor.document.stringValue(manifest.frameworkAttribute("versionName")))
        assertEquals(12, manifest.frameworkAttribute("versionCode")!!.data)

        val usesSdk = manifest.childElements("uses-sdk").single()
        assertEquals(26, usesSdk.frameworkAttribute("minSdkVersion")!!.data)
        assertEquals(35, usesSdk.frameworkAttribute("targetSdkVersion")!!.data)
    }

    @Test
    fun `parses permissions, provider and launcher components`() {
        val editor = ManifestEditor.parse(TestManifests.standard())
        val document = editor.document

        val permissions = document.root.children
            .filter { it.name == "uses-permission" }
            .mapNotNull { document.stringValue(it.frameworkAttribute("name")) }
        assertEquals(listOf("android.permission.INTERNET", "android.permission.CAMERA"), permissions)

        val provider = editor.applicationElement()!!.childElements("provider").single()
        assertEquals(
            "com.example.source.fileprovider",
            document.stringValue(provider.frameworkAttribute("authorities")),
        )

        val launchers = editor.applicationElement()!!.children
            .filter { it.name == "activity" }
            .filter { editor.hasLauncherIntent(it) }
        assertEquals(1, launchers.size)
        assertEquals(
            "com.example.source.MainActivity",
            document.stringValue(launchers.single().frameworkAttribute("name")),
        )
    }

    @Test
    fun `parses a UTF-16 string pool`() {
        val editor = ManifestEditor.parse(TestManifests.standard(utf16 = true))
        assertEquals("com.example.source", editor.packageName())
        assertTrue("pool should be flagged as UTF-16", !editor.document.strings.isUtf8)
    }

    @Test
    fun `re-encoding is stable and lossless`() {
        val original = TestManifests.standard()
        val once = AxmlCodec.encode(AxmlCodec.parse(original))
        val twice = AxmlCodec.encode(AxmlCodec.parse(once))

        assertTrue("encoding the same document twice must be deterministic", once.contentEquals(twice))
        assertEquals("com.example.source", ManifestEditor.parse(once).packageName())
    }

    @Test
    fun `rejects a plain text manifest`() {
        val thrown = runCatching {
            AxmlCodec.parse("<manifest package=\"com.example\" />".toByteArray())
        }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(thrown is AxmlCodec.AxmlFormatException)
    }

    @Test
    fun `rejects a truncated document`() {
        val bytes = TestManifests.standard()
        val truncated = bytes.copyOf(bytes.size / 2)
        val thrown = runCatching { AxmlCodec.parse(truncated) }.exceptionOrNull()
        assertNotNull(thrown)
    }

    @Test
    fun `keeps the resource map in sync with the string pool`() {
        val document = AxmlCodec.parse(TestManifests.standard())
        assertEquals(document.strings.size, document.resourceMapSize)

        val labelIndex = document.addString("android:label", AndroidAttr.LABEL)
        assertEquals(AndroidAttr.LABEL, document.resourceIdAt(labelIndex))
        assertEquals(document.strings.size, document.resourceMapSize)
    }

    @Test
    fun `documents without a resource map are parsed`() {
        val bytes = com.yundac.apkcloner.core.support.AxmlTestFactory.encode(
            root = com.yundac.apkcloner.core.support.AxmlTestFactory.Element(
                name = "manifest",
                attributes = listOf(
                    com.yundac.apkcloner.core.support.AxmlTestFactory.Attr(
                        name = "package",
                        stringValue = "com.example.plain",
                        namespace = null,
                    ),
                ),
            ),
            includeResourceMap = false,
        )
        val document = AxmlCodec.parse(bytes)
        assertTrue(!document.hadResourceMap)
        assertNull(document.root.attributes.firstOrNull()?.namespace)
        assertEquals("com.example.plain", ManifestEditor(document).packageName())
    }
}
