package org.koitharu.kotatsu.reader.ui.epub

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import org.koitharu.kotatsu.R

/**
 * Reading fonts bundled with the app (all open-source families, taken from Novery's set).
 * The stored `epubFontFamily` value for these is `bundled:<id>`.
 */
object EpubBundledFonts {

	const val PREFIX = "bundled:"

	class Entry(val id: String, val label: String, @FontRes val res: Int) {
		val value: String get() = PREFIX + id
	}

	val all: List<Entry> = listOf(
		Entry("nunito", "Nunito", R.font.epub_nunito),
		Entry("literata", "Literata", R.font.epub_literata),
		Entry("merriweather", "Merriweather", R.font.epub_merriweather),
		Entry("lora", "Lora", R.font.epub_lora),
		Entry("source_serif_4", "Source Serif", R.font.epub_source_serif_4),
		Entry("spectral", "Spectral", R.font.epub_spectral),
		Entry("crimson_pro", "Crimson Pro", R.font.epub_crimson_pro),
		Entry("eb_garamond", "EB Garamond", R.font.epub_eb_garamond),
		Entry("libre_baskerville", "Libre Baskerville", R.font.epub_libre_baskerville),
		Entry("atkinson_hyperlegible", "Atkinson Hyperlegible", R.font.epub_atkinson_hyperlegible),
		Entry("lexend", "Lexend", R.font.epub_lexend),
		Entry("open_dyslexic", "OpenDyslexic", R.font.epub_open_dyslexic),
	)

	fun find(value: String): Entry? = all.firstOrNull { it.value == value }

	fun typeface(context: Context, value: String): Typeface? =
		find(value)?.let { runCatching { ResourcesCompat.getFont(context, it.res) }.getOrNull() }
}
