package ca.devdcs.backup.core.retention

import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.core.storage.StoragePaths
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Recursive delete over a [StorageSession], which itself only removes files and empty folders.
 * Exists so retention deletes a job folder the same way on every protocol: depth-first listing,
 * children before parents (bottom-up), paths rebuilt from the parent plus the entry name so a
 * backend that reports odd absolute paths can never redirect the delete outside the folder.
 */
object RemoteTreeDeleter {

    /** Guard against cyclic or absurdly deep listings (symlink loops on some servers). */
    const val MAX_DEPTH = 64

    /**
     * Deletes [root] and everything below it. Returns the number of removed entries (0 when it did
     * not exist). Any [ca.devdcs.backup.core.storage.StorageException] propagates; already removed
     * entries stay removed, and a later run continues where this one stopped.
     */
    suspend fun deleteTree(session: StorageSession, root: String): Int {
        val start = StoragePaths.normalize(root)
        require(start.isNotEmpty()) { "Refusing to delete the destination base" }
        val top = session.stat(start) ?: return 0
        if (!top.isDirectory) {
            session.delete(start)
            return 1
        }
        var removed = 0
        // (path, depth, childrenExpanded)
        val stack = ArrayDeque<Triple<String, Int, Boolean>>()
        stack.addLast(Triple(start, 0, false))
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (dir, depth, expanded) = stack.removeLast()
            if (expanded) {
                session.delete(dir)
                removed++
                continue
            }
            stack.addLast(Triple(dir, depth, true))
            for (entry in session.list(dir)) {
                val name = entry.name
                if (name.isEmpty() || name == "." || name == ".." || name.contains('/')) continue
                val child = StoragePaths.join(dir, name)
                if (entry.isDirectory) {
                    check(depth + 1 <= MAX_DEPTH) { "Folder nesting deeper than $MAX_DEPTH under $start" }
                    stack.addLast(Triple(child, depth + 1, false))
                } else {
                    session.delete(child)
                    removed++
                }
            }
        }
        return removed
    }
}
