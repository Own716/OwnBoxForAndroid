package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity

class ProfileSelectActivity : ThemedActivity(R.layout.layout_empty),
    ConfigurationFragment.SelectCallback {

    companion object {
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_PROFILE_ID = "id"
        const val EXTRA_PROFILE_IDS = "ids"
        const val EXTRA_MULTI_SELECT = "multi_select"
        const val EXTRA_SELECTED_IDS = "selected_ids"
        const val EXTRA_EXCLUDE_IDS = "exclude_ids"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) return
        val selected = intent.getParcelableExtra<ProxyEntity>(EXTRA_SELECTED)

        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_holder,
                ConfigurationFragment.forSelection(selected, R.string.select_profile, hideAutoSelectors = true)
            )
            .commitAllowingStateLoss()
    }

    override fun returnProfile(profileId: Long) {
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_PROFILE_ID, profileId)
        })
        finish()
    }

}
