package dev.stanislav.focusdeck.data.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** A derived child-document URI is not a newly selected tree; fromTreeUri would silently expand it. */
internal fun selectedTelemetryTree(context:Context,uri:Uri,write:Boolean):Boolean = runCatching {
    require(uri.scheme=="content" && uri.authority=="com.android.externalstorage.documents" && DocumentsContract.isTreeUri(uri))
    val canonical=DocumentsContract.buildTreeDocumentUri(uri.authority,DocumentsContract.getTreeDocumentId(uri))
    require(uri==canonical)
    context.contentResolver.persistedUriPermissions.any { it.uri==uri && it.isReadPermission && (!write || it.isWritePermission) }
}.getOrDefault(false)

internal fun syncedBatchDigest(name:String):String? = Regex("^([a-f0-9]{64})(?:\\.retry[1-3])?\\.jsonl$").matchEntire(name)?.groupValues?.get(1)
