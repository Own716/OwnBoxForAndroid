package io.nekohasekai.sagernet.bg

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.utils.CustomIconManager
import android.service.quicksettings.TileService as BaseTileService

@RequiresApi(24)
class TileService : BaseTileService(), SagerConnection.Callback {
    private val defaultIcon by lazy { Icon.createWithResource(this, R.drawable.ic_throne_tile) }
    private var tapPending = false

    private fun getTileIcon(): Icon {
        val customTileBitmap = if (CustomIconManager.isTileApplied(this)) {
            CustomIconManager.loadTileAlphaBitmap(this)
        } else null
        return if (customTileBitmap != null) {
            Icon.createWithBitmap(customTileBitmap)
        } else {
            defaultIcon
        }
    }

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_TILE)
    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) =
        updateTile(state, profileName)

    override fun onServiceConnected(service: ISagerNetService) {
        updateTile(BaseService.State.values()[service.state], service.profileName)
        if (tapPending) {
            tapPending = false
            onClick()
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val profile = SagerDatabase.proxyDao.getById(id) ?: return
        updateTile(BaseService.State.Connected, profile.displayName())
    }

    override fun onStartListening() {
        super.onStartListening()
        connection.connect(this, this)
    }

    override fun onStopListening() {
        connection.disconnect(this)
        super.onStopListening()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(this::toggle) else toggle()
    }

    private fun updateTile(serviceState: BaseService.State, profileName: String?) {
        qsTile?.apply {
            val currentIcon = getTileIcon()
            icon = currentIcon

            // 防御性空值与空白字符串过滤
            val validProfileName = profileName?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

            when (serviceState) {
                BaseService.State.Idle -> error("serviceState")
                BaseService.State.Connecting -> {
                    state = Tile.STATE_ACTIVE
                    label = getString(R.string.connecting)
                }

                BaseService.State.Connected -> {
                    state = Tile.STATE_ACTIVE
                    label = getString(R.string.tile_connected)
                }

                BaseService.State.Stopping -> {
                    state = Tile.STATE_UNAVAILABLE
                    label = getString(R.string.stopping)
                }

                BaseService.State.Stopped -> {
                    state = Tile.STATE_INACTIVE
                    label = getString(R.string.app_name)
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                setSubtitle(when (serviceState) {
                    BaseService.State.Connected -> validProfileName
                    BaseService.State.Connecting -> validProfileName
                    BaseService.State.Stopping -> null
                    BaseService.State.Stopped -> getString(R.string.not_connected)
                    else -> null
                })
            } else {
                if (serviceState == BaseService.State.Connected && validProfileName != null) {
                    label = validProfileName
                }
            }
            updateTile()
        }
    }

    private fun toggle() {
        val service = connection.service
        if (service == null) tapPending =
            true else BaseService.State.values()[service.state].let { state ->
            when {
                state.canStop -> SagerNet.stopService()
                state == BaseService.State.Stopped -> SagerNet.startService()
            }
        }
    }
}
