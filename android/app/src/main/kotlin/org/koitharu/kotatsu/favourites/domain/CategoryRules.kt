package org.koitharu.kotatsu.favourites.domain

/** How often the background check looks at a category's titles. No value (null) means the global schedule. */
enum class CategoryUpdateInterval(val key: String, val periodMs: Long?) {

	DAILY("daily", DAY_MS),
	WEEKLY("weekly", 7 * DAY_MS),

	/** Never checked in the background; titles can still be refreshed by hand. */
	PAUSED("paused", null);

	companion object {

		fun of(key: String?): CategoryUpdateInterval? = entries.firstOrNull { it.key == key }
	}
}

/**
 * What a category is for. [ANY] is a universal category. A category with no tag at all (null) is *Auto*: it follows
 * its titles, see [CategoryRules.resolveTag].
 */
enum class CategoryMediaTag {

	ANY, MANGA, NOVEL, ANIME, TV_MOVIES;

	companion object {

		fun of(name: String?): CategoryMediaTag? = entries.firstOrNull { it.name == name }
	}
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Pure rules behind subcategories, tags and update intervals; no Android types so they stay unit-testable. */
object CategoryRules {

	data class Node<T>(val item: T, val depth: Int)

	/**
	 * Orders [items] so each subcategory follows its parent, keeping the given order inside every group.
	 * Only one level of nesting exists: a category whose parent is missing, is itself, or is already a child
	 * (e.g. after a sync conflict or a parent cycle) is shown as a top-level one rather than being hidden.
	 */
	fun <T> hierarchy(items: List<T>, id: (T) -> Long, parent: (T) -> Long?): List<Node<T>> {
		val byId = items.associateBy(id)
		fun isTop(item: T): Boolean {
			val p = parent(item) ?: return true
			return p == id(item) || byId[p] == null
		}
		val tops = items.filter { isTop(it) || !isTop(byId.getValue(parent(it)!!)) }
		val topIds = tops.mapTo(HashSet(tops.size)) { id(it) }
		val children = items.filter { id(it) !in topIds }.groupBy { parent(it)!! }
		val result = ArrayList<Node<T>>(items.size)
		for (top in tops) {
			result += Node(top, 0)
			children[id(top)]?.forEach { result += Node(it, 1) }
		}
		return result
	}

	/**
	 * Categories [self] may be nested under: top-level ones other than itself. A category that already has
	 * subcategories cannot become one (only one level of nesting). [self] is null for a new category.
	 */
	fun <T> parentCandidates(all: List<T>, self: Long?, id: (T) -> Long, parent: (T) -> Long?): List<T> {
		if (self != null && all.any { parent(it) == self }) return emptyList()
		val ids = all.mapTo(HashSet(all.size)) { id(it) }
		return all.filter { c ->
			val p = parent(c)
			id(c) != self && (p == null || p == id(c) || p !in ids)
		}
	}

	fun displayTitle(title: String, parentTitle: String?): String =
		if (parentTitle.isNullOrBlank()) title else "$parentTitle › $title"

	/** The tag a category effectively has: its own choice, else what all of its titles are, else universal. */
	fun resolveTag(explicit: CategoryMediaTag?, titleTags: Collection<CategoryMediaTag>): CategoryMediaTag {
		if (explicit != null) return explicit
		val distinct = titleTags.toSet()
		return if (distinct.size == 1) distinct.first() else CategoryMediaTag.ANY
	}

	/**
	 * Whether a category with [tag] is offered for titles of kinds [titleTags]. Universal categories always are,
	 * and with a mixed selection a category is offered when it fits any of them.
	 */
	fun accepts(tag: CategoryMediaTag, titleTags: Set<CategoryMediaTag>): Boolean =
		tag == CategoryMediaTag.ANY || titleTags.isEmpty() || tag in titleTags

	/** Whether the title last checked at [lastCheck] is due for another check at [now] under [interval]. */
	fun isDue(interval: CategoryUpdateInterval?, lastCheck: Long, now: Long): Boolean {
		if (interval == null) return true
		val period = interval.periodMs ?: return false
		return now - lastCheck >= period
	}
}
