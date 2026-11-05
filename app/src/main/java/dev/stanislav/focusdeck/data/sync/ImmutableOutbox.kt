package dev.stanislav.focusdeck.data.sync

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal const val SYNC_MAX_BYTES=128*1024
internal const val SYNC_MAX_EVENTS=256
internal fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}

internal data class SyncEntry(val eventId:String,val json:String) {
    init {
        require(UUID.fromString(eventId).toString()==eventId)
        require(json.startsWith("{\"schemaVersion\":1,\"eventId\":\"$eventId\""))
        require(!json.contains('\n') && json.toByteArray(Charsets.UTF_8).size+1<=SYNC_MAX_BYTES)
    }
}
data class EnqueueResult(val added:Int,val pendingBatches:Int)

/** Only this installation writes its producer directory. No credential, title, note or URL is accepted here. */
internal class ImmutableOutbox(private val directory:File) {
    init { check(directory.isDirectory || directory.mkdirs()) }
    val producerId:String get()=locked {
        val file=File(directory,"producer-id")
        if(!file.exists())atomic(file,(UUID.randomUUID().toString()+"\n").toByteArray())
        file.readText().trim().also { require(UUID.fromString(it).toString()==it) }
    }
    private fun <T> locked(block:()->T):T = synchronized(globalLock) {
        RandomAccessFile(File(directory,"queue.lock"),"rw").use { stream -> stream.channel.lock().use { block() } }
    }
    fun enqueue(entries:List<SyncEntry>):EnqueueResult=locked {
        val pending=File(directory,"pending").apply { check(isDirectory || mkdirs()) }
        val index=File(directory,"seen.tsv")
        val seen=linkedMapOf<String,String>()
        if(index.exists())index.readLines().filter(String::isNotBlank).forEach { line ->
            val parts=line.split('\t');require(parts.size==2 && parts[1].matches(Regex("[a-f0-9]{64}")))
            require(UUID.fromString(parts[0]).toString()==parts[0]);seen[parts[0]]=parts[1]
        }
        // Recover a crash between durable batch write and index commit.
        files(pending).forEach { batch -> verified(batch).lineSequence().filter(String::isNotBlank).forEach { line ->
            val id=Regex("^\\{\"schemaVersion\":1,\"eventId\":\"([a-f0-9-]{36})\"").find(line)?.groupValues?.get(1) ?: error("Invalid local batch")
            val hash=digest(line.toByteArray());require(seen[id]==null || seen[id]==hash);seen[id]=hash
        } }
        val fresh=mutableListOf<SyncEntry>()
        entries.forEach { entry ->
            val hash=digest(entry.json.toByteArray())
            require(seen[entry.eventId]==null || seen[entry.eventId]==hash){"Immutable event ID conflict"}
            if(seen[entry.eventId]==null){fresh+=entry;seen[entry.eventId]=hash}
        }
        val indexBytes=seen.entries.sortedBy { it.key }.joinToString("\n",postfix=if(seen.isEmpty())"" else "\n"){"${it.key}\t${it.value}"}.toByteArray()
        require(indexBytes.size<=16*1024*1024)
        require(files(pending).sumOf { it.length() }+fresh.sumOf { it.json.toByteArray().size+1 }<=32*1024*1024)
        var lines=mutableListOf<String>();var size=0
        fun flush() {
            if(lines.isEmpty())return
            val bytes=(lines.joinToString("\n")+"\n").toByteArray()
            val file=File(pending,digest(bytes)+".jsonl")
            if(file.exists())check(file.readBytes().contentEquals(bytes)) else atomic(file,bytes)
            lines=mutableListOf();size=0
        }
        fresh.sortedBy { it.eventId }.forEach { entry ->
            val bytes=entry.json.toByteArray().size+1
            if(lines.size==SYNC_MAX_EVENTS || size+bytes>SYNC_MAX_BYTES)flush()
            lines+=entry.json;size+=bytes
        }
        flush()
        atomic(index,indexBytes)
        EnqueueResult(fresh.size,files(pending).size)
    }
    fun pending():List<File> = locked { files(File(directory,"pending")).onEach { verified(it) } }
    fun acknowledge(file:File) = locked {
        val expected=File(directory,"pending").canonicalFile
        require(file.parentFile.canonicalFile==expected)
        verified(file);check(file.delete() || !file.exists())
    }
    private fun files(parent:File)=parent.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.jsonl")) }.sortedBy { it.name }
    private fun verified(file:File):String {
        require(file.length() in 1..SYNC_MAX_BYTES.toLong())
        val bytes=file.readBytes();require(digest(bytes)==file.name.removeSuffix(".jsonl"))
        val text=bytes.toString(Charsets.UTF_8);require(text.endsWith('\n') && text.lineSequence().count { it.isNotBlank() }<=SYNC_MAX_EVENTS)
        return text
    }
    private fun atomic(file:File,bytes:ByteArray) {
        val temp=File(file.parentFile,".${UUID.randomUUID()}.tmp")
        try {
            RandomAccessFile(temp,"rw").use { it.write(bytes);it.fd.sync() }
            try { Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
            catch(_:java.nio.file.AtomicMoveNotSupportedException){Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING)}
        } finally { temp.delete() }
    }
    companion object { private val globalLock=Any() }
}
