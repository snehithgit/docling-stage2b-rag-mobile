package com.shiprag.mobile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiprag.mobile.data.Source
import com.shiprag.mobile.data.SourceAssets
import com.shiprag.mobile.data.LibraryStore
import com.shiprag.mobile.data.ManualReferenceResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceScreen(source: Source, store: LibraryStore, resolver: ManualReferenceResolver,
                 onBack: () -> Unit, onFollow: (Source) -> Unit) {
    val assets=remember(source.id,store.root) { SourceAssets(store) }
    val pdf=remember(source.id) { assets.pdfFor(source) }
    val pics by produceState(initialValue = emptyList<File>(), source.id) {
        value = withContext(Dispatchers.IO) { assets.pageImages(source) }
    }
    var tab by remember(source.id) { mutableIntStateOf(if (pdf!=null) 0 else 2) }
    // Without the original input PDF, a cited visual should open its Docling picture directly.
    LaunchedEffect(source.id, pdf, pics.size) {
        if (pdf == null && pics.isNotEmpty() && source.evidenceKind == "visual") tab = 1
    }
    var currentPage by remember(source.id) { mutableIntStateOf(((source.page ?: 1)-1).coerceAtLeast(0)) }
    var imgIndex by remember(source.id) { mutableIntStateOf(0) }
    val pageCount by produceState(initialValue = 0, pdf) {
        value = withContext(Dispatchers.IO) {
            if (pdf == null) 0 else runCatching {
                ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { it.pageCount }
                }
            }.getOrDefault(0)
        }
    }
    Scaffold(topBar={ TopAppBar(title={ Column {
        Text("Source evidence",style=MaterialTheme.typography.titleMedium)
        Text(source.manual,style=MaterialTheme.typography.labelSmall,maxLines=1)
    } },navigationIcon={ IconButton(onClick=onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back") } }) }) { inset ->
        Column(Modifier.fillMaxSize().padding(inset)) {
            Text("${source.pages.joinToString(prefix="PDF page ").ifBlank { "Page unspecified" }}  •  ${source.chunkId}",
                Modifier.padding(horizontal=20.dp, vertical=10.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            ReferenceRoutes(source, resolver, onFollow)
            TabRow(selectedTabIndex=tab) {
                Tab(selected=tab==0,onClick={tab=0},text={Text("PDF")},icon={Icon(Icons.Default.Description,null)})
                Tab(selected=tab==1,onClick={tab=1},text={Text("Images")},icon={Icon(Icons.Default.Image,null)})
                Tab(selected=tab==2,onClick={tab=2},text={Text("Text")},icon={Icon(Icons.Default.TextSnippet,null)})
            }
            when(tab) {
                0 -> if (pdf == null) MissingAsset("The original PDF is not in this ZIP. Include a PDF whose filename matches the source manual.")
                    else Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                            IconButton(onClick={if(currentPage>0) currentPage--}, enabled=currentPage>0) { Icon(Icons.Default.ChevronLeft,"Previous page") }
                            Text("PDF page ${currentPage+1}${if(pageCount>0) " / $pageCount" else ""}",style=MaterialTheme.typography.titleSmall)
                            IconButton(onClick={currentPage++},enabled=pageCount>0 && currentPage+1<pageCount) { Icon(Icons.Default.ChevronRight,"Next page") }
                        }
                        PdfPreview(pdf,currentPage,Modifier.fillMaxSize())
                    }
                1 -> if (pics.isEmpty()) MissingAsset("No confidently matched source page images in this ZIP. The viewer will not show unrelated diagrams as evidence.")
                    else Column(Modifier.fillMaxSize()) {
                        if (pics.size > 1) Row(Modifier.fillMaxWidth().padding(6.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                            IconButton(onClick={if(imgIndex>0)imgIndex--}) { Icon(Icons.Default.ChevronLeft,"Previous image") }
                            Text("Image ${imgIndex+1} of ${pics.size}")
                            IconButton(onClick={if(imgIndex<pics.lastIndex)imgIndex++}) { Icon(Icons.Default.ChevronRight,"Next image") }
                        }
                        Text(pics[imgIndex].name,Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.labelSmall)
                        ImagePreview(pics[imgIndex],Modifier.fillMaxSize())
                    }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Text(source.title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text("${source.manual} • ${source.pages.joinToString()} • ${source.docItems.joinToString().take(140)}",
                        style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(vertical=18.dp))
                    Text(source.content,style=MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable private fun MissingAsset(message: String) {
    Column(Modifier.fillMaxSize().padding(28.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(Icons.Default.Description,null,tint=MaterialTheme.colorScheme.outline,modifier=Modifier.size(50.dp))
        Spacer(Modifier.height(20.dp))
        Text(message,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun PdfPreview(file: File,index: Int, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue=null,file,index) {
        value = withContext(Dispatchers.IO) { runCatching {
            ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    require(index in 0 until pdf.pageCount) { "Page ${index+1} is outside this PDF (${pdf.pageCount} pages)" }
                    pdf.openPage(index).use { page ->
                        val ratio=min(2.0, 1800.0 / page.width.coerceAtLeast(1))
                        val w=(page.width * ratio).toInt().coerceAtLeast(1)
                        val h=(page.height * ratio).toInt().coerceAtLeast(1)
                        val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    }
                }
            }
        }.getOrNull() }
    }
    if (bitmap == null) Box(modifier,contentAlignment=Alignment.Center) { Text("Page unavailable or loading…") }
    else ZoomableBitmap(requireNotNull(bitmap),modifier)
}

@Composable private fun ImagePreview(file: File,modifier: Modifier=Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue=null,file) {
        value=withContext(Dispatchers.IO) { runCatching {
            val opts=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFile(file.path,opts)
            val sample=(maxOf(opts.outWidth,opts.outHeight)/2048).coerceAtLeast(1)
            BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize=sample })
        }.getOrNull() }
    }
    if(bitmap==null) Box(modifier,contentAlignment=Alignment.Center) { Text("Image unavailable or loading…") }
    else ZoomableBitmap(requireNotNull(bitmap),modifier)
}

@Composable private fun ZoomableBitmap(bitmap: Bitmap, modifier: Modifier = Modifier) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var panX by remember(bitmap) { mutableFloatStateOf(0f) }
    var panY by remember(bitmap) { mutableFloatStateOf(0f) }
    val state=rememberTransformableState { z,pan,_ ->
        zoom=(zoom*z).coerceIn(1f,5f)
        if(zoom<=1.001f) { panX=0f; panY=0f } else { panX += pan.x; panY += pan.y }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant).transformable(state),contentAlignment=Alignment.Center) {
        Image(bitmap.asImageBitmap(),"Source page",Modifier.fillMaxSize().graphicsLayer {
            scaleX=zoom; scaleY=zoom; translationX=panX; translationY=panY
        },contentScale=ContentScale.Fit)
    }
}