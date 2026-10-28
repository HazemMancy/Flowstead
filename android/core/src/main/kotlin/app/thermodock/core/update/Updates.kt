/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The fields ThermoDock reads from GitHub's "latest release" answer. */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
data class GithubAsset(val name: String, @SerialName("browser_download_url") val downloadUrl: String)

sealed interface UpdateCheck {
    data class UpToDate(val latest: String) : UpdateCheck
    /**
     * [apkUrl] is the release's APK, null if the release has none (then [pageUrl] only); [sha256Url] its
     * published SHA-256, which the in-app installer checks before handing the file to Android.
     */
    data class Available(val version: String, val pageUrl: String, val apkUrl: String?, val sha256Url: String? = null) : UpdateCheck
}

/** Updates come from the project's GitHub releases (the app isn't on a store yet; decision D35). */
object Updates {
    const val REPOSITORY = "https://github.com/HazemMancy/ThermoDock"
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/HazemMancy/ThermoDock/releases/latest"
    const val RELEASES_PAGE = "$REPOSITORY/releases"

    /**
     * The repository's address before its rename (D66). GitHub forwards it once the repository is renamed; until then
     * it is the only one that answers, so the update check falls back to it.
     */
    const val LATEST_RELEASE_URL_BEFORE_RENAME = "https://api.github.com/repos/HazemMancy/Flowstead/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): GithubRelease = json.decodeFromString(GithubRelease.serializer(), body)

    fun check(installed: String, release: GithubRelease): UpdateCheck {
        val latest = release.tagName.removePrefix("v")
        if (release.draft || release.prerelease || compare(latest, installed) <= 0) return UpdateCheck.UpToDate(latest)
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") && !it.name.contains("debug") }
        val sha = apk?.let { a -> release.assets.firstOrNull { it.name == a.name + ".sha256" } }
        return UpdateCheck.Available(latest, release.htmlUrl, apk?.downloadUrl, sha?.downloadUrl)
    }

    /** The hash in a `.sha256` file ("<64 hex>  thermodock-v1.3.2.apk", as `sha256sum` writes it), lower case; null if there is none. */
    fun parseSha256(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }

    /** Compares dotted versions numerically ("1.10.0" > "1.9.2"); a suffix such as "-beta" or " (on device)" is ignored. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").takeWhile { it.isDigit() || it == '.' }
            .split('.').filter { it.isNotEmpty() }.map { it.toInt() }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
