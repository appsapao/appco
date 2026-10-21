# APK Cloner

An independent, open reconstruction of App Cloner's core function: take an
installed app's APK, produce a **new, separately installable app** from it, and
let the user configure the clone before it is built.

Everything runs on the device. There is no cloud service, no paid API, no
proprietary SDK, and no network permission. No code inside the cloned app is
patched â€” the clone contains the original app's DEX and resources, with a
rewritten manifest, a re-aligned ZIP container, and a new signature.

---

## Status: what is actually verified

| Item | Result |
| --- | --- |
| Unit tests (`:core`, plain JVM) | 49 tests, all passing |
| Debug APK build | `:app:assembleDebug` succeeds (AGP 8.7.3, compileSdk 35, minSdk 26) |
| Android lint | 0 errors, 0 warnings |
| Signature cross-check | Output APKs verify with Google's `apksig` **and** with `apksigner verify --print-certs` |
| Binary-XML cross-check | AOSP `aapt2 dump badging` / `dump xmltree` parse the rewritten manifest |
| Real-world smoke test | Clones the 84 MiB `com.applisto.appcloner` 4.0.10 APK (2788 entries) in ~7 s; result verifies |
| On-device UI run | **Not performed in this environment** (no emulator/device available) |

The last row matters: the clone engine is covered by tests that run on a plain
JVM, and the APK is built and inspected by Android tooling, but the app's UI has
not been exercised on a real device here. Install the artifact on a device to try
it.

---

## Build it without Android Studio or an SDK

All compilation happens in GitHub Actions. Push the repository, then:

1. Open the **Actions** tab â†’ the `Build debug APK and run tests` workflow.
2. Open the latest run.
3. Download the **`apk-cloner-debug`** artifact.

That artifact is an installable debug APK. The workflow runs on every push and
on pull requests, executes the unit tests, runs lint, builds the APK, verifies
its signature with `apksigner`, and uploads it.

Locally you need only a JDK 17 and an Android SDK (the SDK is required for *any*
Android build; Android Studio is not). If you have one:

```bash
export ANDROID_HOME=/path/to/android-sdk    # platform 35 + build-tools 35.0.0
./gradlew test lintDebug assembleDebug
```

---

## What a clone actually is

Given a source APK, `ClonePipeline` performs five steps, then verifies its own
output from disk before reporting success:

1. **Inspect** â€” parse the binary `AndroidManifest.xml` and read the package id,
   version, SDK range, declared permissions, providers, launcher components,
   native-library ABIs and existing signature schemes.
2. **Patch the manifest** (`AxmlCodec` / `ManifestEditor`) â€” rewrite the
   `package` attribute, rename provider authorities that share the old package
   prefix (required: two apps cannot declare the same authority), set the
   application and launcher labels as literal strings, optionally strip
   `uses-permission` elements, optionally add `android:allowBackup="false"`.
   Class names in `android:name` are deliberately **not** touched â€” the DEX still
   declares the original classes.
3. **Repackage** (`ApkRepackager`) â€” rewrite the ZIP by hand: drop old `META-INF`
   signatures, store `resources.arsc` uncompressed at a 4-byte boundary
   (mandatory for `targetSdk >= 30`), keep native libraries stored and 16 KiB
   page-aligned, and use fixed timestamps.
4. **Sign** (`ApkSignatureSchemeV2`) â€” build an APK Signature Scheme v2 block
   from scratch: chunked SHA-256 content digest, a self-signed certificate
   generated in-process (ASN.1/DER written by hand, no BouncyCastle), and an
   RSA/SHA-256 signature. v2 is the minimum that a `targetSdk >= 30` app needs
   in order to install at all.
5. **Verify** â€” re-read the APK from disk: recompute the v2 digest and verify the
   signature against the signer certificate (`ApkSignatureVerifier`), CRC-check
   every ZIP entry (`ApkIntegrity`), and re-parse the manifest to confirm the
   package name really changed. A clone that fails any check is deleted.

### Per-clone configuration

* Clone name (written as a literal label on `<application>` and on every launcher
  activity/alias, so the home screen shows the new name).
* Package id, defaulting to `<source>.cloneN` and validated as an Android
  application id; refuses ids that are already installed.
* Permissions to strip, chosen from the permissions the APK actually declares.
* `android:allowBackup="false"` toggle.

### Compatibility checks (before anything is built)

| Severity | Condition | Why |
| --- | --- | --- |
| Blocker | Split APK / app bundle | only the base APK is readable; the clone would be missing resources and native code |
| Blocker | `android:sharedUserId` | Android refuses another package with that shared user id unless it is signed with the same key |
| Blocker | `minSdkVersion` newer than the device | cannot run |
| Blocker | APK > 1.9 GiB | ZIP offsets are 32-bit here (no ZIP64) |
| Warning | always | the signature changes: Google sign-in, Play Integrity, billing, DRM and any self-signature check stop working |
| Warning | Play Billing permission | purchases are bound to the original package and signature |
| Info | custom permissions, native libraries, resource-referenced label | behaviour that may need attention |

---

## Project layout

```
core/    Pure Kotlin/JVM engine (no Android APIs) â€” unit-tested without an SDK
  axml/    binary XML: format constants, string pool, parser/serializer, editor
  apk/     metadata inspection, ZIP repacking, entry integrity checks
  sign/    DER encoder, X.509 builder, v2 signer + verifier, keystore
  clone/   package-name rules, compatibility findings, the clone pipeline
app/     Android UI (AppCompat only): app list, clone configuration, installer
```

The split is deliberate: all byte-level logic is testable on a plain JVM, and the
Android module only does UI, threading and the PackageInstaller hand-off.

---

## Tests

```bash
./gradlew test            # 49 JVM tests across parser, editor, ZIP, signing, pipeline
./gradlew lintDebug       # Android lint
```

What the tests pin down:

* **AXML** â€” parsing/serialization written against a *separate* test-only encoder,
  plus UTF-16 pools, resource-map/string-pool consistency, and that
  re-encoding is deterministic and lossless.
* **Manifest edits** â€” package rename keeps class names; authorities are renamed
  only when they share the prefix; labels land on the application *and* only the
  launcher components; permission removal drops exactly one element; a missing
  `allowBackup` is added with its framework resource id.
* **ZIP** â€” old signatures dropped, `resources.arsc` stored and 4-byte aligned,
  `.so` stored and 16 KiB aligned, payloads CRC-intact.
* **Signing** â€” our v2 blocks verify with `apksig` (Google's verifier), tampering
  is detected, certificates round-trip through `CertificateFactory`.
* **Pipeline** â€” end-to-end on a synthetic APK: source untouched, package/label
  changed, permission removed, output signed and intact; invalid inputs and
  split APKs are refused.

`RealApkSmokeTest` additionally clones a *real* APK when
`APKCLONER_SAMPLE_APK=/path/to/app.apk` is set (skipped otherwise), and checks the
result with `aapt2` when the Android build-tools are available.

---

## Reproducibility

* `gradle/wrapper/gradle-wrapper.properties` pins Gradle **8.9** by version *and*
  `distributionSha256Sum`.
* Every dependency and plugin version lives in `gradle/libs.versions.toml`; no
  dynamic versions.
* The Android SDK/compile level is pinned (`compileSdk 35`, `buildToolsVersion 35.0.0`).
* `keystore/debug.jks` is committed **on purpose**: it is a public debug key with
  the password `android`, so CI runs sign the same APK the same way instead of
  generating a new debug key each run. Never use it for anything real.
* ZIP timestamps are fixed, so the repackager's output does not depend on the
  clock.

Byte-identical APKs additionally require the same Build-Tools and platform
versions, which is why the workflow pins `ubuntu-24.04` and JDK 17.

---

## Dependencies

| Dependency | Scope | License |
| --- | --- | --- |
| AndroidX Core-KTX, AppCompat | app runtime | Apache-2.0 |
| JUnit 4 | tests | EPL-1.0 |
| `com.android.tools.build:apksig` | **tests only** (independent verifier) | Apache-2.0 |

No signing library ships in the APK: the v2 signer, DER encoder and certificate
builder are part of this project.

---

## Limitations

Read [docs/LIMITATIONS.md](docs/LIMITATIONS.md). It covers the platform
restrictions that make a cloner impossible to implement fully â€” signature
verification, Play Integrity, split APKs, shared UIDs, install confirmation,
Play Store updates, data migration â€” and this implementation's own bounds.

Short version: **a clone is a differently-signed rebuild of the same app, not a
transparent copy.** Apps that require their original signing identity or a
verified Play installation will not work, and that cannot be fixed without
root-level tampering or the developer's private key.

Only clone apps you have the right to copy. The source apps' licences still
apply to their code and assets.

