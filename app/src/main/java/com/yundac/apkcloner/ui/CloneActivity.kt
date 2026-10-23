package com.yundac.apkcloner.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.yundac.apkcloner.R
import com.yundac.apkcloner.clone.CloneSession
import com.yundac.apkcloner.core.apk.ApkInspector
import com.yundac.apkcloner.core.apk.ApkMetadata
import com.yundac.apkcloner.core.clone.CompatibilityChecker
import com.yundac.apkcloner.core.clone.Finding
import com.yundac.apkcloner.core.clone.PackageNames
import com.yundac.apkcloner.core.clone.Severity
import com.yundac.apkcloner.data.CloneStore
import com.yundac.apkcloner.data.InstalledApps
import com.yundac.apkcloner.install.ApkInstaller
import com.yundac.apkcloner.ui.theme.ThemePreferences
import java.io.File
import java.util.concurrent.Executors

/**
 * Configures and runs one clone: name, package id, permissions to strip.
 *
 * Everything shown here comes from the APK itself (manifest inspection) and from
 * PackageManager; nothing is assumed about the source app.
 */
class CloneActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var sourceIcon: ImageView
    private lateinit var sourceName: TextView
    private lateinit var sourceDetail: TextView
    private lateinit var findingsCard: MaterialCardView
    private lateinit var findingsStatusIcon: ImageView
    private lateinit var findingsText: TextView
    private lateinit var labelInputLayout: TextInputLayout
    private lateinit var labelInput: TextInputEditText
    private lateinit var packageInputLayout: TextInputLayout
    private lateinit var packageInput: TextInputEditText
    private lateinit var permissionContainer: LinearLayout
    private lateinit var permissionSection: LinearLayout
    private lateinit var disableBackup: MaterialSwitch
    private lateinit var createButton: MaterialButton
    private lateinit var progressSection: View
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var progressText: TextView
    private lateinit var resultSection: View
    private lateinit var resultText: TextView
    private lateinit var installButton: MaterialButton
    private lateinit var shareButton: MaterialButton

    private val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "apk-analysis").apply { isDaemon = true }
    }

    private var sourceApk: File? = null
    private var sourcePackage: String = ""
    private var sourceLabel: String = ""
    private var installedSplits: List<String> = emptyList()
    private var metadata: ApkMetadata? = null
    private var hasBlockers = false
    private var analyzed = false
    private var lastBuiltPackage: String? = null

    private val permissionCheckboxes = LinkedHashMap<String, MaterialCheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyActivityTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_clone)

        toolbar = findViewById(R.id.toolbar)
        sourceIcon = findViewById(R.id.sourceIcon)
        sourceName = findViewById(R.id.sourceName)
        sourceDetail = findViewById(R.id.sourceDetail)
        findingsCard = findViewById(R.id.findingsCard)
        findingsStatusIcon = findViewById(R.id.findingsStatusIcon)
        findingsText = findViewById(R.id.findingsText)
        labelInputLayout = findViewById(R.id.labelInputLayout)
        labelInput = findViewById(R.id.labelInput)
        packageInputLayout = findViewById(R.id.packageInputLayout)
        packageInput = findViewById(R.id.packageInput)
        permissionContainer = findViewById(R.id.permissionContainer)
        permissionSection = findViewById(R.id.permissionSection)
        disableBackup = findViewById(R.id.disableBackup)
        createButton = findViewById(R.id.createButton)
        progressSection = findViewById(R.id.progressSection)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        resultSection = findViewById(R.id.resultSection)
        resultText = findViewById(R.id.resultText)
        installButton = findViewById(R.id.installButton)
        shareButton = findViewById(R.id.shareButton)

        toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { _, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            toolbar.updatePadding(top = statusBarInsets.top)
            findViewById<View>(R.id.scrollView).updatePadding(bottom = navBarInsets.bottom + 24)
            insets
        }

        val path = intent.getStringExtra(EXTRA_APK_PATH)
        sourcePackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        sourceLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        sourceApk = path?.let(::File)

        createButton.isEnabled = false
        createButton.setOnClickListener { startClone() }
        installButton.setOnClickListener { installLastClone() }
        shareButton.setOnClickListener { shareLastClone() }

        loadSourceIcon()

        val apk = sourceApk
        if (apk == null || !apk.isFile) {
            showFatal(getString(R.string.apk_missing))
            return
        }
        analyze(apk)
    }

    private fun loadSourceIcon() {
        if (sourcePackage.isNotEmpty()) {
            runCatching {
                val icon = packageManager.getApplicationIcon(sourcePackage)
                sourceIcon.setImageDrawable(icon)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        CloneSession.observe { state -> render(state) }
    }

    override fun onStop() {
        CloneSession.observe(null)
        super.onStop()
    }

    private fun analyze(apk: File) {
        sourceName.text = sourceLabel.ifBlank { getString(R.string.analyzing) }
        sourceDetail.text = apk.absolutePath
        analysisExecutor.execute {
            val result = runCatching {
                val inspected = ApkInspector().inspect(apk)
                val splits = if (sourcePackage.isNotEmpty()) {
                    InstalledApps.splitApksOf(this, sourcePackage)
                } else {
                    emptyList()
                }
                // Package enumeration is slow; keep it off the main thread.
                val taken = InstalledApps.installedPackageNames(this) +
                    CloneStore.load(this).map { it.packageName }
                Analysis(
                    metadata = inspected,
                    splits = splits,
                    findings = CompatibilityChecker.analyze(inspected, android.os.Build.VERSION.SDK_INT, splits),
                    takenPackageNames = taken,
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { analysis ->
                    metadata = analysis.metadata
                    installedSplits = analysis.splits
                    hasBlockers = CompatibilityChecker.hasBlockers(analysis.findings)
                    analyzed = true
                    renderSource(analysis.metadata)
                    renderFindings(analysis.findings)
                    renderPermissions(analysis.metadata)
                    applyDefaults(analysis.metadata, analysis.takenPackageNames)
                    createButton.isEnabled = !hasBlockers && !CloneSession.snapshot().running
                }.onFailure {
                    showFatal(getString(R.string.analysis_failed, it.message ?: it.javaClass.simpleName))
                }
            }
        }
    }

    private data class Analysis(
        val metadata: ApkMetadata,
        val splits: List<String>,
        val findings: List<Finding>,
        val takenPackageNames: Set<String>,
    )

    private fun renderSource(inspected: ApkMetadata) {
        sourceName.text = inspected.label ?: inspected.packageName
        val version = inspected.versionName ?: "?"
        val sdk = "min ${inspected.minSdk ?: "?"} / target ${inspected.targetSdk ?: "?"}"
        sourceDetail.text = buildString {
            append(inspected.packageName)
            append(" · v").append(version)
            append(" · ").append(sdk)
            append(" · ").append(inspected.apkSizeBytes.readableSize())
            append("\n").append(inspected.entryCount).append(" zip entries")
            if (inspected.hasNativeLibraries) {
                append(" · native ").append(inspected.nativeLibraryAbis.joinToString(","))
            }
            if (inspected.hasV2Signature) append(" · v2 signed")
        }
        if (sourcePackage.isEmpty()) {
            // For imported APKs, try reading icon from archive if available
            val archiveInfo = packageManager.getPackageArchiveInfo(inspected.packageName, PackageManager.GET_ACTIVITIES)
            archiveInfo?.applicationInfo?.let { appInfo ->
                appInfo.sourceDir = inspected.packageName
                appInfo.publicSourceDir = inspected.packageName
                runCatching {
                    sourceIcon.setImageDrawable(appInfo.loadIcon(packageManager))
                }
            }
        }
    }

    private fun renderFindings(report: List<Finding>) {
        val summary = if (report.isEmpty()) {
            getString(R.string.compatibility_good)
        } else {
            report.joinToString("\n\n") { finding ->
                val prefix = when (finding.severity) {
                    Severity.BLOCKER -> "BLOCKER — "
                    Severity.WARNING -> "Warning — "
                    Severity.INFO -> "Info — "
                }
                "$prefix${finding.title}\n${finding.detail}"
            }
        }

        findingsText.text = if (hasBlockers) {
            getString(R.string.findings_with_blocker, summary, getString(R.string.findings_blocked))
        } else {
            summary
        }

        val severity = CompatibilityChecker.overallSeverity(report)
        val iconRes = when (severity) {
            Severity.BLOCKER -> R.drawable.ic_error
            Severity.WARNING -> R.drawable.ic_warning
            Severity.INFO -> R.drawable.ic_check_circle
        }
        val colorRes = when (severity) {
            Severity.BLOCKER -> R.color.severity_blocker
            Severity.WARNING -> R.color.severity_warning
            Severity.INFO -> R.color.severity_info
        }

        val color = ContextCompat.getColor(this, colorRes)
        findingsStatusIcon.setImageResource(iconRes)
        findingsStatusIcon.setColorFilter(color)
        findingsText.setTextColor(color)
    }

    private fun renderPermissions(inspected: ApkMetadata) {
        permissionContainer.removeAllViews()
        permissionCheckboxes.clear()
        val permissions = inspected.permissions.sorted()
        if (permissions.isEmpty()) {
            permissionSection.visibility = View.GONE
            return
        }
        permissionSection.visibility = View.VISIBLE
        permissions.forEach { permission ->
            val checkBox = MaterialCheckBox(this).apply {
                text = permission.substringAfterLast('.')
                contentDescription = permission
                textSize = 14f
                setPadding(0, 4, 0, 4)
            }
            permissionContainer.addView(checkBox)
            permissionCheckboxes[permission] = checkBox
        }
    }

    private fun applyDefaults(inspected: ApkMetadata, takenPackageNames: Set<String>) {
        val baseLabel = inspected.label?.takeIf { !it.startsWith("@") } ?: inspected.packageName
        if (labelInput.text.isNullOrBlank()) {
            labelInput.setText(getString(R.string.clone_name_default, baseLabel))
        }
        if (packageInput.text.isNullOrBlank()) {
            packageInput.setText(PackageNames.suggestFree(inspected.packageName, takenPackageNames))
        }
    }

    private fun startClone() {
        val inspected = metadata ?: return
        if (CloneSession.snapshot().running) {
            Toast.makeText(this, R.string.clone_in_progress, Toast.LENGTH_SHORT).show()
            return
        }
        val label = labelInput.text?.toString()?.trim().orEmpty()
        val packageName = packageInput.text?.toString()?.trim().orEmpty()

        labelInputLayout.error = null
        packageInputLayout.error = null

        if (label.isEmpty()) {
            labelInputLayout.error = getString(R.string.name_required)
            return
        }
        PackageNames.problem(packageName)?.let {
            packageInputLayout.error = it
            return
        }
        if (packageName == inspected.packageName) {
            packageInputLayout.error = getString(R.string.package_must_differ)
            return
        }
        if (InstalledApps.isInstalled(this, packageName)) {
            packageInputLayout.error = getString(R.string.package_already_installed, packageName)
            return
        }

        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(packageInput.windowToken, 0)

        resultSection.visibility = View.GONE
        val remove = permissionCheckboxes.filterValues { it.isChecked }.keys
        CloneSession.start(
            this,
            CloneSession.Request(
                sourceApk = requireNotNull(sourceApk),
                sourcePackage = inspected.packageName,
                sourceLabel = inspected.label ?: inspected.packageName,
                targetPackage = packageName,
                targetLabel = label,
                permissionsToRemove = remove,
                disableBackup = disableBackup.isChecked,
                installedSplits = installedSplits,
            )
        )
    }

    private fun render(state: CloneSession.State) {
        if (state.running) {
            progressSection.visibility = View.VISIBLE
            progressBar.progress = state.percent
            progressText.text = getString(R.string.progress_format, state.percent, state.message)
            createButton.isEnabled = false
            createButton.text = getString(R.string.creating_clone)
            resultSection.visibility = View.GONE
            return
        }

        progressSection.visibility = if (state.error != null) View.VISIBLE else View.GONE
        progressText.text = state.error?.let { getString(R.string.clone_failed, it) }.orEmpty()
        // The button stays disabled until the APK has actually been analysed, so
        // a tap during startup can never start an unconfigured clone.
        createButton.isEnabled = analyzed && !hasBlockers
        createButton.text = getString(R.string.create_clone)

        val report = state.report
        // A result belongs to the package this screen is configured for; without
        // this check, opening another app after a clone would show a stale result.
        if (report != null && state.targetPackage == packageInput.text?.toString()?.trim()) {
            lastBuiltPackage = report.changes.newPackage
            val apkFile = File(CloneStore.directory(this), "${report.changes.newPackage}.apk")
            resultSection.visibility = View.VISIBLE
            resultText.text = getString(
                R.string.result_summary,
                report.changes.label,
                report.changes.newPackage,
                apkFile.length().readableSize(),
                report.changes.repackagedEntryCount,
                report.signatureSubject ?: "?",
                report.changes.removedPermissions.size,
                report.changes.renamedAuthorities.size,
            )
        }
    }

    private fun lastCloneFile(): File? {
        val packageName = lastBuiltPackage ?: return null
        val file = File(CloneStore.directory(this), "$packageName.apk")
        return if (file.isFile) file else null
    }

    private fun installLastClone() {
        val file = lastCloneFile() ?: return
        val packageName = lastBuiltPackage ?: return
        if (!ApkInstaller.canInstall(this)) {
            showInstallPermissionDialog(file)
            return
        }
        if (!ApkInstaller.install(this, file, packageName)) {
            ApkInstaller.view(this, file)
        }
    }

    private fun shareLastClone() {
        val file = lastCloneFile() ?: return
        ApkInstaller.share(this, file)
    }

    private fun showInstallPermissionDialog(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.install_permission_title)
            .setMessage(R.string.install_permission_body)
            .setPositiveButton(R.string.open_settings) { _, _ -> ApkInstaller.openInstallPermissionSettings(this) }
            .setNegativeButton(R.string.open_installer) { _, _ -> ApkInstaller.view(this, file) }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun showFatal(message: String) {
        analyzed = false
        sourceName.text = getString(R.string.cannot_clone)
        findingsText.text = message
        createButton.isEnabled = false
    }

    override fun onDestroy() {
        analysisExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_APK_PATH = "com.yundac.apkcloner.extra.APK_PATH"
        private const val EXTRA_PACKAGE = "com.yundac.apkcloner.extra.PACKAGE"
        private const val EXTRA_LABEL = "com.yundac.apkcloner.extra.LABEL"

        fun intent(context: Context, apkPath: String, packageName: String?, label: String?): Intent =
            Intent(context, CloneActivity::class.java).apply {
                putExtra(EXTRA_APK_PATH, apkPath)
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_LABEL, label)
            }
    }
}
