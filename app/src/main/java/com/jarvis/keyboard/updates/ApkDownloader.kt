
package com.jarvis.keyboard.updates

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object ApkDownloader {

    suspend fun download(
        context: Context,
        updateInfo: UpdateInfo
    ): Uri = withContext(Dispatchers.IO) {

        val downloadManager = context.getSystemService(
            Context.DOWNLOAD_SERVICE
        ) as DownloadManager

        val fileName = "jarvis-keyboard-${updateInfo.versionName}.apk"

        val request = DownloadManager.Request(
            Uri.parse(updateInfo.apkUrl)
        ).apply {

            setTitle("JarvisKeyboard Update")

            setDescription(
                "Downloading version ${updateInfo.versionName}"
            )

            setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )

            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)

            setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )
        }

        val downloadId = downloadManager.enqueue(request)

        val query = DownloadManager.Query()
            .setFilterById(downloadId)

        val timeout = 30 * 60 * 1000L
        val startTime = System.currentTimeMillis()

        while (true) {

            if (System.currentTimeMillis() - startTime > timeout) {
                downloadManager.remove(downloadId)
                throw Exception("APK download timed out")
            }

            val cursor = downloadManager.query(query)

            if (cursor == null) {
                throw Exception("Unable to query download")
            }

            try {
                if (!cursor.moveToFirst()) {
                    throw Exception("Download not found")
                }

                val status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_STATUS
                    )
                )

                when (status) {

                    DownloadManager.STATUS_SUCCESSFUL -> {
                        return@withContext (
                            downloadManager.getUriForDownloadedFile(downloadId)
                                ?: throw Exception("Downloaded APK URI unavailable")
                        )
                    }

                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(
                            cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_REASON
                            )
                        )

                        throw Exception("APK download failed: $reason")
                    }
                }

            } finally {
                cursor.close()
            }

            delay(1000)
        }

        @Suppress("UNREACHABLE_CODE")
        throw Exception("Unexpected download state")
    }
}
