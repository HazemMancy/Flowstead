/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.PhaseEnvelope
import app.flowstead.core.api.ProfileKeys
import app.flowstead.core.units.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlotsTest {
    @Test
    fun ticksAreRoundAndCoverTheRange() {
        assertEquals(listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), Plots.ticks(3.0, 97.0))
        assertEquals(listOf(0.2, 0.25, 0.3, 0.35), Plots.ticks(0.21, 0.34, 3).map { Math.round(it * 100) / 100.0 })
        val flat = Plots.ticks(5.0, 5.0)
        assertTrue(flat.first() < 5.0 && flat.last() > 5.0)
        assertTrue(Plots.ticks(Double.NaN, 1.0).isEmpty())
    }

    @Test
    fun exchangerGivesATqDiagramAndColumnsTheirStages() {
        val hx = Plots.forObject(
            ObjectType.HeatExchanger,
            mapOf(ProfileKeys.HEAT_FLOW to listOf(0.0, 1e5), ProfileKeys.HOT_TEMPERATURE to listOf(400.0, 350.0), ProfileKeys.COLD_TEMPERATURE to listOf(380.0, 300.0)),
        ).single()
        assertEquals(Quantity.Power, hx.xQuantity)
        assertEquals(listOf("Hot side", "Cold side"), hx.series.map { it.label })

        val column = Plots.forObject(
            ObjectType.DistillationColumn,
            mapOf(ProfileKeys.STAGE to listOf(1.0, 2.0, 3.0), ProfileKeys.TEMPERATURE to listOf(300.0, 320.0, 340.0), ProfileKeys.PRESSURE to listOf(1e6, 1e6, 1.02e6)),
        )
        // No flows from the engine: no flows plot.
        assertEquals(listOf("Temperature", "Pressure"), column.map { it.title })
        // A series whose lengths disagree is dropped, not stretched.
        assertTrue(Plots.forObject(ObjectType.PipeSegment, mapOf(ProfileKeys.LENGTH to listOf(0.0, 1.0), ProfileKeys.PRESSURE to listOf(1e5))).isEmpty())
        assertTrue(Plots.forObject(ObjectType.Valve, mapOf(ProfileKeys.LENGTH to listOf(0.0, 1.0))).isEmpty())
    }

    @Test
    fun envelopeMarksOnlyThePointsTheEngineFound() {
        val env = PhaseEnvelope(
            bubbleTemperature = listOf(200.0, 250.0), bubblePressure = listOf(1e6, 5e6),
            dewTemperature = listOf(260.0, 300.0), dewPressure = listOf(1e5, 3e6),
            cricondenthermTemperature = 300.0, cricondenthermPressure = 3e6,
        )
        val plot = Plots.envelope(env, 280.0, 2e6)!!
        assertEquals(listOf("Cricondentherm", "This stream"), plot.markers.map { it.label })
        assertNull(Plots.envelope(PhaseEnvelope()))
        val full = Plots.envelope(env.copy(
            qualityLines = listOf(app.flowstead.core.api.QualityLine(0.5, listOf(220.0, 240.0), listOf(1e6, 3e6))),
            hydrateTemperature = listOf(280.0, 290.0), hydratePressure = listOf(2e6, 8e6),
        ))!!
        assertEquals(listOf("Bubble point", "Dew point", "VF 0.5", "Hydrate formation"), full.series.map { it.label })
        // A wet gas (D118): the envelope is labelled water-free and the water dew line is its own series.
        val wet = Plots.envelope(env.copy(waterFree = true, waterDewTemperature = listOf(300.0, 330.0), waterDewPressure = listOf(1e5, 6e5)))!!
        assertEquals(listOf("Bubble point (water-free)", "Dew point (water-free)", "Water dew point"), wet.series.map { it.label })
        assertEquals(listOf(330.0), wet.series.last().x.drop(1))
        // Its close-up (D119): the water dew line and hydrates stretch the axes, so the envelope alone is offered, without
        // the stream far outside it.
        val far = env.copy(waterFree = true, waterDewTemperature = listOf(300.0, 400.0), waterDewPressure = listOf(1e5, 2e7))
        val close = Plots.envelopeCloseUp(far, 380.0, 1e5)!!
        assertEquals(listOf("Bubble point (water-free)", "Dew point (water-free)"), close.series.map { it.label })
        assertEquals(listOf("Cricondentherm"), close.markers.map { it.label })
        // Without other curves the envelope already fills the plot: no close-up.
        assertNull(Plots.envelopeCloseUp(env, 280.0, 2e6))
    }

    @Test
    fun envelopeNotesSayWhenTheInhibitedWaterFreezesFirst() {
        val k = { v: Double -> app.flowstead.core.format.ValueFormat.number(v, 1) + " K" }
        val frozen = PhaseEnvelope(hydrateDepression = 0.0, inhibitedHydrateTemperatureAtPressure = 251.2, inhibitedWaterFreezingTemperature = 262.2)
        assertTrue(EnvelopeNotes.inhibitor(frozen, k)!!.contains("they form from ice there"))
        val working = PhaseEnvelope(hydrateDepression = 8.0, inhibitedHydrateTemperatureAtPressure = 275.0, inhibitedWaterFreezingTemperature = 262.2)
        assertEquals("With the inhibitor in the water, hydrates form 8.0 K lower, below 275.0 K at this stream's pressure; the inhibited water freezes below 262.2 K (dashed curve).",
            EnvelopeNotes.inhibitor(working, k))
        val wet = PhaseEnvelope(waterDewTemperatureAtPressure = 330.0)
        assertEquals("Water dew point at this stream's pressure: 330.0 K. The stream is colder: it carries free (liquid) water.", EnvelopeNotes.waterDew(wet, 311.0, k))
        assertNull(EnvelopeNotes.waterDew(PhaseEnvelope(), 311.0, k))
    }

    @Test
    fun pvtPlotsSkipMissingSteps() {
        val r = app.flowstead.core.api.PvtResult(
            experiment = "cvd", columns = listOf("pressure", "liquidDropout"),
            table = mapOf("pressure" to listOf(3e7, 2e7, 1e7), "liquidDropout" to listOf(0.0, null, 0.1)),
        )
        val plot = PvtTables.plots(r).single()
        assertEquals(listOf(3e7, 1e7), plot.series.single().x)
        assertEquals(Quantity.Fraction, plot.yQuantity)
    }
}

class LabelPlacerTest {
    private fun noClashes(boxes: List<LabelBox>) {
        for (i in boxes.indices) for (j in i + 1 until boxes.size) assertEquals(0.0, boxes[i].overlap(boxes[j]), "labels $i and $j overlap")
    }

    @Test
    fun nearbyEnvelopePointsGetSeparateLabels() {
        // The owner's screenshot: critical point and cricondenbar a few pixels apart near the right edge,
        // the cricondentherm just below them.
        val points = listOf(700.0 to 120.0, 708.0 to 118.0, 740.0 to 150.0, 800.0 to 40.0)
        val sizes = listOf(110.0 to 24.0, 120.0 to 24.0, 140.0 to 24.0, 100.0 to 24.0)
        val boxes = LabelPlacer.place(points, sizes, 860.0, 440.0, gap = 4.0, dot = 4.0)
        noClashes(boxes)
        boxes.forEachIndexed { i, b ->
            assertTrue(b.left >= 0 && b.right <= 860.0 && b.top >= 0 && b.bottom <= 440.0, "label $i off the canvas: $b")
            points.forEach { (x, y) -> assertEquals(0.0, b.overlap(LabelBox(x - 4, y - 4, 8.0, 8.0)), "label $i covers the dot at $x, $y") }
        }
    }

    @Test
    fun aLonePointKeepsItsLabelAboveRight() {
        val b = LabelPlacer.place(listOf(100.0 to 100.0), listOf(50.0 to 20.0), 400.0, 300.0, gap = 4.0, dot = 4.0).single()
        assertEquals(LabelBox(104.0, 76.0, 50.0, 20.0), b)
    }

    @Test
    fun manyPointsOnOneSpotStillDoNotOverlap() {
        val points = List(5) { 200.0 to 150.0 }
        val sizes = List(5) { 80.0 to 20.0 }
        noClashes(LabelPlacer.place(points, sizes, 600.0, 400.0, gap = 4.0, dot = 4.0))
    }

    @Test
    fun aSmallGapStillLeavesTheLabelBesideItsOwnDot() {
        // A low-density screen: a 3 px gap beside a 7 px dot.
        val b = LabelPlacer.place(listOf(100.0 to 100.0), listOf(50.0 to 20.0), 400.0, 300.0, gap = 3.0, dot = 7.0).single()
        assertEquals(LabelBox(107.0, 73.0, 50.0, 20.0), b)
    }
}
