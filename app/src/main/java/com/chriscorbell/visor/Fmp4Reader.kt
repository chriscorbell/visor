package com.chriscorbell.visor

import android.media.MediaFormat
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Reads a live fragmented MP4 stream (go2rtc's /api/stream.mp4): one init
 * segment, then moof+mdat fragments. Hands out MediaCodec-ready formats and
 * samples, with H.264/H.265 converted to Annex B.
 */
class Fmp4Reader(input: InputStream) {
    class Track(val id: Int, val format: MediaFormat, val timescale: Long, internal val nalLength: Int)

    class Sample(val track: Track, val timeUs: Long, val data: ByteArray, val isKey: Boolean)

    private val input = DataInputStream(input.buffered(64 * 1024))
    private val tracks = HashMap<Int, Track>()
    private val pending = ArrayDeque<Sample>()

    /** The tracks, available after the init segment. */
    fun readInit(): Collection<Track> {
        while (true) {
            val (type, body) = nextBox()
            if (type == "moov") {
                parseMoov(body)
                return tracks.values
            }
        }
    }

    /** The next sample in stream order; blocks until one arrives. */
    fun next(): Sample {
        while (pending.isEmpty()) readFragment()
        return pending.removeFirst()
    }

    private fun readFragment() {
        val (type, moof) = nextBox()
        if (type != "moof") return
        val runs = parseMoof(moof)
        val (mdatType, mdat) = nextBox()
        check(mdatType == "mdat") { "expected mdat after moof, got $mdatType" }
        // trun data offsets count from the moof's first byte; mdat's payload
        // starts after both boxes' headers.
        val payloadStart = (moof.limit() + 8) + 8
        for (run in runs) {
            var offset = if (run.dataOffset == 0) 0 else run.dataOffset - payloadStart
            var time = run.baseTime
            for (s in run.samples) {
                val bytes = ByteArray(s.size)
                mdat.position(offset)
                mdat.get(bytes)
                offset += s.size
                val track = run.track
                val data = if (track.nalLength > 0) toAnnexB(bytes, track.nalLength) else bytes
                pending.addLast(Sample(track, (time + s.cto) * 1_000_000 / track.timescale, data, s.isKey))
                time += s.duration
            }
        }
    }

    private fun nextBox(): Pair<String, ByteBuffer> {
        var size = input.readInt().toLong() and 0xffffffffL
        val type = ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII)
        var header = 8
        if (size == 1L) {
            size = input.readLong()
            header = 16
        }
        if (size < header || size > MAX_BOX) throw EOFException("bad box $type of $size bytes")
        val body = ByteArray((size - header).toInt())
        input.readFully(body)
        return type to ByteBuffer.wrap(body)
    }

    // ---- init segment -------------------------------------------------------

    private fun parseMoov(moov: ByteBuffer) {
        for ((type, trak) in children(moov)) if (type == "trak") parseTrak(trak)
    }

    private fun parseTrak(trak: ByteBuffer) {
        val tkhd = child(trak, "tkhd") ?: return
        val version = tkhd.get().toInt()
        tkhd.position(if (version == 1) 20 else 12)
        val id = tkhd.int
        val mdia = child(trak, "mdia") ?: return
        val mdhd = child(mdia, "mdhd") ?: return
        val mdhdVersion = mdhd.get().toInt()
        mdhd.position(if (mdhdVersion == 1) 20 else 12)
        val timescale = mdhd.int.toLong() and 0xffffffffL
        val stsd = child(mdia, "minf")?.let { child(it, "stbl") }?.let { child(it, "stsd") } ?: return
        stsd.position(8) // version, flags, entry count
        val (entry, body) = children(stsd).firstOrNull() ?: return
        val track = when (entry) {
            "avc1", "avc3" -> videoTrack(id, timescale, body, MediaFormat.MIMETYPE_VIDEO_AVC, "avcC")
            "hvc1", "hev1" -> videoTrack(id, timescale, body, MediaFormat.MIMETYPE_VIDEO_HEVC, "hvcC")
            "mp4a" -> audioTrack(id, timescale, body)
            else -> null
        } ?: return
        tracks[id] = track
    }

    private fun videoTrack(id: Int, timescale: Long, entry: ByteBuffer, mime: String, configBox: String): Track? {
        entry.position(24)
        val width = entry.short.toInt() and 0xffff
        val height = entry.short.toInt() and 0xffff
        entry.position(78) // past the VisualSampleEntry fields
        val config = children(entry).firstOrNull { it.first == configBox }?.second ?: return null
        val format = MediaFormat.createVideoFormat(mime, width, height)
        val nalLength: Int
        if (configBox == "avcC") {
            config.position(4)
            nalLength = (config.get().toInt() and 3) + 1
            val sps = ArrayList<ByteArray>()
            repeat(config.get().toInt() and 31) { sps += readParam(config) }
            val pps = ArrayList<ByteArray>()
            repeat(config.get().toInt() and 0xff) { pps += readParam(config) }
            format.setByteBuffer("csd-0", ByteBuffer.wrap(annexB(sps)))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(annexB(pps)))
        } else {
            config.position(21)
            nalLength = (config.get().toInt() and 3) + 1
            val params = ArrayList<ByteArray>()
            repeat(config.get().toInt() and 0xff) {
                config.get() // NAL unit type
                repeat(config.short.toInt() and 0xffff) { params += readParam(config) }
            }
            format.setByteBuffer("csd-0", ByteBuffer.wrap(annexB(params)))
        }
        return Track(id, format, timescale, nalLength)
    }

    private fun audioTrack(id: Int, timescale: Long, entry: ByteBuffer): Track? {
        entry.position(16)
        val channels = entry.short.toInt()
        entry.position(24)
        val sampleRate = (entry.int ushr 16)
        entry.position(28)
        val esds = children(entry).firstOrNull { it.first == "esds" }?.second ?: return null
        val asc = audioSpecificConfig(esds) ?: return null
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(asc))
        return Track(id, format, timescale, 0)
    }

    /** Finds the DecoderSpecificInfo (tag 5) inside an esds descriptor tree. */
    private fun audioSpecificConfig(esds: ByteBuffer): ByteArray? {
        esds.position(4)
        while (esds.remaining() > 2) {
            val tag = esds.get().toInt() and 0xff
            var length = 0
            do {
                val b = esds.get().toInt() and 0xff
                length = (length shl 7) or (b and 0x7f)
            } while (b and 0x80 != 0)
            when (tag) {
                3 -> esds.position(esds.position() + 3) // ES_ID, flags
                4 -> esds.position(esds.position() + 13) // DecoderConfigDescriptor fields
                5 -> return ByteArray(length).also { esds.get(it) }
                else -> esds.position(esds.position() + length)
            }
        }
        return null
    }

    // ---- fragments ---------------------------------------------------------------

    private class RunSample(val duration: Long, val size: Int, val cto: Long, val isKey: Boolean)

    private class Run(val track: Track, val baseTime: Long, val dataOffset: Int, val samples: List<RunSample>)

    private fun parseMoof(moof: ByteBuffer): List<Run> {
        val runs = ArrayList<Run>()
        for ((type, traf) in children(moof)) {
            if (type != "traf") continue
            val tfhd = child(traf, "tfhd") ?: continue
            val tfhdFlags = tfhd.int and 0xffffff
            val track = tracks[tfhd.int] ?: continue
            if (tfhdFlags and 0x1 != 0) tfhd.long // base data offset: go2rtc uses moof-relative offsets
            if (tfhdFlags and 0x2 != 0) tfhd.int
            val defaultDuration = if (tfhdFlags and 0x8 != 0) tfhd.int.toLong() and 0xffffffffL else 0
            val defaultSize = if (tfhdFlags and 0x10 != 0) tfhd.int else 0
            val defaultFlags = if (tfhdFlags and 0x20 != 0) tfhd.int else 0
            var baseTime = 0L
            child(traf, "tfdt")?.let { tfdt ->
                baseTime = if (tfdt.get().toInt() == 1) tfdt.apply { position(4) }.long
                else tfdt.apply { position(4) }.int.toLong() and 0xffffffffL
            }
            for ((runType, trun) in children(traf)) {
                if (runType != "trun") continue
                val flags = trun.int and 0xffffff
                val count = trun.int
                val dataOffset = if (flags and 0x1 != 0) trun.int else 0
                val firstFlags = if (flags and 0x4 != 0) trun.int else null
                val samples = ArrayList<RunSample>(count)
                repeat(count) { i ->
                    val duration = if (flags and 0x100 != 0) trun.int.toLong() and 0xffffffffL else defaultDuration
                    val size = if (flags and 0x200 != 0) trun.int else defaultSize
                    val sampleFlags = if (flags and 0x400 != 0) trun.int else if (i == 0 && firstFlags != null) firstFlags else defaultFlags
                    val cto = if (flags and 0x800 != 0) trun.int.toLong() else 0
                    samples += RunSample(duration, size, cto, sampleFlags and 0x10000 == 0)
                }
                runs += Run(track, baseTime, dataOffset, samples)
                baseTime += samples.sumOf { it.duration }
            }
        }
        return runs
    }

    // ---- helpers -------------------------------------------------------------------

    private fun children(parent: ByteBuffer): List<Pair<String, ByteBuffer>> {
        val out = ArrayList<Pair<String, ByteBuffer>>()
        while (parent.remaining() >= 8) {
            val start = parent.position()
            val size = parent.int
            val type = ByteArray(4).also { parent.get(it) }.toString(Charsets.US_ASCII)
            if (size < 8 || start + size > parent.limit()) break
            val body = parent.duplicate().apply { position(start + 8); limit(start + size) }.slice()
            out += type to body
            parent.position(start + size)
        }
        return out
    }

    private fun child(parent: ByteBuffer, type: String) =
        children(parent.duplicate()).firstOrNull { it.first == type }?.second

    private fun readParam(buffer: ByteBuffer): ByteArray =
        ByteArray(buffer.short.toInt() and 0xffff).also { buffer.get(it) }

    private fun annexB(units: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (unit in units) {
            out.write(START_CODE)
            out.write(unit)
        }
        return out.toByteArray()
    }

    /** Replaces each NAL unit's length prefix with a start code. */
    private fun toAnnexB(sample: ByteArray, lengthSize: Int): ByteArray {
        if (lengthSize == 4) {
            var i = 0
            while (i + 4 <= sample.size) {
                val length = ((sample[i].toInt() and 0xff) shl 24) or ((sample[i + 1].toInt() and 0xff) shl 16) or
                    ((sample[i + 2].toInt() and 0xff) shl 8) or (sample[i + 3].toInt() and 0xff)
                System.arraycopy(START_CODE, 0, sample, i, 4)
                i += 4 + length
            }
            return sample
        }
        val out = java.io.ByteArrayOutputStream(sample.size + 16)
        var i = 0
        while (i + lengthSize <= sample.size) {
            var length = 0
            repeat(lengthSize) { length = (length shl 8) or (sample[i + it].toInt() and 0xff) }
            i += lengthSize
            out.write(START_CODE)
            out.write(sample, i, minOf(length, sample.size - i))
            i += length
        }
        return out.toByteArray()
    }

    private companion object {
        val START_CODE = byteArrayOf(0, 0, 0, 1)
        const val MAX_BOX = 32L * 1024 * 1024
    }
}
