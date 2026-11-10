package org.koitharu.kotatsu.reader.ui.epub

import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.prefs.AppSettings

/** A reader preset the user saved from their current settings ("Create preset"). */
data class UserNovelPreset(
	val name: String,
	val fontSize: Int,
	val lineHeight: Int,
	val paragraphSpacing: Int,
	val horizontalPadding: Int,
	val verticalPadding: Int,
	val letterSpacing: Int,
	val indent: Int,
	val textAlign: String,
	val font: String,
	val theme: String,
	val customBackgroundColor: Int,
	val customTextColor: Int,
	val hyphenation: Boolean,
	val maxWidth: Int,
	val readingMode: String,
	val scrollStyle: String,
)

/**
 * Persists [UserNovelPreset]s as a JSON array in [AppSettings.epubCustomPresets]. The custom background
 * picture and its blur are deliberately not part of a preset: they are about the device's photos, not
 * about how text is set.
 */
object NovelPresetStore {

	fun load(settings: AppSettings): List<UserNovelPreset> = runCatching {
		val array = JSONArray(settings.epubCustomPresets)
		(0 until array.length()).map { i ->
			val o = array.getJSONObject(i)
			UserNovelPreset(
				name = o.getString("name"),
				fontSize = o.getInt("fontSize"),
				lineHeight = o.getInt("lineHeight"),
				paragraphSpacing = o.getInt("paragraphSpacing"),
				horizontalPadding = o.getInt("horizontalPadding"),
				verticalPadding = o.optInt("verticalPadding", 112),
				letterSpacing = o.getInt("letterSpacing"),
				indent = o.getInt("indent"),
				textAlign = o.getString("textAlign"),
				font = o.getString("font"),
				theme = o.getString("theme"),
				customBackgroundColor = o.optInt("customBackgroundColor", 0xFFFFFFFF.toInt()),
				customTextColor = o.optInt("customTextColor", 0xFF1B1B1F.toInt()),
				hyphenation = o.optBoolean("hyphenation", false),
				maxWidth = o.optInt("maxWidth", 0),
				readingMode = o.optString("readingMode", "scroll"),
				scrollStyle = o.optString("scrollStyle", "continuous"),
			)
		}
	}.getOrDefault(emptyList())

	private fun save(settings: AppSettings, presets: List<UserNovelPreset>) {
		val array = JSONArray()
		presets.forEach { p ->
			array.put(
				JSONObject()
					.put("name", p.name)
					.put("fontSize", p.fontSize)
					.put("lineHeight", p.lineHeight)
					.put("paragraphSpacing", p.paragraphSpacing)
					.put("horizontalPadding", p.horizontalPadding)
					.put("verticalPadding", p.verticalPadding)
					.put("letterSpacing", p.letterSpacing)
					.put("indent", p.indent)
					.put("textAlign", p.textAlign)
					.put("font", p.font)
					.put("theme", p.theme)
					.put("customBackgroundColor", p.customBackgroundColor)
					.put("customTextColor", p.customTextColor)
					.put("hyphenation", p.hyphenation)
					.put("maxWidth", p.maxWidth)
					.put("readingMode", p.readingMode)
					.put("scrollStyle", p.scrollStyle),
			)
		}
		settings.epubCustomPresets = array.toString()
	}

	fun capture(settings: AppSettings, name: String) = UserNovelPreset(
		name = name,
		fontSize = settings.epubFontSize,
		lineHeight = settings.epubLineHeight,
		paragraphSpacing = settings.epubParagraphSpacing,
		horizontalPadding = settings.epubHorizontalPadding,
		verticalPadding = settings.epubVerticalPadding,
		letterSpacing = settings.epubLetterSpacing,
		indent = settings.epubParagraphIndent,
		textAlign = settings.epubTextAlign,
		font = settings.epubFontFamily,
		theme = settings.epubTheme,
		customBackgroundColor = settings.epubCustomBackgroundColor,
		customTextColor = settings.epubCustomTextColor,
		hyphenation = settings.isEpubHyphenationEnabled,
		maxWidth = settings.epubMaxTextWidth,
		readingMode = settings.epubReadingMode,
		scrollStyle = settings.epubScrollStyle,
	)

	/** Saves the current settings under [name]; a preset with the same name is replaced. */
	fun create(settings: AppSettings, name: String) {
		val trimmed = name.trim().ifEmpty { return }
		val others = load(settings).filterNot { it.name.equals(trimmed, ignoreCase = true) }
		save(settings, others + capture(settings, trimmed))
	}

	fun delete(settings: AppSettings, name: String) {
		save(settings, load(settings).filterNot { it.name == name })
	}

	fun apply(settings: AppSettings, p: UserNovelPreset) {
		settings.epubFontSize = p.fontSize
		settings.epubLineHeight = p.lineHeight
		settings.epubParagraphSpacing = p.paragraphSpacing
		settings.epubHorizontalPadding = p.horizontalPadding
		settings.epubVerticalPadding = p.verticalPadding
		settings.epubLetterSpacing = p.letterSpacing
		settings.epubParagraphIndent = p.indent
		settings.epubTextAlign = p.textAlign
		settings.epubFontFamily = p.font
		settings.epubTheme = p.theme
		settings.epubCustomBackgroundColor = p.customBackgroundColor
		settings.epubCustomTextColor = p.customTextColor
		settings.isEpubHyphenationEnabled = p.hyphenation
		settings.epubMaxTextWidth = p.maxWidth
		settings.epubReadingMode = p.readingMode
		settings.epubScrollStyle = p.scrollStyle
	}

	fun isActive(settings: AppSettings, p: UserNovelPreset): Boolean =
		settings.epubFontSize == p.fontSize && settings.epubFontFamily == p.font &&
			settings.epubTheme == p.theme && settings.epubLineHeight == p.lineHeight &&
			settings.epubParagraphSpacing == p.paragraphSpacing &&
			settings.epubHorizontalPadding == p.horizontalPadding &&
			settings.epubLetterSpacing == p.letterSpacing && settings.epubTextAlign == p.textAlign &&
			settings.epubMaxTextWidth == p.maxWidth
}
