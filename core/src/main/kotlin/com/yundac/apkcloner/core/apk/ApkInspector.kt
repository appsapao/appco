package com.yundac.apkcloner.core.apk

import com.yundac.apkcloner.core.axml.AxmlCodec
import com.yundac.apkcloner.core.axml.AxmlElement
import com.yundac.apkcloner.core.axml.ManifestEditor
import com.yundac.apkcloner.core.axml.ValueType
import java.io.File
import java.util.zip.ZipFile

/** What a clone decision needs to know about a source APK. */
data class ApkMetadata(
    val packageName: String,
    val versionCode: Long?,
    val versionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val label: String?,
    val labelIsResourceReference: Boolean,
    val applicationClass: String?,
    val permissions: List<String>,
    val declaredPermissions: List<String>,
    val sharedUserId: String?,
    val splitName: String?,
    val providerAuthorities: List<String>,
    val launcherComponents: List<String>,
    val extractNativeLibs: Boolean?,
    val hasNativeLibraries: Boolean,
    val nativeLibraryAbis: List<String>,
    val hasV1Signature: Boolean,
    val hasV2Signature: Boolean,
    val entryCount: Int,
    val apkSizeBytes: Long,
) {
    val isSplitApk: Boolean get() = splitName != null
}

class ApkInspector {

    fun readManifestBytes(apk: File): ByteArray {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(MANIFEST_NAME)
                ?: throw ApkFormatException("APK has no $MANIFEST_NAME")
            return zip.getInputStream(entry).readBytes()
        }
    }

    fun inspect(apk: File): ApkMetadata {
        if (!apk.isFile) throw ApkFormatException("Not a file: ${apk.absolutePath}")

        val manifestBytes = readManifestBytes(apk)
        val editor = ManifestEditor.parse(manifestBytes)
        val document = editor.document
        val manifest = document.root
        val application = editor.applicationElement()

        var entryCount = 0
        var hasV1 = false
        var hasLibs = false
        val abis = sortedSetOf<String>()

        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                entryCount++
                val name = entry.name
                if (name.startsWith("META-INF/") && V1_SIGNATURE_SUFFIXES.any { name.endsWith(it) }) {
                    hasV1 = true
                }
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    hasLibs = true
                    name.split('/').getOrNull(1)?.let { abis.add(it) }
                }
            }
        }

        val labelAttribute = application?.frameworkAttribute("label")
        val labelIsReference = labelAttribute != null && labelAttribute.type == ValueType.REFERENCE
        val label = when {
            labelAttribute == null -> null
            labelAttribute.type == ValueType.STRING -> document.stringValue(labelAttribute)
            labelAttribute.type == ValueType.REFERENCE ->
                "@0x%08x".format(labelAttribute.data.toLong() and 0xFFFFFFFFL)
            else -> labelAttribute.rawValue
        }

        val usesSdk = manifest.childElements("uses-sdk").firstOrNull()
        val permissions = manifest.children
            .filter { it.name == "uses-permission" || it.name == "uses-permission-sdk-23" }
            .mapNotNull { document.stringValue(it.frameworkAttribute("name")) }
        val declared = manifest.childElements("permission")
            .mapNotNull { document.stringValue(it.frameworkAttribute("name")) }
        val authorities = buildList {
            application?.childElements("provider")?.forEach { provider ->
                document.stringValue(provider.frameworkAttribute("authorities"))?.let { add(it) }
            }
        }
        val launchers = buildList {
            application?.children
                ?.filter { it.name == "activity" || it.name == "activity-alias" }
                ?.forEach { component ->
                    if (editor.hasLauncherIntent(component)) {
                        document.stringValue(component.frameworkAttribute("name"))?.let { add(it) }
                    }
                }
        }

        return ApkMetadata(
            packageName = editor.packageName() ?: throw ApkFormatException("Manifest declares no package name"),
            versionCode = readLong(manifest, "versionCode", document),
            versionName = document.stringValue(manifest.frameworkAttribute("versionName")),
            minSdk = readInt(usesSdk, "minSdkVersion", document),
            targetSdk = readInt(usesSdk, "targetSdkVersion", document),
            label = label,
            labelIsResourceReference = labelIsReference,
            applicationClass = application?.let { document.stringValue(it.frameworkAttribute("name")) },
            permissions = permissions,
            declaredPermissions = declared,
            sharedUserId = document.stringValue(manifest.frameworkAttribute("sharedUserId")),
            splitName = document.stringValue(manifest.frameworkAttribute("split")),
            providerAuthorities = authorities,
            launcherComponents = launchers,
            extractNativeLibs = readBoolean(application, "extractNativeLibs", document),
            hasNativeLibraries = hasLibs,
            nativeLibraryAbis = abis.toList(),
            hasV1Signature = hasV1,
            hasV2Signature = ZipLayout.fileHasSigningBlock(apk),
            entryCount = entryCount,
            apkSizeBytes = apk.length(),
        )
    }

    private fun readInt(element: AxmlElement?, name: String, document: com.yundac.apkcloner.core.axml.AxmlDocument): Int? {
        val attribute = element?.frameworkAttribute(name) ?: return null
        return when (attribute.type) {
            ValueType.INT_DEC, ValueType.INT_HEX -> attribute.data
            ValueType.STRING -> document.stringValue(attribute)?.trim()?.toIntOrNull()
            else -> null
        }
    }

    private fun readLong(
        element: AxmlElement?,
        name: String,
        document: com.yundac.apkcloner.core.axml.AxmlDocument,
    ): Long? {
        val attribute = element?.frameworkAttribute(name) ?: return null
        return when (attribute.type) {
            ValueType.INT_DEC, ValueType.INT_HEX -> attribute.data.toLong() and 0xFFFFFFFFL
            ValueType.STRING -> document.stringValue(attribute)?.trim()?.toLongOrNull()
            else -> null
        }
    }

    private fun readBoolean(
        element: AxmlElement?,
        name: String,
        document: com.yundac.apkcloner.core.axml.AxmlDocument,
    ): Boolean? {
        val attribute = element?.frameworkAttribute(name) ?: return null
        return when (attribute.type) {
            ValueType.INT_BOOLEAN -> attribute.data != 0
            ValueType.STRING -> document.stringValue(attribute)?.equals("true", ignoreCase = true)
            else -> null
        }
    }

    companion object {
        const val MANIFEST_NAME: String = "AndroidManifest.xml"
        private val V1_SIGNATURE_SUFFIXES = listOf(".RSA", ".DSA", ".EC")

        fun parseManifest(bytes: ByteArray): ManifestEditor = ManifestEditor(AxmlCodec.parse(bytes))
    }
}
