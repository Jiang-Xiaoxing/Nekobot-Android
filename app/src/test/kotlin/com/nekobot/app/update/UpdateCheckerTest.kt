package com.nekobot.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun githubAssetsUseTheOfficialDownloadUrlOnly() {
        val source = "https://github.com/asukaneko/Nekobot-Android/releases/download/v0.5.2/Nekobot-v0.5.2.apk"

        assertEquals(listOf(source), UpdateChecker.buildDownloadUrls(source))
    }

    @Test
    fun detectsRc2WhenRc1IsInstalled() {
        assertTrue(UpdateChecker.isNewer("v0.8.6-rc2", "0.8.6-rc1"))
        assertFalse(UpdateChecker.isNewer("v0.8.6-rc1", "0.8.6-rc2"))
        assertFalse(UpdateChecker.isNewer("v0.8.6-rc2", "0.8.6-rc2"))
    }

    @Test
    fun comparesPrereleaseNumbersNumerically() {
        assertTrue(UpdateChecker.isNewer("0.8.6-rc10", "0.8.6-rc2"))
        assertFalse(UpdateChecker.isNewer("0.8.6-rc2", "0.8.6-rc10"))
        assertTrue(UpdateChecker.isNewer("0.8.6-beta.11", "0.8.6-beta.2"))
        assertTrue(UpdateChecker.isNewer("0.8.6-rc.2", "0.8.6-rc.1"))
    }

    @Test
    fun ordersPrereleaseStagesAndFinalRelease() {
        val versions = listOf(
            "0.8.6-alpha", "0.8.6-alpha.1", "0.8.6-alpha.beta",
            "0.8.6-beta", "0.8.6-beta.2", "0.8.6-beta.11",
            "0.8.6-rc1", "0.8.6-rc2", "0.8.6"
        )
        versions.zipWithNext().forEach { (older, newer) ->
            assertTrue("$newer > $older", UpdateChecker.isNewer(newer, older))
            assertFalse("$older < $newer", UpdateChecker.isNewer(older, newer))
        }
    }

    @Test
    fun comparesCoreVersionBeforePrerelease() {
        assertTrue(UpdateChecker.isNewer("0.8.7-rc1", "0.8.6"))
        assertTrue(UpdateChecker.isNewer("0.8.10", "0.8.9"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.99.99"))
        assertFalse(UpdateChecker.isNewer("0.8.5", "0.8.6-rc1"))
    }

    @Test
    fun ignoresTagPrefixBuildMetadataAndMissingZeroComponents() {
        assertTrue(UpdateChecker.isNewer(" V0.8.6-RC2+build.1 ", "0.8.6-rc1+build.99"))
        assertFalse(UpdateChecker.isNewer("v0.8.6+build.2", "0.8.6+build.1"))
        assertFalse(UpdateChecker.isNewer("v0.8.6", "0.8.6.0"))
        assertFalse(UpdateChecker.isNewer("0.8.6.0", "0.8.6"))
        assertFalse(UpdateChecker.isNewer("0.8.6-rc2+new", "0.8.6-rc2+old"))
    }

    @Test
    fun rejectsInvalidVersions() {
        listOf("", "latest", "0.bad.9", "0.8.6-", "0..9").forEach { invalid ->
            assertFalse(UpdateChecker.isNewer(invalid, "0.8.6-rc1"))
            assertFalse(UpdateChecker.isNewer("0.8.6-rc2", invalid))
        }
    }
}
