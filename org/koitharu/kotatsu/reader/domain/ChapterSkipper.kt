package org.koitharu.kotatsu.reader.domain

import org.koitharu.kotatsu.parsers.model.MangaChapter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The chapters Next / Previous in the reader walks through. With skipping off that is every chapter; with it
 * on it is a shorter list, so reading goes 1, 2, 3 instead of 1, 1 (other scanlator), 1.1, 1.2, 2...
 *
 * One rule, per whole number (1, 2, 3 ...):
 *  - if the whole chapter exists (1), only one copy of it stays and its in-between chapters (1.1, 1.2) are skipped;
 *  - if it does not exist (there is a 2.1 but no 2), the in-between chapters stay, so nothing is lost
 *    (only identical repeats of the same number are merged).
 * The chapter being read is always part of the list, so navigation can start from any chapter.
 */
object ChapterSkipper {

	fun navigable(
		chapters: List<MangaChapter>,
		currentId: Long,
		skipDuplicates: Boolean,
	): List<MangaChapter> {
		if (!skipDuplicates) return chapters
		val current = chapters.firstOrNull { it.id == currentId } ?: return chapters

		// Which whole numbers have a real whole chapter (1, 2, ...) and which copy of it is kept.
		val keptWhole = HashMap<Int, MangaChapter>()
		// Same for in-between chapters, keyed by their exact number (so 2.1 from two sources is one chapter).
		val keptDecimal = HashMap<Int, MangaChapter>()
		for (c in chapters) {
			if (c.number <= 0f) continue
			val target = if (isDecimal(c.number)) keptDecimal else keptWhole
			val key = if (isDecimal(c.number)) decimalKey(c.number) else wholePart(c.number)
			val best = target[key]
			if (best == null || rank(c, current) < rank(best, current)) target[key] = c
		}

		return chapters.filter { c ->
			when {
				c.id == currentId || c.number <= 0f -> true
				isDecimal(c.number) ->
					// 1.1 / 1.2 only stay when there is no whole chapter 1 to stand in for them.
					wholePart(c.number) !in keptWhole && keptDecimal[decimalKey(c.number)]?.id == c.id
				else -> keptWhole[wholePart(c.number)]?.id == c.id
			}
		}
	}

	/** 1.1, 1.5, 12.5 ...; chapters without a known number (0) are never treated as in-between ones. */
	fun isDecimal(number: Float): Boolean = number > 0f && abs(number - number.roundToInt()) > 0.001f

	/** 1 for 1, 1.1 and 1.9; a tiny tolerance keeps 1.9999 style float noise on the right side. */
	private fun wholePart(number: Float): Int = floor(number + 0.001f).toInt()

	private fun decimalKey(number: Float): Int = (number * 1000f).roundToInt()

	/** Which copy stays: the one being read, else the same scanlator and source as it, else the first in the list. */
	private fun rank(c: MangaChapter, current: MangaChapter): Int = when {
		c.id == current.id -> 0
		c.scanlator == current.scanlator && c.branch == current.branch && c.source.name == current.source.name -> 1
		else -> 2
	}
}
