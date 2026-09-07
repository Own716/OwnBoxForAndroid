package io.nekohasekai.sagernet.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import io.nekohasekai.sagernet.AppIcon
import io.nekohasekai.sagernet.AppIconManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr

object AppIconDialog {

    fun show(context: Context) {
        val currentIcon = AppIconManager.current(context)
        val density = context.resources.displayMetrics.density
        val iconSize = (52 * density).toInt()

        val previews = mutableMapOf<AppIcon, Bitmap?>()
        for (icon in AppIcon.values()) {
            val drawable = AppIconManager.loadIcon(context, icon)
            previews[icon] = drawable?.toBitmap(iconSize, iconSize)
        }

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_app_icon, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(context)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.change_icon)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        val selectedColor = context.getColorAttr(R.attr.selectedColorPrimary)
        val selectedTextColor = context.getColorAttr(android.R.attr.textColorPrimary)
        val normalTextColor = context.getColorAttr(android.R.attr.textColorPrimary)

        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        val selectableItemBackgroundRes = outValue.resourceId

        recyclerView.adapter = object : RecyclerView.Adapter<IconViewHolder>() {
            override fun getItemCount(): Int = AppIcon.values().size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IconViewHolder {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_icon, parent, false)
                return IconViewHolder(view)
            }

            override fun onBindViewHolder(holder: IconViewHolder, position: Int) {
                val icon = AppIcon.values()[position]
                holder.title.setText(icon.titleRes)
                val bmp = previews[icon]
                if (bmp != null) {
                    holder.preview.setImageBitmap(bmp)
                } else {
                    holder.preview.setImageResource(icon.iconRes)
                }
                holder.preview.shapeAppearanceModel = holder.preview.shapeAppearanceModel
                    .toBuilder()
                    .setAllCornerSizes(14f * density)
                    .build()

                val isSelected = (icon == currentIcon)
                if (isSelected) {
                    val selBg = GradientDrawable().apply {
                        cornerRadius = 16f * density
                        setColor(selectedColor)
                    }
                    holder.container.background = selBg
                    holder.title.setTextColor(selectedTextColor)
                } else {
                    holder.container.setBackgroundResource(selectableItemBackgroundRes)
                    holder.title.setTextColor(normalTextColor)
                }

                holder.container.setOnClickListener {
                    if (icon != currentIcon) {
                        AppIconManager.set(context, icon)
                    }
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private class IconViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val container: View = itemView.findViewById(R.id.itemContainer)
        val preview: ShapeableImageView = itemView.findViewById(R.id.iconPreview)
        val title: TextView = itemView.findViewById(R.id.iconTitle)
    }
}
