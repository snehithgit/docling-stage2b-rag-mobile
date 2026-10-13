package com.shiprag.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shiprag.mobile.data.OnDeviceEmbeddings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opt-in one-time setup. Neither embedding inference nor retrieval needs the network. */
@Composable
fun EmbeddingPanel(model: OnDeviceEmbeddings, onInstalled: () -> Unit) {
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(model.available()) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(model.description()) }
    var problem by remember { mutableStateOf("") }

    fun perform(operation: suspend () -> Unit) {
        if (working) return
        working = true
        problem = ""
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { operation() } }
                .onSuccess {
                    installed = model.available()
                    status = model.description()
                    if (installed) onInstalled()
                }.onFailure { problem = it.message ?: "Model setup failed" }
            working = false
        }
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform {
            model.importFile(uri, false) { text -> scope.launch { status = text } }
        }
    }
    val vocabPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform {
            model.importFile(uri, true) { text -> scope.launch { status = text } }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (installed) "Local BGE embedding model ready" else "Install BGE-small-en-v1.5 (one time)",
            style = MaterialTheme.typography.titleSmall)
        Text(status, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!installed) {
            Button(
                onClick = {
                    perform { model.download { text -> scope.launch { status = text } } }
                },
                enabled = !working, modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Download BGE once (~133 MB)")
            }
            Text("Download while connected to Wi-Fi. For an offline transfer, first save the official BAAI ONNX model and vocab.txt on your phone, then import both.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
            }, modifier = Modifier.weight(1f), enabled = !working) {
                Text("Import ONNX")
            }
            OutlinedButton(onClick = {
                vocabPicker.launch(arrayOf("text/plain", "*/*"))
            }, modifier = Modifier.weight(1f), enabled = !working) {
                Text("Import vocab.txt")
            }
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (problem.isNotEmpty()) Text(problem, color = MaterialTheme.colorScheme.error)
        Text("Compatible index: BAAI/bge-small-en-v1.5 · 384 dimensions. The original 133 MB model is verified by SHA-256. Do not use another model or the quantized variant against the existing vectors.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
