/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.update

import android.content.Intent
import app.flowstead.FakeUpdatePromptStore
import app.flowstead.MainDispatcherRule
import app.flowstead.core.update.UpdateCheck
import app.flowstead.core.update.UpdatePrompt
import app.flowstead.data.update.AppUpdater
import app.flowstead.data.update.InstallState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdatePromptViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val release = UpdateCheck.Available("99.0.0", "https://example/page", "https://example/f.apk", "https://example/f.apk.sha256")
    private val updater = object : AppUpdater {
        val started = mutableListOf<UpdateCheck.Available>()
        override val state = MutableStateFlow<InstallState>(InstallState.Idle)
        override fun start(update: UpdateCheck.Available) { started += update }
        override fun install() = Unit
        override fun permissionSettings() = Intent()
    }

    private fun vm(store: FakeUpdatePromptStore, latest: UpdateCheck = release): UpdatePromptViewModel {
        UpdatePromptViewModel.launchChecked.set(true) // tests call checkOnce() themselves
        return UpdatePromptViewModel({ Result.success(latest) }, store, updater)
    }

    @Test
    fun offersANewVersionThreeTimesThenStops() = runTest {
        val store = FakeUpdatePromptStore()
        repeat(3) { i ->
            val vm = vm(store)
            vm.checkOnce()
            assertEquals(release, vm.offer.value, "launch ${i + 1}")
        }
        val fourth = vm(store)
        fourth.checkOnce()
        assertNull(fourth.offer.value)
        assertEquals(UpdatePrompt.Seen("99.0.0", 3), store.seen)
    }

    @Test
    fun nothingWhenTurnedOffOrUpToDate() = runTest {
        val off = vm(FakeUpdatePromptStore(onOpen = false))
        off.checkOnce()
        assertNull(off.offer.value)
        val current = vm(FakeUpdatePromptStore(), UpdateCheck.UpToDate("1.0.0"))
        current.checkOnce()
        assertNull(current.offer.value)
    }

    @Test
    fun updateStartsTheInAppInstall() = runTest {
        val vm = vm(FakeUpdatePromptStore())
        vm.checkOnce()
        vm.update()
        assertEquals(listOf(release), updater.started)
        vm.later()
        assertNull(vm.offer.value)
    }
}
