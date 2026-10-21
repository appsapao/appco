package com.yundac.apkcloner.ui

import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.yundac.apkcloner.R
import com.yundac.apkcloner.data.CloneRecord
import com.yundac.apkcloner.data.CloneStore
import com.yundac.apkcloner.install.ApkInstaller

/** Lists the clone APKs built by this app and lets the user install or remove them. */
class ClonesActivity : AppCompatActivity() {

    private lateinit var list: ListView
    private lateinit var emptyText: TextView
    private val adapter by lazy { CloneListAdapter(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clones)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        list = findViewById(R.id.cloneList)
        emptyText = findViewById(R.id.emptyText)
        list.adapter = adapter

        list.setOnItemClickListener { _, _, position, _ -> install(adapter.getItem(position)) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            showActions(adapter.getItem(position))
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refresh() {
        val records = CloneStore.load(this)
        adapter.submit(records)
        list.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
        emptyText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun install(record: CloneRecord) {
        if (!ApkInstaller.canInstall(this)) {
            AlertDialog.Builder(this)
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

    private fun showActions(record: CloneRecord) {
        AlertDialog.Builder(this)
            .setTitle(record.label)
            .setItems(
                arrayOf(
                    getString(R.string.action_install),
                    getString(R.string.action_share),
                    getString(R.string.action_delete),
                )
            ) { _, which ->
                when (which) {
                    0 -> install(record)
                    1 -> ApkInstaller.share(this, record.apkFile)
                    2 -> confirmDelete(record)
                }
            }
            .show()
    }

    private fun confirmDelete(record: CloneRecord) {
        AlertDialog.Builder(this)
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
