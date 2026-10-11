package com.shiprag.mobile.data

data class Source(
    val id: Long,
    val manual: String,
    val chunkId: String,
    val content: String,
    val headings: List<String>,
    val pages: List<Int>,
    val docItems: List<String>,
    val location: String,
    val evidenceKind: String = "text",
    val score: Double = 0.0,
    val method: String = "lexical",
    val vectorPath: String = "",
    val vectorIndex: Int = -1,
    val vectorDim: Int = 0,
    val vectorModel: String = ""
) {
    val page: Int? get() = pages.firstOrNull()
    val title: String get() = headings.lastOrNull().orEmpty().ifBlank { manual.substringBeforeLast('.') }
}

data class ImportReport(val manuals: Int, val chunks: Int, val vectors: Int, val pdfs: Int, val images: Int, val embeddedModels: List<String>, val visualSources: Int = 0)

data class AppSettings(
    val chatUrl: String = "http://127.0.0.1:8080/v1/chat/completions",
    val chatModel: String = "local",
    val embeddingUrl: String = "http://127.0.0.1:8090/embed",
    val embeddingModel: String = "",
    val queryPrefix: String = "",
    val answerSources: Int = 5,
    val chatApiKey: String = "",
    val embeddingApiKey: String = ""
)

data class Generated(val answer: String, val warning: String, val model: String)