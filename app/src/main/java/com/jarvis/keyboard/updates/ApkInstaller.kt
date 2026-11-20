
package com.jarvis.keyboard.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object ApkInstaller {

    /**
     * Returns true if the installer screen was opened.
     * Returns false if the user must first allow installation
     * from this app.
     */
    fun install(
        context: Context,
        apkUri: Uri
    ): Boolean {

        // Android 8.0 and above require per-app
        // permission for installing unknown apps.
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {

            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(settingsIntent)

            return false
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {

            setDataAndType(
                apkUri,
                "application/vnd.android.package-archive"
            )

            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(installIntent)

        return true
    }
}
