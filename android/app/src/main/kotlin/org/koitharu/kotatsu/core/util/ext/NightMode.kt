package org.koitharu.kotatsu.core.util.ext

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Applies an [AppCompatDelegate] night mode. Yugen draws its own cold-start splash, so unlike
 * DropSauce there is no need to route this through the system's UiModeManager.
 */
@Suppress("UnusedReceiverParameter")
fun Context.applyNightMode(mode: Int) {
	AppCompatDelegate.setDefaultNightMode(mode)
}
