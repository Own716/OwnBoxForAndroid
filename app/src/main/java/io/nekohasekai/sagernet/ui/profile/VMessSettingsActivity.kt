package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.fmt.v2ray.VMessBean

class VMessSettingsActivity : StandardV2RaySettingsActivity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val vmess = proxyEntity?.requireBean() as? VMessBean
        val isXhttp = intent?.getBooleanExtra("xhttp", false) == true ||
            vmess?.type.equals("xhttp", ignoreCase = true) ||
            vmess?.type.equals("splithttp", ignoreCase = true)
        val isReality = vmess?.security.equals("reality", ignoreCase = true) ||
            !vmess?.realityPubKey.isNullOrBlank()
        val isVless = intent?.getBooleanExtra("vless", false) == true ||
            intent?.getBooleanExtra("xhttp", false) == true ||
            vmess?.isVLESS == true
        if (isVless) {
            supportActionBar?.title = when {
                isXhttp -> "VLESS + XHTTP"
                isReality -> "VLESS · Reality"
                else -> "VLESS"
            }
        }
    }

    override fun createEntity() = VMessBean().apply {
        val isXhttp = intent?.getBooleanExtra("xhttp", false) == true
        if (intent?.getBooleanExtra("vless", false) == true || isXhttp) {
            alterId = -1
        }
        if (isXhttp) {
            type = "xhttp"
        }
        initializeDefaultValues()
    }

}
