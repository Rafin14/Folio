@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.folio.scanner.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import dev.folio.scanner.data.PdfAsset
import dev.folio.scanner.pdf.*
import kotlinx.coroutines.*
import java.io.File

fun pdfShareIntent(context: Context, asset: PdfAsset): Intent {
    val file = File(asset.path).canonicalFile
    require(file.toPath().startsWith(File(context.cacheDir, "shared-pdfs/${asset.operationId}").canonicalFile.toPath()))
    check(file.isFile)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file, asset.title.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".pdf")
    return Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
        .apply { clipData = ClipData.newRawUri(asset.title, uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

fun pdfShareIntent(context: Context, assets: List<PdfAsset>): Intent {
    require(assets.isNotEmpty())
    if(assets.size==1) return pdfShareIntent(context,assets.single())
    val uris=assets.map { asset -> requireNotNull(androidx.core.content.IntentCompat.getParcelableExtra(pdfShareIntent(context,asset),Intent.EXTRA_STREAM,Uri::class.java)) }
    return Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/pdf").putParcelableArrayListExtra(Intent.EXTRA_STREAM,ArrayList(uris))
        .apply { clipData=ClipData.newRawUri("Folio documents",uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } } }
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

fun pdfImageShareIntent(context:Context,files:List<File>):Intent {
    require(files.isNotEmpty())
    val root=File(context.filesDir,"documents").canonicalFile.toPath()
    val uris=files.map { file -> require(file.canonicalFile.toPath().startsWith(root) && file.isFile); FileProvider.getUriForFile(context,"${context.packageName}.files",file) }
    return Intent(if(uris.size==1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg").apply {
        if(uris.size==1) putExtra(Intent.EXTRA_STREAM,uris.single()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM,ArrayList(uris))
        clipData=ClipData.newRawUri("PDF page images",uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
