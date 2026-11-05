package dev.stanislav.focusdeck.data.repo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Batches never exceed the receiver's byte/event limits; each input line is already a JSON event. */
internal fun harnessBatches(jsonl: String): List<String> {
    val prefix = "{\"schemaVersion\":1,\"events\":["
    val suffix = "]}"
    val batches = mutableListOf<String>()
    var current = mutableListOf<String>()
    var bytes = (prefix + suffix).toByteArray(StandardCharsets.UTF_8).size
    for (line in jsonl.lineSequence().filter { it.isNotBlank() }) {
        val size = line.toByteArray(StandardCharsets.UTF_8).size
        require(size + prefix.length + suffix.length <= 128 * 1024) { "Event exceeds receiver limit" }
        if (current.size == 256 || bytes + size + (if (current.isEmpty()) 0 else 1) > 128 * 1024) {
            batches += prefix + current.joinToString(",") + suffix
            current = mutableListOf()
            bytes = (prefix + suffix).toByteArray(StandardCharsets.UTF_8).size
        }
        if (current.isNotEmpty()) bytes++
        current += line
        bytes += size
    }
    if (current.isNotEmpty()) batches += prefix + current.joinToString(",") + suffix
    return batches
}

/** Receiver advertises retention; older records stay local and are reported as unsent. */
internal fun retainedUploadSnapshot(jsonl: String, now: Instant, retentionDays: Int): Pair<String, Int> {
    require(retentionDays in 1..3650)
    val cutoff = now.minusSeconds(retentionDays * 86_400L - 3600L) // grace for clock drift and transfer time
    val pattern = Regex("\"occurredAt\":\"([^\"]+)\"")
    var excluded = 0
    val kept = jsonl.lineSequence().filter { it.isNotBlank() }.filter { line ->
        val time = Instant.parse(requireNotNull(pattern.find(line)?.groupValues?.get(1)))
        if (time.isBefore(cutoff)) { excluded++; false } else true
    }.toList()
    return kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n") to excluded
}

/** Explicit opt-in. The address is fixed to loopback and the token is encrypted with Android Keystore. */
class HarnessUploader(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("harness-uploader", Context.MODE_PRIVATE)
    private val token = AtomicReference<String?>(null)
    private val latest = AtomicReference("")
    private val conflicted = AtomicBoolean(false)
    private val sendLock = Mutex()
    private val transport = HarnessTransport()
    private val settingsLock = Mutex()
    private val revision = AtomicLong(0)
    private val _status = MutableStateFlow("Передача выключена")
    val status: StateFlow<String> = _status

    init {
        val restoreRevision = revision.get()
        scope.launch {
            val encrypted = prefs.getString("token", null)
            if (encrypted != null) {
                try {
                    val restored = decrypt(encrypted)
                    if (revision.get() == restoreRevision) {
                        token.set(restored)
                        _status.value = "Передача включена · ожидание локального приёмника"
                        sendLatest()
                    }
                } catch (_: Exception) {
                    if (revision.get() == restoreRevision)
                        _status.value = "Не удалось открыть ключ передачи. Выключи её и настрой заново."
                }
            }
            while (true) { delay(60_000); if (token.get() != null && !conflicted.get()) sendLatest() }
        }
    }

    fun enable(value: String) {
        val request = revision.incrementAndGet()
        scope.launch { settingsLock.withLock {
            try {
                if (revision.get() != request) return@withLock
                require(value.isNotBlank() && value.length <= 512 && value.none { it == '\n' || it == '\r' })
                val encrypted = encrypt(value)
                check(prefs.edit().putString("token", encrypted).commit())
                if (revision.get() != request) return@withLock
                token.set(value)
                conflicted.set(false)
                _status.value = "Передача включена · подключение…"
                sendLatest()
            } catch (_: Exception) { if (revision.get() == request) _status.value = "Не удалось включить передачу. Проверь ключ и повтори." }
        } }
    }

    fun disable() {
        val request = revision.incrementAndGet()
        token.set(null)
        conflicted.set(false)
        _status.value = "Отключение передачи…"
        scope.launch { settingsLock.withLock {
            if (revision.get() == request) {
                _status.value = if (prefs.edit().remove("token").commit()) "Передача выключена"
                    else "Не удалось сохранить выключение передачи."
            }
        } }
    }

    fun updateSnapshot(jsonl: String) {
        latest.set(jsonl)
        if (token.get() != null && !conflicted.get()) scope.launch { sendLatest() }
    }

    private suspend fun sendLatest() = sendLock.withLock {
        val activeToken = token.get() ?: return@withLock
        if (conflicted.get()) return@withLock
        try {
            val health = transport.health(activeToken)
            check(health.code == 200)
            val retentionDays = Regex("\"retentionDays\"\\s*:\\s*(\\d+)")
                .find(health.body)?.groupValues?.get(1)?.toIntOrNull()
            check(retentionDays != null && retentionDays in 1..3650) { "Receiver retention unavailable" }
            val (retained, excluded) = retainedUploadSnapshot(latest.get(), Instant.now(), retentionDays)
            val batches = harnessBatches(retained)
            for (body in batches) {
                if (token.get() != activeToken) return@withLock
                val code = transport.post(activeToken, body)
                if (code == 409) {
                    conflicted.set(true)
                    _status.value = "Конфликт событий: передача остановлена до проверки."
                    return@withLock
                }
                check(code == 202)
            }
            if (token.get() == activeToken) _status.value = when {
                excluded > 0 -> "Переданы доступные события · старые записи только на телефоне: $excluded"
                batches.isEmpty() -> "Приёмник подключён · событий пока нет"
                else -> "События переданы локальному приёмнику"
            }
        } catch (_: Exception) {
            if (token.get() == activeToken) _status.value = "Приёмник недоступен или ключ не принят · повторим позже"
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("focusdeck-harness-token", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("focusdeck-harness-token", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }
}
