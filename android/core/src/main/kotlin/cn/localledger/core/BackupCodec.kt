package cn.localledger.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable private data class Manifest(val version: Int = 1, val createdAt: String, val sha256: String, val records: Int)
object BackupCodec {
    private val json = Json { encodeDefaults = true }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun write(snapshot: Snapshot, output: OutputStream) {
        val data = snapshot.copy(entries = snapshot.entries.filter { it.deletedAt == null }).validate()
        val bytes = json.encodeToString(data).toByteArray(Charsets.UTF_8)
        val manifest = Manifest(createdAt = Instant.now().toString(), sha256 = hash(bytes), records = data.entries.size)
        ZipOutputStream(output).use { zip ->
            listOf("manifest.json" to json.encodeToString(manifest).toByteArray(Charsets.UTF_8), "data.json" to bytes).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
            }
        }
    }
    fun read(input: InputStream): Snapshot {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var total = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                require(entry.name in setOf("manifest.json", "data.json") && entry.name !in files) { "备份结构无效" }
                val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = zip.read(buffer); if (count < 0) break; total += count; require(total <= 32 * 1024 * 1024) { "备份过大" }; bytes.write(buffer, 0, count) }
                files[entry.name] = bytes.toByteArray()
            }
        }
        require(files.size == 2) { "备份不完整" }
        val manifest = json.decodeFromString<Manifest>(files.getValue("manifest.json").toString(Charsets.UTF_8))
        require(manifest.version == 1) { "暂不支持此备份版本" }
        val data = files.getValue("data.json")
        require(hash(data) == manifest.sha256) { "备份校验失败" }
        return json.decodeFromString<Snapshot>(data.toString(Charsets.UTF_8)).validate().also { require(it.entries.size == manifest.records) }
    }
}
