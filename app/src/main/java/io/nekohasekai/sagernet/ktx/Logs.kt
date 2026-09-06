package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.database.DataStore
import libcore.Libcore
import java.io.InputStream
import java.io.OutputStream

object Logs {

    private fun mkTag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace[4].className.substringAfterLast(".")
    }

    // 级别语义与 ConfigBuilder 的 sing-box log.level 映射一致：
    // 0=panic 1=warn 2=info 3=debug 4=trace。
    // 本通道（Kotlin -> JNI nekoLogPrintln -> Go std log）官方不过滤，
    // 必须在源头按 DataStore.logLevel 门控，否则 warn 档也会冒出 debug 日志。
    // 读取失败（如 DataStore 未就绪）时放行，避免吞掉关键日志。
    private fun enabled(required: Int): Boolean {
        return runCatching { DataStore.logLevel >= required }.getOrDefault(true)
    }

    fun d(message: String) {
        if (!enabled(3)) return
        Libcore.nekoLogPrintln("[Debug] [${mkTag()}] $message")
    }

    fun d(message: String, exception: Throwable) {
        if (!enabled(3)) return
        Libcore.nekoLogPrintln("[Debug] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun i(message: String) {
        if (!enabled(2)) return
        Libcore.nekoLogPrintln("[Info] [${mkTag()}] $message")
    }

    fun i(message: String, exception: Throwable) {
        if (!enabled(2)) return
        Libcore.nekoLogPrintln("[Info] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(message: String) {
        if (!enabled(1)) return
        Libcore.nekoLogPrintln("[Warning] [${mkTag()}] $message")
    }

    fun w(message: String, exception: Throwable) {
        if (!enabled(1)) return
        Libcore.nekoLogPrintln("[Warning] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(exception: Throwable) {
        if (!enabled(1)) return
        Libcore.nekoLogPrintln("[Warning] [${mkTag()}] " + exception.stackTraceToString())
    }

    fun e(message: String) {
        Libcore.nekoLogPrintln("[Error] [${mkTag()}] $message")
    }

    fun e(message: String, exception: Throwable) {
        Libcore.nekoLogPrintln("[Error] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun e(exception: Throwable) {
        Libcore.nekoLogPrintln("[Error] [${mkTag()}] " + exception.stackTraceToString())
    }

}

fun InputStream.use(out: OutputStream) {
    use { input ->
        out.use { output ->
            input.copyTo(output)
        }
    }
}