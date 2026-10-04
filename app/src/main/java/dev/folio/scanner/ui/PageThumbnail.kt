package dev.folio.scanner.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.folio.scanner.data.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Native bounded cache; evicted bitmaps remain valid for visible Compose images. */
internal object ThumbnailCache {
    private val cache=object: LruCache<String,Bitmap>(16*1024*1024) {
        override fun sizeOf(key: String,value: Bitmap)=value.allocationByteCount
    }
    fun peek(path: String): Bitmap? = cache.snapshot().entries.lastOrNull { it.key.startsWith("$path:") }?.value
    @Synchronized fun load(path: String): Bitmap? {
        val file=File(path); val key="$path:${file.lastModified()}:${file.length()}"
        cache.get(key)?.let { return it }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(path,bounds)
        val options=BitmapFactory.Options().apply {
            inPreferredConfig=Bitmap.Config.ARGB_8888; inSampleSize=1
            while(maxOf(bounds.outWidth,bounds.outHeight)/inSampleSize>768) inSampleSize*=2
        }
        return BitmapFactory.decodeFile(path,options)?.also { cache.put(key,it) }
    }
}

@Composable
internal fun PageThumbnail(page: Page,model: LibraryViewModel,description: String,modifier: Modifier=Modifier) {
    val bitmap by produceState(ThumbnailCache.peek(page.thumbnailUri),page.id,page.thumbnailUri,page.enhancement,page.rotation) {
        value=withContext(Dispatchers.IO) {
            try { ThumbnailCache.load(model.repository.thumbnail(page)) }
            catch(cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch(failure: Exception) { android.util.Log.w("Folio thumbnails","Could not load thumbnail",failure); null }
        }
    }
    val opacity by animateFloatAsState(if(bitmap==null) 0f else 1f,animationSpec=androidx.compose.animation.core.tween(100),label="Thumbnail")
    val edge=MaterialTheme.colorScheme.outlineVariant
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow),contentAlignment=Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(),description,Modifier.fillMaxSize().semantics { stateDescription=if(opacity>=.99f) "Preview loaded" else "Loading preview" }.alpha(opacity).drawWithContent {
            drawContent()
            val paper=fit(size.width,size.height,it.width.toFloat()/it.height)
            drawRect(edge,Offset(paper[0],paper[1]),Size(paper[2],paper[3]),style=Stroke(1.dp.toPx()))
        },contentScale=ContentScale.Fit) }
            ?: Icon(Icons.Outlined.Description,description,tint=MaterialTheme.colorScheme.outline)
    }
}
