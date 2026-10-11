package dev.folio.scanner.pdfanalysis

import dev.folio.scanner.ocr.textMatches

data class AnalysisTextHit(val region:Int,val range:IntRange,val box:Box)
data class AnalysisSearchMatch(val page:Int,val hits:List<AnalysisTextHit>)

/** Match existing text, preserving UTF-16 offsets and honest region/line geometry. */
fun searchAnalysisPage(page:AnalysisPage,query:String):List<AnalysisSearchMatch> {
    if(query.isBlank()) return emptyList()
    val regions=page.regions.filter {it.text.isNotBlank()}.sortedBy {it.order}
    val starts=mutableListOf<Int>()
    val text=buildString {regions.forEachIndexed {index,region ->if(index>0) append("\n\n");starts+=length;append(region.text)}}
    val offsets=mutableListOf<Int>()
    val ends=mutableListOf<Int>()
    val normalized=buildString {text.forEachIndexed {index,char ->
        if(char.isWhitespace()) {if(isNotEmpty() && last()==' ') ends[ends.lastIndex]=index else {append(' ');offsets+=index;ends+=index}}
        else {append(char);offsets+=index;ends+=index}
    }}
    val needle=query.trim().replace(Regex("\\s+")," ")
    return textMatches(normalized,needle).map {match ->
        val first=offsets[match.first];val last=ends[match.last]
        AnalysisSearchMatch(page.index,regions.mapIndexedNotNull {index,region ->
            val start=starts[index];val end=start+region.text.length-1
            if(first>end || last<start) null else AnalysisTextHit(region.id,(maxOf(first,start)-start)..(minOf(last,end)-start),region.box)
        })
    }
}
