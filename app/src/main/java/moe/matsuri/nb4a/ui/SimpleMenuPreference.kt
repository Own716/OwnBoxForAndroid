/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package moe.matsuri.nb4a.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.preference.DropDownPreference
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr

/**
 * Bend [DropDownPreference] to support
 * [Simple Menus](https://material.google.com/components/menus.html#menus-behavior).
 */

open class SimpleMenuPreference
@JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.dropdownPreferenceStyle,
    defStyleRes: Int = 0
) : DropDownPreference(context!!, attrs, defStyleAttr, defStyleRes) {

    private lateinit var mAdapter: SimpleMenuAdapter

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val mSpinner = holder.itemView.findViewById<Spinner>(R.id.spinner) ?: return
        mSpinner.layoutParams?.width = ViewGroup.LayoutParams.WRAP_CONTENT
        mSpinner.setPopupBackgroundResource(R.drawable.bg_spinner_dropdown)
        if (::mAdapter.isInitialized) {
            mAdapter.currentPosition = findIndexOfValue(value)
        }
    }

    override fun createAdapter(): ArrayAdapter<CharSequence?> {
        mAdapter = SimpleMenuAdapter(getContext(), R.layout.simple_menu_dropdown_item)
        mAdapter.currentPosition = findIndexOfValue(value)
        return mAdapter
    }

    override fun setValue(value: String?) {
        super.setValue(value)
        if (::mAdapter.isInitialized) {
            mAdapter.currentPosition = findIndexOfValue(value)
            mAdapter.notifyDataSetChanged()
        }
    }

    private class SimpleMenuAdapter(context: Context, resource: Int) :
        ArrayAdapter<CharSequence?>(context, resource) {

        var currentPosition = -1

        private val density = context.resources.displayMetrics.density
        private val primaryColor = context.getColorAttr(androidx.appcompat.R.attr.colorPrimary)
        private val surfaceColor = context.getColorAttr(com.google.android.material.R.attr.colorSurface)
        private val defaultTextColor = context.getColorAttr(android.R.attr.textColorPrimary)

        private val isDark = ColorUtils.calculateLuminance(surfaceColor) < 0.5

        // Inset pill: Theme primary color with ~14% (light) or ~22% (dark) alpha blended over surface
        private val selectedBgColor = ColorUtils.compositeColors(
            ColorUtils.setAlphaComponent(primaryColor, if (isDark) (255 * 0.22).toInt() else (255 * 0.14).toInt()),
            surfaceColor
        )
        private val selectedStrokeColor = ColorUtils.setAlphaComponent(
            primaryColor,
            if (isDark) (255 * 0.50).toInt() else (255 * 0.38).toInt()
        )

        // 选中的胶囊和弹窗的圆角完全一致，统一采用 24dp
        private val pillRadius = context.resources.getDimension(R.dimen.popup_corner_radius)
        private val pillStrokeWidth = (1.5f * density).toInt()
        // 边距严格统一，确保左右与上下呼吸间距对称平衡
        private val insetH = (4f * density).toInt()
        private val insetV = (2f * density).toInt()

        private val selectedItemDrawable = InsetDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = pillRadius
                setColor(selectedBgColor)
                setStroke(pillStrokeWidth, selectedStrokeColor)
            },
            insetH, insetV, insetH, insetV
        )

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view: View = super.getDropDownView(position, convertView, parent)
            val textView = view as? TextView

            if (position == currentPosition) {
                view.background = selectedItemDrawable
                textView?.setTextColor(primaryColor)
                textView?.setTypeface(null, Typeface.BOLD)
            } else {
                view.background = null
                textView?.setTextColor(defaultTextColor)
                textView?.setTypeface(null, Typeface.NORMAL)
            }
            return view
        }
    }
}
