package org.koitharu.kotatsu.anime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.anime.AnimeExtension
import org.koitharu.kotatsu.anime.AnimeRepoEntry
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import java.util.Locale

/**
 * "Manage extensions" for anime, laid out like the manga/novel manager: language and content
 * filters, an Installed tab plus one tab per repository, and rows with icon, "Language • version •
 * repo • 18+" and round action buttons (hide from Explore / remove / install / update).
 */
@AndroidEntryPoint
class AnimeExtensionsActivity : AppCompatActivity() {

	private val viewModel: AnimeExtensionsViewModel by viewModels()

	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		setContent {
			DropSauceTheme {
				val snackbar = remember { SnackbarHostState() }
				val message by viewModel.message.collectAsState()
				LaunchedEffect(message) {
					message?.let {
						snackbar.showSnackbar(it)
						viewModel.consumeMessage()
					}
				}
				Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
					ManageScreen(viewModel, onBack = { finish() }, modifier = Modifier.padding(padding))
				}
			}
		}
	}

	companion object {
		fun newIntent(context: Context) = Intent(context, AnimeExtensionsActivity::class.java)
	}
}

private fun languageName(code: String): String = when (code.lowercase()) {
	"", "all", "multi" -> "Multi"
	else -> Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code }
}

private fun AnimeExtension.languages(): Set<String> =
	sources.map { (it as? eu.kanade.tachiyomi.animesource.AnimeCatalogueSource)?.lang ?: "all" }.toSet()

@Composable
private fun ManageScreen(vm: AnimeExtensionsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
	val installed by vm.installed.collectAsState()
	val errors by vm.loadErrors.collectAsState()
	val hidden by vm.hidden.collectAsState()
	val entries by vm.entries.collectAsState()
	val busy by vm.busy.collectAsState()
	val loading by vm.loading.collectAsState()

	var tab by rememberSaveable { mutableIntStateOf(0) }
	var language by rememberSaveable { mutableStateOf<String?>(null) }
	var contentFilter by rememberSaveable { mutableIntStateOf(0) } // 0 all, 1 safe only, 2 18+ only
	var query by rememberSaveable { mutableStateOf("") }
	var searchOpen by rememberSaveable { mutableStateOf(false) }
	var showRepos by rememberSaveable { mutableStateOf(false) }

	val groups = remember(entries) { entries.groupBy { it.repoBase }.values.map { it.first().repoLabel to it } }
	if (tab > groups.size) tab = 0
	val entryByPkg = remember(entries) { entries.associateBy { it.pkg } }

	val allLanguages = remember(installed, entries) {
		(installed.flatMap { it.languages() } + entries.map { it.lang }).filter { it.isNotBlank() }.toSortedSet().toList()
	}

	fun matches(name: String, langs: Set<String>, nsfw: Boolean): Boolean {
		if (query.isNotBlank() && !name.contains(query, ignoreCase = true)) return false
		if (language != null && language !in langs) return false
		return when (contentFilter) {
			1 -> !nsfw
			2 -> nsfw
			else -> true
		}
	}

	Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
		Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
			RoundButton(R.drawable.ic_ap_back, "Back", circle = true, onClick = onBack)
			Spacer(Modifier.weight(1f))
			RoundButton(R.drawable.ic_ap_search, "Search") { searchOpen = !searchOpen; if (!searchOpen) query = "" }
			Spacer(Modifier.width(2.dp))
			RoundButton(R.drawable.ic_ap_edit, "Repositories and player") { showRepos = true }
		}
		Text(
			"Manage extensions",
			fontSize = 32.sp,
			fontWeight = FontWeight.Bold,
			modifier = Modifier.padding(horizontal = 24.dp),
		)
		if (searchOpen) {
			OutlinedTextField(
				value = query,
				onValueChange = { query = it },
				singleLine = true,
				placeholder = { Text("Search extensions") },
				modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
			)
		}
		Row(
			Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
			horizontalArrangement = Arrangement.spacedBy(10.dp),
		) {
			var langMenu by remember { mutableStateOf(false) }
			Box {
				FilterButton(R.drawable.ic_ap_language, language?.let(::languageName) ?: "All languages", expand = true) { langMenu = true }
				DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
					DropdownMenuItem(text = { Text("All languages") }, onClick = { language = null; langMenu = false })
					allLanguages.forEach { code ->
						DropdownMenuItem(text = { Text(languageName(code)) }, onClick = { language = code; langMenu = false })
					}
				}
			}
			FilterButton(
				R.drawable.ic_ap_adult,
				when (contentFilter) { 1 -> "Safe only"; 2 -> "18+ only"; else -> "Show all" },
			) { contentFilter = (contentFilter + 1) % 3 }
		}

		// tabs
		Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
			TabLabel("Installed", tab == 0) { tab = 0 }
			groups.forEachIndexed { i, (label, _) -> TabLabel(label, tab == i + 1) { tab = i + 1 } }
			if (loading) {
				CircularProgressIndicator(Modifier.padding(start = 16.dp, top = 12.dp).size(18.dp), strokeWidth = 2.dp)
			}
		}
		Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))

		LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
			if (tab == 0) {
				val rows = installed.filter { matches(it.name, it.languages(), it.nsfw) }
				val (hiddenRows, enabledRows) = rows.partition { it.pkg in hidden }
				if (rows.isEmpty()) {
					item { EmptyHint(if (installed.isEmpty()) "No anime extensions installed yet. Add a repository with the pencil button, then install from its tab." else "Nothing matches the current filters.") }
				}
				if (enabledRows.isNotEmpty()) item { SectionHeader("Enabled") }
				items(enabledRows, key = { "e_" + it.pkg }) { ext -> InstalledRow(vm, ext, entryByPkg[ext.pkg], hidden = false, busy = ext.pkg in busy) }
				if (hiddenRows.isNotEmpty()) item { SectionHeader("Hidden") }
				items(hiddenRows, key = { "h_" + it.pkg }) { ext -> InstalledRow(vm, ext, entryByPkg[ext.pkg], hidden = true, busy = ext.pkg in busy) }
				if (errors.isNotEmpty()) {
					item { SectionHeader("Failed to load") }
					items(errors.entries.toList(), key = { "x_" + it.key }) { (name, msg) ->
						Text(
							"$name\n$msg",
							color = MaterialTheme.colorScheme.error,
							fontSize = 13.sp,
							modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
						)
					}
				}
			} else {
				val list = groups.getOrNull(tab - 1)?.second.orEmpty()
					.filter { matches(it.name, setOf(it.lang), it.nsfw) }
				if (list.isEmpty()) item { EmptyHint("Nothing matches the current filters.") }
				items(list, key = { "r_" + it.repoBase + it.pkg }) { entry -> RepoRow(vm, entry, installed.firstOrNull { it.pkg == entry.pkg }, entry.pkg in busy) }
			}
		}
	}

	if (showRepos) RepoDialog(vm, onDismiss = { showRepos = false })
}

@Composable
private fun InstalledRow(vm: AnimeExtensionsViewModel, ext: AnimeExtension, entry: AnimeRepoEntry?, hidden: Boolean, busy: Boolean) {
	val langs = ext.languages()
	val lang = if (langs.size > 1) "Multi" else languageName(langs.firstOrNull() ?: "all")
	val subtitle = buildString {
		append(lang).append(" • ").append(ext.versionName)
		append(" • ").append(entry?.repoLabel ?: if (ext.privateApk != null) "Local" else "System")
		if (ext.nsfw) append(" • 18+")
	}
	val outdated = entry != null && entry.versionCode > ext.versionCode
	ExtRow(entry?.iconUrl, ext.name, subtitle, dim = hidden) {
		if (busy) {
			CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
		} else {
			if (outdated) SquareButton(R.drawable.ic_ap_download, "Update") { vm.install(entry!!) }
			SquareButton(if (hidden) R.drawable.ic_ap_eye_off else R.drawable.ic_ap_eye, if (hidden) "Show in Explore" else "Hide from Explore") {
				vm.setHidden(ext.pkg, !hidden)
			}
			if (ext.privateApk != null) SquareButton(R.drawable.ic_ap_close, "Remove") { vm.uninstall(ext) }
		}
	}
}

@Composable
private fun RepoRow(vm: AnimeExtensionsViewModel, entry: AnimeRepoEntry, have: AnimeExtension?, busy: Boolean) {
	val subtitle = buildString {
		append(languageName(entry.lang)).append(" • ").append(entry.versionName).append(" • ").append(entry.repoLabel)
		if (entry.nsfw) append(" • 18+")
	}
	val outdated = have != null && entry.versionCode > have.versionCode
	ExtRow(entry.iconUrl, entry.name, subtitle, dim = false) {
		when {
			busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
			have == null -> SquareButton(R.drawable.ic_ap_download, "Install") { vm.install(entry) }
			outdated -> SquareButton(R.drawable.ic_ap_download, "Update") { vm.install(entry) }
			have.privateApk != null -> SquareButton(R.drawable.ic_ap_close, "Remove") { vm.uninstall(have) }
		}
	}
}

@Composable
private fun ExtRow(iconUrl: String?, title: String, subtitle: String, dim: Boolean, actions: @Composable () -> Unit) {
	Row(
		Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Box(
			Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
			contentAlignment = Alignment.Center,
		) {
			Text(title.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
			if (iconUrl != null) {
				AsyncImage(
					model = iconUrl,
					contentDescription = null,
					contentScale = ContentScale.Crop,
					modifier = Modifier.size(46.dp),
				)
			}
		}
		Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
			Text(
				title,
				fontSize = 18.sp,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
			)
			Text(
				subtitle,
				fontSize = 14.sp,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
	}
}

@Composable
private fun RoundButton(icon: Int, description: String, circle: Boolean = false, onClick: () -> Unit) {
	Box(
		Modifier.size(width = if (circle) 52.dp else 52.dp, height = 52.dp)
			.clip(if (circle) CircleShape else RoundedCornerShape(18.dp))
			.background(MaterialTheme.colorScheme.surfaceContainerHigh)
			.clickable(onClick = onClick),
		contentAlignment = Alignment.Center,
	) { Icon(painterResource(icon), description, Modifier.size(24.dp)) }
}

@Composable
private fun SquareButton(icon: Int, description: String, onClick: () -> Unit) {
	Box(
		Modifier.size(width = 52.dp, height = 48.dp).clip(RoundedCornerShape(16.dp))
			.background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick),
		contentAlignment = Alignment.Center,
	) { Icon(painterResource(icon), description, Modifier.size(24.dp)) }
}

@Composable
private fun FilterButton(icon: Int, text: String, expand: Boolean = false, onClick: () -> Unit) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(14.dp),
		color = androidx.compose.ui.graphics.Color.Transparent,
		border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface),
	) {
		Row(
			Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Icon(painterResource(icon), null, Modifier.size(22.dp))
			Text(text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
			if (expand) Icon(painterResource(R.drawable.ic_ap_expand), null, Modifier.size(20.dp))
		}
	}
}

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
	Column(
		Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp).padding(top = 12.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Text(
			text,
			fontSize = 18.sp,
			fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
			color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Spacer(Modifier.height(8.dp))
		Box(
			Modifier.width(52.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
				.background(if (selected) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent),
		)
	}
}

@Composable
private fun SectionHeader(text: String) {
	Text(
		text,
		fontSize = 16.sp,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
	)
}

@Composable
private fun EmptyHint(text: String) {
	Text(
		text,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(24.dp),
	)
}

@Composable
private fun RepoDialog(vm: AnimeExtensionsViewModel, onDismiss: () -> Unit) {
	val repos by vm.repos.collectAsState()
	val useVlc by vm.useVlc.collectAsState()
	var input by remember { mutableStateOf("") }
	AlertDialog(
		onDismissRequest = onDismiss,
		confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
		title = { Text("Repositories & player") },
		text = {
			Column {
				if (repos.isEmpty()) {
					Text("No repositories yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
				repos.forEach { url ->
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(url, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
						TextButton(onClick = { vm.removeRepo(url) }) { Text("Remove") }
					}
				}
				OutlinedTextField(
					value = input,
					onValueChange = { input = it },
					singleLine = true,
					label = { Text("Repo URL (index.min.json or base)") },
					modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
				)
				Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Button(onClick = { vm.addRepo(input); input = "" }, enabled = input.isNotBlank()) { Text("Add") }
					TextButton(onClick = vm::refreshRepos) { Text("Refresh") }
					TextButton(onClick = vm::rescan) { Text("Rescan") }
				}
				Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
					Column(Modifier.weight(1f)) {
						Text("Open in VLC directly")
						Text(
							"Skips the built-in player. Falls back to it if VLC is missing.",
							fontSize = 12.sp,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					Switch(checked = useVlc, onCheckedChange = vm::setUseVlc)
				}
			}
		},
	)
}
