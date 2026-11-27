package org.koitharu.kotatsu.settings.webhook

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import org.koitharu.kotatsu.webhook.WebhookDispatcher
import org.koitharu.kotatsu.webhook.WebhookPayloads
import javax.inject.Inject

@AndroidEntryPoint
class WebhookSettingsFragment : BaseComposeSettingsFragment(R.string.webhooks) {

	@Inject
	lateinit var dispatcher: WebhookDispatcher

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				WebhookScreen(onSendTest = ::sendTest)
			}
		}
	}

	private fun sendTest() {
		viewLifecycleOwner.lifecycleScope.launch {
			val message = when (val result = dispatcher.sendTest()) {
				WebhookDispatcher.TestResult.Sent -> getString(R.string.webhook_test_sent)
				WebhookDispatcher.TestResult.NoTarget -> getString(R.string.webhook_test_no_url)
				is WebhookDispatcher.TestResult.Failed -> getString(R.string.webhook_test_failed, result.detail)
			}
			Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
		}
	}
}

@Composable
private fun WebhookScreen(onSendTest: () -> Unit) {
	var enabled by rememberBooleanPref(AppSettings.KEY_WEBHOOKS_ENABLED, false)
	var discordUrl by rememberStringPref(AppSettings.KEY_WEBHOOK_DISCORD_URL, "")
	var jsonUrl by rememberStringPref(AppSettings.KEY_WEBHOOK_JSON_URL, "")
	var libraryEvents by rememberBooleanPref(AppSettings.KEY_WEBHOOK_EVENT_LIBRARY, true)
	var syncEvents by rememberBooleanPref(AppSettings.KEY_WEBHOOK_EVENT_SYNC, true)
	var backupEvents by rememberBooleanPref(AppSettings.KEY_WEBHOOK_EVENT_BACKUP, true)

	var editDiscord by remember { mutableStateOf(false) }
	var editJson by remember { mutableStateOf(false) }

	// The URL itself is a secret (a Discord webhook URL contains its token), so it is never shown in the list.
	val configured = stringResource(R.string.webhook_configured)
	val notSet = stringResource(R.string.webhook_not_set)

	SettingsScaffold {
		item {
			SettingsGroup {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.webhooks),
						subtitle = stringResource(R.string.webhooks_summary),
						checked = enabled,
						onCheckedChange = { enabled = it },
						icon = R.drawable.ic_web,
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.webhook_destinations)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.webhook_discord_url),
						subtitle = if (discordUrl.isBlank()) notSet else configured,
						icon = R.drawable.ic_discord,
						shape = pos.shape,
						enabled = enabled,
						onClick = { editDiscord = true },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.webhook_json_url),
						subtitle = if (jsonUrl.isBlank()) notSet else configured,
						icon = R.drawable.ic_website,
						shape = pos.shape,
						enabled = enabled,
						onClick = { editJson = true },
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.webhook_events)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.webhook_event_library),
						checked = libraryEvents,
						onCheckedChange = { libraryEvents = it },
						shape = pos.shape,
						enabled = enabled,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.webhook_event_sync),
						checked = syncEvents,
						onCheckedChange = { syncEvents = it },
						shape = pos.shape,
						enabled = enabled,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.webhook_event_backup),
						checked = backupEvents,
						onCheckedChange = { backupEvents = it },
						shape = pos.shape,
						enabled = enabled,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.webhook_send_test),
						subtitle = stringResource(R.string.webhook_send_test_summary),
						icon = R.drawable.ic_notification,
						shape = pos.shape,
						onClick = onSendTest,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}

	if (editDiscord) {
		WebhookUrlDialog(
			title = stringResource(R.string.webhook_discord_url),
			initial = discordUrl,
			requireHttps = true,
			onConfirm = { discordUrl = it },
			onDismiss = { editDiscord = false },
		)
	}
	if (editJson) {
		WebhookUrlDialog(
			title = stringResource(R.string.webhook_json_url),
			initial = jsonUrl,
			requireHttps = false,
			onConfirm = { jsonUrl = it },
			onDismiss = { editJson = false },
		)
	}
}

@Composable
private fun WebhookUrlDialog(
	title: String,
	initial: String,
	requireHttps: Boolean,
	onConfirm: (String) -> Unit,
	onDismiss: () -> Unit,
) {
	var value by remember { mutableStateOf(initial) }
	val trimmed = value.trim()
	val valid = trimmed.isEmpty() || WebhookPayloads.isValidUrl(trimmed, requireHttps)
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column {
				Text(stringResource(R.string.webhook_url_hint), style = MaterialTheme.typography.bodyMedium)
				Spacer(Modifier.height(12.dp))
				OutlinedTextField(
					value = value,
					onValueChange = { value = it },
					singleLine = true,
					isError = !valid,
					supportingText = if (valid) null else ({ Text(stringResource(R.string.webhook_url_invalid)) }),
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
					modifier = Modifier.fillMaxWidth(),
				)
			}
		},
		confirmButton = {
			TextButton(
				enabled = valid,
				onClick = {
					onConfirm(trimmed)
					onDismiss()
				},
			) { Text(stringResource(android.R.string.ok)) }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}
