package com.yundac.apkcloner.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.yundac.apkcloner.R
import com.yundac.apkcloner.data.InstalledApp

class AppListAdapter(private val context: Context) : BaseAdapter() {

    private val items = ArrayList<InstalledApp>()

    fun submit(apps: List<InstalledApp>) {
        items.clear()
        items.addAll(apps)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): InstalledApp = items[position]

    override fun getItemId(position: Int): Long = items[position].packageName.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
        val app = getItem(position)

        val holder = view.tag as? ViewHolder ?: ViewHolder(view).also { view.tag = it }
        holder.icon.setImageDrawable(
            app.icon ?: androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_app_default)
        )
        holder.name.text = app.label
        holder.detail.text = if (app.splitCount > 0) {
            context.getString(
                R.string.app_item_detail_split,
                app.packageName,
                app.versionName ?: "?",
                app.sizeBytes.readableSize(),
            )
        } else {
            context.getString(
                R.string.app_item_detail,
                app.packageName,
                app.versionName ?: "?",
                app.sizeBytes.readableSize(),
            )
        }
        holder.detail.alpha = if (app.splitCount > 0) 0.6f else 1f

        return view
    }

    private class ViewHolder(view: View) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val detail: TextView = view.findViewById(R.id.appDetail)
    }
}
