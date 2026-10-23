package com.yundac.apkcloner.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.yundac.apkcloner.R
import com.yundac.apkcloner.data.InstalledApp
import com.yundac.apkcloner.data.InstalledApps
import com.yundac.apkcloner.ui.theme.ThemePreferences
import java.io.File
import java.util.concurrent.Executors

/**
 * Entry point: lists the installed apps that can be cloned and starts the
 * configuration flow for one of them (or for an APK picked from storage).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var filterInput: EditText
    private lateinit var clearFilterButton: ImageButton
    private lateinit var statusText: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var appList: RecyclerView
    private lateinit var emptyStateView: View
    private lateinit var fabPickApk: ExtendedFloatingActionButton

    private val adapter by lazy {
        AppListAdapter(this) { app -> openClone(app) }
    }

    private val pickApkLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            importApk(uri)
        }
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "app-list-loader").apply { isDaemon = true }
    }

    private var allApps: List<InstalledApp> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen initialization
        installSplashScreen()

        // Apply AMOLED theme overlay if configured
        ThemePreferences.applyActivityTheme(this)

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        filterInput = findViewById(R.id.filterInput)
        clearFilterButton = findViewById(R.id.clearFilterButton)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        appList = findViewById(R.id.appList)
        emptyStateView = findViewById(R.id.emptyStateView)
        fabPickApk = findViewById(R.id.fabPickApk)

        // Setup Toolbar & Menu
        toolbar.inflateMenu(R.menu.main)
        toolbar.setOnMenuItemClickListener { onMenuItemSelected(it) }

        // Setup Edge-to-Edge Window Insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { _, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            toolbar.updatePadding(top = statusBarInsets.top)
            appList.updatePadding(bottom = navBarInsets.bottom + 88)
            emptyStateView.updatePadding(bottom = navBarInsets.bottom)
            fabPickApk.updatePadding(bottom = 0) // default layout margin handles elevation
            val lp = fabPickApk.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            lp.bottomMargin = navBarInsets.bottom + 16
            fabPickApk.layoutParams = lp

            insets
        }

        appList.layoutManager = LinearLayoutManager(this)
        appList.adapter = adapter

        // Shrink FAB on scroll
        appList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8 && fabPickApk.isExtended) {
                    fabPickApk.shrink()
                } else if (dy < -8 && !fabPickApk.isExtended) {
                    fabPickApk.extend()
                }
            }
        })

        fabPickApk.setOnClickListener { pickApkFile() }

        clearFilterButton.setOnClickListener {
            filterInput.text?.clear()
        }

        filterInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearFilterButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
        })

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        if (allApps.isNotEmpty()) loadApps()
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        statusText.text = getString(R.string.loading_apps)
        executor.execute {
            val apps = runCatching { InstalledApps.load(this) }.getOrElse { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allApps = apps
                progressBar.visibility = View.GONE
                statusText.text = if (apps.isEmpty()) {
                    getString(R.string.no_apps)
                } else {
                    resources.getQuantityString(R.plurals.apps_count, apps.size, apps.size)
                }
                applyFilter(filterInput.text?.toString().orEmpty())
            }
        }
    }

    private fun applyFilter(query: String) {
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.contains(trimmed, ignoreCase = true) ||
                    it.packageName.contains(trimmed, ignoreCase = true)
            }
        }
        adapter.submit(filtered)

        if (filtered.isEmpty() && allApps.isNotEmpty()) {
            emptyStateView.visibility = View.VISIBLE
            appList.visibility = View.GONE
        } else {
            emptyStateView.visibility = View.GONE
            appList.visibility = View.VISIBLE
        }
    }

    private fun openClone(app: InstalledApp) {
        startActivity(
            CloneActivity.intent(
                context = this,
                apkPath = app.apkPath,
                packageName = app.packageName,
                label = app.label,
            )
        )
    }

    private fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_clones -> {
            startActivity(Intent(this, ClonesActivity::class.java))
            true
        }

        R.id.action_pick_apk -> {
            pickApkFile()
            true
        }

        R.id.action_theme -> {
            ThemePreferences.showThemeDialog(this)
            true
        }

        R.id.action_about -> {
            showAbout()
            true
        }

        else -> false
    }

    private fun pickApkFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/zip"))
        }
        runCatching { pickApkLauncher.launch(intent) }
            .onFailure { Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_SHORT).show() }
    }

    private fun importApk(uri: Uri) {
        progressBar.visibility = View.VISIBLE
        statusText.text = getString(R.string.copying_apk)
        executor.execute {
            val target = File(cacheDir, "imported/source.apk")
            val copied = runCatching {
                target.parentFile?.mkdirs()
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Cannot open the selected file" }
                    target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                }
                target
            }
            runOnUiThread {
                progressBar.visibility = View.GONE
                statusText.text = resources.getQuantityString(R.plurals.apps_count, allApps.size, allApps.size)
                copied.onSuccess { file ->
                    if (file.length() == 0L) {
                        Toast.makeText(this, R.string.empty_file, Toast.LENGTH_LONG).show()
                        return@onSuccess
                    }
                    startActivity(CloneActivity.intent(this, file.absolutePath, null, null))
                }.onFailure {
                    Toast.makeText(this, getString(R.string.import_failed, it.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
