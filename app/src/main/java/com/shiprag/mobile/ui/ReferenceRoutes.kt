package com.shiprag.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiprag.mobile.data.ManualReferenceResolver
import com.shiprag.mobile.data.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Every button points to a verified source chunk and PDF page in the imported manual. */
@Composable
fun ReferenceRoutes(source: Source, resolver: ManualReferenceResolver, onOpen: (Source) -> Unit,
                    compact: Boolean = false) {
    val links by produceState(initialValue = emptyList<com.shiprag.mobile.data.ManualLink>(),
        source.id, resolver) {
        value = withContext(Dispatchers.IO) { resolver.resolve(source) }
    }
    if (links.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(if (compact) "Follow references" else "Referenced sections and figures",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            links.forEach { link ->
                OutlinedButton(onClick = { onOpen(link.target) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)) {
                    Text(link.label + " · p.${link.target.page ?: "?"}", maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = "Open reference", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}