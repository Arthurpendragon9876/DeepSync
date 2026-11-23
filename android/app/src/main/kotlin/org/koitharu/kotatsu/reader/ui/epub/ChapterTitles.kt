package org.koitharu.kotatsu.reader.ui.epub

/**
 * Pulls the chapter's own name out of its title, dropping the numbering most books put in front of it:
 * "Chapter 12: Name", "Ch.12 - Name", "ch 12 Name", "Chapter XII. Name", "Chapter Twelve Name", "12. Name"...
 */
internal object ChapterTitles {

	private const val SEPARATORS = """[\s.:;,\-–—|)\]】》»]*"""

	private const val SPELLED_WORD =
		"zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|" +
			"sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred"

	// Roman numerals only count when written in capitals, so a title like "Chapter Mix" keeps its name
	private const val ROMAN = """(?-i:(?=[MDCLXVI])M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3}))"""

	private const val NUMBER =
		"""(?:\d+(?:[.\-]\d+)?|(?:$SPELLED_WORD)(?:[\s\-]+(?:$SPELLED_WORD))*|$ROMAN)(?![\p{L}\d])"""

	private val keywordPrefix = Regex(
		"""^\s*[\[(【《]?\s*(?:(?:chapter|chap|chp|ch|episode|ep|capítulo|capitulo|chapitre|kapitel)(?!\p{L})\.?|#)""" +
			"""\s*$NUMBER\s*[\])】》]?$SEPARATORS""",
		RegexOption.IGNORE_CASE,
	)

	private val cjkPrefix = Regex("""^\s*第\s*[0-9０-９〇零一二三四五六七八九十百千两兩]+\s*[章话話回节節卷]\s*[.:：、\-–—·\s]*""")

	// "12. Name", "012 - Name", "(12) Name": only a bare number followed by punctuation, and not "3:10"
	private val barePrefix = Regex("""^\s*[\[(]?\s*(\d+)(?:\.\d+)?\s*[\])]?\s*[.:;\-–—|)]+\s*(?!\d)""")

	/**
	 * The chapter's name without its number, or an empty string when the title was nothing but a number.
	 * [bareNumberLimit] stops real names that start with a year ("2001: A Space Odyssey") from being cut.
	 */
	fun nameOf(title: String, bareNumberLimit: Int = 999): String {
		var name = title.trim()
		for (pass in 0 until 2) {
			val stripped = strip(name, allowBare = pass == 0, bareNumberLimit)
			if (stripped == name) break
			name = stripped
		}
		return name
	}

	private fun strip(text: String, allowBare: Boolean, limit: Int): String {
		(keywordPrefix.find(text) ?: cjkPrefix.find(text))?.let { return text.substring(it.range.last + 1).trim() }
		if (allowBare) {
			barePrefix.find(text)?.let { match ->
				if ((match.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE) <= limit) {
					return text.substring(match.range.last + 1).trim()
				}
			}
		}
		return text
	}
}
