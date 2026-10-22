package com.astramaps.app.data.map

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A map pack found on disk.
 *
 * Until the Phase 6 pack manager exists there is no installer, so the map uses
 * the first usable archive it can find. The archive's header is parsed here for
 * two reasons: a pack that cannot be opened must not be handed to the renderer
 * (the failure would be a blank map with no explanation), and the header's
 * centre and zoom are what the camera starts from.
 */
data class LocalPack(
    val file: File,
    val header: PmtilesArchive.Header,
) {
    /**
     * What MapLibre's native file source reads.
     *
     * `Uri.fromFile` is used rather than string concatenation so the path is
     * encoded correctly and the result is `file:///…` with the third slash that
     * a file URL needs.
     */
    val archiveUrl: String get() = "pmtiles://" + Uri.fromFile(file)
}

/**
 * Locates map packs in the two directories a pack can live in before the pack
 * manager exists.
 *
 * `filesDir/packs` is where a downloaded pack will go; `getExternalFilesDir`
 * exists so a development build can be handed a pack with `adb push` without
 * root. Both are app-private, so scoped storage does not get in the way.
 */
class PackLocator(private val context: Context) {

    /** I/O, so callers use this from a background dispatcher. */
    suspend fun find(): LocalPack? = withContext(Dispatchers.IO) { scan() }

    /** Blocking variant, for tests and background threads. */
    fun scan(): LocalPack? =
        candidates()
            .firstNotNullOfOrNull { file ->
                runCatching { PmtilesArchive.open(file) }.fold(
                    onSuccess = { archive -> archive.use { LocalPack(file, it.header) } },
                    onFailure = { error ->
                        Log.w(TAG, "Ignoring unreadable pack ${file.name}: ${error.message}")
                        null
                    },
                )
            }

    /** Every candidate archive, in a deterministic order. */
    fun candidates(): List<File> = buildList {
        addAll(pmtilesIn(File(context.filesDir, DIRECTORY_NAME)))
        context.getExternalFilesDir(null)?.let { external ->
            addAll(pmtilesIn(File(external, DIRECTORY_NAME)))
        }
    }

    private fun pmtilesIn(directory: File): List<File> =
        directory
            .listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            ?.sortedBy { it.name }
            .orEmpty()

    private companion object {
        const val TAG = "PackLocator"
        const val DIRECTORY_NAME = "packs"
        const val EXTENSION = ".pmtiles"
    }
}
