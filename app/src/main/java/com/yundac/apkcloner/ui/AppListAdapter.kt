package com.yundac.apkcloner.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import com.yundac.apkcloner.R
import com.yundac.apkcloner.data.InstalledApp

class AppListAdapter(
    private val context: Context,
    private val onItemClick: (InstalledApp) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    private val items = ArrayList<InstalledApp>()

    fun submit(apps: List<InstalledApp>) {
        items.clear()
        items.addAll(apps)
        notifyDataSetChanged()
    }

    fun getItem(position: Int): InstalledApp = items[position]

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = items[position]
        holder.bind(app)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.appIcon)
        private val name: TextView = view.findViewById(R.id.appName)
        private val detail: TextView = view.findViewById(R.id.appDetail)
        private val splitBadge: TextView = view.findViewById(R.id.splitBadge)

        init {
            view.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(items[position])
                }
            }
        }

        fun bind(app: InstalledApp) {
            icon.setImageDrawable(
                app.icon ?: AppCompatResources.getDrawable(context, R.drawable.ic_app_default)
            )
            name.text = app.label
            detail.text = context.getString(
                R.string.app_item_detail,
                app.packageName,
                app.versionName ?: "?",
                app.sizeBytes.readableSize(),
            )

            if (app.splitCount > 0) {
                splitBadge.visibility = View.VISIBLE
                itemView.alpha = 0.65f
            } else {
                splitBadge.visibility = View.GONE
                itemView.alpha = 1.0f
            }
        }
    }
}
