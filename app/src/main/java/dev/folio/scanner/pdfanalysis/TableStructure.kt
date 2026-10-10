package dev.folio.scanner.pdfanalysis

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

data class GridLine(val fixed:Float,val from:Float,val to:Float)
private fun clustered(values:List<Float>,tolerance:Float):List<Float> {
    val groups=mutableListOf<MutableList<Float>>()
    values.sorted().forEach {v ->if(groups.lastOrNull()?.let {abs(it.average()-v)<=tolerance}==true) groups.last()+=v else groups+=mutableListOf(v)}
    return groups.map {it.average().toFloat()}
}
/** Actual ruled-table structure recognition; missing internal borders produce merged cells. */
fun reconstructGrid(horizontal:List<GridLine>,vertical:List<GridLine>,order:Int,text:(Box)->String):WordTable? {
    val xs=clustered(vertical.map {it.fixed},3f);val ys=clustered(horizontal.map {it.fixed},3f)
    if(xs.size !in 2..65 || ys.size !in 2..65) return null
    val columns=xs.size-1;val rows=ys.size-1
    if(xs.zipWithNext().any {it.second-it.first<6} || ys.zipWithNext().any {it.second-it.first<6}) return null
    fun present(lines:List<GridLine>,fixed:Float,from:Float,to:Float):Boolean {
        val intervals=lines.filter {abs(it.fixed-fixed)<=3f}.map {max(from,it.from) to min(to,it.to)}.filter {it.second>it.first}.sortedBy {it.first}
        var coverage=0f;var end=from
        intervals.forEach {(a,b) ->if(b>end) {coverage+=b-max(a,end);end=b}}
        return coverage/(to-from)>.75
    }
    if(!present(vertical,xs.first(),ys.first(),ys.last()) || !present(vertical,xs.last(),ys.first(),ys.last()) || !present(horizontal,ys.first(),xs.first(),xs.last()) || !present(horizontal,ys.last(),xs.first(),xs.last())) return null
    val roots=IntArray(rows*columns) {it}
    fun root(value:Int):Int {var i=value;while(roots[i]!=i) i=roots[i];return i}
    fun join(a:Int,b:Int) {roots[root(b)]=root(a)}
    for(r in 0 until rows) for(c in 0 until columns) {
        val index=r*columns+c
        if(c+1<columns && !present(vertical,xs[c+1],ys[r],ys[r+1])) join(index,index+1)
        if(r+1<rows && !present(horizontal,ys[r+1],xs[c],xs[c+1])) join(index,index+columns)
    }
    val groups=roots.indices.groupBy(::root)
    val cells=mutableListOf<WordCell>()
    for(group in groups.values) {
        val firstRow=group.minOf {it/columns};val lastRow=group.maxOf {it/columns};val firstCol=group.minOf {it%columns};val lastCol=group.maxOf {it%columns}
        if(group.size!=(lastRow-firstRow+1)*(lastCol-firstCol+1)) return null // Ambiguous nonrectangular merge: keep editable text instead.
        val b=Box(xs[firstCol],ys[firstRow],xs[lastCol+1],ys[lastRow+1])
        cells+=WordCell(firstRow,firstCol,lastRow-firstRow+1,lastCol-firstCol+1,text(b))
    }
    return WordTable(Box(xs.first(),ys.first(),xs.last(),ys.last()),order,rows,xs.zipWithNext().map {it.second-it.first},cells.sortedWith(compareBy<WordCell> {it.row}.thenBy {it.column}))
}

/** OpenCV line morphology complements native vector segments; it is not the layout detector. */
fun recognizeTable(bitmap:Bitmap,page:AnalysisPage,region:AnalysisRegion):WordTable? {
    check(OpenCVLoader.initLocal())
    val b=region.box.clipped(bitmap.width,bitmap.height);if(b.width<24 || b.height<24) return null
    val x=b.left.toInt();val y=b.top.toInt();val w=b.width.toInt().coerceIn(1,bitmap.width-x);val h=b.height.toInt().coerceIn(1,bitmap.height-y)
    val crop=Bitmap.createBitmap(bitmap,x,y,w,h);val rgba=Mat();val gray=Mat();val binary=Mat();val hm=Mat();val vm=Mat();val hierarchy=Mat()
    val horizontal=mutableListOf<GridLine>();val vertical=mutableListOf<GridLine>()
    try {
        Utils.bitmapToMat(crop,rgba);Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
        Imgproc.adaptiveThreshold(gray,binary,255.0,Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,Imgproc.THRESH_BINARY_INV,31,12.0)
        fun lines(mat:Mat,horizontalAxis:Boolean) {
            val kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,if(horizontalAxis) Size(max(10.0,w/20.0),1.0) else Size(1.0,max(10.0,h/20.0)))
            val contours=mutableListOf<MatOfPoint>()
            try {
                Imgproc.morphologyEx(binary,mat,Imgproc.MORPH_OPEN,kernel);Imgproc.findContours(mat,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE)
                contours.forEach {val r=Imgproc.boundingRect(it)
                    if(horizontalAxis && r.width>w*.12) horizontal+=GridLine(y+r.y+r.height/2f,(x+r.x).toFloat(),(x+r.x+r.width).toFloat())
                    if(!horizontalAxis && r.height>h*.12) vertical+=GridLine(x+r.x+r.width/2f,(y+r.y).toFloat(),(y+r.y+r.height).toFloat())
                }
            } finally {kernel.release();contours.forEach {it.release()}}
        }
        lines(hm,true);lines(vm,false)
        // Native vector line geometry is available even when antialiasing erodes raster strokes.
        repeat(page.objects.length()) {i ->val obj=page.objects.getJSONObject(i);if(obj.getInt("type")==2) {
            val segments=obj.getJSONArray("segments");var previous:Pair<Float,Float>?=null
            repeat(segments.length()) {n ->val s=segments.getJSONArray(n);val point=s.getDouble(0).toFloat() to s.getDouble(1).toFloat();val before=previous
                if(before!=null && s.getInt(2)==0 && b.contains(point.first,point.second) && b.contains(before.first,before.second)) {
                    if(abs(point.second-before.second)<2 && abs(point.first-before.first)>w*.12) horizontal+=GridLine((point.second+before.second)/2,min(point.first,before.first),max(point.first,before.first))
                    if(abs(point.first-before.first)<2 && abs(point.second-before.second)>h*.12) vertical+=GridLine((point.first+before.first)/2,min(point.second,before.second),max(point.second,before.second))
                };previous=point
            }
        }}
        val table=reconstructGrid(horizontal,vertical,region.order) {cell ->
            val native=buildString {repeat(page.characters.length()) {i ->val char=page.characters.getJSONObject(i);val box=boxFrom(char.getJSONArray("box"));if(cell.contains((box.left+box.right)/2,(box.top+box.bottom)/2)) {val code=char.getInt("unicode");if(Character.isValidCodePoint(code)) append(Character.toChars(code))}}}.trim()
            if(native.count(Char::isLetterOrDigit)>0) native else List(page.lines.length()) {page.lines.getJSONObject(it)}.filter {val box=boxFrom(it.getJSONArray("box"));cell.contains((box.left+box.right)/2,(box.top+box.bottom)/2)}.sortedBy {boxFrom(it.getJSONArray("box")).top}.joinToString("\n") {it.getString("text")}
        } ?: return null
        val xs=clustered(vertical.map {it.fixed},3f);val ys=clustered(horizontal.map {it.fixed},3f)
        return table.copy(cells=table.cells.map {cell ->
            val bounds=Box(xs[cell.column],ys[cell.row],xs[cell.column+cell.columnSpan],ys[cell.row+cell.rowSpan])
            val chars=List(page.characters.length()) {page.characters.getJSONObject(it)}.filter {val box=boxFrom(it.getJSONArray("box"));bounds.contains((box.left+box.right)/2,(box.top+box.bottom)/2) && it.getInt("unicode")>32}
            val textBoxes=if(chars.isNotEmpty()) chars.map {boxFrom(it.getJSONArray("box"))} else List(page.lines.length()) {page.lines.getJSONObject(it)}.map {boxFrom(it.getJSONArray("box"))}.filter {bounds.contains((it.left+it.right)/2,(it.top+it.bottom)/2)}
            val alignment=if(textBoxes.isEmpty()) "left" else {
                val left=textBoxes.minOf {it.left}-bounds.left;val right=bounds.right-textBoxes.maxOf {it.right}
                when {abs(left-right)<bounds.width*.08 ->"center";right<bounds.width*.12 && left>bounds.width*.2 ->"right";else->"left"}
            }
            val size=chars.map {it.optDouble("size",10.0)}.filter {it.isFinite() && it>0}.average().takeIf {it.isFinite()} ?: 10.0
            val font=chars.map {it.optString("font")}.filter {it.isNotBlank()}.groupingBy {it}.eachCount().maxByOrNull {it.value}?.key?.substringAfter('+') ?: "Arial"
            cell.copy(alignment=alignment,size=size,bold=chars.isNotEmpty() && chars.count {it.optBoolean("bold")}>chars.size/2,font=font)
        })
    } finally {rgba.release();gray.release();binary.release();hm.release();vm.release();hierarchy.release();if(crop!==bitmap) crop.recycle()}
}
