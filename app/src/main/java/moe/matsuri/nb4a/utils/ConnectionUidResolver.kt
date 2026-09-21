package moe.matsuri.nb4a.utils

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import android.util.LruCache
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

object ConnectionUidResolver {
    private val portToUidCache = LruCache<Int, Int>(256)

    fun resolveUid(
        context: Context,
        network: String,
        sourceIP: String,
        sourcePort: Int,
        destIP: String,
        destPort: Int
    ): Int {
        if (sourcePort <= 0) return -1
        portToUidCache.get(sourcePort)?.let { return it }

        var resolvedUid = -1

        // Method 1: Android 10+ ConnectivityManager.getConnectionOwnerUid
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null && sourceIP.isNotEmpty() && destIP.isNotEmpty() && destPort > 0) {
                    val proto = if (network.equals("UDP", ignoreCase = true)) OsConstants.IPPROTO_UDP else OsConstants.IPPROTO_TCP
                    val srcAddr = InetSocketAddress(InetAddress.getByName(sourceIP), sourcePort)
                    val dstAddr = InetSocketAddress(InetAddress.getByName(destIP), destPort)
                    val ownerUid = cm.getConnectionOwnerUid(proto, srcAddr, dstAddr)
                    if (ownerUid > 0) {
                        resolvedUid = ownerUid
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // Method 2: Fallback to /proc/net/tcp, /proc/net/tcp6, /proc/net/udp, /proc/net/udp6
        if (resolvedUid <= 0) {
            resolvedUid = findUidFromProcNet(sourcePort)
        }

        if (resolvedUid > 0) {
            portToUidCache.put(sourcePort, resolvedUid)
        }
        return resolvedUid
    }

    private fun findUidFromProcNet(port: Int): Int {
        val hexPort = String.format("%04X", port)
        val procFiles = listOf("/proc/net/tcp", "/proc/net/tcp6", "/proc/net/udp", "/proc/net/udp6")
        for (filePath in procFiles) {
            try {
                val file = File(filePath)
                if (!file.exists() || !file.canRead()) continue
                for (line in file.readLines()) {
                    val tokens = line.trim().split("\\s+".toRegex())
                    if (tokens.size > 7) {
                        val localAddr = tokens[1]
                        if (localAddr.endsWith(":$hexPort", ignoreCase = true)) {
                            val uid = tokens[7].toIntOrNull()
                            if (uid != null && uid > 0) {
                                return uid
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return -1
    }
}
