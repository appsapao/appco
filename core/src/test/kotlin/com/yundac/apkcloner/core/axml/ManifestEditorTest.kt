package com.yundac.apkcloner.core.axml

import com.yundac.apkcloner.core.support.TestManifests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestEditorTest {

    @Test
    fun `renames the package but keeps class names`() {
        val editor = ManifestEditor.parse(TestManifests.standard())
        val old = editor.renamePackage("com.example.clone1")

        assertEquals("com.example.source", old)
        assertEquals("com.example.clone1", editor.packageName())

        // Class names must survive: the DEX still declares the original classes.
        val reparsed = ManifestEditor.parse(editor.toBytes())
        val activity = reparsed.applicationElement()!!
            .childElements("activity")
            .first { reparsed.hasLauncherIntent(it) }
        assertEquals(
            "com.example.source.MainActivity",
            reparsed.document.stringValue(activity.frameworkAttribute("name")),
        )
        assertEquals(
            "com.example.source.SourceApplication",
            reparsed.document.stringValue(
                reparsed.applicationElement()!!.frameworkAttribute("name"),
            ),
        )
    }

    @Test
    fun `renames provider authorities that share the package prefix`() {
        val direct = ManifestEditor.parse(
            TestManifests.standard(providerAuthority = "com.example.source.fileprovider;com.example.source.backup"),
        )
        val changes = direct.renameProviderAuthorities("clone2")
        assertEquals(2, changes.size)

        val provider = direct.applicationElement()!!.childElements("provider").single()
        assertEquals(
            "com.example.source.fileprovider.clone2;com.example.source.backup.clone2",
            direct.document.stringValue(provider.frameworkAttribute("authorities")),
        )
    }

    @Test
    fun `renames unrelated provider authorities as well`() {
        val editor = ManifestEditor.parse(
            TestManifests.standard(providerAuthority = "com.othercompany.provider"),
        )
        val changes = editor.renameProviderAuthorities("clone3")
        assertEquals(1, changes.size)
        assertEquals("com.othercompany.provider" to "com.othercompany.provider.clone3", changes.single())
        val provider = editor.applicationElement()!!.childElements("provider").single()
        assertEquals(
            "com.othercompany.provider.clone3",
            editor.document.stringValue(provider.frameworkAttribute("authorities")),
        )
    }

    @Test
    fun `sets the application and launcher labels only`() {
        val editor = ManifestEditor.parse(
            TestManifests.standard(secondaryActivityWithLabel = "com.example.source.SettingsActivity"),
        )
        editor.setApplicationLabel("Cloned Name")
        editor.setLauncherLabels("Cloned Name")

        val reparsed = ManifestEditor.parse(editor.toBytes())
        val application = reparsed.applicationElement()!!
        assertEquals("Cloned Name", reparsed.document.stringValue(application.frameworkAttribute("label")))

        val activities = application.childElements("activity")
        val launcher = activities.first { reparsed.hasLauncherIntent(it) }
        val secondary = activities.first { !reparsed.hasLauncherIntent(it) }
        assertEquals("Cloned Name", reparsed.document.stringValue(launcher.frameworkAttribute("label")))
        assertEquals(
            "Original Settings Label",
            reparsed.document.stringValue(secondary.frameworkAttribute("label")),
        )
    }

    @Test
    fun `adds a label when the application had none`() {
        val editor = ManifestEditor.parse(TestManifests.standard(labelLiteral = null, labelReference = null))
        editor.setApplicationLabel("Added Label")

        val reparsed = ManifestEditor.parse(editor.toBytes())
        val label = reparsed.applicationElement()!!.frameworkAttribute("label")
        assertNotNull(label)
        assertEquals("Added Label", reparsed.document.stringValue(label))
    }

    @Test
    fun `replaces a label that was a resource reference`() {
        val editor = ManifestEditor.parse(TestManifests.standard(labelLiteral = null, labelReference = 0x7F0F0001))
        assertEquals(
            ValueType.REFERENCE,
            editor.applicationElement()!!.frameworkAttribute("label")!!.type,
        )
        editor.setApplicationLabel("Literal Label")

        val reparsed = ManifestEditor.parse(editor.toBytes())
        val label = reparsed.applicationElement()!!.frameworkAttribute("label")!!
        assertEquals(ValueType.STRING, label.type)
        assertEquals("Literal Label", reparsed.document.stringValue(label))
    }

    @Test
    fun `removes selected permissions only`() {
        val editor = ManifestEditor.parse(TestManifests.standard())
        val removed = editor.removePermissions(setOf("android.permission.CAMERA"))

        assertEquals(listOf("android.permission.CAMERA"), removed)

        val reparsed = ManifestEditor.parse(editor.toBytes())
        val remaining = reparsed.document.root.children
            .filter { it.name == "uses-permission" }
            .mapNotNull { reparsed.document.stringValue(it.frameworkAttribute("name")) }
        assertEquals(listOf("android.permission.INTERNET"), remaining)
        // The element count must drop by exactly one.
        assertEquals(
            editor.document.root.children.size,
            reparsed.document.root.children.size,
        )
    }

    @Test
    fun `adds allowBackup when absent and updates it when present`() {
        val withoutAttribute = ManifestEditor.parse(TestManifests.standard())
        withoutAttribute.setApplicationBoolean("allowBackup", AndroidAttr.ALLOW_BACKUP, false)
        val added = ManifestEditor.parse(withoutAttribute.toBytes())
        val addedAttribute = added.applicationElement()!!.frameworkAttribute("allowBackup")
        assertNotNull(addedAttribute)
        assertEquals(0, addedAttribute!!.data)

        val withAttribute = ManifestEditor.parse(TestManifests.standard(allowBackup = true))
        withAttribute.setApplicationBoolean("allowBackup", AndroidAttr.ALLOW_BACKUP, false)
        val updated = ManifestEditor.parse(withAttribute.toBytes())
        assertEquals(0, updated.applicationElement()!!.frameworkAttribute("allowBackup")!!.data)
        assertEquals(
            1,
            updated.applicationElement()!!.attributes.count { it.name == "allowBackup" },
        )
    }

    @Test
    fun `reports a missing package attribute`() {
        val bytes = com.yundac.apkcloner.core.support.AxmlTestFactory.encode(
            com.yundac.apkcloner.core.support.AxmlTestFactory.Element(name = "manifest"),
        )
        val editor = ManifestEditor.parse(bytes)
        val thrown = runCatching { editor.renamePackage("com.example.clone") }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(thrown is ManifestEditorException)
    }

    @Test
    fun `launcher detection ignores non launcher filters`() {
        val editor = ManifestEditor.parse(TestManifests.standard())
        val application = editor.applicationElement()!!
        val settings = AxmlElement("activity").apply {
            attributes.add(AxmlAttribute(AndroidAttr.NAMESPACE, "name", null, ValueType.STRING, 0))
            children.add(
                AxmlElement("intent-filter").apply {
                    children.add(
                        AxmlElement("action").apply {
                            attributes.add(AxmlAttribute(AndroidAttr.NAMESPACE, "name", null, ValueType.STRING, 0))
                        },
                    )
                },
            )
        }
        application.children.add(settings)
        assertFalse(editor.hasLauncherIntent(settings))
    }
}
