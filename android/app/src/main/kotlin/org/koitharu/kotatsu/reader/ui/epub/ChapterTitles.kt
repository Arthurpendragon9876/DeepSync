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

	/** Longest line that can still be a chapter title; anything longer is story text. */
	private const val MAX_TITLE_LINE = 200

	/** What to remove from a chapter's body when the header already shows its title. */
	enum class Redundancy(val key: String) {
		OFF("off"), NUMBER("number"), NAME("name"), BOTH("both");

		companion object {
			fun of(key: String?): Redundancy = values().firstOrNull { it.key == key } ?: OFF
		}
	}

	/** A title split into its numbering ("Chapter 12", empty when it has none) and its name. */
	data class Heading(val number: String, val name: String)

	fun split(title: String): Heading {
		val text = title.trim()
		val match = keywordPrefix.find(text) ?: cjkPrefix.find(text)
			?: barePrefix.find(text)?.takeIf { (it.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE) <= 999 }
			?: return Heading("", text)
		val number = text.substring(0, match.range.last + 1).trim()
			.trimEnd(':', ';', ',', '-', '–', '—', '|', '.', '：', '、', '·', ' ')
		return Heading(number, text.substring(match.range.last + 1).trim())
	}

	private fun normalize(text: String): String = buildString {
		text.forEach { if (it.isLetterOrDigit()) append(it.lowercaseChar()) }
	}

	/**
	 * True when [line] (the first text line of a chapter) is that chapter's own title: the same title or name,
	 * the name behind a "Chapter N:" lead-in, or a bare "Chapter N".
	 */
	fun isTitleLine(line: String, chapterTitle: String): Boolean {
		val text = line.trim()
		if (text.isEmpty() || text.length > MAX_TITLE_LINE) return false
		val heading = split(text)
		if (heading.number.isNotEmpty() && heading.name.isBlank()) return true
		val lineKey = normalize(text)
		if (lineKey.isEmpty()) return false
		if (lineKey == normalize(chapterTitle)) return true
		val nameKey = normalize(split(chapterTitle).name)
		return nameKey.isNotEmpty() && (lineKey == nameKey || normalize(heading.name) == nameKey)
	}

	/** What a title line should read after cleaning: null drops the line, otherwise the text to keep. */
	fun cleaned(line: String, mode: Redundancy): String? {
		val heading = split(line)
		return when (mode) {
			Redundancy.OFF -> line
			Redundancy.BOTH -> null
			Redundancy.NUMBER -> when {
				heading.number.isEmpty() -> line
				heading.name.isBlank() -> null
				else -> heading.name
			}
			Redundancy.NAME -> when {
				heading.number.isEmpty() -> null
				heading.name.isBlank() -> line
				else -> heading.number
			}
		}
	}

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
