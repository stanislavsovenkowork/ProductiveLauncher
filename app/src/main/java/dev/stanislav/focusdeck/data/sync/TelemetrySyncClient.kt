package dev.stanislav.focusdeck.data.sync

import android.util.AtomicFile
import org.json.JSONObject
import java.time.Instant
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Existing Syncthing carries verified SAF batches; no HTTP server, cloud, token or socket is used. */
class TelemetrySyncClient(context:Context,restoreSchedule:Boolean=true) {
    private val app=context.applicationContext
    private val prefs=app.getSharedPreferences("telemetry-syncthing",Context.MODE_PRIVATE)
    private val local=File(app.noBackupFilesDir,"telemetry-sync-v1").apply { mkdirs() }
    private val marker=File(local,"enabled")
    private val optedIn=AtomicBoolean(prefs.getBoolean("enabled",false) && marker.isFile)
    private val outbox=ImmutableOutbox(local)
    private val projections=FrozenProjectionStore(local)
    private val latest=AtomicReference<String?>(null)
    private val executor=Executors.newSingleThreadExecutor { task -> Thread(task,"FocusDeckTelemetrySync").apply { isDaemon=true } }
    private val _status=MutableStateFlow("Синхронизация метрик выключена")
    val status:StateFlow<String> = _status
    val review:StateFlow<CrossDeviceReview> = sharedReview
    val enabled:Boolean get()=optedIn.get() && marker.isFile
    val producerId:String get()=outbox.producerId
    val automaticRetryScheduled:Boolean get()=runCatching { app.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID)!=null }.getOrDefault(false)
    init { if(enabled) {
        _status.value="Сохранён добровольный обмен · доставка Syncthing пока неизвестна"
        if(restoreSchedule && !automaticRetryScheduled)schedule()
    } }
    /** Root is the existing user grant, or a separate QA telemetry child grant. No grant is acquired silently. */
    fun configure(rootUri:Uri,telemetryRoot:Boolean=false,enabled:Boolean=true):Boolean {
        if(!enabled){disable();return true}
        if(!selectedTelemetryTree(app,rootUri,write=true)) {
            _status.value="Нет сохранённого права записи выбранной папки · очередь остаётся локально";return false
        }
        val saved=prefs.edit().putString("root",rootUri.toString()).putBoolean("telemetryRoot",telemetryRoot).putString("generation",UUID.randomUUID().toString()).putBoolean("enabled",true).commit()
        if(!saved){_status.value="Выбор не сохранён · обмен не включён";return false}
        try { RandomAccessFile(marker,"rw").use { it.setLength(0);it.write(1);it.fd.sync() } }
        catch(_:Exception){optedIn.set(false);marker.delete();_status.value="Не удалось сохранить добровольное включение · обмен не запущен";return false}
        optedIn.set(true)
        schedule()
        val snapshot=latest.get()
        if(snapshot!=null)updateSnapshot(snapshot) else retry()
        return true
    }
    fun disable() {
        optedIn.set(false)
        val markerRemoved=!marker.exists() || marker.delete()
        val saved=prefs.edit().putBoolean("enabled",false).commit()
        app.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        _status.value=if(saved && markerRemoved)"Обмен метрик выключен · локальная очередь сохранена" else "Не удалось сохранить выключение · проверьте хранилище"
    }
    /** Called on existing Room/audit snapshot change. It never opens notes or requires a connected Mac. */
    fun updateSnapshot(jsonl:String) {
        latest.set(jsonl)
        if(!enabled)return
        executor.execute {
            try { outbox.enqueue(projections.freeze(TelemetrySnapshotCodec.entries(jsonl)));publishPending() }
            catch(_:Exception){_status.value="Метрики не доставлены · локальный источник и очередь сохранены; повторим позже"}
        }
    }
    fun retry() { if(enabled)executor.execute { retryBlocking() } }
    internal fun retryBlocking():Boolean {
        return try {
            if(!enabled)true else {
                outbox.enqueue(projections.all()) // Recover a crash between projection, queue write and acknowledgement.
                publishPending();true
            }
        } catch(_:Exception){_status.value="Папка/право недоступны · очередь сохранена до повтора";false}
    }
    private fun publishPending()=synchronized(publishLock) {
        if(!enabled)return@synchronized
        val root=Uri.parse(prefs.getString("root",null) ?: error("No selected root"))
        val telemetryRoot=prefs.getBoolean("telemetryRoot",false)
        val generation=prefs.getString("generation",null)
        val publisher=SafBatchPublisher(app)
        val files=outbox.pending()
        files.forEach { file ->
            if(!enabled || prefs.getString("generation",null)!=generation)return@synchronized
            publisher.publish(root,telemetryRoot,outbox.producerId,file)
            outbox.acknowledge(file)
        }
        _status.value="Метрики записаны в папку · получение Mac зависит от Syncthing, сети и сна устройств"+if(automaticRetryScheduled)"" else " · автоповтор не назначен"
        if(enabled && prefs.getString("generation",null)==generation)readMac(root,telemetryRoot)
    }
    private fun readMac(root:Uri,telemetryRoot:Boolean) {
        val received=SafTelemetryReader(app).read(root,telemetryRoot)
        val cache=AtomicFile(File(local,"mac-received.jsonl"))
        try {
            if(received.batches>0) {
                var stream:java.io.FileOutputStream?=null
                try {
                    val bytes=received.entries.joinToString("\n",postfix=if(received.entries.isEmpty())"" else "\n"){it.json}.toByteArray()
                    require(bytes.size<=32*1024*1024)
                    stream=cache.startWrite();stream.write(bytes);cache.finishWrite(stream)
                } catch(error:Exception){if(stream!=null)cache.failWrite(stream);throw error}
            }
            val mac=if(cache.baseFile.isFile)TelemetrySnapshotCodec.entries(String(cache.readFully(),Charsets.UTF_8),"mac",strict=true) else emptyList()
            val own=latest.get() ?: File(app.filesDir,"harness/events.jsonl").takeIf { it.isFile && it.length()<=32*1024*1024 }?.readText()
            val android=own?.let { TelemetrySnapshotCodec.entries(it) }.orEmpty()
            val samples=(mac+android).map { entry ->
                val row=JSONObject(entry.json)
                fun instant(key:String):Long?=if(!row.has(key) || row.isNull(key))null else Instant.parse(row.getString(key)).toEpochMilli()
                val metrics=row.getJSONObject("metrics")
                MetricSample(entry.eventId,digest(entry.json.toByteArray()),if(row.isNull("category"))null else row.getString("category"),
                    requireNotNull(instant("occurredAt")),instant("startedAt"),instant("endedAt"),
                    if(!metrics.has("focusSeconds") || metrics.isNull("focusSeconds"))null else metrics.getDouble("focusSeconds"),row.getString("evidence"))
            }
            val computed=CrossDeviceMath.review(samples,Instant.now())
            val receipt=if(mac.isEmpty())null else mac.size
            sharedReview.value=computed.copy(macEvents=receipt,remoteState=received.state,
                message="Mac: "+(receipt?.let { "$it событий получено" } ?: "ещё нет данных")+" · "+received.state+
                    " · часы по таймеру, пересечения устройств объединены; внимание неизвестно"+
                    if(computed.futureRecords>0)" · есть будущие даты: исключены" else "")
        } catch(_:Exception){sharedReview.value=CrossDeviceReview(remoteState="unavailable",message="Общий обзор недоступен · часы неизвестны; исходники сохранены")}
    }
    private fun schedule() {
        try {
            val job=JobInfo.Builder(JOB_ID,ComponentName(app,TelemetrySyncJobService::class.java))
                .setPeriodic(maxOf(15*60_000L,JobInfo.getMinPeriodMillis())).setPersisted(true).build()
            check(app.getSystemService(JobScheduler::class.java).schedule(job)==JobScheduler.RESULT_SUCCESS)
        } catch(_:Exception){_status.value="Автоповтор не назначен; локальная очередь сохранена · проверьте разрешение после загрузки"}
    }
    companion object {
        const val JOB_ID=48734
        private val publishLock=Any()
        private val sharedReview=MutableStateFlow(CrossDeviceReview())
    }
}
