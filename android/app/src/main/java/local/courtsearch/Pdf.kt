package local.courtsearch

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Writes a PDF into the cache and opens a chooser so the person can view or share it. */
fun openPdf(context: Context, bytes: ByteArray, filename: String) {
    val dir = File(context.cacheDir, "pdfs").apply { mkdirs() }
    val file = File(dir, safeFileName(filename))
    file.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(share, "Open PDF").apply {
        if (view.resolveActivity(context.packageManager) != null) {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(view))
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}
