package io.github.bbzq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionParserTest {
    @Test
    fun parsesForkTag() {
        val parsed = UpdateVersionParser.parseTag("v1.2.0-238")

        assertNotNull(parsed)
        assertEquals("v1.2.0", parsed?.versionName)
        assertEquals(238, parsed?.versionCode)
    }

    @Test
    fun parsesLegacyTag() {
        val parsed = UpdateVersionParser.parseTag("238-v1.2.0-238")

        assertNotNull(parsed)
        assertEquals("v1.2.0", parsed?.versionName)
        assertEquals(238, parsed?.versionCode)
    }

    @Test
    fun newerRemoteCodeIsAnUpdate() {
        val remote = requireNotNull(UpdateVersionParser.parseTag("v1.2.0-238"))

        assertTrue(UpdateVersionParser.isUpdateAvailable(remote, "1.2.0", 237))
    }

    @Test
    fun equalRemoteCodeIsNotAnUpdate() {
        val remote = requireNotNull(UpdateVersionParser.parseTag("v1.2.0-238"))

        assertFalse(UpdateVersionParser.isUpdateAvailable(remote, "1.2.0", 238))
    }

    @Test
    fun legacyTagRemainsComparable() {
        val remote = requireNotNull(UpdateVersionParser.parseTag("238-v1.2.0-238"))

        assertTrue(UpdateVersionParser.isUpdateAvailable(remote, "1.1.9", 237))
    }
}
