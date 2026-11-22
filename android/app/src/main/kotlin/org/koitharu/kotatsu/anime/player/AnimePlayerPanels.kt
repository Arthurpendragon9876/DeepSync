package org.koitharu.kotatsu.anime.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.anime.AnimeEpisodeMeta
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter

private val PanelColor = Color(0xF2171719)
private val PanelDivider = Color(0x22FFFFFF)
private val PanelMuted = Color(0xFFAAAAAA)

/**
 * The player's pop-up pages (settings, speed, subtitles, audio, servers, episodes) as a YouTube-style panel: docked
 * to the right edge in landscape, a bottom sheet in portrait. A header carries an optional back arrow, the title and
 * a close button; tapping the dimmed video behind closes it.
 */
@Composable
internal fun PlayerPanel(
	title: String,
	subtitle: String?,
	onBack: (() -> Unit)?,
	onClose: () -> Unit,
	content: @Composable () -> Unit,
) {
	BoxWithConstraints(
		Modifier.fillMaxSize().background(Color(0x80000000)).clickable(interactionSource = null, indication = null, onClick = onClose),
	) {
		val landscape = maxWidth > maxHeight
		val visible = remember { MutableTransitionState(false).apply { targetState = true } }
		val placement = if (landscape) {
			Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(minOf(400.dp, maxWidth * 0.5f))
		} else {
			Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(maxHeight * 0.68f)
		}
		AnimatedVisibility(
			visibleState = visible,
			modifier = placement,
			enter = if (landscape) slideInHorizontally { it } + fadeIn() else slideInVertically { it } + fadeIn(),
		) {
			Surface(
				shape = if (landscape) RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp) else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
				color = PanelColor,
				// Swallow taps so touching the panel does not reach the dimmed area and close it.
				modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } },
			) {
				Column(
					if (landscape) Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical)) else Modifier.navigationBarsPadding(),
				) {
					Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
						if (onBack != null) {
							PanelIcon(R.drawable.ic_ap_back, "Back", onBack)
						} else {
							Spacer(Modifier.width(12.dp))
						}
						Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
							Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
							if (subtitle != null) {
								Text(subtitle, color = PanelMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
							}
						}
						PanelIcon(R.drawable.ic_ap_close, "Close", onClose)
					}
					Box(Modifier.fillMaxWidth().height(1.dp).background(PanelDivider))
					Box(Modifier.weight(1f)) { content() }
				}
			}
		}
	}
}

@Composable
private fun PanelIcon(icon: Int, description: String, onClick: () -> Unit) {
	Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
		Icon(painterResource(icon), description, tint = Color.White, modifier = Modifier.size(22.dp))
	}
}

/** A scrolling column for the short pages (the settings menu). */
@Composable
internal fun PanelScroll(content: @Composable ColumnScope.() -> Unit) {
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), content = content)
}

/** Settings-menu row: icon, label, the current value on the right and a chevron. */
@Composable
internal fun PanelMenuRow(icon: Int, label: String, value: String?, onClick: () -> Unit) {
	Row(
		Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(painterResource(icon), null, tint = Color.White, modifier = Modifier.size(24.dp))
		Text(label, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 20.dp, end = 8.dp))
		if (value != null) Text(value, color = PanelMuted, fontSize = 14.sp, maxLines = 1)
		Icon(painterResource(R.drawable.ic_ap_expand), null, tint = PanelMuted, modifier = Modifier.size(20.dp).rotate(-90f))
	}
}

/** One pickable option: a check on the left marks the current one. */
@Composable
internal fun PanelChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
	val accent = MaterialTheme.colorScheme.primary
	Row(
		Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
			if (selected) Icon(painterResource(R.drawable.ic_ap_check), null, tint = accent, modifier = Modifier.size(22.dp))
		}
		Text(
			label,
			color = if (selected) accent else Color.White,
			fontSize = 16.sp,
			fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f).padding(start = 20.dp),
		)
	}
}

@Composable
internal fun PanelEmpty(text: String) {
	Text(text, color = PanelMuted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp))
}

/** Episode with its preview image, like a YouTube playlist entry; the playing one is tinted and shows a play mark. */
@Composable
internal fun EpisodePanelRow(
	chapter: MangaChapter,
	manga: Manga,
	imageLoader: ImageLoader,
	current: Boolean,
	onClick: () -> Unit,
) {
	val ctx = LocalContext.current
	val thumb = AnimeEpisodeMeta.previews[chapter.id] ?: manga.coverUrl
	val request = remember(thumb, manga.source) {
		ImageRequest.Builder(ctx).data(thumb).crossfade(true).mangaSourceExtra(manga.source).build()
	}
	val label = AnimePlaybackSession.episodeLabel(chapter)
	val title = chapter.title?.takeIf { it.isNotBlank() && it != label } ?: manga.title
	val accent = MaterialTheme.colorScheme.primary
	Row(
		Modifier.fillMaxWidth()
			.background(if (current) Color(0x2EFFFFFF) else Color.Transparent)
			.clickable(onClick = onClick)
			.padding(horizontal = 12.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(Modifier.width(124.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
			AsyncImage(
				model = request,
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize().background(Color(0xFF2A2A2E)),
			)
			if (current) {
				Box(Modifier.fillMaxSize().background(Color(0x77000000)), contentAlignment = Alignment.Center) {
					Icon(painterResource(R.drawable.ic_ap_play), null, tint = Color.White, modifier = Modifier.size(26.dp))
				}
			}
		}
		Column(Modifier.weight(1f).padding(start = 12.dp)) {
			Text(label.uppercase(), color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
			Text(title, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
		}
	}
}
