package dev.stanislav.focusdeck.data.life

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/** Local unfinished text is not parsed as Markdown; pasting a heading must never lose a draft. */
object LifeDraftCodec {
    private const val PREFIX = "fd-draft-1:"
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun encode(entry: LifeEntry): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(value: String) {
                val data = value.toByteArray(Charsets.UTF_8)
                out.writeInt(data.size); out.write(data)
            }
            text(entry.path)
            text(LifeNoteCodec.create(entry.copy(fields = emptyMap())))
            text(entry.title)
            out.writeInt(entry.fields.size)
            entry.fields.forEach { (field, value) -> text(field.name); text(value) }
            text(entry.sourceRoot)
        }
        require(bytes.size() <= MAX_BYTES) { "Черновик больше 8 МиБ" }
        return PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(value: String, legacyPath: String = ""): LifeEntry? = runCatching {
        if (!value.startsWith(PREFIX)) return@runCatching LifeNoteCodec.parse(legacyPath, value)
        val bytes = Base64.getDecoder().decode(value.removePrefix(PREFIX))
        require(bytes.size <= MAX_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            fun text(): String {
                val size = input.readInt()
                require(size in 0..MAX_BYTES && size <= input.available())
                val data = ByteArray(size); input.readFully(data)
                return data.toString(Charsets.UTF_8)
            }
            val path = text()
            val header = LifeNoteCodec.parse(path, text()) ?: return@runCatching null
            val title = text()
            val count = input.readInt()
            require(count in 0..LifeField.entries.size)
            val fields = buildMap {
                repeat(count) {
                    val field = LifeField.valueOf(text())
                    require(field !in this)
                    put(field, text())
                }
            }
            val sourceRoot = if (input.available() > 0) text() else ""
            require(input.available() == 0)
            header.copy(title = title, fields = fields, sourceRoot = sourceRoot)
        }
    }.getOrNull()
}
