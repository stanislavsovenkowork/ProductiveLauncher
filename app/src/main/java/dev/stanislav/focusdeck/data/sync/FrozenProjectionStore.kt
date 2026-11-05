package dev.stanislav.focusdeck.data.sync

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** First safe wire projection per event ID stays immutable when a later app learns a new reason code. */
internal class FrozenProjectionStore(private val directory:File) {
    init { check(directory.isDirectory || directory.mkdirs()) }
    private val target=File(directory,"projections.jsonl")
    fun all():List<SyncEntry> = locked { read().values.toList() }
    fun freeze(entries:List<SyncEntry>):List<SyncEntry> = locked {
        val known=read()
        val result=entries.map { next ->
            val old=known[next.eventId]
            if(old!=null){require(withoutReason(old.json)==withoutReason(next.json)){"Immutable core payload conflict"};old}
            else {known[next.eventId]=next;next}
        }
        val bytes=known.values.joinToString("\n",postfix=if(known.isEmpty())"" else "\n"){it.json}.toByteArray()
        require(bytes.size<=16*1024*1024)
        val temp=File(directory,".${UUID.randomUUID()}.projection.tmp")
        try {
            RandomAccessFile(temp,"rw").use { it.write(bytes);it.fd.sync() }
            try { Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
            catch(_:java.nio.file.AtomicMoveNotSupportedException){Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)}
        } finally { temp.delete() }
        result
    }
    private fun read():LinkedHashMap<String,SyncEntry> {
        val result=linkedMapOf<String,SyncEntry>()
        if(target.exists()) {
            require(target.length()<=16*1024*1024)
            target.forEachLine { line -> if(line.isNotBlank()) {
                val id=Regex("^\\{\"schemaVersion\":1,\"eventId\":\"([a-f0-9-]{36})\"").find(line)?.groupValues?.get(1) ?: error("Projection cache invalid")
                require(result[id]==null || result[id]?.json==line);result[id]=SyncEntry(id,line)
            } }
        }
        return result
    }
    private fun withoutReason(value:String)=value.replace(Regex(",\"reason\":(?:\"[A-Za-z0-9_]+\"|null)"),"")
    private fun <T> locked(block:()->T):T=synchronized(lock) {
        RandomAccessFile(File(directory,"projection.lock"),"rw").use { it.channel.lock().use { block() } }
    }
    companion object { private val lock=Any() }
}
