package com.dinoe.clockwise.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupLimitsTest {
    private val limit = BackupRepository.MAX_BACKUP_BYTES

    @Test
    fun documentsUpToTheLimitAreAccepted() {
        assertFalse(backupInputTooLarge(0L))
        assertFalse(backupInputTooLarge(limit - 1))
        assertFalse(backupInputTooLarge(limit))
    }

    @Test
    fun documentsAboveTheLimitAreRejected() {
        assertTrue(backupInputTooLarge(limit + 1))
        assertTrue(backupInputTooLarge(Long.MAX_VALUE))
    }

    @Test
    fun readBoundedBackupTextDecodesTextUpToTheLimit() {
        val text = "{\"schemaVersion\":1,\"events\":[]}"
        val read = readBoundedBackupText(
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
            limitBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
        )
        assertEquals(text, read)
    }

    @Test
    fun readBoundedBackupTextRejectsOneByteMoreThanTheLimit() {
        val text = "0123456789"
        val error = runCatching {
            readBoundedBackupText(
                ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
                limitBytes = 9L,
            )
        }.exceptionOrNull()
        assertTrue(error is BackupException)
        assertEquals(
            "The backup file is larger than ${limit / (1024L * 1024L)} MB",
            error?.message,
        )
    }
}
