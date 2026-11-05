package dev.stanislav.focusdeck.data.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.UUID

internal data class RemoteRead(val entries:List<SyncEntry>,val state:String,val batches:Int,val invalid:Int,val duplicates:Int,val conflicts:Int)

/** Reads only the peer's explicit events/mac namespace. Never opens or edits any task/note. */
internal class SafTelemetryReader(private val context:Context) {
    fun read(rootUri:Uri,telemetryRoot:Boolean):RemoteRead {
        try {
            require(selectedTelemetryTree(context,rootUri,write=false))
            var directory=requireNotNull(DocumentFile.fromTreeUri(context,rootUri))
            val segments=if(telemetryRoot)listOf("events","mac") else listOf("FocusDeck","Telemetry","v1","events","mac")
            for(segment in segments)directory=directory.findFile(segment)?.takeIf { it.isDirectory }
                ?: return RemoteRead(emptyList(),"missing",0,0,0,0)
            val seen=linkedMapOf<String,SyncEntry>();val conflicts=mutableSetOf<String>()
            var valid=0;var invalid=0;var duplicate=0;var temporary=0
            val producers=directory.listFiles().filter { it.isDirectory && canonicalUuid(it.name) }
            require(producers.size<=256)
            for(producer in producers)for(batch in producer.listFiles()) {
                val name=batch.name ?: continue
                if(syncedBatchDigest(name)==null){temporary++;continue}
                if(valid+invalid>=10000)error("Batch limit")
                try {
                    require(batch.isFile && batch.length() in 1..SYNC_MAX_BYTES.toLong())
                    val bytes=requireNotNull(context.contentResolver.openInputStream(batch.uri)).use { it.readNBytes(SYNC_MAX_BYTES+1) }
                    require(bytes.size<=SYNC_MAX_BYTES && digest(bytes)==syncedBatchDigest(name))
                    val text=bytes.toString(Charsets.UTF_8)
                    require(text.endsWith('\n') && text.lineSequence().count(String::isNotBlank) in 1..SYNC_MAX_EVENTS)
                    val entries=TelemetrySnapshotCodec.entries(text,expectedSource="mac",strict=true)
                    valid++
                    for(entry in entries) {
                        val old=seen[entry.eventId]
                        if(old!=null){if(old.json!=entry.json)conflicts+=entry.eventId else duplicate++}else seen[entry.eventId]=entry
                    }
                } catch(_:Exception){invalid++}
            }
            return RemoteRead(seen.values.filter { it.eventId !in conflicts },if(invalid>0 || conflicts.isNotEmpty() || temporary>0)"partial" else if(valid==0)"empty" else "observed",valid,invalid,duplicate,conflicts.size)
        } catch(_:Exception){return RemoteRead(emptyList(),"unavailable",0,0,0,0)}
    }
    private fun canonicalUuid(value:String?)=value!=null && runCatching { UUID.fromString(value).toString()==value }.getOrDefault(false)
}
