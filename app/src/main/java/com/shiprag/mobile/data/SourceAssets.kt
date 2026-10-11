package com.shiprag.mobile.data

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/** Faithful source resolver: exactly matched input PDF, page images or Docling picture provenance. */
class SourceAssets(private val library: LibraryStore) {
    private val root = requireNotNull(library.root)
    private val files by lazy { root.walkTopDown().filter { it.isFile }.toList() }
    private fun normalize(s: String) = s.lowercase().substringAfterLast('/').substringAfterLast('\\')
        .removeSuffix(".pdf").removeSuffix(".zip").replace(Regex("[^a-z0-9]+"), "")

    fun pdfFor(source: Source): File? {
        val expected = normalize(source.manual)
        if (expected.isBlank()) return null
        // Do not choose among duplicate basenames (might be a different manual revision).
        val matching = files.filter { it.extension.equals("pdf", true) && normalize(it.name) == expected }
        return matching.singleOrNull()
    }

    fun pageImages(source: Source): List<File> {
        val direct = directPageImages(source)
        val embedded = convertedImages(source)
        return (embedded + direct).distinctBy { it.canonicalPath }
    }

    private fun directPageImages(source: Source): List<File> {
        val page = source.page ?: return emptyList()
        val parent = File(root, source.location)
        val token = page.toString()
        val rx = Regex("(?:^|[^a-z0-9])(?:page|pg)[_-]?0*$token(?:[^0-9]|$)", RegexOption.IGNORE_CASE)
        val candidates = files.filter {
            it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") && rx.containsMatchIn(it.nameWithoutExtension)
        }
        val sameDir = candidates.filter { it.parentFile?.canonicalPath == parent.canonicalPath ||
            it.canonicalPath.startsWith(parent.canonicalPath + File.separator) }
        if (sameDir.isNotEmpty()) return sameDir.sortedBy { it.name }
        val named = candidates.filter { normalize(it.nameWithoutExtension).contains(normalize(source.manual)) }
        // A filename-only match must be unique to avoid showing an unrelated source picture.
        return named.singleOrNull()?.let(::listOf) ?: emptyList()
    }

    private fun convertedImages(source: Source): List<File> {
        val dir = File(root, source.location)
        val manifest = File(dir, "source_manifest.json")
        if (!manifest.isFile) return emptyList()
        val converted = runCatching { JSONObject(manifest.readText()).optString("converted_zip").substringAfterLast('/').substringAfterLast('\\') }.getOrDefault("")
        if (converted.isBlank()) return emptyList()
        val archives = files.filter { it.name == converted && it.extension.equals("zip", true) &&
            it.relativeTo(root).invariantSeparatorsPath.split('/').contains("output") }
        val archive = archives.singleOrNull() ?: return emptyList()
        val explicit = source.docItems.mapNotNull { Regex("^#/pictures/(\\d+)$").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
        val page = source.page
        val target = File(root, ".shiprag/pictures").apply { mkdirs() }
        return runCatching { ZipFile(archive).use { zip ->
            // Resolve Docling JSON in the immutable converted ZIP, following the original app's semantics.
            val documents = zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".json", true) && it.size in 1..(100L*1024*1024) }
                .mapNotNull { entry -> runCatching { JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() }) }.getOrNull() }
                .filter { it.optJSONArray("texts") != null }.toList()
            val document = documents.singleOrNull() ?: return@use emptyList<File>()
            val pictures = document.optJSONArray("pictures") ?: return@use emptyList<File>()
            val outputs = mutableListOf<File>()
            for (i in 0 until pictures.length()) {
                val picture = pictures.optJSONObject(i) ?: continue
                val p = picture.optJSONArray("prov")?.optJSONObject(0)?.optInt("page_no", -1) ?: -1
                // A visual citation has an exact Docling picture reference. Never display a different figure from the same page first.
                if (explicit.isNotEmpty()) { if (i !in explicit) continue }
                else if (page == null || page != p) continue
                val uri = picture.optJSONObject("image")?.optString("uri").orEmpty().replace('\\','/')
                if (uri.isBlank() || uri.startsWith('/') || uri.split('/').any { it == ".." }) continue
                val member = zip.getEntry(uri) ?: continue
                if (member.isDirectory || member.size > 20L*1024*1024 || member.size < 0) continue
                val extension = uri.substringAfterLast('.', "png").lowercase()
                if (extension !in setOf("png", "jpg", "jpeg", "webp")) continue
                val pathKey = archive.absolutePath.hashCode().toUInt().toString(16)
                val saved = File(target, "${pathKey}_${archive.length()}_$i.$extension")
                if (!saved.isFile) {
                    val tmp = File(target, saved.name + ".part")
                    var total = 0L
                    try {
                        zip.getInputStream(member).use { input -> FileOutputStream(tmp).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) { val n = input.read(buffer); if (n < 0) break
                                total += n; check(total <= 20L*1024*1024) { "Picture too large" }; out.write(buffer, 0, n) }
                        } }
                        if (!tmp.renameTo(saved)) { tmp.delete(); continue }
                    } catch (_: Exception) { tmp.delete(); continue }
                }
                outputs.add(saved)
                if (outputs.size >= 24) break // Avoid rendering hundreds of page figures at once.
            }
            outputs
        } }.getOrDefault(emptyList())
    }
}