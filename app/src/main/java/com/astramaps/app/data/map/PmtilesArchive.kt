package com.astramaps.app.data.map

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

// PMTiles v3 header constants (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md).
private const val MAX_ZOOM = 31
private const val PMTILES_VERSION = 3
private const val TILE_TYPE_MVT = 1
private const val COMPRESSION_NONE = 1
private const val COMPRESSION_GZIP = 2
private const val COMPRESSION_BROTLI = 3
private const val COMPRESSION_ZSTD = 4

/** Protobuf tag for `Tile.layers`, field 3, wire type 2. */
private const val MVT_LAYER_TAG: Byte = 0x1A

/**
 * A read-only reader for a local [PMTiles](https://github.com/protomaps/PMTiles)
 * v3 archive.
 *
 * MapLibre Native reads `pmtiles://file://…` itself, so this class is **not**
 * on the rendering path. It exists for two jobs the renderer cannot do:
 *
 *  * prove a downloaded pack is a usable archive *before* it is marked
 *    installed — PMTiles has no checksums of its own, so a truncated file with a
 *    valid-looking header would otherwise be discovered only when the map
 *    failed to draw;
 *  * let an instrumented test pull a real tile out of a real archive on a
 *    device with the radios off, which is what the Phase 2 offline acceptance
 *    harness asserts.
 *
 * The format is small enough that a hand-written reader is cheaper than a
 * dependency: a 127-byte header, gzip-compressed directories of varint-encoded
 * entries, and gzip-compressed MVT payloads.
 *
 * Only the compression schemes planetiler emits are supported — gzip and none.
 * A zstd archive is rejected by name rather than mis-parsed.
 */
class PmtilesArchive private constructor(
    private val file: RandomAccessFile,
    val header: Header,
) : Closeable {

    private val lock = Any()

    /** Parsed root directory, loaded on first use. */
    private val rootEntries: List<Entry> by lazy { readDirectory(header.rootOffset, header.rootLength) }

    /** The TileJSON-ish metadata document, decompressed to UTF-8. */
    fun metadataJson(): String =
        decompress(readBytes(header.metadataOffset, header.metadataLength.toInt()), header.internalCompression)
            .toString(Charsets.UTF_8)

    /**
     * The raw MVT bytes for a tile, already gunzipped, or `null` when the
     * archive has no such tile.
     *
     * Decompressed bytes are what a MVT parser wants; MapLibre asks for the
     * compressed payload itself and never calls this.
     */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        require(z in 0..MAX_ZOOM) { "zoom $z outside 0..$MAX_ZOOM" }
        val side = 1L shl z
        require(x >= 0 && y >= 0 && x < side && y < side) { "tile $z/$x/$y outside the z$z grid" }

        val wanted = SlippyTile.tileId(z, x, y)
        var directory = rootEntries
        while (true) {
            val entry = find(directory, wanted) ?: return null
            if (entry.runLength > 0) {
                if (entry.offset < 0 || entry.length == 0) return null
                val raw = readBytes(header.dataOffset + entry.offset, entry.length)
                return decompress(raw, header.tileCompression)
            }
            // runLength == 0 marks a leaf directory pointer; the entry covers
            // every tile id up to the next entry.
            directory = readDirectory(header.leafOffset + entry.offset, entry.length.toLong())
        }
    }

    /**
     * Every tile entry in the archive, with leaf directories flattened.
     *
     * Used to check the archive is self-consistent without knowing its tiles in
     * advance. Entries with `runLength > 1` stand for a run of identical tiles.
     */
    fun entries(): List<Entry> {
        val all = ArrayList<Entry>(header.tileEntries.toInt())
        for (entry in rootEntries) {
            if (entry.runLength > 0) {
                all += entry
            } else {
                all += readDirectory(header.leafOffset + entry.offset, entry.length.toLong())
            }
        }
        return all
    }

    /**
     * Structural problems with the archive, empty when it is healthy.
     *
     * Deliberately checks the things a download can plausibly break: a short
     * file, a corrupt directory, counts that disagree, and a tile that does not
     * decompress into something MVT-shaped.
     */
    fun check(): List<String> {
        val problems = mutableListOf<String>()
        if (header.version != PMTILES_VERSION) {
            problems += "PMTiles version ${header.version}, expected $PMTILES_VERSION"
        }
        if (header.tileType != TILE_TYPE_MVT) {
            problems += "tile type ${header.tileType}, expected $TILE_TYPE_MVT (MVT)"
        }
        val described = maxOf(
            header.metadataOffset + header.metadataLength,
            header.leafOffset + header.leafLength,
            header.dataOffset + header.dataLength,
        )
        if (file.length() < described) {
            problems += "file is ${file.length()} bytes, header describes $described"
        }

        val entries = try {
            entries()
        } catch (e: Exception) {
            return problems + "directory is unreadable: ${e.message}"
        }
        if (entries.size.toLong() != header.tileEntries) {
            problems += "directory lists ${entries.size} entries, header says ${header.tileEntries}"
        }
        if (entries.isEmpty()) {
            problems += "archive contains no tiles"
            return problems
        }
        if (entries.zipWithNext().any { (a, b) -> b.tileId <= a.tileId }) {
            problems += "directory is not sorted by tile id"
        }

        // Every entry must decode to a tile inside the declared grid, and the
        // last one must decompress into something that starts like a MVT tile.
        for (entry in entries) {
            val (z, x, y) = try {
                SlippyTile.fromTileId(entry.tileId)
            } catch (e: Exception) {
                problems += "tile id ${entry.tileId} does not decode: ${e.message}"
                break
            }
            if (z !in header.minZoom..header.maxZoom) {
                problems += "tile id ${entry.tileId} decodes to zoom $z, outside " +
                    "${header.minZoom}..${header.maxZoom}"
                break
            }
            if (x.toLong() >= (1L shl z) || y.toLong() >= (1L shl z)) {
                problems += "tile id ${entry.tileId} decodes to out-of-grid $z/$x/$y"
                break
            }
        }

        val last = entries.last()
        if (last.runLength == 1L) {
            val (z, x, y) = SlippyTile.fromTileId(last.tileId)
            val bytes = tile(z, x, y)
            when {
                bytes == null -> problems += "tile $z/$x/$y is listed but has no data"
                bytes.isEmpty() -> problems += "tile $z/$x/$y decompressed to nothing"
                bytes[0] != MVT_LAYER_TAG ->
                    problems += "tile $z/$x/$y does not start with a MVT layer field (0x1a)"
            }
        }
        return problems
    }

    override fun close() {
        file.close()
    }

    // ------------------------------------------------------------- internals

    private fun find(entries: List<Entry>, tileId: Long): Entry? {
        // Last entry whose first tile id is <= the wanted id.
        var low = 0
        var high = entries.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (entries[mid].tileId <= tileId) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (found < 0) return null
        val entry = entries[found]
        // A run of length N covers ids [tileId, tileId + N). A leaf pointer
        // covers everything until the next entry, so its range is unbounded.
        return if (entry.runLength > 0 && tileId >= entry.tileId + entry.runLength) null else entry
    }

    private fun readDirectory(offset: Long, length: Long): List<Entry> {
        if (length <= 0) return emptyList()
        val bytes = decompress(readBytes(offset, length.toInt()), header.internalCompression)

        // v3 stores a directory column-wise, not row-wise: a count, then every
        // tile-id delta, then every run length, then every length, then every
        // offset. Parsing it row-wise silently produces plausible garbage, so
        // this mirrors the reference decoder rather than the prose in the spec.
        val cursor = Cursor(bytes)
        val count = cursor.varint().toInt()
        check(count >= 0) { "negative directory entry count $count" }

        val ids = LongArray(count)
        var lastId = 0L
        for (i in 0 until count) {
            lastId += cursor.varint()
            ids[i] = lastId
        }
        val runLengths = LongArray(count) { cursor.varint() }
        val lengths = IntArray(count) { cursor.varint().toInt() }

        val offsets = LongArray(count)
        for (i in 0 until count) {
            val raw = cursor.varint()
            offsets[i] = when {
                // 0 means "contiguous with the previous entry".
                i > 0 && raw == 0L -> offsets[i - 1] + lengths[i - 1]
                // Stored as offset + 1; 0 (only possible for the first entry)
                // means this tile has no data.
                else -> raw - 1
            }
        }

        return List(count) { Entry(ids[it], runLengths[it], lengths[it], offsets[it]) }
    }

    private fun readBytes(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0) { "bad read offset=$offset length=$length" }
        val out = ByteArray(length)
        synchronized(lock) {
            file.seek(offset)
            file.readFully(out)
        }
        return out
    }

    private fun decompress(bytes: ByteArray, compression: Int): ByteArray = when (compression) {
        COMPRESSION_NONE -> bytes
        COMPRESSION_GZIP -> GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        COMPRESSION_BROTLI -> throw UnsupportedOperationException(
            "brotli-compressed PMTiles are not supported; planetiler writes gzip",
        )
        COMPRESSION_ZSTD -> throw UnsupportedOperationException(
            "zstd-compressed PMTiles are not supported; planetiler writes gzip",
        )
        else -> throw IllegalStateException("unknown PMTiles compression id $compression")
    }

    data class Entry(
        val tileId: Long,
        val runLength: Long,
        val length: Int,
        /** Offset into the data (or leaf directory) section; -1 for no data. */
        val offset: Long,
    )

    data class Bounds(
        val minLon: Double,
        val minLat: Double,
        val maxLon: Double,
        val maxLat: Double,
    )

    data class Header(
        val version: Int,
        val rootOffset: Long,
        val rootLength: Long,
        val metadataOffset: Long,
        val metadataLength: Long,
        val leafOffset: Long,
        val leafLength: Long,
        val dataOffset: Long,
        val dataLength: Long,
        val addressedTiles: Long,
        val tileEntries: Long,
        val tileContents: Long,
        val clustered: Boolean,
        val internalCompression: Int,
        val tileCompression: Int,
        val tileType: Int,
        val minZoom: Int,
        val maxZoom: Int,
        val bounds: Bounds,
        val centerZoom: Int,
        val centerLon: Double,
        val centerLat: Double,
    ) {
        val internalCompressionName: String
            get() = when (internalCompression) {
                COMPRESSION_NONE -> "none"
                COMPRESSION_GZIP -> "gzip"
                COMPRESSION_BROTLI -> "brotli"
                COMPRESSION_ZSTD -> "zstd"
                else -> "unknown($internalCompression)"
            }

        val tileCompressionName: String
            get() = when (tileCompression) {
                COMPRESSION_NONE -> "none"
                COMPRESSION_GZIP -> "gzip"
                COMPRESSION_BROTLI -> "brotli"
                COMPRESSION_ZSTD -> "zstd"
                else -> "unknown($tileCompression)"
            }
    }

    /** Little-endian varint reader over a decompressed directory. */
    private class Cursor(private val bytes: ByteArray) {
        private var position = 0

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                check(position < bytes.size) { "truncated varint at byte $position" }
                val byte = bytes[position++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                check(shift <= 63) { "varint longer than 64 bits" }
            }
        }
    }

    companion object {
        private const val HEADER_BYTES = 127

        fun open(path: File): PmtilesArchive {
            require(path.isFile) { "no such PMTiles archive: $path" }
            val file = RandomAccessFile(path, "r")
            try {
                return PmtilesArchive(file, readHeader(file))
            } catch (e: Exception) {
                file.close()
                throw e
            }
        }

        private fun readHeader(file: RandomAccessFile): Header {
            check(file.length() >= HEADER_BYTES) { "file is shorter than a PMTiles header" }
            val bytes = ByteArray(HEADER_BYTES).also { file.seek(0); file.readFully(it) }
            val magic = bytes.copyOfRange(0, 7).toString(Charsets.US_ASCII)
            check(magic == "PMTiles") { "not a PMTiles archive (magic '$magic')" }

            val buffer = java.nio.ByteBuffer.wrap(bytes, 8, HEADER_BYTES - 8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)

            return Header(
                version = bytes[7].toInt() and 0xFF,
                rootOffset = buffer.long,
                rootLength = buffer.long,
                metadataOffset = buffer.long,
                metadataLength = buffer.long,
                leafOffset = buffer.long,
                leafLength = buffer.long,
                dataOffset = buffer.long,
                dataLength = buffer.long,
                addressedTiles = buffer.long,
                tileEntries = buffer.long,
                tileContents = buffer.long,
                clustered = buffer.get().toInt() != 0,
                internalCompression = buffer.get().toInt() and 0xFF,
                tileCompression = buffer.get().toInt() and 0xFF,
                tileType = buffer.get().toInt() and 0xFF,
                minZoom = buffer.get().toInt() and 0xFF,
                maxZoom = buffer.get().toInt() and 0xFF,
                bounds = Bounds(
                    minLon = buffer.int / 1e7,
                    minLat = buffer.int / 1e7,
                    maxLon = buffer.int / 1e7,
                    maxLat = buffer.int / 1e7,
                ),
                centerZoom = buffer.get().toInt() and 0xFF,
                centerLon = buffer.int / 1e7,
                centerLat = buffer.int / 1e7,
            )
        }
    }
}

/**
 * Web-Mercator XYZ tiles and the PMTiles tile-id ordering.
 *
 * PMTiles numbers tiles along a Hilbert curve within each zoom level, with
 * zoom 0 first: `tileId = (4^z - 1) / 3 + hilbert(z, x, y)`. The two functions
 * here are inverses; the instrumented test asserts that property over every
 * entry in a real archive, which is what catches a transcription error in the
 * curve arithmetic.
 */
object SlippyTile {

    fun tileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..31) { "zoom $z outside 0..31" }
        val side = 1L shl z
        require(x >= 0 && y >= 0 && x < side && y < side) {
            "tile $z/$x/$y outside the z$z grid"
        }

        var tx = x.toLong()
        var ty = y.toLong()
        var distance = 0L
        var step = side / 2
        while (step > 0) {
            val rx = if (tx and step != 0L) 1L else 0L
            val ry = if (ty and step != 0L) 1L else 0L
            distance += step * step * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) {
                    tx = side - 1 - tx
                    ty = side - 1 - ty
                }
                val swap = tx
                tx = ty
                ty = swap
            }
            step /= 2
        }
        return tilesBelow(z) + distance
    }

    /** Inverse of [tileId]. */
    fun fromTileId(tileId: Long): Triple<Int, Int, Int> {
        require(tileId >= 0) { "negative tile id $tileId" }
        var z = 0
        while (z < 31 && tilesBelow(z + 1) <= tileId) z++

        var remaining = tileId - tilesBelow(z)
        val side = 1L shl z
        var x = 0L
        var y = 0L
        var step = 1L
        while (step < side) {
            val rx = remaining / 2 and 1
            val ry = (remaining xor rx) and 1
            if (ry == 0L) {
                if (rx == 1L) {
                    x = step - 1 - x
                    y = step - 1 - y
                }
                val swap = x
                x = y
                y = swap
            }
            x += step * rx
            y += step * ry
            remaining /= 4
            step *= 2
        }
        return Triple(z, x.toInt(), y.toInt())
    }

    /** Number of tiles in every zoom level below [z]. */
    fun tilesBelow(z: Int): Long = ((1L shl (2 * z)) - 1) / 3

    fun lonToX(lon: Double, z: Int): Int {
        val side = 1L shl z
        return ((lon + 180.0) / 360.0 * side).toInt().coerceIn(0, (side - 1).toInt())
    }

    fun latToY(lat: Double, z: Int): Int {
        val radians = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        val side = (1L shl z).toDouble()
        val y = (1.0 - Math.log(Math.tan(radians) + 1.0 / Math.cos(radians)) / Math.PI) / 2.0 * side
        return y.toInt().coerceIn(0, (side - 1).toInt())
    }

    fun xToLon(x: Int, z: Int): Double = x.toDouble() / (1L shl z) * 360.0 - 180.0

    fun yToLat(y: Int, z: Int): Double {
        val n = Math.PI - 2.0 * Math.PI * y / (1L shl z)
        return Math.toDegrees(Math.atan(Math.sinh(n)))
    }
}
