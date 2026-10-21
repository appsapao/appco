package com.yundac.apkcloner.core.clone

import com.yundac.apkcloner.core.apk.ApkMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageNamesTest {

    @Test
    fun `accepts valid application ids`() {
        assertTrue(PackageNames.isValid("com.example.app"))
        assertTrue(PackageNames.isValid("com.example.app.clone1"))
        assertTrue(PackageNames.isValid("a.b"))
        assertTrue(PackageNames.isValid("com.example_2.app2"))
    }

    @Test
    fun `rejects invalid application ids`() {
        assertFalse(PackageNames.isValid(""))
        assertFalse(PackageNames.isValid("single"))
        assertFalse(PackageNames.isValid("com..app"))
        assertFalse(PackageNames.isValid("com.2example.app"))
        assertFalse(PackageNames.isValid("com.example.app."))
        assertFalse(PackageNames.isValid("com.exa-mple.app"))
        assertFalse(PackageNames.isValid("com/example/app"))
        assertFalse(PackageNames.isValid("com." + "a".repeat(200)))
    }

    @Test
    fun `explains why a name is rejected`() {
        assertNull(PackageNames.problem("com.example.ok"))
        assertNotNull(PackageNames.problem("single"))
        assertNotNull(PackageNames.problem("com..app"))
        assertTrue(PackageNames.problem("com.1bad.app")!!.contains("start with a letter"))
    }

    @Test
    fun `suggests clones with an incrementing suffix`() {
        assertEquals("com.example.app.clone1", PackageNames.suggest("com.example.app", 1))
        assertEquals("com.example.app.clone12", PackageNames.suggest("com.example.app", 12))
        assertTrue(PackageNames.isValid(PackageNames.suggest("com.example.app", 3)))
    }

    @Test
    fun `suggestion stays valid for very long source package names`() {
        val longName = "com." + "abcdefghij".repeat(12)
        val suggestion = PackageNames.suggest(longName, 2)
        assertTrue(suggestion.length <= PackageNames.MAX_LENGTH)
        assertTrue(PackageNames.isValid(suggestion))
    }

    @Test
    fun `suggestFree skips names already in use`() {
        val taken = setOf("com.example.app.clone1", "com.example.app.clone2")
        assertEquals("com.example.app.clone3", PackageNames.suggestFree("com.example.app", taken))
    }
}

class CompatibilityTest {

    @Test
    fun `plain app has no blockers but warns about signatures`() {
        val findings = CompatibilityChecker.analyze(metadata(), deviceSdkInt = 34)
        assertFalse(CompatibilityChecker.hasBlockers(findings))
        assertTrue(findings.any { it.code == "SIGNATURE_CHANGED" && it.severity == Severity.WARNING })
    }

    @Test
    fun `split apk is a blocker`() {
        val findings = CompatibilityChecker.analyze(metadata(splitName = "config.arm64_v8a"), deviceSdkInt = 34)
        assertTrue(CompatibilityChecker.hasBlockers(findings))
        assertEquals(Severity.BLOCKER, CompatibilityChecker.overallSeverity(findings))
    }

    @Test
    fun `shared user id is a blocker`() {
        val findings = CompatibilityChecker.analyze(metadata(sharedUserId = "com.example.shared"), deviceSdkInt = 34)
        assertTrue(findings.any { it.code == "SHARED_USER_ID" && it.severity == Severity.BLOCKER })
    }

    @Test
    fun `split APKs reported by the package manager are a blocker`() {
        val findings = CompatibilityChecker.analyze(
            metadata(),
            deviceSdkInt = 34,
            installedSplits = listOf("split_config.arm64_v8a.apk"),
        )
        assertTrue(findings.any { it.code == "SPLIT_APK" && it.severity == Severity.BLOCKER })
    }

    @Test
    fun `newer minimum sdk than the device is a blocker`() {
        val findings = CompatibilityChecker.analyze(metadata(minSdk = 35), deviceSdkInt = 33)
        assertTrue(findings.any { it.code == "MIN_SDK_TOO_HIGH" && it.severity == Severity.BLOCKER })
    }

    @Test
    fun `billing apps are flagged`() {
        val findings = CompatibilityChecker.analyze(
            metadata(permissions = listOf("com.android.vending.BILLING")),
            deviceSdkInt = 34,
        )
        assertTrue(findings.any { it.code == "BILLING" })
    }

    @Test
    fun `native libraries are reported as informational`() {
        val findings = CompatibilityChecker.analyze(
            metadata(hasNativeLibraries = true, nativeLibraryAbis = listOf("arm64-v8a")),
            deviceSdkInt = 34,
        )
        val finding = findings.first { it.code == "NATIVE_LIBRARIES" }
        assertEquals(Severity.INFO, finding.severity)
        assertTrue(finding.detail.contains("arm64-v8a"))
    }

    private fun metadata(
        splitName: String? = null,
        sharedUserId: String? = null,
        minSdk: Int? = 26,
        permissions: List<String> = listOf("android.permission.INTERNET"),
        hasNativeLibraries: Boolean = false,
        nativeLibraryAbis: List<String> = emptyList(),
    ) = ApkMetadata(
        packageName = "com.example.source",
        versionCode = 1,
        versionName = "1.0",
        minSdk = minSdk,
        targetSdk = 35,
        label = "Source App",
        labelIsResourceReference = false,
        applicationClass = null,
        permissions = permissions,
        declaredPermissions = emptyList(),
        sharedUserId = sharedUserId,
        splitName = splitName,
        providerAuthorities = emptyList(),
        launcherComponents = listOf("com.example.source.MainActivity"),
        extractNativeLibs = true,
        hasNativeLibraries = hasNativeLibraries,
        nativeLibraryAbis = nativeLibraryAbis,
        hasV1Signature = true,
        hasV2Signature = true,
        entryCount = 10,
        apkSizeBytes = 1024,
    )
}
