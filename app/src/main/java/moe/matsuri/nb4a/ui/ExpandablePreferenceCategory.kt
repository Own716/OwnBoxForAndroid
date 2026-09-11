package moe.matsuri.nb4a.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.utils.Theme

class ExpandablePreferenceCategory @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceCategoryStyle,
    defStyleRes: Int = 0,
) : PreferenceCategory(context, attrs, defStyleAttr, defStyleRes) {

    private val childVisibilityRules = mutableMapOf<String, () -> Boolean>()

    var isExpanded: Boolean = false
        private set

    init {
        isSelectable = true
        layoutResource = R.layout.layout_expandable_category
    }

    override fun isSelectable(): Boolean = true
    override fun isEnabled(): Boolean = true

    override fun addPreference(preference: Preference): Boolean {
        val result = super.addPreference(preference)
        if (!isExpanded) {
            preference.isVisible = false
        }
        return result
    }

    fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        applyChildrenVisibility()
        notifyChanged()
    }

    fun toggle() {
        setExpanded(!isExpanded)
    }

    fun setChildVisibilityRule(key: String, rule: () -> Boolean) {
        childVisibilityRules[key] = rule
        if (isExpanded) {
            findPreference<Preference>(key)?.isVisible = rule()
        }
    }

    fun updateChildVisibility(key: String) {
        if (isExpanded) {
            val rule = childVisibilityRules[key]
            findPreference<Preference>(key)?.isVisible = rule?.invoke() ?: true
        }
    }

    fun applyChildrenVisibility() {
        for (i in 0 until preferenceCount) {
            val child = getPreference(i)
            val shouldShow = if (isExpanded) {
                childVisibilityRules[child.key]?.invoke() ?: true
            } else {
                false
            }
            child.isVisible = shouldShow
        }
    }

    override fun onClick() {
        super.onClick()
        toggle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val primaryColor = Theme.getPrimaryColor(context)

        val titleView = holder.findViewById(android.R.id.title) as? TextView
        titleView?.setTextColor(primaryColor)

        val arrow = holder.findViewById(R.id.category_arrow) as? ImageView
        if (arrow != null) {
            arrow.imageTintList = ColorStateList.valueOf(primaryColor)
            arrow.setImageResource(
                if (isExpanded) R.drawable.ic_baseline_keyboard_arrow_up_24
                else R.drawable.ic_baseline_keyboard_arrow_down_24
            )
        }
    }
}
