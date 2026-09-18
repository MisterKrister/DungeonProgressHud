package dev.krister.dungeonprogresshud

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryRepositoryTest {
    @Test fun `unreadable history survives later automatic saves`() {
        val path = Files.createTempDirectory("dph-history").resolve("runs.json")
        Files.writeString(path, "broken")
        val repository = HistoryRepository(path, String::toInt, Int::toString)
        assertEquals(0, repository.load { 0 })
        assertFalse(repository.save(42))
        assertEquals("broken", Files.readString(path))
        Files.delete(path)
        Files.delete(path.parent)
    }

    @Test fun `successful replacement retains previous history backup`() {
        val path = Files.createTempDirectory("dph-history").resolve("runs.json")
        val repository = HistoryRepository(path, String::toInt, Int::toString)
        assertEquals(0, repository.load { 0 })
        assertTrue(repository.save(10))
        assertTrue(repository.save(20))
        assertEquals("20", Files.readString(path))
        assertEquals("10", Files.readString(path.resolveSibling("runs.json.bak")))
        Files.delete(path.resolveSibling("runs.json.bak"))
        Files.delete(path)
        Files.delete(path.parent)
    }
    @Test fun `explicit recovery validates backup and preserves damaged bytes`() {
        val path = Files.createTempDirectory("dph-recovery").resolve("runs.json")
        Files.writeString(path, "broken")
        Files.writeString(path.resolveSibling("runs.json.bak"), "42")
        val repository = HistoryRepository(path, String::toInt, Int::toString)
        repository.load { 0 }
        assertEquals(42, repository.recoverBackup())
        assertEquals("42", Files.readString(path))
        val archived = Files.list(path.parent).use { paths -> paths.filter { it.fileName.toString().startsWith("runs.json.unreadable-") }.toList() }
        assertEquals("broken", Files.readString(archived.single()))
        Files.delete(archived.single())
        Files.delete(path.resolveSibling("runs.json.bak"))
        Files.delete(path)
        Files.delete(path.parent)
    }

    @Test fun `background writer flushes the most recent snapshot`() {
        val path = Files.createTempDirectory("dph-writer").resolve("runs.json")
        val repository = HistoryRepository(path, String::toInt, Int::toString)
        repository.load { 0 }
        val writer = HistoryWriter(repository)
        repeat(100) { writer.submit(it) }
        writer.flush()
        assertEquals("99", Files.readString(path))
        assertFalse(writer.dirty)
        Files.deleteIfExists(path.resolveSibling("runs.json.bak"))
        Files.delete(path)
        Files.delete(path.parent)
    }
}
