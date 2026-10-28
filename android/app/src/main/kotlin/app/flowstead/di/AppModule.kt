/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import app.flowstead.data.Clock
import app.flowstead.data.ProjectRepository
import app.flowstead.data.RoomProjectRepository
import app.flowstead.data.compounds.AssetCompoundLibrary
import app.flowstead.data.compounds.CompoundLibrary
import app.flowstead.data.db.AppDatabase
import app.flowstead.data.db.ProjectDao
import app.flowstead.data.engine.EngineClient
import app.flowstead.data.engine.RoutingEngineClient
import app.flowstead.data.engine.local.EmbeddedLocalEngine
import app.flowstead.data.engine.local.LocalEngine
import app.flowstead.data.files.ContentResolverDocumentIo
import app.flowstead.data.files.DocumentIo
import app.flowstead.data.queue.SolveQueue
import app.flowstead.data.queue.WorkManagerSolveQueue
import app.flowstead.data.update.AppUpdater
import app.flowstead.data.update.DataStoreUpdatePromptStore
import app.flowstead.data.update.UpdatePromptStore
import app.flowstead.data.update.GithubUpdateChecker
import app.flowstead.data.update.DownloadUpdater
import app.flowstead.data.update.UpdateChecker
import app.flowstead.data.settings.DataStoreSettingsRepository
import app.flowstead.data.settings.EncryptedSecretStore
import app.flowstead.data.settings.KeystoreCipher
import app.flowstead.data.settings.SecretCipher
import app.flowstead.data.settings.SecretStore
import app.flowstead.data.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import app.flowstead.core.model.FlowsheetJson
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun projectDao(db: AppDatabase): ProjectDao = db.projectDao()

    @Provides
    @Singleton
    fun clock(): Clock = Clock { System.currentTimeMillis() }

    @Provides
    @Singleton
    @Named("settings")
    fun settingsStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    /** Kept in its own file so backup rules can exclude it (res/xml/data_extraction_rules.xml). */
    @Provides
    @Singleton
    @Named("secrets")
    fun secretsStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("engine_secrets") }

    @Provides
    @Singleton
    fun secretCipher(): SecretCipher = KeystoreCipher()

    @Provides
    @Singleton
    fun httpClient(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        // Same JSON settings as the stored master copy, so what is solved is exactly what is saved.
        install(ContentNegotiation) { json(FlowsheetJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 120_000
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun projects(impl: RoomProjectRepository): ProjectRepository
    @Binds abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun secrets(impl: EncryptedSecretStore): SecretStore
    @Binds abstract fun engine(impl: RoutingEngineClient): EngineClient
    @Binds abstract fun localEngine(impl: EmbeddedLocalEngine): LocalEngine
    @Binds abstract fun documents(impl: ContentResolverDocumentIo): DocumentIo
    @Binds abstract fun compounds(impl: AssetCompoundLibrary): CompoundLibrary
    @Binds abstract fun solveQueue(impl: WorkManagerSolveQueue): SolveQueue
    @Binds abstract fun updates(impl: GithubUpdateChecker): UpdateChecker
    @Binds abstract fun updater(impl: DownloadUpdater): AppUpdater
    @Binds abstract fun updatePrompts(impl: DataStoreUpdatePromptStore): UpdatePromptStore
}
