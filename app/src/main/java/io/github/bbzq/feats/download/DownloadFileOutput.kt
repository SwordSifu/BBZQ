package io.github.bbzq.feats.download

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Helpers for publishing downloaded files without exposing a partially written output.
 *
 * The temporary file is always created beside the destination. A move within that
 * directory is therefore atomic on the filesystems used by Android in normal cases.
 */
internal object DownloadFileOutput {
    private const val BUFFER_SIZE = 128 * 1024

    /**
     * Copies a completed source file to [target] and publishes it in one step.
     *
     * The existing target is not touched until the copy has completed and its size
     * matches the source. A failed copy or publish leaves the existing target intact.
     */
    fun copy(source: File, target: File): Boolean {
        val expectedLength = source.length()
        if (!source.isFile || expectedLength <= 0L) return false

        val temporary = try {
            createTemporary(target)
        } catch (_: Exception) {
            return false
        }
        return try {
            source.inputStream().buffered(BUFFER_SIZE).use { input ->
                temporary.outputStream().buffered(BUFFER_SIZE).use { output ->
                    val copiedLength = input.copyTo(output, BUFFER_SIZE)
                    output.flush()
                    if (copiedLength != expectedLength) {
                        throw IOException(
                            "Copied file size mismatch: expected=$expectedLength actual=$copiedLength",
                        )
                    }
                }
            }
            if (temporary.length() != expectedLength) {
                throw IOException(
                    "Temporary file size mismatch: expected=$expectedLength actual=${temporary.length()}",
                )
            }
            commit(temporary, target)
            true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { if (temporary.exists()) temporary.delete() }
        }
    }

    /** Creates an empty temporary output beside [target]. */
    fun createTemporary(target: File): File {
        val parent = target.absoluteFile.parentFile
            ?: throw IOException("Output path has no parent: ${target.absolutePath}")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Unable to create output directory: ${parent.absolutePath}")
        }
        if (!parent.isDirectory) {
            throw IOException("Output parent is not a directory: ${parent.absolutePath}")
        }
        return File.createTempFile(".bbzq-${target.name}-", ".part", parent)
    }

    /** Publishes a completed temporary file at [target]. */
    fun commit(temporary: File, target: File) {
        if (!temporary.isFile || temporary.length() <= 0L) {
            throw IOException("Temporary output is empty: ${temporary.absolutePath}")
        }

        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            // A non-atomic replacement could destroy an existing successful output if
            // the filesystem fails midway. Leave the target untouched in that case.
            throw IOException("Atomic output publish is not supported", e)
        }
    }
}
