package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.fmt.v2ray.VMessBean

class VMessSettingsActivity : StandardV2RaySettingsActivity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("vless", false) == true || (proxyEntity?.requireBean() as? VMessBean)?.isVLESS == true) {
            supportActionBar?.title = "VLESS"
        }
    }

    override fun createEntity() = VMessBean().apply {
        if (intent?.getBooleanExtra("vless", false) == true) {
            alterId = -1
        }
        initializeDefaultValues()
    }

}
