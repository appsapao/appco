package com.yundac.apkcloner.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yundac.apkcloner.R
import com.yundac.apkcloner.data.CloneRecord
import com.yundac.apkcloner.data.CloneStore
import com.yundac.apkcloner.install.ApkInstaller
import com.yundac.apkcloner.ui.theme.ThemePreferences

/** Lists the clone APKs built by this app and lets the user install, share or remove them. */
class ClonesActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private lateinit var emptyStateView: View
    private lateinit var emptyActionButton: MaterialButton

    private val adapter by lazy {
        CloneListAdapter(
            context = this,
            onInstall = { record -> install(record) },
            onShare = { record -> ApkInstaller.share(this, record.apkFile) },
            onDelete = { record -> confirmDelete(record) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences.applyActivityTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_clones)

        toolbar = findViewById(R.id.toolbar)
        list = findViewById(R.id.cloneList)
        emptyStateView = findViewById(R.id.emptyStateView)
        emptyActionButton = findViewById(R.id.emptyActionButton)

        toolbar.setNavigationOnClickListener { finish() }
        emptyActionButton.setOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { _, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            toolbar.updatePadding(top = statusBarInsets.top)
            list.updatePadding(bottom = navBarInsets.bottom + 16)
            emptyStateView.updatePadding(bottom = navBarInsets.bottom)
            insets
        }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val records = CloneStore.load(this)
        adapter.submit(records)
        list.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
        emptyStateView.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun install(record: CloneRecord) {
        if (!ApkInstaller.canInstall(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.install_permission_title)
                .setMessage(R.string.install_permission_body)
                .setPositiveButton(R.string.open_settings) { _, _ -> ApkInstaller.openInstallPermissionSettings(this) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        if (!ApkInstaller.install(this, record.apkFile, record.packageName)) {
            ApkInstaller.view(this, record.apkFile)
        }
    }

    private fun confirmDelete(record: CloneRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.delete_clone_body, record.label))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                CloneStore.remove(this, record)
                refresh()
                Toast.makeText(this, R.string.clone_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
