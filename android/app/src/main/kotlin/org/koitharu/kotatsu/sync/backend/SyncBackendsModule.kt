package org.koitharu.kotatsu.sync.backend

import dagger.Binds
import dagger.MapKey
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import org.koitharu.kotatsu.sync.backend.drive.GoogleDriveBackend
import org.koitharu.kotatsu.sync.backend.dropbox.DropboxBackend
import org.koitharu.kotatsu.sync.backend.github.GithubBackend
import org.koitharu.kotatsu.sync.backend.onedrive.OneDriveBackend
import org.koitharu.kotatsu.sync.backend.webdav.WebDavBackend

@MapKey
@Retention(AnnotationRetention.RUNTIME)
annotation class SyncProviderKey(val value: SyncProvider)

/** The one place a new provider is registered. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncBackendsModule {

	@Binds
	@IntoMap
	@SyncProviderKey(SyncProvider.DRIVE)
	abstract fun drive(impl: GoogleDriveBackend): SyncBackend

	@Binds
	@IntoMap
	@SyncProviderKey(SyncProvider.GITHUB)
	abstract fun github(impl: GithubBackend): SyncBackend

	@Binds
	@IntoMap
	@SyncProviderKey(SyncProvider.DROPBOX)
	abstract fun dropbox(impl: DropboxBackend): SyncBackend

	@Binds
	@IntoMap
	@SyncProviderKey(SyncProvider.ONEDRIVE)
	abstract fun oneDrive(impl: OneDriveBackend): SyncBackend

	@Binds
	@IntoMap
	@SyncProviderKey(SyncProvider.WEBDAV)
	abstract fun webDav(impl: WebDavBackend): SyncBackend
}
