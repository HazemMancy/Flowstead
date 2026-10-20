/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.ui.flowsheet

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.flowstead.core.model.ObjectType
import app.flowstead.core.model.SimObject
import app.flowstead.core.model.Spec
import app.flowstead.core.model.SpecKey
import app.flowstead.core.units.Quantity
import app.flowstead.core.units.UnitContext
import app.flowstead.core.units.UnitSet
import app.flowstead.core.units.Units
import app.flowstead.ui.theme.FlowsteadTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class SpecFieldTest {
    @get:Rule val compose = createComposeRule()

    private class Recorder : EditorActions {
        val specs = mutableListOf<Triple<String, String, Spec?>>()
        override fun rename(id: String, tag: String) = Unit
        override fun setSpec(id: String, key: String, spec: Spec?) { specs += Triple(id, key, spec) }
        override fun setExclusiveSpec(id: String, key: String, spec: Spec, group: Collection<String>) { specs += Triple(id, key, spec) }
        override fun setComposition(id: String, fractions: Map<String, Double>) = Unit
        override fun normalise(id: String, fractions: Map<String, Double>) = Unit
        override fun delete(id: String) = Unit
        override fun setRef(id: String, key: String, value: String?) = Unit
    }

    @Test
    fun typedValueIsStoredInSiWithTheUnitTyped() {
        val rec = Recorder()
        val obj = SimObject("s", "1", ObjectType.MaterialStream)
        compose.setContent {
            FlowsteadTheme { SpecField(obj, SpecKey.TEMPERATURE, UnitSet.SI, UnitContext(), rec) }
        }
        compose.onNodeWithTag("spec_temperature").performTextInput("25")
        compose.onNodeWithTag("spec_temperature").performImeAction()
        val (_, key, spec) = rec.specs.single()
        assertEquals(SpecKey.TEMPERATURE, key)
        assertEquals("C", assertNotNull(spec).unit)
        assertEquals(298.15, spec.si, 1e-9)
    }

    @Test
    fun gaugePressureUsesTheProjectAtmosphere() {
        val rec = Recorder()
        val obj = SimObject("s", "1", ObjectType.MaterialStream)
        val set = UnitSet.SI.with(Quantity.Pressure, Units.barg)
        compose.setContent {
            FlowsteadTheme { SpecField(obj, SpecKey.PRESSURE, set, UnitContext(90_000.0), rec) }
        }
        compose.onNodeWithTag("spec_pressure").performTextInput("70")
        compose.onNodeWithTag("spec_pressure").performImeAction()
        assertEquals(7_090_000.0, assertNotNull(rec.specs.single().third).si, 1e-6)
    }

    @Test
    fun clearingTheFieldRemovesTheSpecAndJunkIsRejected() {
        val rec = Recorder()
        val obj = SimObject("s", "1", ObjectType.MaterialStream, specs = mapOf(SpecKey.PRESSURE to Spec(3e6, "bara")))
        compose.setContent {
            FlowsteadTheme { SpecField(obj, SpecKey.PRESSURE, UnitSet.SI, UnitContext(), rec) }
        }
        compose.onNodeWithTag("spec_pressure").performTextReplacement("abc")
        compose.onNodeWithTag("spec_pressure").performImeAction()
        assertEquals(0, rec.specs.size)
        compose.onNodeWithTag("spec_pressure").performTextReplacement("")
        compose.onNodeWithTag("spec_pressure").performImeAction()
        assertEquals(Triple("s", SpecKey.PRESSURE, null), rec.specs.single())
    }
}
