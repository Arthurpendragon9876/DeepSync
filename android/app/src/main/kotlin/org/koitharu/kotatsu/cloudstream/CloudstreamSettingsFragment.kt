package org.koitharu.kotatsu.cloudstream

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.anime.AnimeExtensionManager
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.PlainInfoSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import javax.inject.Inject

/** Manage Cloudstream (.cs3) plugins: repositories, installing, updating and removing. */
@AndroidEntryPoint
class CloudstreamSettingsFragment : BaseComposeSettingsFragment(R.string.cloudstream_plugins) {

	@Inject
	lateinit var runtime: CloudstreamRuntime

	@Inject
	lateinit var animeExtensions: AnimeExtensionManager

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				CloudstreamScreen(runtime, animeExtensions)
			}
		}
	}
}

private sealed interface Dialog {
	data object AddRepo : Dialog
	data object InstallUrl : Dialog
	data class RemovePlugin(val plugin: CloudstreamInstalledPlugin) : Dialog
	data class RemoveRepo(val url: String) : Dialog
}

@Composable
private fun CloudstreamScreen(runtime: CloudstreamRuntime, animeExtensions: AnimeExtensionManager) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val installed by runtime.installed.collectAsState()
	val repos by runtime.repos.collectAsState()
	var available by remember { mutableStateOf<List<CloudstreamRepoPlugin>>(emptyList()) }
	var loadingRepos by remember { mutableStateOf(false) }
	var busy by remember { mutableStateOf<String?>(null) }
	var dialog by remember { mutableStateOf<Dialog?>(null) }

	fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

	/** Runs [block], then reloads the anime sources so the change shows up; failures are shown, not thrown. */
	fun act(label: String?, block: suspend () -> Unit) {
		scope.launch {
			busy = label
			try {
				block()
				animeExtensions.reload()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				toast(context.getString(R.string.cloudstream_failed_toast, e.message ?: e.javaClass.simpleName))
				runCatching { animeExtensions.reload() }
			} finally {
				busy = null
			}
		}
	}

	fun install(name: String, block: suspend () -> Unit) {
		act(context.getString(R.string.cloudstream_installing, name)) {
			block()
			toast(context.getString(R.string.cloudstream_installed_toast, name))
		}
	}

	val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
		if (uri == null) return@rememberLauncherForActivityResult
		val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
			val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
			if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
		} ?: uri.lastPathSegment ?: "plugin.cs3"
		install(name) {
			val stream = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open $uri" }
			stream.use { runtime.installFromStream(it, name) }
		}
	}

	// A plugin that wanted an Activity while loading at start-up gets one here: this screen is in front.
	LaunchedEffect(Unit) { runCatching { animeExtensions.reload() } }
	LaunchedEffect(repos) {
		if (repos.isEmpty()) {
			available = emptyList()
			return@LaunchedEffect
		}
		loadingRepos = true
		val (plugins, failures) = runtime.fetchAvailable()
		available = plugins
		loadingRepos = false
		failures.forEach { (repo, message) ->
			toast(context.getString(R.string.cloudstream_repo_failed, repo, message))
		}
	}

	val pending = available.mapNotNull { plugin ->
		val have = installed.firstOrNull { it.internalName == plugin.internalName }
		when {
			have == null -> plugin to false
			plugin.version > have.version -> plugin to true
			else -> null
		}
	}.sortedWith(compareByDescending<Pair<CloudstreamRepoPlugin, Boolean>> { it.second }.thenBy { it.first.name.lowercase() })

	SettingsScaffold {
		item {
			SettingsGroup(title = stringResource(R.string.cloudstream_installed)) {
				if (installed.isEmpty()) {
					item {
						PlainInfoSettingsItem(
							text = stringResource(R.string.cloudstream_none_installed),
							icon = R.drawable.ic_extension_settings,
						)
					}
				}
				installed.forEach { plugin ->
					item { pos ->
						ActionSettingsItem(
							title = if (plugin.version > 0) "${plugin.displayName}  (v${plugin.version})" else plugin.displayName,
							subtitle = plugin.error ?: plugin.providers.joinToString(", "),
							icon = if (plugin.error == null) R.drawable.ic_extension_settings else R.drawable.ic_alert_outline,
							shape = pos.shape,
							onClick = { dialog = Dialog.RemovePlugin(plugin) },
						)
					}
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.cloudstream_add_sources)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.cloudstream_add_repository),
						subtitle = stringResource(R.string.cloudstream_add_repository_summary),
						icon = R.drawable.ic_storefront,
						shape = pos.shape,
						onClick = { dialog = Dialog.AddRepo },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.cloudstream_install_url),
						subtitle = stringResource(R.string.cloudstream_install_url_summary),
						icon = R.drawable.ic_download,
						shape = pos.shape,
						onClick = { dialog = Dialog.InstallUrl },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.cloudstream_install_file),
						subtitle = stringResource(R.string.cloudstream_install_file_summary),
						icon = R.drawable.ic_file_zip,
						shape = pos.shape,
						onClick = { filePicker.launch(arrayOf("*/*")) },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.cloudstream_reload),
						subtitle = busy ?: stringResource(R.string.cloudstream_reload_summary),
						icon = R.drawable.ic_extension_update,
						shape = pos.shape,
						enabled = busy == null,
						onClick = { act(null) { } },
					)
				}
			}
		}
		if (repos.isNotEmpty()) {
			item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
			item {
				SettingsGroup(title = stringResource(R.string.cloudstream_repositories)) {
					repos.forEach { repo ->
						item { pos ->
							ActionSettingsItem(
								title = repo,
								icon = R.drawable.ic_storefront,
								shape = pos.shape,
								onClick = { dialog = Dialog.RemoveRepo(repo) },
							)
						}
					}
				}
			}
			item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
			item {
				SettingsGroup(title = stringResource(R.string.cloudstream_available)) {
					if (loadingRepos && pending.isEmpty()) {
						item {
							PlainInfoSettingsItem(
								text = stringResource(R.string.loading_),
								icon = R.drawable.ic_download,
							)
						}
					} else if (pending.isEmpty()) {
						item {
							PlainInfoSettingsItem(
								text = stringResource(R.string.cloudstream_no_available),
								icon = R.drawable.ic_download,
							)
						}
					}
					pending.forEach { (plugin, isUpdate) ->
						item { pos ->
							val details = listOfNotNull(
								if (isUpdate) stringResource(R.string.cloudstream_update_to, plugin.version) else null,
								plugin.language,
								plugin.types.takeIf { it.isNotEmpty() }?.joinToString(", "),
								if (plugin.status == 0) stringResource(R.string.cloudstream_repo_down) else null,
								plugin.description,
							).joinToString(" · ")
							ActionSettingsItem(
								title = plugin.name,
								subtitle = details.ifBlank { null },
								icon = if (isUpdate) R.drawable.ic_extension_update else R.drawable.ic_download,
								shape = pos.shape,
								enabled = busy == null,
								onClick = { install(plugin.name) { runtime.installFromUrl(plugin.url, plugin.internalName) } },
							)
						}
					}
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
	}

	when (val current = dialog) {
		null -> Unit
		Dialog.AddRepo -> TextInputDialog(
			title = stringResource(R.string.cloudstream_add_repository),
			onDismiss = { dialog = null },
			onConfirm = { text ->
				dialog = null
				runCatching { runtime.addRepo(text) }.onFailure { toast(it.message ?: "Invalid URL") }
			},
		)
		Dialog.InstallUrl -> TextInputDialog(
			title = stringResource(R.string.cloudstream_install_url),
			onDismiss = { dialog = null },
			onConfirm = { text ->
				dialog = null
				val url = text.trim()
				install(url.substringBefore('?').substringAfterLast('/').ifBlank { "plugin" }) {
					runtime.installFromUrl(url, null)
				}
			},
		)
		is Dialog.RemovePlugin -> {
			val error = current.plugin.error
			AlertDialog(
			onDismissRequest = { dialog = null },
			title = { Text(stringResource(R.string.cloudstream_remove_plugin_title, current.plugin.displayName)) },
			text = if (error != null) {
				{ Text(error) }
			} else {
				null
			},
			confirmButton = {
				TextButton(
					onClick = {
						dialog = null
						act(null) { runtime.uninstall(current.plugin.internalName) }
					},
				) { Text(stringResource(R.string.remove)) }
			},
			dismissButton = {
				TextButton(onClick = { dialog = null }) { Text(stringResource(android.R.string.cancel)) }
			},
			)
		}
		is Dialog.RemoveRepo -> AlertDialog(
			onDismissRequest = { dialog = null },
			title = { Text(stringResource(R.string.cloudstream_remove_repo_title)) },
			text = { Text(current.url) },
			confirmButton = {
				TextButton(
					onClick = {
						dialog = null
						runtime.removeRepo(current.url)
					},
				) { Text(stringResource(R.string.remove)) }
			},
			dismissButton = {
				TextButton(onClick = { dialog = null }) { Text(stringResource(android.R.string.cancel)) }
			},
		)
	}
}

@Composable
private fun TextInputDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
	var text by remember { mutableStateOf("") }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			OutlinedTextField(
				value = text,
				onValueChange = { text = it },
				singleLine = true,
				placeholder = { Text(stringResource(R.string.cloudstream_url_hint)) },
				modifier = Modifier.fillMaxWidth(),
			)
		},
		confirmButton = {
			TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.add)) }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}
