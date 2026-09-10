package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.res.TypedArrayUtils
import androidx.preference.EditTextPreference
import io.nekohasekai.sagernet.database.DataStore

class UserAgentPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = TypedArrayUtils.getAttr(
        context, androidx.preference.R.attr.editTextPreferenceStyle, android.R.attr.editTextPreferenceStyle
    )
) : EditTextPreference(context, attrs, defStyle) {

    public override fun notifyChanged() {
        super.notifyChanged()
    }

    override fun getSummary(): CharSequence? {
        val custom = text?.trim()
        if (custom.isNullOrBlank()) {
            return DataStore.defaultSubscriptionUserAgent
        }
        return custom
    }

}