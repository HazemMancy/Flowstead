/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.flowstead.core.units.StandardConditions
import app.flowstead.core.units.UnitSet
import app.flowstead.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class AppSettings(
    val engineUrl: String = "",
    val unitSet: UnitSet = UnitSet.SI,
    val themeMode: ThemeMode = ThemeMode.System,
    /** Atmospheric reference for gauge pressures in new projects, Pa (D9). */
    val defaultAtmosphericPressurePa: Double = StandardConditions.DEFAULT_PATM_PA,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setEngineUrl(url: String)
    suspend fun setUnitSet(unitSet: UnitSet)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDefaultAtmosphericPressure(pa: Double)
}

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @Named("settings") private val store: DataStore<Preferences>,
) : SettingsRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            engineUrl = p[ENGINE_URL].orEmpty(),
            unitSet = p[UNIT_SET]?.let { runCatching { json.decodeFromString(UnitSet.serializer(), it) }.getOrNull() }
                ?: UnitSet.SI,
            themeMode = p[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            defaultAtmosphericPressurePa = p[PATM]?.takeIf { it.isFinite() && it > 0 }
                ?: StandardConditions.DEFAULT_PATM_PA,
        )
    }

    override suspend fun setEngineUrl(url: String) {
        store.edit { it[ENGINE_URL] = url.trim().trimEnd('/') }
    }

    override suspend fun setUnitSet(unitSet: UnitSet) {
        store.edit { it[UNIT_SET] = json.encodeToString(UnitSet.serializer(), unitSet) }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[THEME] = mode.name }
    }

    override suspend fun setDefaultAtmosphericPressure(pa: Double) {
        require(pa.isFinite() && pa > 0) { "Atmospheric pressure must be positive" }
        store.edit { it[PATM] = pa }
    }

    private companion object {
        val ENGINE_URL = stringPreferencesKey("engine_url")
        val UNIT_SET = stringPreferencesKey("unit_set")
        val THEME = stringPreferencesKey("theme_mode")
        val PATM = doublePreferencesKey("default_patm_pa")
    }
}

/** Checks an engine URL before it is saved. HTTPS only (brief §2a); a bare host gets https:// added. */
object EngineUrl {
    sealed interface Result {
        data class Ok(val url: String) : Result
        data class Invalid(val reason: String) : Result
    }

    fun normalise(input: String): Result {
        val raw = input.trim().trimEnd('/')
        if (raw.isEmpty()) return Result.Invalid("Enter the engine address")
        val withScheme = if ("://" in raw) raw else "https://$raw"
        val uri = runCatching { java.net.URI(withScheme) }.getOrNull()
            ?: return Result.Invalid("Not a valid address")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return Result.Invalid("The engine must use HTTPS")
        }
        if (uri.host.isNullOrBlank()) return Result.Invalid("Address has no host name")
        if (uri.rawQuery != null || uri.rawFragment != null) return Result.Invalid("Remove the query or fragment")
        return Result.Ok(withScheme)
    }
}
