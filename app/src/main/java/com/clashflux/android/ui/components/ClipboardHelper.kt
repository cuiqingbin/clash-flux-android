package com.clashflux.android.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

fun readClipboardText(context: Context): String {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return ""
    if (clip.itemCount <= 0) return ""
    return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
}

fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
    toastMessage: String? = null
) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    if (!toastMessage.isNullOrBlank()) {
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
    }
}
