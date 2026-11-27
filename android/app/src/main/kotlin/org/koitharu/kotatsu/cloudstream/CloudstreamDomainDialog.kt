package org.koitharu.kotatsu.cloudstream

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.koitharu.kotatsu.R

/**
 * Settings of an installed Cloudstream plugin: one address field per provider, for when a site's default
 * domain is blocked or has moved. An empty field means "use the address the plugin ships with".
 */
fun showCloudstreamDomainDialog(
	context: Context,
	runtime: CloudstreamRuntime,
	internalName: String,
	title: String,
	onSaved: () -> Unit = {},
) {
	val domains = runtime.providerDomains(internalName)
	if (domains.isEmpty()) {
		Toast.makeText(context, R.string.cloudstream_domain_none, Toast.LENGTH_LONG).show()
		return
	}
	val density = context.resources.displayMetrics.density
	val form = LinearLayout(context).apply {
		orientation = LinearLayout.VERTICAL
		setPadding((20 * density).toInt(), (8 * density).toInt(), (20 * density).toInt(), 0)
	}
	form.addView(
		TextView(context).apply {
			setText(R.string.cloudstream_domain_summary)
			setPadding(0, 0, 0, (12 * density).toInt())
		},
	)
	val layouts = LinkedHashMap<String, TextInputLayout>()
	val edits = LinkedHashMap<String, TextInputEditText>()
	for (domain in domains) {
		val layout = TextInputLayout(context).apply {
			hint = domain.provider
			placeholderText = domain.default
		}
		val edit = TextInputEditText(layout.context).apply {
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
			setSingleLine()
			setText(domain.custom.orEmpty())
		}
		layout.addView(
			edit,
			ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
		)
		form.addView(
			layout,
			LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
		)
		layouts[domain.provider] = layout
		edits[domain.provider] = edit
	}
	val dialog = MaterialAlertDialogBuilder(context)
		.setTitle(context.getString(R.string.cloudstream_domain_title, title))
		.setView(ScrollView(context).apply { addView(form) })
		.setNegativeButton(android.R.string.cancel, null)
		.setNeutralButton(R.string.reset, null)
		.setPositiveButton(R.string.save, null)
		.create()
	dialog.setOnShowListener {
		// Wired here rather than in the builder so a bad address keeps the dialog open.
		dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
			edits.values.forEach { it.setText("") }
		}
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
			val entered = edits.mapValues { (_, edit) -> edit.text?.toString().orEmpty().trim() }
			var valid = true
			for ((provider, value) in entered) {
				val ok = value.isEmpty() || value.startsWith("https://") || value.startsWith("http://")
				layouts.getValue(provider).error = if (ok) null else context.getString(R.string.cloudstream_domain_invalid)
				if (!ok) valid = false
			}
			if (!valid) return@setOnClickListener
			for ((provider, value) in entered) {
				runtime.setProviderDomain(internalName, provider, value)
			}
			Toast.makeText(context, R.string.cloudstream_domain_saved, Toast.LENGTH_SHORT).show()
			onSaved()
			dialog.dismiss()
		}
	}
	dialog.show()
}
