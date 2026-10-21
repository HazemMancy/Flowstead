/*
 * Flowstead — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 Flowstead contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.flowstead.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdatesTest {
    private val body = """
        {"tag_name":"v1.1.0","html_url":"https://github.com/HazemMancy/Flowstead/releases/tag/v1.1.0","draft":false,
         "prerelease":false,"name":"Flowstead v1.1.0","assets":[
           {"name":"flowstead-v1.1.0-mapping.txt","browser_download_url":"https://example/m.txt","size":1},
           {"name":"flowstead-v1.1.0.apk","browser_download_url":"https://example/f.apk","size":2}]}
    """.trimIndent()

    @Test
    fun versionsCompareNumerically() {
        assertTrue(Updates.compare("1.10.0", "1.9.2") > 0)
        assertEquals(0, Updates.compare("v1.0", "1.0.0"))
        assertEquals(0, Updates.compare("1.0.0 (on device)", "1.0.0"))
        assertTrue(Updates.compare("0.2.0", "1.0.0") < 0)
    }

    @Test
    fun aNewerReleaseOffersItsApk() {
        val r = Updates.check("1.0.0", Updates.parse(body))
        assertIs<UpdateCheck.Available>(r)
        assertEquals("1.1.0", r.version)
        assertEquals("https://example/f.apk", r.apkUrl)
    }

    @Test
    fun theSameOrAnOlderReleaseIsUpToDate() {
        assertIs<UpdateCheck.UpToDate>(Updates.check("1.1.0", Updates.parse(body)))
        assertIs<UpdateCheck.UpToDate>(Updates.check("1.2.0", Updates.parse(body)))
    }

    @Test
    fun aPrereleaseIsNotOffered() {
        val pre = Updates.parse(body).copy(prerelease = true)
        assertIs<UpdateCheck.UpToDate>(Updates.check("1.0.0", pre))
    }
}
