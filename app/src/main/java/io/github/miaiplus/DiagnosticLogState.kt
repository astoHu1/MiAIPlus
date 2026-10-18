package io.github.miaiplus

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

internal data class DiagnosticEntry(val raw: String, val event: String, val source: String,
                                    val time: Long, val session: String, val step: Int)

/** Only the visible log page polls. File IO and JSON decoding stay off the UI/task threads. */
class DiagnosticLogState(private val application: Application, private val scope: CoroutineScope) {
    internal var entries by mutableStateOf<List<DiagnosticEntry>>(emptyList())
        private set
    var bytes by mutableStateOf(0L)
        private set
    var health by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var shareUri by mutableStateOf<Uri?>(null)
    var notice by mutableStateOf<String?>(null)
    private val store get() = DiagnosticLogStore.get(application)

    fun refresh() {
        if (loading || busy) return
        scope.launch {
            loading = true
            try { load() } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { error = "读取日志失败，请重试" }
            finally { loading = false }
        }
    }

    private suspend fun load() {
        val (snapshot, records) = withContext(Dispatchers.IO) {
            val snapshot = store.snapshot()
            snapshot to snapshot.lines().mapNotNull { line ->
                try {
                    val json = JSONObject(line)
                    DiagnosticEntry(line, json.optString("event"), json.optString("source"), json.optLong("time"),
                        json.optString("session"), json.optInt("step"))
                } catch (_: Exception) { null }
            }.asReversed()
        }
        entries = records
        bytes = snapshot.bytes()
        health = "模块丢弃 ${snapshot.dropped()} · 写入失败 ${snapshot.errors()}"
        error = null
    }

    fun clear() = operation("清空日志失败，请重试") {
        withContext(Dispatchers.IO) {
            store.clear()
            exportDirectory().listFiles()?.forEach { if (!it.delete()) throw java.io.IOException("Cannot remove export") }
        }
        load()
        notice = "日志已清空"
    }

    fun save(uri: Uri) = operation("导出失败，请重新选择保存位置") {
        withContext(Dispatchers.IO) {
            val output = application.contentResolver.openOutputStream(uri, "wt")
                ?: throw java.io.IOException("No output stream")
            output.use { store.exportZip(it, Config(application).phoneUseDetailedLog) }
        }
        notice = "日志压缩包已保存"
    }

    fun share() = operation("生成日志压缩包失败，请重试") {
        shareUri = withContext(Dispatchers.IO) {
            val directory = exportDirectory()
            if (!directory.isDirectory && !directory.mkdirs()) throw java.io.IOException("Cannot create export directory")
            directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach {
                if (!it.delete()) throw java.io.IOException("Cannot remove old export")
            }
            val file = File(directory, archiveName())
            try { file.outputStream().use { store.exportZip(it, Config(application).phoneUseDetailedLog) } }
            catch (failure: Exception) { file.delete(); throw failure }
            FileProvider.getUriForFile(application, "${application.packageName}.log-files", file)
        }
    }

    private fun exportDirectory() = File(application.cacheDir, "log-exports")

    private fun operation(message: String, block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; error = null
            try {
                while (loading) kotlinx.coroutines.delay(20)
                block()
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { error = message }
            finally { busy = false }
        }
    }

    fun archiveName() = "MiAIPlus-logs-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())}.zip"
}
