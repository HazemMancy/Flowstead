/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data.update

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.flowstead.core.update.UpdatePrompt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Whether to check for updates when the app opens, and how often the prompt was shown (D45). */
interface UpdatePromptStore {
    val checkOnOpen: Flow<Boolean>
    suspend fun setCheckOnOpen(enabled: Boolean)
    suspend fun seen(): UpdatePrompt.Seen
    suspend fun remember(seen: UpdatePrompt.Seen)
}

@Singleton
class DataStoreUpdatePromptStore @Inject constructor(
    @Named("settings") private val store: DataStore<Preferences>,
) : UpdatePromptStore {
    override val checkOnOpen: Flow<Boolean> = store.data.map { it[CHECK_ON_OPEN] ?: true }

    override suspend fun setCheckOnOpen(enabled: Boolean) {
        store.edit { it[CHECK_ON_OPEN] = enabled }
    }

    override suspend fun seen(): UpdatePrompt.Seen = store.data.first().let { UpdatePrompt.Seen(it[VERSION], it[COUNT] ?: 0) }

    override suspend fun remember(seen: UpdatePrompt.Seen) {
        store.edit { p ->
            seen.version?.let { p[VERSION] = it }
            p[COUNT] = seen.count
        }
    }

    private companion object {
        val CHECK_ON_OPEN = booleanPreferencesKey("update_check_on_open")
        val VERSION = stringPreferencesKey("update_prompt_version")
        val COUNT = intPreferencesKey("update_prompt_count")
    }
}
