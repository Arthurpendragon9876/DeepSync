package org.koitharu.kotatsu.reader.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R

/*
 * Building blocks of the manga reader quick settings (Reading / More / Extra). Everything is coloured
 * from MaterialTheme only, so the app theme and OLED mode apply without any extra palette.
 */

private val CardShape = RoundedCornerShape(24.dp)

/** Small uppercase label above a group of rows. */
@Composable
internal fun QsSectionLabel(text: String) {
	Text(
		text = text.uppercase(),
		style = MaterialTheme.typography.labelMedium,
		fontWeight = FontWeight.SemiBold,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(horizontal = 24.dp),
	)
}

/** Square-ish quick action card of the Reading tab: icon chip on top, title and one-line subtitle below. */
@Composable
internal fun QsActionCard(
	icon: Int,
	title: String,
	subtitle: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	trailingIcon: Int? = null,
) {
	Surface(
		onClick = onClick,
		shape = CardShape,
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		contentColor = MaterialTheme.colorScheme.onSurface,
		modifier = modifier,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(16.dp),
			verticalArrangement = Arrangement.SpaceBetween,
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.Top,
			) {
				QsIconChip(icon, size = 40)
				if (trailingIcon != null) {
					Icon(
						painter = painterResource(trailingIcon),
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier
							.padding(top = 4.dp)
							.size(16.dp),
					)
				}
			}
			Spacer(Modifier.height(12.dp))
			Column {
				Text(
					text = title,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Bold,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

/** Wide card with a chevron: the banner that leads to the More tab. */
@Composable
internal fun QsBannerCard(
	icon: Int,
	title: String,
	subtitle: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		onClick = onClick,
		shape = CardShape,
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		contentColor = MaterialTheme.colorScheme.onSurface,
		modifier = modifier.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 18.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			QsIconChip(icon, size = 40)
			Spacer(Modifier.width(14.dp))
			Column(Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Bold,
				)
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Icon(
				painter = painterResource(R.drawable.ic_chevron_right),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(18.dp),
			)
		}
	}
}

/** A settings row with a switch. Dimmed and inert when [enabled] is false. */
@Composable
internal fun QsToggleRow(
	icon: Int,
	title: String,
	subtitle: String,
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	enabled: Boolean = true,
) {
	Surface(
		onClick = { onCheckedChange(!checked) },
		enabled = enabled,
		shape = CardShape,
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		contentColor = MaterialTheme.colorScheme.onSurface,
		modifier = Modifier
			.fillMaxWidth()
			.alpha(if (enabled) 1f else 0.38f),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			QsIconChip(icon, size = 36)
			Spacer(Modifier.width(12.dp))
			Column(Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
				)
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Spacer(Modifier.width(8.dp))
			Switch(
				checked = checked,
				onCheckedChange = onCheckedChange,
				enabled = enabled,
			)
		}
	}
}

/** A settings row that just runs [onClick] (no switch). */
@Composable
internal fun QsActionRow(
	icon: Int,
	title: String,
	subtitle: String? = null,
	onClick: () -> Unit,
) {
	Surface(
		onClick = onClick,
		shape = CardShape,
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		contentColor = MaterialTheme.colorScheme.onSurface,
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			QsIconChip(icon, size = 36)
			Spacer(Modifier.width(12.dp))
			Column(Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
				)
				if (subtitle != null) {
					Text(
						text = subtitle,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			Icon(
				painter = painterResource(R.drawable.ic_chevron_right),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(18.dp),
			)
		}
	}
}

/** Rounded tile behind an icon: one neutral tonal colour for every icon, taken from the theme. */
@Composable
private fun QsIconChip(icon: Int, size: Int) {
	Box(
		modifier = Modifier
			.size(size.dp)
			.then(Modifier),
		contentAlignment = Alignment.Center,
	) {
		Surface(
			shape = RoundedCornerShape((size / 2.5f).dp),
			color = MaterialTheme.colorScheme.secondaryContainer,
			contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
			modifier = Modifier.size(size.dp),
		) {
			Box(contentAlignment = Alignment.Center) {
				Icon(
					painter = painterResource(icon),
					contentDescription = null,
					modifier = Modifier.size((size * 0.5f).dp),
				)
			}
		}
	}
}
