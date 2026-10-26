package dev.mrwick.redline.util

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object DiagnosticBundle {
    private val names = listOf("app.log", "app.1.log", "frames.log", "frames.1.log")

    fun create(directory: File, output: File, context: String, crashDirectory: File? = null): File {
        val available = names.map { File(directory, it) }.filter { it.isFile }
        val crashes = crashDirectory?.takeIf { it.isDirectory }?.let { directory ->
            directory.listFiles() ?: throw IOException("Cannot list saved crash logs")
        }?.filter { it.isFile && it.name.startsWith("crash-") && it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }?.take(5).orEmpty()
        if (available.isEmpty() && crashes.isEmpty()) throw IOException("No saved diagnostic logs are available")
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("capture-info.txt"))
            zip.write((
                "REDLINE navigation diagnostics v1\n$context\n" +
                    "\nReading the logs:\n" +
                    "NavTrace INPUT / MAPS_TO_APP: original Google Maps Android notification fields.\n" +
                    "NavTrace DECISION/PARSED/MUX: app interpretation, fallbacks and selected arrow/distances/ETA.\n" +
                    "Follow the same trace=maps-... ID from input through the transmitted frame note.\n" +
                    "NavTrace WRITE / APP_TO_CLUSTER: local BLE write result, not proof of displayed content.\n" +
                    "ClusterTrace CLUSTER_TO_APP: decoded incoming cluster data (fuel bars, speed, odometer).\n" +
                    "frames.log TX = app writes; RX = cluster notifications; hex = actual wire bytes.\n" +
                    "Fuel litres are estimates from bars and app capacity, not measured litres from BLE.\n" +
                    "No received display-rendering acknowledgement is established by these logs.\n\n" +
                    "Crash logs included: ${crashes.size} (up to 5 most recent saved uncaught-exception stacks).\n" +
                    "No crash file does not prove no crash; clearing app data deletes saved stacks.\n" +
                    "Logs contain route text and device identifiers; share privately.\n" +
                    "Live file snapshot; writes in progress may not be included.\n" +
                    "Missing files: ${names.filter { name -> available.none { it.name == name } }}\n"
                ).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            (available + crashes).forEach { file ->
                val name = if (file in crashes) "crash-logs/${file.name}" else file.name
                zip.putNextEntry(ZipEntry(name))
                // Bound each copy to the file length at capture, even if logging continues.
                var remaining = file.length()
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(8192)
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        if (count < 0) break
                        zip.write(buffer, 0, count)
                        remaining -= count
                    }
                }
                zip.closeEntry()
            }
        }
        return output
    }
}
