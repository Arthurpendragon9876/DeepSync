package org.koitharu.kotatsu.sync.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.sync.backend.SyncProvider
import org.koitharu.kotatsu.sync.backend.github.GithubDeviceChallenge

// Pieces of the sign-in UI shared by the sync settings screen and the onboarding screen.

internal fun SyncProvider.titleRes(): Int = when (this) {
	SyncProvider.DRIVE -> R.string.sync_sign_in
	SyncProvider.GITHUB -> R.string.sync_github_sign_in
	SyncProvider.DROPBOX -> R.string.sync_dropbox_sign_in
	SyncProvider.ONEDRIVE -> R.string.sync_onedrive_sign_in
	SyncProvider.WEBDAV -> R.string.sync_webdav_sign_in
}

internal fun SyncProvider.summaryRes(): Int = when (this) {
	SyncProvider.DRIVE -> R.string.sync_sign_in_summary
	SyncProvider.GITHUB -> R.string.sync_github_sign_in_summary
	SyncProvider.DROPBOX -> R.string.sync_dropbox_sign_in_summary
	SyncProvider.ONEDRIVE -> R.string.sync_onedrive_sign_in_summary
	SyncProvider.WEBDAV -> R.string.sync_webdav_sign_in_summary
}

/** Shows the 8-character code, copies it, and opens GitHub's verification page — then just waits. */
@Composable
internal fun GithubCodeDialog(challenge: GithubDeviceChallenge, onCancel: () -> Unit) {
	val context = LocalContext.current
	fun openVerification() {
		runCatching {
			context.startActivity(
				android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(challenge.verificationUri))
					.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
			)
		}
	}
	LaunchedEffect(challenge.userCode) {
		val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
		clipboard.setPrimaryClip(ClipData.newPlainText("GitHub code", challenge.userCode))
		openVerification()
	}
	AlertDialog(
		onDismissRequest = onCancel,
		title = { Text(stringResource(R.string.sync_github_sign_in)) },
		text = {
			Column {
				Text(stringResource(R.string.sync_github_code_hint))
				Spacer(Modifier.height(16.dp))
				Text(
					text = challenge.userCode,
					style = MaterialTheme.typography.headlineMedium,
					modifier = Modifier.fillMaxWidth(),
				)
				Spacer(Modifier.height(12.dp))
				Text(
					text = stringResource(R.string.sync_github_scope_note),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		confirmButton = { TextButton(onClick = { openVerification() }) { Text(stringResource(R.string.sync_github_open)) } },
		dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) } },
	)
}

/** Address, user and password of a WebDAV server; the password is kept encrypted once connected. */
@Composable
internal fun WebDavDialog(
	prefill: Pair<String?, String?>,
	onConnect: (address: String, user: String, password: String) -> Unit,
	onDismiss: () -> Unit,
) {
	var address by rememberSaveable { mutableStateOf(prefill.first.orEmpty()) }
	var user by rememberSaveable { mutableStateOf(prefill.second.orEmpty()) }
	var password by rememberSaveable { mutableStateOf("") }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.sync_webdav_sign_in)) },
		text = {
			Column {
				OutlinedTextField(
					value = address,
					onValueChange = { address = it },
					label = { Text(stringResource(R.string.sync_webdav_address)) },
					singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
					modifier = Modifier.fillMaxWidth(),
				)
				Spacer(Modifier.height(8.dp))
				OutlinedTextField(
					value = user,
					onValueChange = { user = it },
					label = { Text(stringResource(R.string.sync_webdav_user)) },
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
				)
				Spacer(Modifier.height(8.dp))
				OutlinedTextField(
					value = password,
					onValueChange = { password = it },
					label = { Text(stringResource(R.string.sync_webdav_password)) },
					singleLine = true,
					visualTransformation = PasswordVisualTransformation(),
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
					modifier = Modifier.fillMaxWidth(),
				)
				Spacer(Modifier.height(12.dp))
				Text(
					text = stringResource(R.string.sync_webdav_hint),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		},
		confirmButton = {
			TextButton(
				onClick = { onConnect(address, user, password) },
				enabled = address.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
			) { Text(stringResource(R.string.sync_webdav_connect)) }
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
	)
}
