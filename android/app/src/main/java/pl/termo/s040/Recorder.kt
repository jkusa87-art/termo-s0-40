package pl.termo.s040

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface

/** Nagrywanie tego, co widac (obraz + markery + status) do MP4 w Movies/Termo. Wolane z watku UI. */
class Recorder(private val ctx: Context, private val log: (String) -> Unit) {
    companion object { const val W = 1280; const val H = 720 }

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var track = -1
    private var muxStarted = false
    private val info = MediaCodec.BufferInfo()
    var name = ""; private set
    val active get() = codec != null

    fun start(fileName: String): Boolean {
        name = fileName
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$fileName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Termo")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return try {
            val u = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
            uri = u
            val fd = ctx.contentResolver.openFileDescriptor(u, "rw")!!
            pfd = fd
            muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 25)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = c.createInputSurface()
            c.start()
            codec = c
            log("nagrywanie: start $fileName.mp4")
            true
        } catch (e: Exception) {
            log("nagrywanie: blad startu $e"); release(keep = false); false
        }
    }

    fun frame(draw: (Canvas) -> Unit) {
        val s = surface ?: return
        try {
            val c = s.lockHardwareCanvas()
            try { draw(c) } finally { s.unlockCanvasAndPost(c) }
            drain(false)
        } catch (e: Exception) { log("nagrywanie: klatka $e") }
    }

    private fun drain(eos: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        while (true) {
            val i = c.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!eos) return else continue
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = m.addTrack(c.outputFormat); m.start(); muxStarted = true }
                i >= 0 -> {
                    val buf = c.getOutputBuffer(i)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxStarted) m.writeSampleData(track, buf, info)
                    c.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Zwraca true, gdy plik zostal zapisany. */
    fun stop(): Boolean {
        val c = codec ?: return false
        try { c.signalEndOfInputStream(); drain(true) } catch (e: Exception) { log("nagrywanie: stop $e") }
        val ok = muxStarted
        release(keep = ok)
        log(if (ok) "nagrywanie: zapisano Movies/Termo/$name.mp4" else "nagrywanie: brak klatek, plik usuniety")
        return ok
    }

    private fun release(keep: Boolean) {
        runCatching { codec?.stop() }; runCatching { codec?.release() }
        runCatching { surface?.release() }
        if (muxStarted) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        runCatching { pfd?.close() }
        uri?.let { u ->
            if (keep) ctx.contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            else runCatching { ctx.contentResolver.delete(u, null, null) }
        }
        codec = null; surface = null; muxer = null; pfd = null; uri = null; muxStarted = false; track = -1
    }
}
