/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.ui.projects

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thermodock.FakeDocumentIo
import app.thermodock.FakeEngineClient
import app.thermodock.FakeProjectRepository
import app.thermodock.FakeSecretStore
import app.thermodock.FakeSettingsRepository
import app.thermodock.MainDispatcherRule
import app.thermodock.core.api.ImportResult
import app.thermodock.core.model.Flowsheet
import app.thermodock.core.model.ObjectType
import app.thermodock.core.model.SimObject
import app.thermodock.data.engine.EngineResult
import app.thermodock.data.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ImportDwsimTest {
    @get:Rule val main = MainDispatcherRule()

    private val repo = FakeProjectRepository()
    private val settings = FakeSettingsRepository(AppSettings(engineUrl = "https://engine.example.com", engineMode = app.thermodock.data.settings.EngineMode.Remote))
    private val engine = FakeEngineClient()
    private val documents = FakeDocumentIo()
    private val vm by lazy { ProjectsViewModel(repo, settings, FakeSecretStore(), engine, documents) }
    private val uri = Uri.parse("content://test/plant")

    private val imported = Flowsheet(
        propertyPackage = "Peng-Robinson (PR)",
        compounds = listOf("Methane"),
        objects = listOf(SimObject("a", "FEED", ObjectType.MaterialStream)),
    )

    @Test
    fun cleanImportCreatesTheProjectAndOpensIt() = runTest {
        engine.importResult = EngineResult.Ok(ImportResult(imported))
        vm.import(uri)
        val effect = vm.effects.first()
        assertEquals(ProjectsEffect.OpenProject("p1"), effect)
        assertEquals("UEs=", engine.imported.single().data) // the file bytes, base64
        val project = repo.observeProject("p1").first()!!
        assertEquals("Plant", project.name)
        assertEquals(imported, project.flowsheet)
    }

    @Test
    fun importWithNotesShowsThemBeforeOpening() = runTest {
        engine.importResult = EngineResult.Ok(ImportResult(imported, listOf("PIPE-1: Pipe is not supported by ThermoDock and was left out, with its connections.")))
        vm.import(uri)
        val effect = vm.effects.first()
        assertEquals(ProjectsEffect.ImportReport("p1", "Plant", (engine.importResult as EngineResult.Ok).value.warnings), effect)
    }

    @Test
    fun failuresAreExplainedAndCreateNothing() = runTest {
        engine.importResult = EngineResult.Failed("The engine could not use it: DWSIM could not open the file")
        vm.import(uri)
        assertEquals(ProjectsEffect.Message("Not imported: The engine could not use it: DWSIM could not open the file"), vm.effects.first())

        documents.toRead = null
        vm.import(uri)
        assertEquals(ProjectsEffect.Message("Couldn't read the file: Permission denied"), vm.effects.first())

        settings.setEngineUrl("")
        vm.import(uri)
        assertEquals(ProjectsEffect.Message("Set the engine address in Settings to import DWSIM files"), vm.effects.first())
        assertTrue(vm.state.first { !it.loading }.projects.isEmpty())
    }

    @Test
    fun projectNameFromFileName() {
        assertEquals("Gas plant", ProjectsViewModel.projectName("Gas plant.dwxmz"))
        assertEquals("v1.2 case", ProjectsViewModel.projectName("v1.2 case.dwxml"))
        assertEquals("noextension", ProjectsViewModel.projectName("noextension"))
        assertEquals(".dwxmz", ProjectsViewModel.projectName(".dwxmz"))
    }
}
