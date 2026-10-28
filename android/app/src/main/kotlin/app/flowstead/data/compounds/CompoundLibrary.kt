/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.data.compounds

import android.content.Context
import app.flowstead.core.compounds.CompoundCatalogue
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The bundled compound library (all of DWSIM's databases), loaded once on first use. */
interface CompoundLibrary {
    suspend fun catalogue(): CompoundCatalogue
}

@Singleton
class AssetCompoundLibrary @Inject constructor(@ApplicationContext private val context: Context) : CompoundLibrary {
    private val lock = Mutex()
    private var loaded: CompoundCatalogue? = null

    override suspend fun catalogue(): CompoundCatalogue = lock.withLock {
        loaded ?: withContext(Dispatchers.IO) {
            CompoundCatalogue.parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
        }.also { loaded = it }
    }

    companion object {
        /** Bundled from shared/compounds/ (see app/build.gradle.kts). */
        const val ASSET = "catalogue.json"
    }
}
