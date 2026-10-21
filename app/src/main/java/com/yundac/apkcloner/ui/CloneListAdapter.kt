package com.yundac.apkcloner.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.yundac.apkcloner.R
import com.yundac.apkcloner.data.CloneRecord

class CloneListAdapter(private val context: Context) : BaseAdapter() {

    private val items = ArrayList<CloneRecord>()

    fun submit(records: List<CloneRecord>) {
        items.clear()
        items.addAll(records)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): CloneRecord = items[position]

    override fun getItemId(position: Int): Long = items[position].packageName.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_clone, parent, false)
        val record = getItem(position)
        val holder = view.tag as? ViewHolder ?: ViewHolder(view).also { view.tag = it }
        holder.name.text = record.label
        holder.detail.text = buildString {
            append(record.packageName)
            append(" · from ").append(record.sourcePackage)
            append("\n").append(record.sizeBytes.readableSize())
            append(" · ").append(record.createdAt.readableDate())
        }
        return view
    }

    private class ViewHolder(view: View) {
        val name: TextView = view.findViewById(R.id.cloneName)
        val detail: TextView = view.findViewById(R.id.cloneDetail)
    }
}
