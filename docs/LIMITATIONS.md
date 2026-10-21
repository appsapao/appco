# Limitations

This document is meant to be read before trusting a clone with real data. It
separates restrictions imposed by **Android itself** from **bounds of this
implementation**.

---

## 1. Platform restrictions that no cloner can avoid

### 1.1 The signature changes, and Android treats that as a different app

An APK's signature is part of its identity. A clone must be signed with a key we
control, so:

* **Play Integrity / SafetyNet / attestation** report the clone as untrusted or
  as the wrong app.
* **Google Sign-In** and most OAuth/account flows reject the clone, because the
  package name + signing certificate pair is registered in the developer console.
* **In-app purchases** (`com.android.vending.BILLING`) fail: purchases belong to
  the original package and signature.
* **DRM** (Widevine/Play Movies class apps) and licence checks fail.
* Apps that call `PackageManager.getPackageInfo(..., GET_SIGNING_CERTIFICATES)`
  and compare against a pinned value will refuse to run, by design.
* **Play Store updates** never apply to a clone. The store does not know it.

There is no way around this without the original private key. Patching the
verification code out of the app is not something this project does, and would be
both licence-violating and malware-adjacent.

### 1.2 Split APKs / app bundles cannot be cloned

Apps distributed as Android App Bundles are installed as a *base* APK plus
configuration splits (`split_config.arm64_v8a.apk`, language splits, …). Only the
base APK declares the app; the resources and native code live in the splits.

* The clone of the base APK alone is missing resources and libraries, so it
  crashes on start.
* Merging splits into one APK is possible in principle but requires repacking
  `resources.arsc` and `lib/` with correct resource tables — out of scope here.
* This tool detects the condition (from PackageManager when cloning an installed
  app, or from a `split=` attribute) and refuses with a blocker.

### 1.3 `android:sharedUserId`

An app that declares `android:sharedUserId` shares a Linux UID with other apps
signed by the same developer. Android refuses to install a second package with
that shared user id unless it is signed with the *same* certificate. Blocked.

### 1.4 Installing always needs the user

Android does not allow silent side-loading:

* the user must grant **"Install unknown apps"** to this app in system settings;
* the system then shows its own confirmation sheet for every install.

`ApkInstaller` uses the `PackageInstaller` session API (so failures are reported
back), and falls back to the system installer UI.

### 1.5 Clone data starts empty

A clone is a different package, therefore a different UID, therefore a different
`/data/data/<package>` directory and a different entry in every Android
permission/role registry. Its app data starts empty.

Copying the original app's private data requires **root or `adb backup`-style
privileges** that a normal app does not have; this tool does not attempt it, and
therefore does not offer it.

### 1.6 Some registrations must be redone by hand

Notification listeners, accessibility services, device administrators, default
handlers, widgets, VPN/Always-on profiles, work-profile policies and
battery-optimisation exemptions are bound to the package name. The clone must be
enabled/approved again by the user.

### 1.7 Apps that inspect their own installation

Anti-tamper, obfuscation checks, "installed from Play?" checks, installer-source
checks (`getInstallSourceInfo`) and package-name/certificate pinning will notice.
Nothing can be done about that without modifying the app's code.

### 1.8 Removing a permission is a manifest-level removal

Stripping `uses-permission` stops the app from *being granted* that permission.
It is not a runtime privacy guard:

* runtime permission requests are silently denied;
* an app that assumes a permission is present may handle the denial badly (crash,
  or a feature that stops working);
* permissions granted through other means (signature-level permissions shared
  with apps signed by the original developer) are unaffected.

---

## 2. Bounds of this implementation

| Boundary | Detail |
| --- | --- |
| ZIP64 | Not supported. APKs larger than 1.9 GiB are refused; offsets are 32-bit. |
| Single huge entry | Entries larger than 96 MiB are refused (the repackager buffers one entry at a time). |
| Plain-text manifests | An APK whose `AndroidManifest.xml` is not binary XML is refused, not converted. |
| Styled string pools | A manifest whose string pool carries style spans is refused (manifests never do in practice). |
| CDATA in the manifest | Refused; `AndroidManifest.xml` has no character data. |
| `resources.arsc` | Not rewritten. The clone's label is overridden with a literal manifest label instead. Resource *names* and the resource table are untouched, so apps that look up their own resources by package still work; apps that compare `resources.arsc` bytes will not. |
| Labels | The launcher label is set on the application and on launcher activities as a literal string. Activity labels that are resource references keep their original value (deliberate: only the clone's visible name changes). |
| Signature scheme | v2 only. Sufficient for `minSdk 26+` and required for `targetSdk 30+`; v1 (JAR) and v3/v4 are not produced. |
| Existing signatures | Stripped, including any previous APK Signing Block, before re-signing. |
| Clone job lifetime | Runs in the app process. If Android kills the app mid-clone, the partial output is deleted and the clone must be restarted. |
| Concurrency | One clone at a time. |
| No root | Nothing here requires or uses root; that is also why data migration, split merging and system-level hooks are impossible. |
| No code patching | The DEX files are copied byte-for-byte. No feature toggles inside the app are provided. |
| No network | The app has no `INTERNET` permission and makes no requests. |

---

## 3. What the analysis of the original found (context)

The reference APK is `com.applisto.appcloner` 4.0.10 (versionCode 26101921),
`minSdk 23`, `targetSdk 28`, 10 996 obfuscated classes, 2788 resources. Its
manifest requests `REQUEST_INSTALL_PACKAGES`, `REQUEST_DELETE_PACKAGES`,
`ENFORCE_UPDATE_OWNERSHIP` and `DUMP`, and it contains `BytesReplacer` plus
`util.axml.AttributesHelper` / `util.axml.ResourcesHelper`, i.e. it does binary
manifest/arsc surgery and re-signs, then installs — the same shape implemented
here. The paid features of the original (device spoofing, per-app hooks,
notification rewriting, data import/export, split merging) are either
root-dependent or require code patching, and are **not** reimplemented.
