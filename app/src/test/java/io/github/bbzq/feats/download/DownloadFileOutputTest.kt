package io.github.bbzq.feats.download

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFileOutputTest {
    @Test
    fun copyPublishesCompleteFileAndReplacesPreviousOutput() {
        val directory = Files.createTempDirectory("bbzq-output-test").toFile()
        try {
            val source = File(directory, "source.m4s").apply { writeText("new complete output") }
            val target = File(directory, "video.mp4").apply { writeText("previous output") }

            assertTrue(DownloadFileOutput.copy(source, target))
            assertEquals("new complete output", target.readText())
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun emptySourceDoesNotReplacePreviousOutput() {
        val directory = Files.createTempDirectory("bbzq-output-test").toFile()
        try {
            val source = File(directory, "source.m4s").apply { createNewFile() }
            val target = File(directory, "video.mp4").apply { writeText("previous output") }

            assertFalse(DownloadFileOutput.copy(source, target))
            assertEquals("previous output", target.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedPublishLeavesExistingTargetUnchanged() {
        val directory = Files.createTempDirectory("bbzq-output-test").toFile()
        try {
            val source = File(directory, "source.m4s").apply { writeText("new output") }
            // A directory at the target path makes the publish operation fail after
            // the temporary copy has completed.
            val target = File(directory, "video.mp4").apply { mkdir() }

            assertFalse(DownloadFileOutput.copy(source, target))
            assertTrue(target.isDirectory)
            assertEquals("new output", source.readText())
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally {
            directory.deleteRecursively()
        }
    }
}
