package com.severeclaw.mp3player

/** Синхронный текст (.lrc): времена (мс) и строчки. */
class Lyrics(val times: LongArray, val lines: List<String>) {
    /** Номер строчки в момент ms (или -1, если первая ещё не началась). */
    fun indexAt(ms: Long): Int {
        var lo = 0
        var hi = times.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (times[mid] <= ms) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    companion object {
        private val TS = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        private val OFFSET = Regex("\\[offset:\\s*([+-]?\\d+)]", RegexOption.IGNORE_CASE)

        fun parse(text: String): Lyrics? {
            val offset = OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val rows = ArrayList<Pair<Long, String>>()
            for (raw in text.lines()) {
                val stamps = TS.findAll(raw).toList()
                if (stamps.isEmpty()) continue
                val body = raw.substring(stamps.last().range.last + 1)
                    .replace(Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>"), "")    // слова по времени
                    .trim()
                for (m in stamps) {
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val frac = m.groupValues[3]
                    val ms = when (frac.length) {
                        0 -> 0L
                        1 -> frac.toLong() * 100
                        2 -> frac.toLong() * 10
                        else -> frac.take(3).toLong()
                    }
                    rows.add((min * 60_000 + sec * 1000 + ms - offset).coerceAtLeast(0) to body)
                }
            }
            if (rows.isEmpty()) return null
            rows.sortBy { it.first }
            return Lyrics(rows.map { it.first }.toLongArray(), rows.map { it.second })
        }
    }
}
