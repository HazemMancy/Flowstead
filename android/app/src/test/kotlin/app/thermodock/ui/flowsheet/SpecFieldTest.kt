/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.ui.flowsheet

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thermodock.core.model.Flowsheet
import app.thermodock.core.model.FlowsheetEditing
import app.thermodock.core.model.ObjectType
import app.thermodock.core.model.Port
import app.thermodock.core.model.Reaction
import app.thermodock.core.model.SimObject
import app.thermodock.core.model.Spec
import app.thermodock.core.model.SpecKey
import app.thermodock.core.model.obj
import app.thermodock.core.units.Quantity
import app.thermodock.core.units.UnitContext
import app.thermodock.core.units.UnitSet
import app.thermodock.core.units.Units
import app.thermodock.ui.theme.ThermoDockTheme
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
        override fun connectPort(unitId: String, port: Port, streamId: String?) = Unit
        override fun disconnectPort(unitId: String, port: Port) = Unit
        override fun upsertReaction(reaction: Reaction) = Unit
        override fun deleteReaction(id: String) = Unit
        override fun setPipeSize(id: String, nps: String?, schedule: String?) = Unit
        override fun editSizingCriteria() = Unit
        override fun openDataSheet(id: String) = Unit
    }

    @Test
    fun typedValueIsStoredInSiWithTheUnitTyped() {
        val rec = Recorder()
        val obj = SimObject("s", "1", ObjectType.MaterialStream)
        compose.setContent {
            ThermoDockTheme { SpecField(obj, SpecKey.TEMPERATURE, UnitSet.SI, UnitContext(), rec) }
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
            ThermoDockTheme { SpecField(obj, SpecKey.PRESSURE, set, UnitContext(90_000.0), rec) }
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
            ThermoDockTheme { SpecField(obj, SpecKey.PRESSURE, UnitSet.SI, UnitContext(), rec) }
        }
        compose.onNodeWithTag("spec_pressure").performTextReplacement("abc")
        compose.onNodeWithTag("spec_pressure").performImeAction()
        assertEquals(0, rec.specs.size)
        compose.onNodeWithTag("spec_pressure").performTextReplacement("")
        compose.onNodeWithTag("spec_pressure").performImeAction()
        assertEquals(Triple("s", SpecKey.PRESSURE, null), rec.specs.single())
    }

    @Test
    fun diametersDefaultToMillimetresOrInches() {
        val rec = Recorder()
        val obj = SimObject("p", "PIPE-100", ObjectType.PipeSegment)
        compose.setContent {
            ThermoDockTheme {
                SpecField(obj, SpecKey.INNER_DIAMETER, UnitSet.SI, UnitContext(), rec)
                SpecField(obj, SpecKey.LENGTH, UnitSet.FIELD, UnitContext(), rec)
            }
        }
        compose.onNodeWithTag("spec_innerDiameter").performTextInput("100")
        compose.onNodeWithTag("spec_innerDiameter").performImeAction()
        compose.onNodeWithTag("spec_length").performTextInput("100")
        compose.onNodeWithTag("spec_length").performImeAction()
        // The recorder doesn't feed values back, so moving focus may commit the first field again.
        assertEquals(
            mapOf(SpecKey.INNER_DIAMETER to Spec(0.1, "mm"), SpecKey.LENGTH to Spec(30.48, "ft")),
            rec.specs.associate { it.second to it.third },
        )
    }

    @Test
    fun aStandardPipeSizeShowsItsAsmeDimensions() {
        val rec = Recorder()
        val pipe = SimObject("p", "PIPE-100", ObjectType.PipeSegment)
        val fs = FlowsheetEditing.setPipeSize(Flowsheet(objects = listOf(pipe)), "p", "4", null)
        compose.setContent {
            ThermoDockTheme { Column { PipeEditor(fs, fs.obj("p")!!, UnitSet.SI, UnitContext(), rec) } }
        }
        // NPS 4 Sch 40: OD 4.500 in, wall 0.237 in, ID 4.026 in.
        compose.onNodeWithText("114.300 mm").assertExists()
        compose.onNodeWithText("6.020 mm").assertExists()
        compose.onNodeWithText("102.260 mm").assertExists()
        compose.onNodeWithTag("spec_innerDiameter").assertDoesNotExist()
    }
}
