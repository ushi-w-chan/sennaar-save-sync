package com.ushi.sennaar

import java.time.Instant

internal data class GlyphProgress(val confirmed: Int, val seen: Int, val total: Int)

internal data class SaveProgress(
    val slot: String,
    val scene: String?,
    val playSeconds: Long?,
    val lastSaved: Instant?,
    val journalSaved: Instant?,
    val glyphs: List<GlyphProgress>,
)

private val COS_HEADER = Regex("[0-9a-fA-F]{32}#[0-9]+#([0-9]{1,12})")
private val SCENE_ID = Regex("[0-9]+_[0-9]+")
private val GLYPH_ENTRY = Regex("([024])\\$.*")

private data class CosSave(val saved: Instant, val body: String)

private fun ByteArray.readCos(): CosSave? {
    val parts = toString(Charsets.UTF_8).split('@', limit = 2)
    if (parts.size != 2) return null
    val header = COS_HEADER.matchEntire(parts[0]) ?: return null
    return CosSave(Instant.ofEpochSecond(header.groupValues[1].toLong()), parts[1])
}

internal fun Snapshot.progress(): List<SaveProgress> {
    fun candidates(type: String, slot: String) =
        listOfNotNull(files["${type}_$slot.cos"], files["${type}_${slot}_backup.cos"])
            .mapNotNull { it.readCos() }

    return files.keys.mapNotNull(::saveSlot).distinct().sorted().mapNotNull { slot ->
        val places = candidates("places", slot).firstNotNullOfOrNull { save ->
            val fields = save.body.lineSequence().first().split('#')
            val seconds = fields.getOrNull(2)?.toLongOrNull()
            if (fields.size == 3 && SCENE_ID.matches(fields[0]) && seconds != null && seconds >= 0) {
                Triple(fields[0], seconds, save.saved)
            } else null
        }
        val journal = candidates("journal", slot).firstNotNullOfOrNull { save ->
            val glyphs = save.body.lineSequence().filter(String::isNotBlank).map { line ->
                val entries = line.substringBefore('#').split(';')
                val states = entries.mapNotNull { GLYPH_ENTRY.matchEntire(it)?.groupValues?.get(1) }
                if (states.size == entries.size) {
                    GlyphProgress(states.count { it == "4" }, states.count { it != "0" }, states.size)
                } else null
            }.toList()
            if (glyphs.isNotEmpty() && glyphs.all { it != null }) save.saved to glyphs.filterNotNull() else null
        }
        if (places == null && journal == null) null else SaveProgress(
            slot, places?.first, places?.second, places?.third, journal?.first, journal?.second.orEmpty(),
        )
    }
}
