package dev.stanislav.focusdeck.qa

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract

/** Metadata only: no URI, names, file contents, or exception messages leave the process. */
internal object TelemetryGrantStatusProbe {
    fun run(context: Context): Bundle {
        val prefs = context.getSharedPreferences("telemetry-syncthing", Context.MODE_PRIVATE)
        val uri = prefs.getString("root", null)?.let(Uri::parse)
        val qaRoot = uri != null && uri.authority == "com.android.externalstorage.documents" &&
            runCatching { DocumentsContract.getTreeDocumentId(uri).matches(Regex("primary:Documents/DB1-Sync/FocusDeck/Telemetry/v1/qa-[0-9a-f-]{36}")) }.getOrDefault(false) &&
            prefs.getBoolean("telemetryRoot", false)
        val grant = context.contentResolver.persistedUriPermissions.firstOrNull { qaRoot && it.uri == uri }
        val writable = if (uri == null || !qaRoot || grant?.isReadPermission != true || !grant.isWritePermission) false else runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
            } == true
        }.getOrDefault(false)
        return Bundle().apply {
            putBoolean("qa_root", qaRoot)
            putBoolean("read_grant", grant?.isReadPermission == true)
            putBoolean("write_grant", grant?.isWritePermission == true)
            putBoolean("directory_reports_create", writable)
        }
    }
}
