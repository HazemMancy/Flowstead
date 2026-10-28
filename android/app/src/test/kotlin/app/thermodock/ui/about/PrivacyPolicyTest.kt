/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.ui.about

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PrivacyPolicyTest {
    @Test
    fun policyIsBundledFromDocs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val text = context.assets.open(PRIVACY_ASSET).bufferedReader().use { it.readText() }
        assertTrue(text.startsWith("# ThermoDock privacy policy"))
        assertTrue("No analytics" in text)
    }

    @Test
    fun featureOverviewIsBundledFromDocs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val text = context.assets.open(FEATURES_ASSET).bufferedReader().use { it.readText() }
        assertTrue("## What ThermoDock adds to the DWSIM engine" in text)
        assertTrue("## Works like HYSYS" in text)
        assertTrue("## Where ThermoDock still differs from HYSYS" in text)
    }
}
