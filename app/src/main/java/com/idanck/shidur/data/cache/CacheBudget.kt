package com.idanck.shidur.data.cache

import android.content.Context
import java.io.File

/**
 * Bounded on-disk cache (FR-SET-15, NFR-PERF-07).
 *
 * Unbounded image and guide caches are a documented failure of players in this class on TV
 * boxes with small internal storage, so the budget is enforced actively: [enforce] evicts
 * least-recently-used files until the tree fits, and is called after every image-heavy
 * session and every guide import rather than only when the user visits Settings.
 */
class CacheBudget(private val context: Context) {

    data class Usage(val imagesBytes: Long, val guideBytes: Long, val otherBytes: Long) {
        val totalBytes: Long get() = imagesBytes + guideBytes + otherBytes
    }

    private val imageDir: File get() = File(context.cacheDir, IMAGE_DIR)
    private val guideDir: File get() = File(context.cacheDir, GUIDE_DIR)

    fun usage(): Usage = Usage(
        imagesBytes = sizeOf(imageDir),
        guideBytes = sizeOf(guideDir),
        otherBytes = (sizeOf(context.cacheDir) - sizeOf(imageDir) - sizeOf(guideDir)).coerceAtLeast(0),
    )

    /** @return bytes reclaimed. */
    fun enforce(budgetMb: Int): Long {
        val budget = budgetMb.coerceAtLeast(MIN_BUDGET_MB) * 1024L * 1024L
        var total = sizeOf(context.cacheDir)
        if (total <= budget) return 0

        val files = collectFiles(context.cacheDir).sortedBy { it.lastModified() }
        var reclaimed = 0L
        for (f in files) {
            if (total <= budget) break
            val size = f.length()
            if (f.delete()) {
                total -= size
                reclaimed += size
            }
        }
        return reclaimed
    }

    fun clearImages(): Long = deleteTree(imageDir)

    fun clearGuideCache(): Long = deleteTree(guideDir)

    fun clearAll(): Long = context.cacheDir.listFiles()?.sumOf { deleteTree(it) } ?: 0L

    private fun collectFiles(root: File): List<File> {
        val out = ArrayList<File>()
        val stack = ArrayDeque<File>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            children.forEach { if (it.isDirectory) stack.add(it) else out.add(it) }
        }
        return out
    }

    private fun sizeOf(root: File): Long =
        if (!root.exists()) 0L else collectFiles(root).sumOf { it.length() }

    private fun deleteTree(root: File): Long {
        if (!root.exists()) return 0L
        var freed = 0L
        collectFiles(root).forEach { f ->
            val size = f.length()
            if (f.delete()) freed += size
        }
        if (root.isDirectory) root.listFiles()?.forEach { if (it.isDirectory) it.deleteRecursively() }
        return freed
    }

    companion object {
        const val IMAGE_DIR = "image_cache"
        const val GUIDE_DIR = "guide_cache"
        const val MIN_BUDGET_MB = 50

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
            bytes >= 1024L * 1024 -> String.format(java.util.Locale.ROOT, "%.0f MB", bytes / 1024.0 / 1024)
            bytes >= 1024L -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
