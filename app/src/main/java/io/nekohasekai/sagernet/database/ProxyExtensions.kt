package io.nekohasekai.sagernet.database

import android.content.Context
import android.content.Intent
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType

enum class TestShowItems(val value: Int) {
    NONE(0),
    IP_ONLY(1),
    SPEED_ONLY(2),
    ALL(3);

    val showIp: Boolean get() = this == IP_ONLY || this == ALL
    val showSpeed: Boolean get() = this == SPEED_ONLY || this == ALL

    companion object {
        fun of(value: Int): TestShowItems = entries.firstOrNull { it.value == value } ?: ALL
    }
}

enum class TrafficBy(val value: Int) {
    TOTAL(0), DL(1), UL(2);

    companion object {
        fun of(v: Int): TrafficBy = entries.firstOrNull { it.value == v } ?: TOTAL
    }
}

enum class TypeBy(val value: Int) {
    BY_TYPE(0), BY_SECURITY(1);

    companion object {
        fun of(v: Int): TypeBy = entries.firstOrNull { it.value == v } ?: BY_TYPE
    }
}

enum class TestBy(val value: Int) {
    LATENCY(0), DL_SPEED(1), UL_SPEED(2), IP_OUT(3);

    companion object {
        fun of(v: Int): TestBy = entries.firstOrNull { it.value == v } ?: LATENCY
    }
}

data class ProfileName(
    val id: Long = 0L,
    val groupId: Long = 0L,
    val type: String = "",
    val name: String? = null,
)

data class ProfilePosition(
    val id: Long = 0L,
    val userOrder: Long = 0L,
)

class FakeOutbound(val entity: ProxyEntity) {
    val name: String get() = entity.displayName()
    fun getAddress(): String = entity.displayAddress()
    val invalid: Boolean get() = false
    fun displaySecurity(skipCert: Boolean = false): String = entity.displayType()
    fun effectiveSecurity(skipCert: Boolean = false) = object {
        val isInsecure: Boolean get() = false
    }
}

val ProxyGroup.archive: Boolean get() = false



class SubscriptionOptions(val group: ProxyGroup) {
    val showInfoCard: Boolean get() = true
    val website: String get() = group.subInfo.webUrl.ifEmpty { group.subscription?.link.orEmpty() }
    val autoUpdate: Boolean get() = group.subscription?.autoUpdate == true
}

val ProxyGroup.subOptions: SubscriptionOptions get() = SubscriptionOptions(this)

val ProxyGroup.subInfo: SubUserInfo
    get() {
        val sub = subscription ?: return SubUserInfo()
        val raw = sub.subscriptionUserinfo
        if (!raw.isNullOrBlank()) {
            val parsed = if (raw.trim().startsWith("{")) SubUserInfo.parse(raw) else SubUserInfo.parseHeader(raw)
            if (parsed.valid) return parsed
        }
        val info = SubUserInfo()
        if (sub.bytesUsed != null || sub.bytesRemaining != null) {
            val used = sub.bytesUsed ?: 0L
            val rem = sub.bytesRemaining ?: 0L
            info.upload = used / 2
            info.download = used - info.upload
            info.total = used + rem
            info.hasQuota = true
            info.valid = true
        }
        if (sub.expiryDate != null && sub.expiryDate > 0) {
            info.expire = sub.expiryDate.toLong()
            info.valid = true
        }
        return info
    }

val ProxyGroup.subLastUpdate: Long get() = (subscription?.lastUpdated ?: 0).toLong()
