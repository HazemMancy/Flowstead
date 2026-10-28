/*
 * ThermoDock — steady-state process simulation, in your pocket.
 * Copyright (C) 2026 ThermoDock contributors. SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.thermodock.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdatesTest {
    private val body = """
        {"tag_name":"v1.1.0","html_url":"https://github.com/HazemMancy/ThermoDock/releases/tag/v1.1.0","draft":false,
         "prerelease":false,"name":"ThermoDock v1.1.0","assets":[
           {"name":"thermodock-v1.1.0-mapping.txt","browser_download_url":"https://example/m.txt","size":1},
           {"name":"thermodock-v1.1.0.apk","browser_download_url":"https://example/f.apk","size":2},
           {"name":"thermodock-v1.1.0.apk.sha256","browser_download_url":"https://example/f.apk.sha256","size":3}]}
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
        assertEquals("https://example/f.apk.sha256", r.sha256Url)
    }

    @Test
    fun sha256FilesParseAsSha256sumWritesThem() {
        val h = "36CC39AE99168B2B142FF761D03AD96AEB359B1EA000FCE75F204C922CA460D0"
        assertEquals(h.lowercase(), Updates.parseSha256("$h  thermodock-v1.3.1.apk\n"))
        assertEquals(null, Updates.parseSha256("not a hash"))
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
