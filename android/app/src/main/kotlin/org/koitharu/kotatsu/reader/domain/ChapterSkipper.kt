package org.koitharu.kotatsu.reader.domain

import org.koitharu.kotatsu.parsers.model.MangaChapter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The chapters Next / Previous in the reader walks through. With the skip options off that is every chapter;
 * with them on it is a shorter list, so reading goes 1, 2, 3 instead of 1, 1 (other scanlator), 1.1, 1.2, 2...
 * The chapter being read is always part of the list, so navigation can start from any chapter.
 */
object ChapterSkipper {

	fun navigable(
		chapters: List<MangaChapter>,
		currentId: Long,
		skipDuplicates: Boolean,
		skipDecimals: Boolean,
	): List<MangaChapter> {
		if (!skipDuplicates && !skipDecimals) return chapters
		val current = chapters.firstOrNull { it.id == currentId } ?: return chapters
		var result = chapters
		if (skipDecimals) result = result.filter { it.id == currentId || !isDecimal(it.number) }
		if (skipDuplicates) result = dropDuplicates(result, current)
		return result
	}

	/** 1.1, 1.5, 12.5 ...; chapters without a known number (0) are never treated as in-between ones. */
	fun isDecimal(number: Float): Boolean = number > 0f && abs(number - number.roundToInt()) > 0.001f

	/**
	 * One chapter per number. Which copy stays: the one being read, else the one from the same scanlator and
	 * source as it (so a series keeps flowing through the same release group), else the first in the list.
	 */
	private fun dropDuplicates(chapters: List<MangaChapter>, current: MangaChapter): List<MangaChapter> {
		val kept = HashMap<Float, MangaChapter>()
		for (c in chapters) {
			if (c.number <= 0f) continue
			val best = kept[c.number]
			if (best == null || rank(c, current) < rank(best, current)) kept[c.number] = c
		}
		return chapters.filter { it.number <= 0f || kept[it.number]?.id == it.id }
	}

	private fun rank(c: MangaChapter, current: MangaChapter): Int = when {
		c.id == current.id -> 0
		c.scanlator == current.scanlator && c.branch == current.branch && c.source.name == current.source.name -> 1
		else -> 2
	}
}
