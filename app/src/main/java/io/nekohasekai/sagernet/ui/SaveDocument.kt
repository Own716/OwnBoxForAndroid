package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import java.io.File

/**
 * CreateDocument that still saves on devices without a document picker (Android TV builds without DocumentsUI): the
 * callback gets a file in Download/OwnBox (the app's external files folder before Android 10) and a dialog names it.
 */
class SaveDocument(private val mime: String) : ActivityResultContract<String, Uri?>() {

    private val picker = ActivityResultContracts.CreateDocument(mime)

    override fun createIntent(context: Context, input: String): Intent = picker.createIntent(context, input)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = picker.parseResult(resultCode, intent)

    override fun getSynchronousResult(context: Context, input: String): SynchronousResult<Uri?>? {
        if (createIntent(context, input).resolveActivity(context.packageManager) != null) return null
        val (uri, location) = try {
            fallback(context, input.replace('/', '_').replace('\\', '_'))
        } catch (e: Exception) {
            Logs.w(e)
            null
        } ?: return null
        Handler(Looper.getMainLooper()).post {
            if (context is Activity && (context.isFinishing || context.isDestroyed)) return@post
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.save_fallback_title)
                .setMessage(context.getString(R.string.save_fallback_message, location))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        return SynchronousResult(uri)
    }

    private fun fallback(context: Context, name: String): Pair<Uri, String>? {
        if (Build.VERSION.SDK_INT >= 29) {
            val folder = "${Environment.DIRECTORY_DOWNLOADS}/OwnBox"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            val stored = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
            return uri to "$folder/$stored"
        }
        val dir = context.getExternalFilesDir(null) ?: return null
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var file = File(dir, name)
        var index = 1
        while (file.exists()) file = File(dir, "${name.substring(0, dot)} (${index++})${name.substring(dot)}")
        return Uri.fromFile(file) to file.absolutePath
    }
}
