
package com.jarvis.keyboard.updates

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releaseUrl: String,
    val releaseNotes: String
) {
    fun isUpdateAvailable(currentVersionCode: Int): Boolean {
        return versionCode > currentVersionCode
    }
}
