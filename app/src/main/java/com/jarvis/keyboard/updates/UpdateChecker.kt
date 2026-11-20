
package com.jarvis.keyboard.updates

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    // CHANGE THESE TO YOUR GITHUB DETAILS
    private const val GITHUB_OWNER = "AARYA77-dev"
    private const val GITHUB_REPO = "Jarvis-keyboard-app"

    private const val RELEASE_API =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private fun getInstalledVersionCode(context: Context): Long {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName, 0
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }

    private fun fetchJson(url: String): JSONObject {
        val connection = URL(url).openConnection()
                as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            connection.setRequestProperty(
                "Accept", "application/vnd.github+json"
            )

            connection.setRequestProperty(
                "User-Agent", "JarvisKeyboard-Android"
            )

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception(
                    "GitHub API error: ${connection.responseCode}"
                )
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            return JSONObject(response)

        } finally {
            connection.disconnect()
        }
    }

    suspend fun checkForUpdate(
        context: Context
    ): UpdateInfo? = withContext(Dispatchers.IO) {

        try {
            val release = fetchJson(RELEASE_API)

            val assets = release.getJSONArray("assets")

            var metadataUrl: String? = null

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)

                if (asset.getString("name") == "version.json") {
                    metadataUrl = asset.getString("browser_download_url")
                    break
                }
            }

            val versionJsonUrl = metadataUrl
                ?: throw Exception("version.json not found in release")

            val metadata = fetchJson(versionJsonUrl)

            val latestVersionCode =
                metadata.getLong("versionCode")

            val latestVersionName =
                metadata.getString("versionName")

            val apkFileName =
                metadata.getString("apkFile")

            val currentVersionCode =
                getInstalledVersionCode(context)

            if (latestVersionCode <= currentVersionCode) {
                return@withContext null
            }

            var apkUrl: String? = null

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)

                if (asset.getString("name") == apkFileName) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }

            val downloadUrl = apkUrl
                ?: throw Exception("APK asset not found")

            UpdateInfo(
                versionCode = latestVersionCode.toInt(),
                versionName = latestVersionName,
                apkUrl = downloadUrl,
                releaseUrl = release.getString("html_url"),
                releaseNotes = release.optString(
                    "body", "No release notes available."
                )
            )

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
