@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.folio.scanner.ui



import androidx.compose.foundation.*

import androidx.compose.foundation.layout.*

import androidx.compose.material3.*

import androidx.compose.runtime.*

import androidx.compose.runtime.saveable.rememberSaveable

import androidx.core.graphics.withClip
import androidx.compose.ui.Modifier

import androidx.compose.ui.Alignment

import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

import androidx.compose.ui.graphics.nativeCanvas

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.stateDescription

import androidx.compose.ui.unit.dp

import dev.folio.scanner.data.PageLayout



@Composable

internal fun PageSizeSheet(initial: PageLayout, image: android.graphics.Bitmap?, rotation: Int, dismiss: () -> Unit, apply: (PageLayout) -> Unit) {

    var size by rememberSaveable { mutableStateOf(initial.size) }
    var landscape by rememberSaveable { mutableStateOf(initial.widthMm>initial.heightMm) }

    var fit by rememberSaveable { mutableStateOf(initial.fit) }

    var width by rememberSaveable { mutableStateOf(if(initial.widthMm>0) initial.widthMm.toString() else "210") }

    var height by rememberSaveable { mutableStateOf(if(initial.heightMm>0) initial.heightMm.toString() else "297") }

    val paper=runCatching { PageLayout(size,fit,if(size=="Custom") width.toDouble() else if(landscape) 297.0 else 210.0,if(size=="Custom") height.toDouble() else if(landscape) 210.0 else 297.0) }.getOrNull()

    ModalBottomSheet(onDismissRequest=dismiss) {

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {

            Text("Page size",style=MaterialTheme.typography.titleLarge)

            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {

                PageLayout.sizes.forEach { preset -> FilterChip(size==preset,{ size=preset },leadingIcon={ if(size==preset) Text("✓") },label={ Text(if(preset=="Original") "Original / Auto" else preset) }) }

            }

            if(size !in listOf("Original","Square","Custom")) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(!landscape,{ landscape=false },label={ Text("Portrait") })
                FilterChip(landscape,{ landscape=true },label={ Text("Landscape") })
            }
            if(size=="Custom") {

                OutlinedTextField(width,{ width=it },label={ Text("Width (mm)") },keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal),singleLine=true,isError=paper==null,modifier=Modifier.fillMaxWidth())

                OutlinedTextField(height,{ height=it },label={ Text("Height (mm)") },keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal),singleLine=true,isError=paper==null,modifier=Modifier.fillMaxWidth())

                if(paper==null) Text("Use dimensions between 10 and 1000 mm.",color=MaterialTheme.colorScheme.error)

            }

            if(paper!=null && image!=null) {

                val turned=rotation % 180 != 0

                val contentWidth=if(turned) image.height else image.width

                val contentHeight=if(turned) image.width else image.height

                val ratio=paper.dimensions?.let { (it.first/it.second).toFloat() } ?: contentWidth.toFloat()/contentHeight

                Box(Modifier.fillMaxWidth().height(220.dp).background(MaterialTheme.colorScheme.surfaceContainer),contentAlignment=Alignment.Center) {

                    Canvas(Modifier.fillMaxSize().padding(12.dp).semantics { contentDescription="Page size preview"; stateDescription="$size · $fit · aspect $ratio" }) {

                        val page=dev.folio.scanner.ui.fit(this.size.width,this.size.height,ratio)

                        drawIntoCanvas { compose ->

                            val canvas=compose.nativeCanvas

                            canvas.withClip(page[0],page[1],page[0]+page[2],page[1]+page[3]) {

                            canvas.drawColor(android.graphics.Color.WHITE)

                            val sx=page[2]/contentWidth; val sy=page[3]/contentHeight

                            val scale=if(paper.fit=="Fit") minOf(sx,sy) else maxOf(sx,sy)

                            canvas.translate(page[0]+page[2]/2,page[1]+page[3]/2)

                            canvas.rotate(rotation.toFloat()); canvas.scale(scale,scale)

                            canvas.drawBitmap(image,-image.width/2f,-image.height/2f,android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))

                            }

                        }

                        drawRect(androidx.compose.ui.graphics.Color.Gray,androidx.compose.ui.geometry.Offset(page[0],page[1]),androidx.compose.ui.geometry.Size(page[2],page[3]),style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))

                    }

                }

            }

            paper?.dimensions?.let { Text("${it.first} × ${it.second} mm",style=MaterialTheme.typography.bodyMedium) }

            if(size!="Original") {

                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {

                    listOf("Fit","Fill").forEach { mode -> FilterChip(fit==mode,{ fit=mode },leadingIcon={ if(fit==mode) Text("✓") },label={ Text(if(mode=="Fill") "Fill / crop" else "Fit") }) }

                }

                Text(if(fit=="Fit") "Keep all content. Add white margins where needed." else "Fill the page. Content outside the page boundary is cropped.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)

            }

            Button(onClick={ paper?.let(apply) },enabled=paper!=null,modifier=Modifier.fillMaxWidth()) { Text("Apply page size") }

            TextButton(onClick=dismiss,modifier=Modifier.fillMaxWidth()) { Text("Cancel") }

        }

    }

}

