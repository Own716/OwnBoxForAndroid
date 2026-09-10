package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.Theme

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false
    private var lastUseSystemTheme: Boolean = false
    private var lastAmoledTheme: Boolean = false
    private var lastAppTheme: Int = 0
    private var lastCustomThemeColor: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        lastUseSystemTheme = DataStore.useSystemTheme
        lastAmoledTheme = DataStore.amoledTheme
        lastAppTheme = DataStore.appTheme
        lastCustomThemeColor = DataStore.customThemeColor

        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }
        Theme.applyNightTheme()

        super.onCreate(savedInstanceState)

        uiMode = resources.configuration.uiMode
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            
            val insetController = WindowCompat.getInsetsController(window, window.decorView)
            // 三大金刚（系统导航栏）按钮固定为白色（深色款），底色仍为主题 colorPrimaryDark
            insetController.isAppearanceLightNavigationBars = false
            insetController.isAppearanceLightStatusBars = 
                if (DataStore.appTheme == Theme.BLACK) !Theme.usingNightMode() else false
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            findViewById<AppBarLayout>(R.id.appbar)?.apply {
                updatePadding(top = bars.top)
            }
            insets
        }
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    override fun onResume() {
        super.onResume()
        if (lastUseSystemTheme != DataStore.useSystemTheme ||
            lastAmoledTheme != DataStore.amoledTheme ||
            (!DataStore.useSystemTheme && (lastAppTheme != DataStore.appTheme || (DataStore.appTheme == Theme.CUSTOM && lastCustomThemeColor != DataStore.customThemeColor)))) {
            ActivityCompat.recreate(this)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            ActivityCompat.recreate(this)
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar = throw NotImplementedError()

}
