package dev.stanislav.focusdeck.data.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.UUID

/** Only its producer namespace; no notes. Staging checkpoints let a killed process retry without new Room events. */
internal class SafBatchPublisher(private val context:Context) {
    private val stages=context.getSharedPreferences("telemetry-sync-stages",Context.MODE_PRIVATE)
    fun publish(rootUri:Uri,telemetryRoot:Boolean,producerId:String,batch:File) {
        require(UUID.fromString(producerId).toString()==producerId)
        require(batch.name.matches(Regex("[a-f0-9]{64}\\.jsonl")))
        val bytes=batch.readBytes();require(bytes.size in 1..SYNC_MAX_BYTES && digest(bytes)+".jsonl"==batch.name)
        require(selectedTelemetryTree(context,rootUri,write=true))
        var directory=requireNotNull(DocumentFile.fromTreeUri(context,rootUri))
        val segments=if(telemetryRoot)listOf("events","android",producerId) else listOf("FocusDeck","Telemetry","v1","events","android",producerId)
        segments.forEach { segment -> directory=directory.findFile(segment)?.also { require(it.isDirectory) }
            ?: requireNotNull(directory.createDirectory(segment)) }
        val stageKey=digest((rootUri.toString()+"|"+telemetryRoot+"|"+producerId+"|"+batch.name).toByteArray())
        var target:String?=null
        for(copy in 0..3) {
            val name=if(copy==0)batch.name else batch.name.removeSuffix(".jsonl")+".retry$copy.jsonl"
            val existing=directory.findFile(name)
            if(existing==null){target=name;break}
            if(matches(existing,bytes)) {
                val old=stages.getString(stageKey,null)
                if(old!=null && old.matches(Regex("\\.[a-f0-9-]{36}\\.part")))directory.findFile(old)?.delete()
                stages.edit().remove(stageKey).commit()
                return
            }
            // Keep mismatching objects untouched. A bounded deterministic alternate carries identical immutable bytes.
        }
        requireNotNull(target){"All deterministic copies conflict; queue retained"}
        val stageName=stages.getString(stageKey,null) ?: ("."+UUID.randomUUID()+".part").also {
            check(stages.edit().putString(stageKey,it).commit())
        }
        require(stageName.matches(Regex("\\.[a-f0-9-]{36}\\.part")))
        val temporary=directory.findFile(stageName) ?: requireNotNull(directory.createFile("application/octet-stream",stageName))
        require(temporary.name==stageName && temporary.isFile)
        // Only our checkpointed, uncommitted stage can be rewritten. Final or foreign mismatches are never deleted.
        requireNotNull(context.contentResolver.openOutputStream(temporary.uri,"wt")).use { it.write(bytes);it.flush() }
        check(matches(temporary,bytes))
        check(temporary.renameTo(target) && temporary.name==target)
        check(matches(temporary,bytes))
        check(stages.edit().remove(stageKey).commit())
    }
    private fun matches(file:DocumentFile,expected:ByteArray):Boolean = runCatching {
        require(file.isFile && file.length()<=SYNC_MAX_BYTES)
        val bytes=requireNotNull(context.contentResolver.openInputStream(file.uri)).use { it.readNBytes(SYNC_MAX_BYTES+1) }
        bytes.contentEquals(expected)
    }.getOrDefault(false)
}
