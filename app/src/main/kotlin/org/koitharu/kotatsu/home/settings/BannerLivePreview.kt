package org.koitharu.kotatsu.home.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.util.ext.isRemoteCoverUrl
import org.koitharu.kotatsu.core.util.ext.mangaCoverDiskCacheKey
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.home.ui.model.HeroSnap
import org.koitharu.kotatsu.home.ui.model.HomeBannerStyle
import java.util.Locale
import kotlin.math.abs

/** Width, in dp, of the phone screen the preview is laid out for. Everything inside uses Home's real dp and sp. */
private const val REFERENCE_WIDTH = 360f
private const val ROTATE_MS = 5000L

/**
 * The real Home banner, drawn live in the settings screen. It uses the same sizes, shapes, scrims and
 * badges as Home and the covers Home last showed, so what you see here is what Home will look like.
 * It turns pages by itself when auto-rotate is on, panels can be tapped to promote them and the Spotlight
 * cards can be swiped, exactly as on Home.
 *
 * It is laid out at [REFERENCE_WIDTH] and scaled down to fit the card, so nothing is re-measured by eye.
 */
@Composable
internal fun BannerLivePreview(
	style: HomeBannerStyle,
	items: List<HeroSnap>,
	autoRotate: Boolean,
	anime: Boolean,
	modifier: Modifier = Modifier,
) {
	val shown = remember(items) { items.ifEmpty { sampleItems() } }
	var index by remember(shown) { mutableIntStateOf(0) }

	// Same cadence as Home: a page every five seconds, except Spotlight which is swiped
	LaunchedEffect(autoRotate, style, shown.size) {
		if (!autoRotate || style == HomeBannerStyle.SPOTLIGHT || shown.size < 2) return@LaunchedEffect
		while (true) {
			delay(ROTATE_MS)
			index = (index + 1) % shown.size
		}
	}

	BoxWithConstraints(modifier.fillMaxWidth().animateContentSize()) {
		val scale = maxWidth.value / REFERENCE_WIDTH
		val outer = LocalDensity.current
		// Lay the banner out at phone width and let the density do the scaling, so dp and sp both shrink together
		val scaled = Density(outer.density * scale, outer.fontScale)
		CompositionLocalProvider(LocalDensity provides scaled) {
			Box(Modifier.width(REFERENCE_WIDTH.dp)) {
				Crossfade(targetState = style, label = "banner-style") { current ->
					when (current) {
						HomeBannerStyle.PANELS -> PanelsPreview(shown, index, anime) { index = it }
						HomeBannerStyle.CARD -> CardPreview(shown, index, anime)
						HomeBannerStyle.SPOTLIGHT -> SpotlightPreview(shown)
					}
				}
			}
		}
	}
}

// ---- Panels ---------------------------------------------------------------------------------

@Composable
private fun PanelsPreview(items: List<HeroSnap>, index: Int, anime: Boolean, onPromote: (Int) -> Unit) {
	val n = items.size
	val main = items[index % n]
	val side1 = if (n > 1) items[(index + 1) % n] else null
	val side2 = if (n > 2) items[(index + 2) % n] else null
	val badge = main.badge ?: "#${(index % n) + 1} TRENDING"

	Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
		Row(
			Modifier
				.fillMaxWidth()
				.aspectRatio(1f / 1.12f)
				.rotate(-1.4f),
		) {
			// Featured panel
			Panel(Modifier.weight(0.632f).fillMaxHeight().padding(end = 3.dp)) {
				Cover(main, Modifier.fillMaxSize())
				Box(
					Modifier
						.fillMaxWidth()
						.height(200.dp)
						.align(Alignment.BottomCenter)
						.background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xE6000000)))),
				)
				Text(
					text = badge,
					color = Color.Black,
					fontSize = 11.sp,
					fontWeight = FontWeight.Bold,
					letterSpacing = 0.08.em(),
					modifier = Modifier
						.align(Alignment.TopStart)
						.background(Color.White, RoundedCornerShape(3.dp))
						.padding(horizontal = 10.dp, vertical = 5.dp),
				)
				Box(
					Modifier
						.align(Alignment.TopEnd)
						.padding(10.dp)
						.size(40.dp)
						.background(Color(0x66000000), CircleShape),
					contentAlignment = Alignment.Center,
				) {
					Icon(painterResource(R.drawable.ic_info), null, tint = Color.White, modifier = Modifier.size(24.dp))
				}
				Column(
					Modifier
						.align(Alignment.BottomStart)
						.fillMaxWidth()
						.padding(start = 14.dp, end = 76.dp, bottom = 18.dp),
				) {
					Text(
						text = main.title,
						color = Color.White,
						fontSize = 20.sp,
						fontWeight = FontWeight.Bold,
						maxLines = 3,
						overflow = TextOverflow.Ellipsis,
					)
					Text(
						text = main.genres.firstOrNull()?.uppercase().orEmpty(),
						color = Color(0xB3FFFFFF),
						fontSize = 11.sp,
						letterSpacing = 0.12.em(),
						maxLines = 1,
						modifier = Modifier.padding(top = 4.dp),
					)
				}
				Box(
					Modifier
						.align(Alignment.BottomEnd)
						.padding(12.dp)
						.size(52.dp)
						.background(Color.White, RoundedCornerShape(3.dp)),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						painterResource(if (anime) R.drawable.ic_play else R.drawable.ic_read),
						null,
						tint = Color.Black,
						modifier = Modifier.size(24.dp),
					)
				}
			}
			// Two small panels: the next titles, tapping one promotes it like on Home
			Column(Modifier.weight(0.368f).fillMaxHeight().padding(start = 3.dp)) {
				if (side1 != null) {
					Panel(
						Modifier.weight(1f).fillMaxWidth().padding(bottom = 3.dp).clickable { onPromote((index + 1) % n) },
					) { Cover(side1, Modifier.fillMaxSize()) }
				} else Spacer(Modifier.weight(1f))
				if (side2 != null) {
					Panel(
						Modifier.weight(1f).fillMaxWidth().padding(top = 3.dp).clickable { onPromote((index + 2) % n) },
					) { Cover(side2, Modifier.fillMaxSize()) }
				} else Spacer(Modifier.weight(1f))
			}
		}
	}
}

/** A manga panel: three-dp corners and a thin inked border. */
@Composable
private fun Panel(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
	Box(
		modifier
			.clip(RoundedCornerShape(3.dp))
			.background(MaterialTheme.colorScheme.surfaceContainerHigh)
			.border(BorderStroke(2.dp, Color(0xEEFFFFFF)), RoundedCornerShape(3.dp)),
		content = content,
	)
}

// ---- Card -----------------------------------------------------------------------------------

@Composable
private fun CardPreview(items: List<HeroSnap>, index: Int, anime: Boolean) {
	val n = items.size
	val main = items[index % n]
	val badge = (main.badge ?: "#${(index % n) + 1} TRENDING") +
		(main.rating?.takeIf { it > 0f }?.let { "   \u2605 " + String.format(Locale.US, "%.1f", it) }.orEmpty())

	Box(
		Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 8.dp)
			.height(300.dp)
			.clip(RoundedCornerShape(28.dp))
			.background(Color.Black),
	) {
		// Wide art when there is some, otherwise the cover zoomed and blurred, as Home does
		val backdrop = main.bannerUrl
		if (backdrop != null) {
			Cover(main, Modifier.fillMaxSize(), useBanner = true)
		} else {
			Cover(main, Modifier.fillMaxSize().graphicsLayer { scaleX = 1.4f; scaleY = 1.4f }.blur(20.dp))
		}
		Box(
			Modifier
				.fillMaxSize()
				.background(
					Brush.verticalGradient(
						0f to Color(0x00000000),
						0.55f to Color(0x66000000),
						1f to Color(0xF2000000),
					),
				),
		)
		Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.Bottom) {
			Box(
				Modifier
					.size(width = 108.dp, height = 158.dp)
					.shadow(8.dp, RoundedCornerShape(18.dp))
					.clip(RoundedCornerShape(18.dp)),
			) { Cover(main, Modifier.fillMaxSize()) }
			Column(Modifier.weight(1f).padding(start = 16.dp)) {
				Text(
					text = badge,
					color = MaterialTheme.colorScheme.primary,
					fontSize = 12.sp,
					fontWeight = FontWeight.Bold,
					letterSpacing = 0.06.em(),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					text = main.title,
					color = Color.White,
					fontSize = 22.sp,
					fontWeight = FontWeight.Bold,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(top = 4.dp),
				)
				Text(
					text = main.genres.take(3).joinToString(" \u00b7 "),
					color = Color(0xB3FFFFFF),
					fontSize = 13.sp,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(top = 4.dp),
				)
				Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
					repeat(n) { i ->
						val on = i == index % n
						Box(
							Modifier
								.size(width = if (on) 18.dp else 6.dp, height = 6.dp)
								.background(if (on) Color.White else Color(0x66FFFFFF), RoundedCornerShape(3.dp)),
						)
					}
				}
			}
		}
	}
}

// ---- Spotlight ------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpotlightPreview(items: List<HeroSnap>) {
	val pagerState = rememberPagerState(pageCount = { items.size })
	HorizontalPager(
		state = pagerState,
		pageSize = PageSize.Fixed(264.dp),
		contentPadding = PaddingValues(horizontal = (REFERENCE_WIDTH.dp - 264.dp) / 2),
		modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
	) { page ->
		val item = items[page]
		// The centred card is full size; the ones peeking in shrink and fade, as on Home
		val distance = abs((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
		Column(
			Modifier
				.graphicsLayer {
					val s = 1f - 0.12f * distance
					scaleX = s
					scaleY = s
					alpha = 1f - 0.45f * distance
				}
				.padding(horizontal = 6.dp)
				.padding(10.dp),
		) {
			Box(Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(28.dp))) {
				Cover(item, Modifier.fillMaxSize())
			}
			Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
				Text(
					text = item.title,
					style = MaterialTheme.typography.titleMedium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				item.rating?.takeIf { it > 0f }?.let {
					Text(
						text = String.format(Locale.US, "\u2605 %.1f", it),
						style = MaterialTheme.typography.labelLarge,
						modifier = Modifier
							.padding(start = 8.dp)
							.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
							.padding(horizontal = 12.dp, vertical = 6.dp),
					)
				}
			}
			Row(
				Modifier
					.fillMaxWidth()
					.padding(top = 10.dp)
					.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
					.padding(14.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = item.description?.take(120) ?: item.genres.take(3).joinToString(" \u00b7 "),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f),
				)
				Icon(
					painterResource(R.drawable.ic_arrow_forward),
					null,
					tint = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.padding(start = 8.dp).size(20.dp).rotate(-45f),
				)
			}
		}
	}
}

// ---- Shared bits ----------------------------------------------------------------------------

/**
 * A cover image, loaded the way Home loads it (a source cover needs its source's headers and shares one cache
 * slot per title, so the covers Home already fetched show here without another download). While it loads, or
 * when it cannot (no connection), a tinted tile with the title's initial stands in.
 */
@Composable
private fun Cover(item: HeroSnap, modifier: Modifier, useBanner: Boolean = false) {
	val context = LocalContext.current
	val url = if (useBanner) item.bannerUrl ?: item.coverUrl else item.coverUrl
	val placeholder: @Composable () -> Unit = {
		Box(
			Modifier
				.fillMaxSize()
				.background(
					Brush.linearGradient(
						listOf(
							MaterialTheme.colorScheme.primaryContainer,
							MaterialTheme.colorScheme.tertiaryContainer,
						),
					),
				),
			contentAlignment = Alignment.Center,
		) {
			Text(
				text = item.title.take(1).uppercase(),
				color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f),
				fontSize = 40.sp,
				fontWeight = FontWeight.Bold,
			)
		}
	}
	if (url == null) {
		Box(modifier) { placeholder() }
		return
	}
	val request = remember(url, item.mangaId, item.sourceName) {
		ImageRequest.Builder(context).data(url).apply {
			val sourceName = item.sourceName
			if (sourceName != null && !useBanner) {
				mangaSourceExtra(MangaSource(sourceName))
				val id = item.mangaId
				if (id != null && isRemoteCoverUrl(url)) diskCacheKey(mangaCoverDiskCacheKey(id))
			}
		}.build()
	}
	SubcomposeAsyncImage(
		model = request,
		contentDescription = null,
		contentScale = ContentScale.Crop,
		modifier = modifier,
		loading = { placeholder() },
		error = { placeholder() },
	)
}

/** Letter spacing in em, as the XML layouts use it. */
private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

/** Shown before Home has loaded any titles (first launch, offline): tinted tiles with sample names. */
private fun sampleItems() = listOf(
	HeroSnap("Sample title", null, null, listOf("Action", "Fantasy"), null, 8.4f, "A short synopsis of the featured title appears here."),
	HeroSnap("Another series", null, null, listOf("Drama", "Romance"), null, 7.9f, "The next title in the banner."),
	HeroSnap("Third pick", null, null, listOf("Comedy", "Slice of life"), null, 8.1f, "Swipe or wait to see the next one."),
	HeroSnap("Fourth pick", null, null, listOf("Mystery", "Thriller"), null, 7.6f, "More titles follow."),
)
