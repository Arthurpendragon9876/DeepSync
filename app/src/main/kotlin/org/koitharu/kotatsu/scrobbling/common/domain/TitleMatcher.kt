package org.koitharu.kotatsu.scrobbling.common.domain

import java.text.Normalizer

/**
 * Compares titles the way a person would: "Shingeki no Kyojin", "Attack on Titan" and
 * "進撃の巨人" are the same series, so every name a title has is compared against every name
 * on the other side, after stripping case, accents, punctuation and bracketed notes.
 */
object TitleMatcher {

	private val diacritics = Regex("\\p{InCombiningDiacriticalMarks}+")
	private val brackets = Regex("[(\\[{][^)\\]}]*[)\\]}]")
	private val separators = Regex("[^\\p{L}\\p{N}]+")

	fun normalize(title: String): String {
		var t = Normalizer.normalize(title, Normalizer.Form.NFKD).replace(diacritics, "")
		t = t.replace(brackets, " ").lowercase()
		t = t.replace(separators, " ").trim()
		return t.removePrefix("the ").trim()
	}

	/** 0..1. 1 means identical once normalised (spaces ignored). */
	fun score(a: String, b: String): Float {
		val x = normalize(a)
		val y = normalize(b)
		if (x.isEmpty() || y.isEmpty()) return 0f
		if (x == y || x.replace(" ", "") == y.replace(" ", "")) return 1f
		val tokensX = x.split(' ').toSet()
		val tokensY = y.split(' ').toSet()
		val overlap = tokensX.intersect(tokensY).size.toFloat()
		val jaccard = overlap / tokensX.union(tokensY).size
		// One title being the other plus a subtitle ("Frieren" vs "Frieren Beyond Journey's End")
		val contained = if (x.length >= 5 && y.length >= 5 && (x.contains(y) || y.contains(x))) {
			minOf(x.length, y.length).toFloat() / maxOf(x.length, y.length) * 0.5f + 0.4f
		} else {
			0f
		}
		val edit = 1f - levenshtein(x, y).toFloat() / maxOf(x.length, y.length)
		return maxOf(jaccard * 0.9f, contained, edit * 0.95f).coerceIn(0f, 0.99f)
	}

	/** The best score between any name in [left] and any name in [right]. */
	fun bestScore(left: Collection<String>, right: Collection<String>): Float {
		var best = 0f
		for (a in left) for (b in right) {
			val s = score(a, b)
			if (s > best) best = s
			if (best >= 1f) return 1f
		}
		return best
	}

	/** True when some name on each side is identical after normalising. */
	fun sameAny(left: Collection<String>, right: Collection<String>): Boolean = bestScore(left, right) >= 1f

	private fun levenshtein(a: String, b: String): Int {
		if (a == b) return 0
		var prev = IntArray(b.length + 1) { it }
		var cur = IntArray(b.length + 1)
		for (i in 1..a.length) {
			cur[0] = i
			for (j in 1..b.length) {
				val cost = if (a[i - 1] == b[j - 1]) 0 else 1
				cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
			}
			val tmp = prev; prev = cur; cur = tmp
		}
		return prev[b.length]
	}
}
