/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.model

import app.flowstead.core.api.PhaseEnvelope
import app.flowstead.core.format.ValueFormat

/**
 * The sentences under a phase envelope that compare the engine's numbers with the stream, shared by the app and the
 * web (D118, D119). [t] formats a temperature (K) in the user's units.
 */
object EnvelopeNotes {
    /** Where the stream's free water drops out (D118), and whether the stream is colder than that. */
    fun waterDew(env: PhaseEnvelope, streamTemperature: Double?, t: (Double) -> String): String? {
        val tw = env.waterDewTemperatureAtPressure ?: return null
        return "Water dew point at this stream's pressure: ${t(tw)}" + when {
            streamTemperature == null -> "."
            streamTemperature < tw -> ". The stream is colder: it carries free (liquid) water."
            else -> ". The stream is warmer: no free water."
        }
    }

    /**
     * What the inhibitor does at the stream's pressure (D119). Where hydrates would form colder than the inhibited
     * water's freezing point, the water is ice there and the inhibitor does not lower them; saying "0 K lower" alone
     * reads as a fault.
     */
    fun inhibitor(env: PhaseEnvelope, t: (Double) -> String): String? {
        val d = env.hydrateDepression ?: return null
        val ti = env.inhibitedHydrateTemperatureAtPressure
        val tf = env.inhibitedWaterFreezingTemperature
        if (ti != null && tf != null && ti <= tf + 0.05) {
            return "At this stream's pressure hydrates form below ${t(ti)}, colder than ${t(tf)}, where the inhibited water freezes: " +
                "they form from ice there, so the inhibitor does not lower them. The dashed curve shows it working where hydrates form warmer than that."
        }
        return "With the inhibitor in the water, hydrates form ${ValueFormat.number(d, 1)} K lower" +
            (ti?.let { ", below ${t(it)} at this stream's pressure" } ?: "") +
            (tf?.let { "; the inhibited water freezes below ${t(it)}" } ?: "") + " (dashed curve)."
    }
}
