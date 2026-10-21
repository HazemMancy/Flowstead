/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The fields Flowstead reads from GitHub's "latest release" answer. */
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
    /** [apkUrl] is the release's APK, null if the release has none (then [pageUrl] only). */
    data class Available(val version: String, val pageUrl: String, val apkUrl: String?) : UpdateCheck
}

/** Updates come from the project's GitHub releases (the app isn't on a store yet; decision D35). */
object Updates {
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/HazemMancy/Flowstead/releases/latest"
    const val RELEASES_PAGE = "https://github.com/HazemMancy/Flowstead/releases"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): GithubRelease = json.decodeFromString(GithubRelease.serializer(), body)

    fun check(installed: String, release: GithubRelease): UpdateCheck {
        val latest = release.tagName.removePrefix("v")
        if (release.draft || release.prerelease || compare(latest, installed) <= 0) return UpdateCheck.UpToDate(latest)
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") && !it.name.contains("debug") }
        return UpdateCheck.Available(latest, release.htmlUrl, apk?.downloadUrl)
    }

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
