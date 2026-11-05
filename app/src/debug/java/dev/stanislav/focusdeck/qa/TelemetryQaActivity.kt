package dev.stanislav.focusdeck.qa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.stanislav.focusdeck.FocusDeckApp
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

/** Isolated test-only picker. It accepts only a named QA telemetry child, never the notes root. */
class TelemetryQaActivity : Activity() {
    private var root: Uri? = null
    private lateinit var state: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val app get() = application as FocusDeckApp
    private var message = "Тестовая папка не выбрана"
    private val refresh = object : Runnable {
        override fun run() {
            state.text = "$message\n${app.telemetrySync.status.value}\nАвтоповтор: ${app.telemetrySync.automaticRetryScheduled}"
            handler.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        layout.addView(TextView(this).apply { text = "Только QA: выбери отдельную пустую папку qa-UUID внутри FocusDeck/Telemetry/v1 в существующей папке Syncthing. Заметки не читаются." })
        state = TextView(this); layout.addView(state)
        fun button(label: String, action: () -> Unit) = Button(this).also { it.text = label; it.setOnClickListener { action() }; layout.addView(it) }
        button("Выбрать тестовую папку") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 71)
        }
        button("Включить тестовую передачу") { message = if (root?.let { app.telemetrySync.configure(it, telemetryRoot = true) } == true) "Тестовая очередь включена" else "Тестовая папка не подключена" }
        button("Повторить") { app.telemetrySync.retry() }
        button("Добавить синтетическое QA-событие") {
            val prefs = getSharedPreferences("telemetry-syncthing", MODE_PRIVATE)
            val savedUri = prefs.getString("root", null)?.let(Uri::parse)
            val id = savedUri?.let { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() }.orEmpty()
            val safe = savedUri?.authority == "com.android.externalstorage.documents" &&
                id.matches(Regex("primary:Documents/DB1-Sync/FocusDeck/Telemetry/v1/qa-[0-9a-f-]{36}")) &&
                prefs.getBoolean("telemetryRoot", false) && app.telemetrySync.enabled
            if (!safe) { message = "Нужен включённый обмен с отдельной QA-папкой" }
            else {
                val row = JSONObject().put("schemaVersion", 1).put("eventId", UUID.randomUUID().toString())
                    .put("sessionId", JSONObject.NULL).put("occurredAt", Instant.now().toString())
                    .put("source", "android").put("category", JSONObject.NULL).put("type", "heartbeat")
                    .put("evidence", "unknown").put("metrics", JSONObject().put("focusSeconds", JSONObject.NULL)
                        .put("distractionSeconds", JSONObject.NULL).put("maintenanceSeconds", JSONObject.NULL))
                app.telemetrySync.updateSnapshot(row.toString() + "\n")
                message = "Синтетическое QA-событие поставлено в очередь"
            }
        }
        button("Выключить") { app.telemetrySync.disable(); message = "Тестовая передача выключена" }
        setContentView(layout); handler.post(refresh)
    }
    @Deprecated("Android activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 71 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
        val allowed = uri.authority == "com.android.externalstorage.documents" &&
            id.matches(Regex("primary:Documents/DB1-Sync/FocusDeck/Telemetry/v1/qa-[0-9a-f-]{36}"))
        if (!allowed) { message = "Нужна отдельная QA-папка внутри FocusDeck/Telemetry/v1"; return }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            root = uri; message = "Выбрана отдельная тестовая папка"
        } catch (_: Exception) { message = "Нет разрешения на тестовую папку" }
    }
    override fun onDestroy() { handler.removeCallbacks(refresh); super.onDestroy() }
}
