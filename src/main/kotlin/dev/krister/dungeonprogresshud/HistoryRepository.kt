package dev.krister.dungeonprogresshud

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*

/** A failed load locks writes until the caller explicitly recovers a readable file. */
internal class HistoryRepository<T : Any>(
    private val path: Path,
    private val decode: (String) -> T,
    private val encode: (T) -> String,
) {
    @Volatile
    var error: Exception? = null
        private set
    @Volatile var writable = false
        private set

    fun load(empty: () -> T): T {
        return try {
            val value = if (Files.notExists(path)) empty() else decode(Files.readString(path))
            writable = true
            error = null
            value
        } catch (failure: Exception) {
            writable = false
            error = failure
            empty()
        }
    }

    /** Validate the backup before archiving the damaged original. Never discard that original. */
    @Synchronized
    fun recoverBackup(): T {
        val backup = path.resolveSibling("${path.fileName}.bak")
        val value = decode(Files.readString(backup))
        if (Files.exists(path)) {
            Files.copy(path, path.resolveSibling("${path.fileName}.unreadable-${java.util.UUID.randomUUID()}"))
        }
        Files.copy(backup, path, REPLACE_EXISTING)
        error = null
        writable = true
        return value
    }

    @Synchronized
    fun save(value: T): Boolean {
        if (!writable) return false
        val temp = path.resolveSibling("${path.fileName}.tmp")
        return try {
            val json = encode(value)
            Files.createDirectories(path.parent)
            Files.writeString(temp, json)
            if (Files.exists(path)) {
                // The file may have been edited or damaged since startup. Never back up corruption
                // over the last good backup, or replace it with a new automatic snapshot.
                try {
                    decode(Files.readString(path))
                } catch (failure: Exception) {
                    writable = false
                    throw failure
                }
                Files.copy(path, path.resolveSibling("${path.fileName}.bak"), REPLACE_EXISTING)
            }
            try {
                Files.move(temp, path, REPLACE_EXISTING, ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, REPLACE_EXISTING)
            }
            error = null
            true
        } catch (failure: Exception) {
            error = failure
            false
        }
    }
}

/** Single writer, latest immutable snapshot wins while an earlier write is in progress. */
internal class HistoryWriter<T : Any>(private val repository: HistoryRepository<T>) {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { work ->
        Thread(work, "DPH-History").apply { isDaemon = true }
    }
    private val pending = java.util.concurrent.atomic.AtomicReference<T?>()
    private val scheduled = java.util.concurrent.atomic.AtomicBoolean()
    @Volatile var dirty = false
        private set

    fun submit(snapshot: T) {
        dirty = true
        pending.set(snapshot)
        schedule()
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (true) {
                    val value = pending.getAndSet(null) ?: break
                    dirty = !repository.save(value) || pending.get() != null
                }
            } finally {
                scheduled.set(false)
                if (pending.get() != null) schedule()
            }
        }
    }

    fun flush() {
        // submit() runs on the game thread; shutdown flush is called on that same thread.
        do {
            executor.submit {}.get(10, java.util.concurrent.TimeUnit.SECONDS)
        } while (scheduled.get() || pending.get() != null)
    }
}
