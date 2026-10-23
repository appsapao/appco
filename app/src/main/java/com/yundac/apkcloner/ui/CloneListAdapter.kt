package com.yundac.apkcloner.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.yundac.apkcloner.R
import com.yundac.apkcloner.data.CloneRecord

class CloneListAdapter(
    private val context: Context,
    private val onInstall: (CloneRecord) -> Unit,
    private val onShare: (CloneRecord) -> Unit,
    private val onDelete: (CloneRecord) -> Unit,
) : RecyclerView.Adapter<CloneListAdapter.ViewHolder>() {

    private val items = ArrayList<CloneRecord>()

    fun submit(records: List<CloneRecord>) {
        items.clear()
        items.addAll(records)
        notifyDataSetChanged()
    }

    fun getItem(position: Int): CloneRecord = items[position]

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_clone, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = items[position]
        holder.bind(record)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.cloneName)
        private val detail: TextView = view.findViewById(R.id.cloneDetail)
        private val btnInstall: MaterialButton = view.findViewById(R.id.btnInstall)
        private val btnShare: ImageButton = view.findViewById(R.id.btnShare)
        private val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)

        fun bind(record: CloneRecord) {
            name.text = record.label
            detail.text = buildString {
                append(record.packageName)
                append(" · from ").append(record.sourcePackage)
                append("\n").append(record.sizeBytes.readableSize())
                append(" · ").append(record.createdAt.readableDate())
            }

            itemView.setOnClickListener { onInstall(record) }
            btnInstall.setOnClickListener { onInstall(record) }
            btnShare.setOnClickListener { onShare(record) }
            btnDelete.setOnClickListener { onDelete(record) }
        }
    }
}
