// SPDX-FileCopyrightText: 2026 UltimateMail contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package com.qtekfun.ultimatemail.ui.conversation

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * The intents that hand a downloaded attachment to other apps. The file stays in app-private
 * storage; the other app gets a `content://` URI of the app's FileProvider and a one-off read
 * grant, never a path (see `res/xml/file_paths.xml`).
 */
internal object AttachmentIntents {
    private const val GENERIC_TYPE = "application/octet-stream"

    fun uriOf(context: Context, path: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))

    /** The MIME type to open with: the declared one, or one from the extension when it is generic. */
    fun mimeTypeOf(mimeType: String, name: String): String {
        val declared = mimeType.trim().lowercase(Locale.ROOT)
        val byExtension = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
        return when {
            declared.isNotEmpty() && declared != GENERIC_TYPE -> declared
            byExtension != null -> byExtension
            else -> "*/*"
        }
    }

    /** Opens the file in an app that can show it; false when there is none. */
    fun open(context: Context, path: String, mimeType: String, name: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriOf(context, path), mimeTypeOf(mimeType, name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Offers the file to the share sheet; false when nothing could be started. */
    fun share(context: Context, path: String, mimeType: String, name: String): Boolean {
        val uri = uriOf(context, path)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeTypeOf(mimeType, name))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(name, uri)
        return runCatching { context.startActivity(Intent.createChooser(send, name)) }.isSuccess
    }
}
