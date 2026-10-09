package com.merchseller

import android.content.Context
import android.os.Build
import com.merchseller.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Данные стабильной версии, опубликованной в публичных GitHub Releases. */
data class PublicRelease(
    val tag: String,
    val version: String,
    val notes: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String?
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: PublicRelease) : UpdateState
    data class Downloading(val release: PublicRelease, val progress: Float) : UpdateState
    data class ReadyToInstall(val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Проверка и загрузка обновлений из публичных релизов этого репозитория.
 * APK дополнительно сверяется с SHA-256, который GitHub возвращает для каждого asset.
 */
object GitHubUpdateManager {
    private const val MAX_APK_SIZE = 250L * 1024 * 1024

    suspend fun checkForUpdate(): PublicRelease? = withContext(Dispatchers.IO) {
        val endpoint = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPOSITORY}/releases/latest"
        val json = requestJson(endpoint)
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@withContext null

        val tag = json.getString("tag_name")
        val latestVersion = SemanticVersion.parse(tag) ?: return@withContext null
        val currentVersion = SemanticVersion.parse(BuildConfig.VERSION_NAME) ?: return@withContext null
        if (latestVersion <= currentVersion) return@withContext null

        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: return@withContext null
        val downloadUrl = URL(apk.getString("browser_download_url"))
        require(downloadUrl.protocol == "https" && downloadUrl.host.equals("github.com", ignoreCase = true)) {
            "Ссылка на APK ведёт за пределы github.com"
        }
        val size = apk.optLong("size", 0L)
        require(size in 1..MAX_APK_SIZE) { "Размер APK в релизе некорректен" }

        PublicRelease(
            tag = tag,
            version = latestVersion.toString(),
            notes = json.optString("body").trim(),
            assetName = apk.getString("name"),
            downloadUrl = downloadUrl.toString(),
            sizeBytes = size,
            sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
        )
    }

    suspend fun download(context: Context, release: PublicRelease, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val connection = URL(release.downloadUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        try {
            connection.setRequestProperty("User-Agent", "MerchSeller/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT})")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "GitHub вернул HTTP ${connection.responseCode}" }

        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val safeName = release.assetName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val apkFile = File(directory, safeName)
            val tempFile = File(directory, "$safeName.part")
            val sha256 = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                tempFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_APK_SIZE && total <= release.sizeBytes) { "Размер скачанного файла больше ожидаемого" }
                        sha256.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        progress((total.toFloat() / release.sizeBytes).coerceIn(0f, 1f))
                    }
                    require(total == release.sizeBytes) { "Скачанный APK неполный" }
                }
            }
            if (release.sha256 != null) {
                val actual = sha256.digest().joinToString("") { "%02x".format(it) }
                require(actual.equals(release.sha256, ignoreCase = true)) { "Контрольная сумма APK не совпала" }
            }
            check(tempFile.renameTo(apkFile)) { "Не удалось сохранить APK обновления" }
            apkFile
        } finally {
            connection.disconnect()
        }
    }

    private fun requestJson(endpoint: String): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
        try {
            connection.setRequestProperty("User-Agent", "MerchSeller/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "GitHub вернул HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }

    private data class SemanticVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<SemanticVersion> {
        override fun compareTo(other: SemanticVersion): Int =
            compareValuesBy(this, other, SemanticVersion::major, SemanticVersion::minor, SemanticVersion::patch)

        override fun toString() = "$major.$minor.$patch"

        companion object {
            private val pattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")
            fun parse(value: String): SemanticVersion? = pattern.matchEntire(value.trim())?.let { match ->
                SemanticVersion(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
            }
        }
    }
}
