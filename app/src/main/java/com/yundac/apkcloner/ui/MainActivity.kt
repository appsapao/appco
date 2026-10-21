package com.yundac.apkcloner.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.yundac.apkcloner.R
import com.yundac.apkcloner.data.InstalledApp
import com.yundac.apkcloner.data.InstalledApps
import java.io.File
import java.util.concurrent.Executors

/**
 * Entry point: lists the installed apps that can be cloned and starts the
 * configuration flow for one of them (or for an APK picked from storage).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var filterInput: EditText
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var appList: ListView

    private val adapter by lazy { AppListAdapter(this) }
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "app-list-loader").apply { isDaemon = true }
    }

    private var allApps: List<InstalledApp> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        filterInput = findViewById(R.id.filterInput)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        appList = findViewById(R.id.appList)

        appList.adapter = adapter
        appList.setOnItemClickListener { _, _, position, _ -> openClone(adapter.getItem(position)) }
        filterInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_clones -> {
            startActivity(Intent(this, ClonesActivity::class.java))
            true
        }

        R.id.action_pick_apk -> {
            pickApkFile()
            true
        }

        R.id.action_about -> {
            showAbout()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun pickApkFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/zip"))
        }
        runCatching { startActivityForResult(intent, REQUEST_PICK_APK) }
            .onFailure { Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Uses the classic result API to avoid an extra androidx.activity dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_APK || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        importApk(uri)
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
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_PICK_APK = 1001
    }
}

