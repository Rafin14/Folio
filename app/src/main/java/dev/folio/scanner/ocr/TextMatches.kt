package dev.folio.scanner.ocr

/** UTF-16 offsets match Compose's text layout, including overlapping literal phrases. */
fun textMatches(text: String, query: String): List<IntRange> {
    if (query.isBlank()) return emptyList()
    val phrase=Regex(query.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) },RegexOption.IGNORE_CASE)
    val matches = mutableListOf<IntRange>()
    var start = 0
    while (start <= text.length) {
        val found = phrase.find(text,start) ?: break
        matches += found.range
        start = found.range.first + 1
    }
    return matches
}

fun nextTextMatch(current: Int, delta: Int, count: Int): Int =
    if (count == 0) 0 else Math.floorMod(current + delta, count)

/** CPU_DISABLED needs API 29; never send older devices to NNAPI's CPU reference driver. */
internal fun ocrHardwareEligible(api:Int,nnapiAvailable:Boolean,forceCpu:Boolean)=api>=29 && nnapiAvailable && !forceCpu
