package org.koitharu.kotatsu.settings.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.github.AppUpdateRepository
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import javax.inject.Inject

/**
 * Settings > About: DeepSync's beta version, its GitHub page, and a credit for DropSauce, the main
 * branch it is built on. The license entry keeps the notices the open-source licenses require.
 */
@AndroidEntryPoint
class AboutSettingsFragment : BaseComposeSettingsFragment(R.string.about) {

	@Inject
	lateinit var appUpdateRepository: AppUpdateRepository

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val update by appUpdateRepository.observeAvailableUpdate().collectAsState()
				AboutScreen(
					version = BuildConfig.VERSION_NAME,
					updateAvailable = update != null,
					onUpdate = { router.openAppUpdate() },
					onLicenses = ::showLicenses,
					onSource = { open(R.string.url_github) },
					onIssues = { open(R.string.url_deepsync_issues) },
					onAuthor = { openUrl("https://github.com/Arthurpendragon9876") },
					onMainBranch = { open(R.string.url_dropsauce_github) },
					onMainAuthor = { openUrl("https://github.com/HuzaifaKhalid1311") },
				)
			}
		}
	}

	private fun open(urlRes: Int) = openUrl(getString(urlRes))

	private fun openUrl(url: String) {
		router.openExternalBrowser(url, getString(R.string.source_code))
	}

	private fun showLicenses() {
		buildAlertDialog(requireContext()) {
			setTitle(R.string.ds_about_licenses)
			setMessage(R.string.ds_about_licenses_body)
			setPositiveButton(R.string.close, null)
		}.show()
	}
}

@androidx.compose.runtime.Composable
private fun AboutScreen(
	version: String,
	updateAvailable: Boolean,
	onUpdate: () -> Unit,
	onLicenses: () -> Unit,
	onSource: () -> Unit,
	onIssues: () -> Unit,
	onAuthor: () -> Unit,
	onMainBranch: () -> Unit,
	onMainAuthor: () -> Unit,
) {
	SettingsScaffold {
		item {
			// Hero: icon, name and two chips (version + beta, author)
			Surface(
				shape = RoundedCornerShape(32.dp),
				color = MaterialTheme.colorScheme.primaryContainer,
				modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
			) {
				Column(
					modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp),
					horizontalAlignment = Alignment.CenterHorizontally,
				) {
					AndroidView(
						modifier = Modifier.size(96.dp).clip(RoundedCornerShape(26.dp)),
						factory = { context ->
							ImageView(context).apply {
								setImageDrawable(context.packageManager.getApplicationIcon(context.packageName))
							}
						},
					)
					Spacer(Modifier.height(14.dp))
					Text(
						text = stringResource(R.string.app_name_plain),
						style = MaterialTheme.typography.headlineLarge,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onPrimaryContainer,
					)
					Spacer(Modifier.height(12.dp))
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						AboutChip(text = stringResource(R.string.ds_about_version_beta, version), onClick = null)
						AboutChip(text = "Arthurpendragon9876", onClick = onAuthor)
					}
				}
			}
		}
		item {
			Surface(
				shape = RoundedCornerShape(24.dp),
				color = MaterialTheme.colorScheme.surfaceContainer,
				modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
			) {
				Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
					Text(
						text = stringResource(R.string.ds_about_blurb),
						style = MaterialTheme.typography.bodyLarge,
					)
					Text(
						text = stringResource(R.string.ds_about_beta_note),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.ds_about_section_updates)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.ds_about_check_updates),
						subtitle = stringResource(
							if (updateAvailable) R.string.ds_about_update_available else R.string.ds_about_up_to_date,
						),
						icon = R.drawable.ic_app_update,
						shape = pos.shape,
						onClick = { if (updateAvailable) onUpdate() },
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.ds_about_section_links)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.ds_about_source),
						subtitle = stringResource(R.string.url_github),
						icon = R.drawable.ic_github,
						shape = pos.shape,
						onClick = onSource,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.ds_about_issues),
						subtitle = stringResource(R.string.url_deepsync_issues),
						icon = R.drawable.ic_info_outline,
						shape = pos.shape,
						onClick = onIssues,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.ds_about_section_main)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.ds_about_main_title),
						subtitle = stringResource(R.string.ds_about_main_summary),
						icon = R.drawable.ic_github,
						shape = pos.shape,
						onClick = onMainBranch,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = "HuzaifaKhalid1311",
						subtitle = stringResource(R.string.ds_about_main_author),
						icon = R.drawable.ic_account_circle,
						shape = pos.shape,
						onClick = onMainAuthor,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.ds_about_section_legal)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.ds_about_licenses),
						subtitle = null,
						icon = R.drawable.ic_info_outline,
						shape = pos.shape,
						onClick = onLicenses,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}

@androidx.compose.runtime.Composable
private fun AboutChip(text: String, onClick: (() -> Unit)?) {
	val shape = RoundedCornerShape(50)
	val color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
	val content = @androidx.compose.runtime.Composable {
		Text(
			text = text,
			style = MaterialTheme.typography.labelLarge,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onPrimaryContainer,
			modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
		)
	}
	if (onClick == null) {
		Surface(shape = shape, color = color, content = content)
	} else {
		Surface(onClick = onClick, shape = shape, color = color, content = content)
	}
}
