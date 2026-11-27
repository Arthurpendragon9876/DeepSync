package org.koitharu.kotatsu.settings.diagnostics

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.diagnostics.DiagnosticsReport
import org.koitharu.kotatsu.diagnostics.DiagnosticsRedactor
import org.koitharu.kotatsu.diagnostics.FailureKind
import org.koitharu.kotatsu.diagnostics.HealthState
import org.koitharu.kotatsu.diagnostics.PluginRuntime
import org.koitharu.kotatsu.diagnostics.SourceHealth
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold

@AndroidEntryPoint
class DiagnosticsFragment : BaseComposeSettingsFragment(R.string.diagnostics) {

	private val viewModel by viewModels<DiagnosticsViewModel>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				DiagnosticsScreen(viewModel = viewModel, onShare = ::shareReport, onClear = ::clear)
			}
		}
	}

	override fun onResume() {
		super.onResume()
		viewModel.refresh()
	}

	private fun shareReport() {
		val state = viewModel.state.value
		val report = DiagnosticsReport.build(
			entries = state.sources,
			issues = state.issues,
			appVersion = BuildConfig.VERSION_NAME,
			formatTime = { DateUtils.getRelativeTimeSpanString(it).toString() },
		)
		val send = Intent(Intent.ACTION_SEND).apply {
			type = "text/plain"
			putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics))
			putExtra(Intent.EXTRA_TEXT, report)
		}
		startActivity(Intent.createChooser(send, getString(R.string.diagnostics_share)))
	}

	private fun clear() {
		viewModel.clear()
		Toast.makeText(requireContext(), R.string.diagnostics_cleared, Toast.LENGTH_SHORT).show()
	}
}

@Composable
private fun DiagnosticsScreen(viewModel: DiagnosticsViewModel, onShare: () -> Unit, onClear: () -> Unit) {
	val state by viewModel.state.collectAsState()
	val seen = state.sources

	SettingsScaffold {
		item {
			Text(
				text = stringResource(R.string.diagnostics_offline_note),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.fillMaxWidth(),
			)
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		if (state.issues.isNotEmpty()) {
			item {
				SettingsGroup(title = stringResource(R.string.diagnostics_plugin_errors)) {
					state.issues.forEach { issue ->
						item { pos ->
							ActionSettingsItem(
								title = issue.name,
								// Already plain text from the runtime; redacted in case a path or URL slipped in
								subtitle = runtimeLabel(issue.runtime) + " · " + DiagnosticsRedactor.redact(issue.message).take(160),
								icon = R.drawable.ic_alert_outline,
								shape = pos.shape,
								onClick = {},
							)
						}
					}
				}
			}
			item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		}
		item {
			SettingsGroup(title = stringResource(R.string.diagnostics_sources)) {
				if (seen.isEmpty()) {
					item { pos ->
						ActionSettingsItem(
							title = stringResource(R.string.diagnostics_empty),
							icon = R.drawable.ic_info_outline,
							shape = pos.shape,
							onClick = {},
						)
					}
				}
				seen.forEach { health ->
					item { pos ->
						ActionSettingsItem(
							title = health.title,
							subtitle = health.subtitle(),
							icon = health.state.icon(),
							shape = pos.shape,
							onClick = {},
						)
					}
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.diagnostics_share),
						subtitle = stringResource(R.string.diagnostics_share_summary),
						icon = R.drawable.ic_share,
						shape = pos.shape,
						onClick = onShare,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.diagnostics_clear),
						icon = R.drawable.ic_delete,
						shape = pos.shape,
						onClick = onClear,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}

private fun runtimeLabel(runtime: PluginRuntime) = when (runtime) {
	PluginRuntime.CS3 -> "Cloudstream (.cs3)"
	PluginRuntime.ANIYOMI -> "Aniyomi"
	PluginRuntime.MIHON -> "Mihon"
	PluginRuntime.LNREADER -> "LNReader"
	PluginRuntime.BUILT_IN -> "Built-in"
}

private fun HealthState.icon() = when (this) {
	HealthState.HEALTHY -> R.drawable.ic_check
	HealthState.DEGRADED -> R.drawable.ic_alert_outline
	HealthState.SUSPECTED_DEAD -> R.drawable.ic_sync_problem
	HealthState.UNKNOWN -> R.drawable.ic_info_outline
}

private fun HealthState.label() = when (this) {
	HealthState.HEALTHY -> R.string.diagnostics_state_healthy
	HealthState.DEGRADED -> R.string.diagnostics_state_degraded
	HealthState.SUSPECTED_DEAD -> R.string.diagnostics_state_dead
	HealthState.UNKNOWN -> R.string.diagnostics_state_unknown
}

private fun FailureKind.label() = when (this) {
	FailureKind.PLUGIN_LOAD -> R.string.diagnostics_kind_plugin
	FailureKind.CLOUDFLARE_AUTH -> R.string.diagnostics_kind_cloudflare
	FailureKind.TIMEOUT -> R.string.diagnostics_kind_timeout
	FailureKind.ENDPOINT -> R.string.diagnostics_kind_endpoint
	FailureKind.OTHER -> R.string.diagnostics_kind_other
}

@Composable
private fun SourceHealth.subtitle(): String {
	val parts = ArrayList<String>(5)
	parts += runtimeLabel(runtime)
	parts += stringResource(state.label())
	if (state != HealthState.HEALTHY) {
		lastKind?.let { parts += stringResource(it.label()) }
	}
	if (endpointFailures > 1) {
		parts += stringResource(R.string.diagnostics_endpoint_streak, endpointFailures)
	}
	if (lastSuccessAt > 0L) {
		parts += stringResource(R.string.diagnostics_last_ok, DateUtils.getRelativeTimeSpanString(lastSuccessAt).toString())
	}
	return parts.joinToString(" · ")
}
