package org.koitharu.kotatsu.anime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * Bridges an Aniyomi [AnimeFilterList] to the vendored Mihon [Filter] tree, the same trick the LNReader
 * plugins use ([org.koitharu.kotatsu.lnreader.LnFilterMapper]). The whole dynamic filter UI - sheet,
 * sort picker, tag encoding, "filter applied" badge - then works for anime sources with no changes, so
 * genre, year, status and so on show up whenever the extension declares them.
 *
 * Both lists are walked in the same order, which is what [org.koitharu.kotatsu.mihon.MihonFilterMapper]
 * encodes its index paths against.
 */
object AnimeFilterMapper {

	/** A Mihon mirror of [anime], in its current state. */
	fun toFilterList(anime: AnimeFilterList): FilterList = FilterList(anime.map { toFilter(it) })

	/** Copies the user's choices from the mirror [source] back onto the extension's own [target] list. */
	fun copyState(source: FilterList, target: AnimeFilterList) {
		source.zip(target).forEach { (src, dst) -> copy(src, dst) }
	}

	private fun toFilter(f: AnimeFilter<*>): Filter<*> = when (f) {
		is AnimeFilter.Select<*> -> object : Filter.Select<String>(
			f.name,
			f.values.map { it.toString() }.toTypedArray(),
			f.state,
		) {}

		is AnimeFilter.Text -> object : Filter.Text(f.name, f.state) {}
		is AnimeFilter.CheckBox -> object : Filter.CheckBox(f.name, f.state) {}
		is AnimeFilter.TriState -> object : Filter.TriState(f.name, f.state) {}
		is AnimeFilter.Group<*> -> object : Filter.Group<Filter<*>>(
			f.name,
			f.state.map { toFilter(it as AnimeFilter<*>) },
		) {}

		is AnimeFilter.Sort -> object : Filter.Sort(
			f.name,
			f.values,
			f.state?.let { Filter.Sort.Selection(it.index, it.ascending) },
		) {}

		is AnimeFilter.Separator -> Filter.Separator(f.name)
		else -> Filter.Header(f.name)
	}

	private fun copy(src: Filter<*>, dst: AnimeFilter<*>) {
		when {
			src is Filter.Select<*> && dst is AnimeFilter.Select<*> -> dst.state = src.state
			src is Filter.Text && dst is AnimeFilter.Text -> dst.state = src.state
			src is Filter.CheckBox && dst is AnimeFilter.CheckBox -> dst.state = src.state
			src is Filter.TriState && dst is AnimeFilter.TriState -> dst.state = src.state
			src is Filter.Sort && dst is AnimeFilter.Sort ->
				dst.state = src.state?.let { AnimeFilter.Sort.Selection(it.index, it.ascending) }

			src is Filter.Group<*> && dst is AnimeFilter.Group<*> ->
				src.state.zip(dst.state).forEach { (s, d) ->
					if (s is Filter<*> && d is AnimeFilter<*>) copy(s, d)
				}
		}
	}
}
