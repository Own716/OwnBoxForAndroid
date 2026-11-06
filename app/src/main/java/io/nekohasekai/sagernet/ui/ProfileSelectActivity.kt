package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.dp2px

class ProfileSelectActivity : ThemedActivity(R.layout.layout_profile_select),
    ConfigurationFragment.SelectCallback {

    companion object {
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_PROFILE_ID = "id"
        const val EXTRA_MULTI_SELECT = "multi_select"
        const val EXTRA_SELECTED_IDS = "selected_ids"
        const val EXTRA_EXCLUDE_IDS = "exclude_ids"
        const val EXTRA_PROFILE_IDS = "profile_ids"
    }

    private var isMultiSelect = false
    private val selectedIds = LinkedHashSet<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selected = intent.getParcelableExtra<ProxyEntity>(EXTRA_SELECTED)
        isMultiSelect = intent.getBooleanExtra(EXTRA_MULTI_SELECT, false)
        val initialSelected = intent.getLongArrayExtra(EXTRA_SELECTED_IDS)
        val excludeIds = intent.getLongArrayExtra(EXTRA_EXCLUDE_IDS)

        if (initialSelected != null) {
            selectedIds.addAll(initialSelected.toList())
        }

        val bottomBar = findViewById<MaterialCardView>(R.id.bottom_bar)
        val bottomBarContent = findViewById<View>(R.id.bottom_bar_content)
        val fragmentHolder = findViewById<View>(R.id.fragment_holder)
        val rootLayout = findViewById<View>(R.id.profile_select_root) ?: findViewById(android.R.id.content)

        if (isMultiSelect) {
            bottomBar?.isVisible = true
            updateBottomBar()
            findViewById<MaterialButton>(R.id.btn_confirm)?.setOnClickListener {
                setResult(RESULT_OK, Intent().apply {
                    putExtra(EXTRA_PROFILE_IDS, selectedIds.toLongArray())
                })
                finish()
            }
        } else {
            bottomBar?.isVisible = false
        }

        fun getNavigationBarHeightFallback(): Int {
            return runCatching {
                val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
                if (resId > 0) resources.getDimensionPixelSize(resId) else 0
            }.getOrDefault(0)
        }

        fun applyBottomInsets(navBottom: Int) {
            if (isMultiSelect && bottomBar != null) {
                bottomBar.setContentPadding(0, 0, 0, navBottom)
                bottomBarContent?.updatePadding(bottom = navBottom)
                fragmentHolder?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = dp2px(64) + navBottom
                }
            } else {
                bottomBar?.setContentPadding(0, 0, 0, 0)
                bottomBarContent?.updatePadding(bottom = 0)
                fragmentHolder?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = 0
                }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val navBars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.systemBars()
            )
            val navBottom = if (navBars.bottom > 0) navBars.bottom else getNavigationBarHeightFallback()
            applyBottomInsets(navBottom)
            insets
        }
        ViewCompat.requestApplyInsets(rootLayout)

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment(
                    select = true,
                    selectedItem = selected,
                    titleRes = if (isMultiSelect) R.string.select_multi_profile else R.string.select_profile,
                    multiSelect = isMultiSelect,
                    initialSelectedIds = initialSelected,
                    excludedIds = excludeIds
                )
            )
            .commitAllowingStateLoss()
    }

    private fun updateBottomBar() {
        val countText = findViewById<TextView>(R.id.selected_count_text)
        val btnConfirm = findViewById<MaterialButton>(R.id.btn_confirm)
        val count = selectedIds.size
        countText?.text = getString(R.string.selected_count, count)
        btnConfirm?.isEnabled = true
    }

    override fun returnProfile(profileId: Long) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_PROFILE_ID, profileId)
        })
        finish()
    }

    override fun onProfileToggled(profileId: Long, isSelected: Boolean, totalSelected: Int) {
        if (isSelected) {
            selectedIds.add(profileId)
        } else {
            selectedIds.remove(profileId)
        }
        updateBottomBar()
    }
}