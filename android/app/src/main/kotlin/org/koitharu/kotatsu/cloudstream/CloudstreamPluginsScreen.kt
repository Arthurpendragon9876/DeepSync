package org.koitharu.kotatsu.cloudstream

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.anime.AnimeExtensionManager
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.PlainInfoSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold

/**
 * The Cloudstream (.cs3) plugin page: installed plugins, repositories, installing and removing. It lives in the
 * "Cloudstream" tab of Extension stores; [refreshKey] changes whenever the toolbar's refresh button is pressed there.
 */
private sealed interface Dialog {
	data object AddRepo : Dialog
	data object InstallUrl : Dialog
	data class RemovePlugin(val plugin: CloudstreamInstalledPlugin) : Dialog
	data class RemoveRepo(val url: String) : Dialog
}

@Composable
internal fun CloudstreamPluginsScreen(
	runtime: CloudstreamRuntime,
	animeExtensions: AnimeExtensionManager,
	refreshKey: Int = 0,
) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val installed by runtime.installed.collectAsState()
	val tvMoviesPackages by animeExtensions.tvMovies.collectAsState()
	val repos by runtime.repos.collectAsState()
	var available by remember { mutableStateOf<List<CloudstreamRepoPlugin>>(emptyList()) }
	var loadingRepos by remember { mutableStateOf(false) }
	var busy by remember { mutableStateOf<String?>(null) }
	var dialog by remember { mutableStateOf<Dialog?>(null) }
	var query by remember { mutableStateOf("") }

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
				// The outermost message is often just a class name; the causes say what actually went wrong.
				val reason = generateSequence<Throwable>(e) { it.cause }.take(3)
					.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
					.take(300)
				android.util.Log.e("CloudstreamSettings", "Cloudstream action failed", e)
				toast(context.getString(R.string.cloudstream_failed_toast, reason))
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
	LaunchedEffect(refreshKey) { runCatching { animeExtensions.reload() } }
	LaunchedEffect(repos, refreshKey) {
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

	val needle = query.trim().lowercase()
	fun matches(vararg parts: String?) = needle.isEmpty() || parts.any { it?.lowercase()?.contains(needle) == true }
	val shownInstalled = installed.filter { matches(it.displayName, it.internalName, it.providers.joinToString(" ")) }
	val shownPending = pending.filter { (plugin, _) ->
		matches(plugin.name, plugin.internalName, plugin.description, plugin.language, plugin.types.joinToString(" "))
	}
	// Installed plugins have no icon of their own; the repository entry of the same plugin does.
	val iconByName = available.mapNotNull { plugin -> plugin.iconUrl?.let { plugin.internalName to it } }.toMap()

	SettingsScaffold {
		item {
			OutlinedTextField(
				value = query,
				onValueChange = { query = it },
				singleLine = true,
				placeholder = { Text(stringResource(R.string.cloudstream_search)) },
				leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
				trailingIcon = if (query.isNotEmpty()) {
					{
						IconButton(onClick = { query = "" }) {
							Icon(painterResource(R.drawable.ic_close), contentDescription = null)
						}
					}
				} else {
					null
				},
				shape = RoundedCornerShape(28.dp),
				modifier = Modifier.fillMaxWidth(),
			)
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
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
				if (installed.isNotEmpty() && shownInstalled.isEmpty()) {
					item {
						PlainInfoSettingsItem(
							text = stringResource(R.string.cloudstream_no_matches),
							icon = R.drawable.ic_search,
						)
					}
				}
				shownInstalled.forEach { plugin ->
					item { pos ->
						PluginRow(
							title = if (plugin.version > 0) "${plugin.displayName}  (v${plugin.version})" else plugin.displayName,
							subtitle = plugin.error ?: plugin.providers.joinToString(", "),
							iconUrl = iconByName[plugin.internalName] ?: runtime.savedIconUrl(plugin.internalName),
							fallbackIcon = if (plugin.error == null) R.drawable.ic_extension_settings else R.drawable.ic_alert_outline,
							shape = pos.shape,
							enabled = true,
							onClick = { dialog = Dialog.RemovePlugin(plugin) },
							trailing = if (plugin.error == null) {
								{
									// Cloudstream plugins are split into Anime and TV & Movies like Aniyomi extensions:
									// tagged automatically from the types they declare, and switchable here.
									val pkg = CloudstreamRuntime.PACKAGE_PREFIX + plugin.internalName
									val isTv = pkg in tvMoviesPackages
									val description = stringResource(if (isTv) R.string.ds_untag_tv_movies else R.string.ds_tag_tv_movies)
									Row(verticalAlignment = Alignment.CenterVertically) {
										IconButton(
											onClick = {
												showCloudstreamDomainDialog(context, runtime, plugin.internalName, plugin.displayName)
											},
										) {
											Icon(
												painter = painterResource(R.drawable.ic_extension_settings),
												contentDescription = stringResource(R.string.settings),
											)
										}
										IconButton(onClick = { animeExtensions.setTvMovies(pkg, !isTv) }) {
											Icon(
												painter = painterResource(if (isTv) R.drawable.ic_streaming else R.drawable.ic_tv),
												contentDescription = description,
											)
										}
									}
								}
							} else {
								null
							},
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
					} else if (pending.isEmpty() || shownPending.isEmpty()) {
						item {
							PlainInfoSettingsItem(
								text = stringResource(
									if (pending.isEmpty()) R.string.cloudstream_no_available else R.string.cloudstream_no_matches,
								),
								icon = if (pending.isEmpty()) R.drawable.ic_download else R.drawable.ic_search,
							)
						}
					}
					shownPending.forEach { (plugin, isUpdate) ->
						item { pos ->
							val details = listOfNotNull(
								if (isUpdate) stringResource(R.string.cloudstream_update_to, plugin.version) else null,
								plugin.language,
								plugin.types.takeIf { it.isNotEmpty() }?.joinToString(", "),
								if (plugin.status == 0) stringResource(R.string.cloudstream_repo_down) else null,
								plugin.description,
							).joinToString(" · ")
							PluginRow(
								title = plugin.name,
								subtitle = details.ifBlank { null },
								iconUrl = plugin.iconUrl,
								fallbackIcon = if (isUpdate) R.drawable.ic_extension_update else R.drawable.ic_download,
								shape = pos.shape,
								enabled = busy == null,
								onClick = { install(plugin.name) { runtime.installFromUrl(plugin.url, plugin.internalName, plugin.iconUrl) } },
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

/** A plugin row with the plugin's own icon (the repository's `iconUrl`), or a stock one when there is none. */
@Composable
private fun PluginRow(
	title: String,
	subtitle: String?,
	iconUrl: String?,
	@DrawableRes fallbackIcon: Int,
	shape: Shape,
	enabled: Boolean,
	onClick: () -> Unit,
	trailing: (@Composable () -> Unit)? = null,
) {
	Surface(
		onClick = onClick,
		enabled = enabled,
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		contentColor = MaterialTheme.colorScheme.onSurface,
		modifier = Modifier.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			PluginIcon(iconUrl, fallbackIcon)
			Spacer(Modifier.width(16.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleMedium,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				if (subtitle != null) {
					Text(
						text = subtitle,
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 3,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
			trailing?.invoke()
		}
	}
}

@Composable
private fun PluginIcon(url: String?, @DrawableRes fallback: Int) {
	var failed by remember(url) { mutableStateOf(false) }
	Box(
		modifier = Modifier
			.size(40.dp)
			.clip(RoundedCornerShape(10.dp))
			.background(MaterialTheme.colorScheme.surfaceContainerHighest),
		contentAlignment = Alignment.Center,
	) {
		if (url.isNullOrBlank() || failed) {
			Icon(
				painter = painterResource(fallback),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(24.dp),
			)
		} else {
			AsyncImage(
				model = url,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				onError = { failed = true },
				modifier = Modifier.fillMaxSize(),
			)
		}
	}
}
