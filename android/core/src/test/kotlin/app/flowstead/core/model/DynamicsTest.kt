/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.DynamicsResult
import app.flowstead.core.api.SolveStatus
import app.flowstead.core.api.Trend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicsTest {
    private val sep = SimObject("sep", "V-100", ObjectType.Separator)
    private val lic = SimObject(
        "lic", "LIC-100", ObjectType.Controller,
        refs = mapOf(
            AdjustKeys.CONTROLLED_OBJECT to "sep", AdjustKeys.CONTROLLED_PROPERTY to "liquidLevel",
            AdjustKeys.MANIPULATED_OBJECT to "lv",
        ),
        specs = mapOf(AdjustKeys.TARGET to Spec(1.5, "m")),
    )
    private val fs = Flowsheet(
        propertyPackage = "Peng-Robinson (PR)", compounds = listOf("Methane"),
        objects = listOf(sep, SimObject("lv", "LV-100", ObjectType.Valve), lic),
    )

    @Test
    fun theRunsSetupChangesNoSteadyResult() {
        val set = FlowsheetEditing.setDynamicParameter(fs, "sep", DynamicKey.VOLUME, Spec(5.0, "m3"))
        assertEquals(fs.modelHash(), set.modelHash())
        assertEquals(5.0, Dynamics.parameter(set, "sep", DynamicKey.VOLUME)?.si)
        val cleared = FlowsheetEditing.setDynamicParameter(set, "sep", DynamicKey.VOLUME, null)
        assertTrue(Dynamics.setup(cleared).parameters.isEmpty())
        // The engine gets the setup apart from the flowsheet.
        val req = Dynamics.request(set)
        assertNull(req.flowsheet.dynamics)
        assertEquals(5.0, req.parameters["sep"]?.get(DynamicKey.VOLUME)?.si)
    }

    @Test
    fun aVesselNeedsItsVolume() {
        assertEquals("V-100: give its volume", Dynamics.problem(fs))
        assertNull(Dynamics.problem(FlowsheetEditing.setDynamicParameter(fs, "sep", DynamicKey.VOLUME, Spec(5.0, "m3"))))
        val column = fs.copy(objects = fs.objects + SimObject("t", "T-100", ObjectType.DistillationColumn))
        assertTrue(Dynamics.problem(column)!!.startsWith("T-100"))
    }

    @Test
    fun aLevelControllerActsInDynamicsOnlyAndNeedsNoSpecToVary() {
        assertEquals(SpecStatus.Complete, Specification.status(fs, lic))
    }

    @Test
    fun trendsBecomePlots() {
        val t = listOf(0.0, 1.0, 2.0)
        val r = DynamicsResult(
            SolveStatus.Converged, times = t,
            trends = listOf(
                Trend("lic", "controlledValue", listOf(1.5, 1.6, 1.5)), Trend("lic", "target", listOf(1.5, 1.5, 1.5)),
                Trend("lic", "output", listOf(50.0, 60.0, 55.0)), Trend("sep", "pressure", listOf(2e6, 2e6, 2e6)),
                Trend("lv", "valveOpening", listOf(0.5, 0.6, 0.55)),
            ),
        )
        val plots = Dynamics.plots(fs, r)
        assertEquals(listOf("LIC-100 PV and SP", "LIC-100 output", "V-100 pressure", "Valve openings"), plots.map { it.title })
        assertEquals(listOf(0.5, 0.6, 0.55), plots[1].series.single().y) // output shown as a fraction
    }
}
