package com.cwcontest

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * CWEngine
 *
 * Drives CW output through:
 *   1) the USB serial RTS/DTR line (hardware key)
 *   2) an AudioTrack sidetone, gated by the key state
 *
 * Timing follows the PARIS standard: dot = 1200 / WPM ms, dash = 3 dots,
 * 1 dot between elements, 3 dots between characters, 7 dots between words.
 * [MorseCode.encode] emits those gaps explicitly, so the transmit loop only
 * converts elements into key-down/key-up windows of the exact length. Every
 * window is scheduled against an absolute nanosecond deadline, which keeps the
 * keying grid from drifting at high WPM (USB writes are not free).
 *
 * Besides message sending the engine contains a paddle keyer: straight key,
 * Iambic A and Iambic B, driven by [setDotPaddle]/[setDashPaddle]. A message
 * from the queue always takes precedence over paddle input.
 */
class CWEngine(private var settings: AppSettings) {

    companion object {
        private const val TAG = "CWEngine"
        private const val SAMPLE_RATE = 44100
        private const val TONE_CHUNK_MS = 20
        private const val BUSY_WAIT_NS = 800_000L        // final ~0.8 ms spin
        private const val PADDLE_HANDOVER_MS = 3000L
    }

    interface Listener {
        fun onKeyDown()
        fun onKeyUp()
        fun onMessageStart(text: String)
        fun onMessageComplete()
        fun onAborted()
    }

    var listener:   Listener?         = null
    var usbManager: USBSerialManager? = null

    // ── Internal state ────────────────────────────────────────────────────────
    private val isSending      = AtomicBoolean(false)
    private val abortRequested = AtomicBoolean(false)
    private val sendQueue      = LinkedBlockingQueue<String>()

    private var workerThread: Thread? = null
    private var toneThread:   Thread? = null
    private var paddleThread: Thread? = null

    private val paddleRunning = AtomicBoolean(false)
    private val paddleStop    = AtomicBoolean(false)

    @Volatile private var currentWpm = settings.wpm.coerceIn(5, 60)
    @Volatile private var sidetoneOn = settings.sidetoneEnabled
    @Volatile private var keyState   = false    // true = key closed

    @Volatile private var dotPaddle  = false
    @Volatile private var dashPaddle = false

    private var audioTrack: AudioTrack? = null
    private var sineBuffer: ShortArray? = null
    private var chunkSamples = SAMPLE_RATE * TONE_CHUNK_MS / 1000

    // ── Public API ────────────────────────────────────────────────────────────

    fun start() {
        if (workerThread?.isAlive == true) return
        abortRequested.set(false)
        buildSineBuffer()
        startAudioTrack()
        startToneThread()
        workerThread = Thread(::workerLoop, "CWWorker").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.d(TAG, "CW engine started")
    }

    fun stop() {
        abortRequested.set(true)
        paddleStop.set(true)
        sendQueue.clear()
        workerThread?.interrupt()
        toneThread?.interrupt()
        workerThread = null
        toneThread   = null
        releaseAudio()
        forceKeyUp()
        Log.d(TAG, "CW engine stopped")
    }

    fun setWpm(wpm: Int) { currentWpm = wpm.coerceIn(5, 60) }
    fun setSidetone(on: Boolean) { sidetoneOn = on }
    fun wpm() = currentWpm

    /** Queue [text] for transmission; aborts any previous abort state */
    fun send(text: String) {
        if (text.isBlank()) return
        abortRequested.set(false)
        sendQueue.offer(text.uppercase())
    }

    /** Stop sending immediately and release the key */
    fun abort() {
        val wasBusy = isBusy() || paddleRunning.get()
        sendQueue.clear()
        abortRequested.set(true)
        paddleStop.set(true)
        forceKeyUp()
        if (wasBusy) listener?.onAborted()
    }

    fun isBusy() = isSending.get() || sendQueue.isNotEmpty() || paddleRunning.get()

    /** Make sure the key line sits at its idle level (call after opening the port) */
    fun setIdle() = forceKeyUp()

    fun applySettings(s: AppSettings) {
        val toneChanged = s.sidetoneFreqHz != settings.sidetoneFreqHz ||
                          s.sidetoneVolume  != settings.sidetoneVolume
        currentWpm = s.wpm.coerceIn(5, 60)
        sidetoneOn = s.sidetoneEnabled
        settings   = s
        if (toneChanged) {
            sineBuffer = null
            buildSineBuffer()
        }
    }

    // ── Paddle input ──────────────────────────────────────────────────────────

    fun setDotPaddle(pressed: Boolean) {
        if (pressed) abortRequested.set(false)   // paddles re-arm after an ABORT
        dotPaddle = pressed
        if (pressed) startPaddleIfIdle()
    }

    fun setDashPaddle(pressed: Boolean) {
        if (pressed) abortRequested.set(false)
        dashPaddle = pressed
        if (pressed) startPaddleIfIdle()
    }

    /** Manually force the paddles released and let the keyer finish its element */
    fun releasePaddles() {
        dotPaddle  = false
        dashPaddle = false
    }

    fun isPaddleActive() = dotPaddle || dashPaddle || paddleRunning.get()

    fun keyerMode(): KeyerMode = settings.keyerMode

    // ── Worker thread (message queue) ─────────────────────────────────────────

    private fun workerLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val text = sendQueue.take()
                if (abortRequested.get()) continue      // aborted before we got here
                isSending.set(true)

                // Let a paddle that is currently being keyed finish, so the two
                // never drive the key line at the same time.
                var waited = 0L
                while (paddleRunning.get() && waited < PADDLE_HANDOVER_MS) {
                    Thread.sleep(5); waited += 5
                }
                listener?.onMessageStart(text)
                transmit(text)
                isSending.set(false)
                forceKeyUp()
                if (!abortRequested.get()) listener?.onMessageComplete()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); break
            } catch (e: Exception) {
                Log.e(TAG, "Worker error", e)
                isSending.set(false); forceKeyUp()
            }
        }
    }

    private fun dotNs(): Long = 1_200_000_000L / currentWpm.coerceIn(5, 60)

    private fun transmit(text: String) {
        val dot = dotNs()
        var t = System.nanoTime()

        for (element in MorseCode.encode(text)) {
            if (abortRequested.get()) return
            when (element) {
                is MorseCode.Element.DotOn  -> t = keyElement(t, dot)
                is MorseCode.Element.DashOn -> t = keyElement(t, dot * 3)
                is MorseCode.Element.Silence -> {
                    t += dot * element.units
                    waitUntil(t)
                }
            }
        }

        val tail = dot * settings.tailGapUnits.coerceIn(0, 10)
        if (tail > 0) waitUntil(t + tail)
    }

    /**
     * Key one element of [onNs] starting at (or after) [nominalStart].
     *
     * The element is never shortened when the USB control transfer that flips
     * RTS/DTR takes longer than expected, which is what keeps the dot/dash
     * ratio intact for the receiving station; the following silence absorbs
     * the small drift. Returns the absolute end of the element.
     */
    private fun keyElement(nominalStart: Long, onNs: Long): Long {
        keyDown()
        val start = System.nanoTime()
        val end   = max(nominalStart + onNs, start + onNs)
        waitUntil(end)
        keyUp()
        return end
    }

    // ── Key control ───────────────────────────────────────────────────────────

    private fun keyDown() {
        keyState = true
        val active = !settings.invertLogic
        if (settings.useRTS) usbManager?.setRTS(active)
        if (settings.useDTR) usbManager?.setDTR(active)
        listener?.onKeyDown()
    }

    private fun keyUp() {
        keyState = false
        val idle = settings.invertLogic
        if (settings.useRTS) usbManager?.setRTS(idle)
        if (settings.useDTR) usbManager?.setDTR(idle)
        listener?.onKeyUp()
    }

    private fun forceKeyUp() {
        keyState = false
        if (settings.useRTS) usbManager?.setRTS(settings.invertLogic)
        if (settings.useDTR) usbManager?.setDTR(settings.invertLogic)
        listener?.onKeyUp()
    }

    // ── Precision timing ──────────────────────────────────────────────────────

    /** Sleep until [targetNs] (absolute), spinning the last fraction of a ms */
    private fun waitUntil(targetNs: Long) {
        while (!abortRequested.get()) {
            val remain = targetNs - System.nanoTime()
            if (remain <= 0) return
            if (remain > BUSY_WAIT_NS) {
                try {
                    Thread.sleep((remain - BUSY_WAIT_NS) / 1_000_000L)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt(); return
                }
            } else {
                while (System.nanoTime() < targetNs && !abortRequested.get()) { /* spin */ }
                return
            }
        }
    }

    private fun sleepMs(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ── Paddle keyer ──────────────────────────────────────────────────────────

    private fun startPaddleIfIdle() {
        if (paddleRunning.get()) return
        if (isSending.get()) return                 // a macro owns the key
        if (paddleThread?.isAlive == true) return
        paddleStop.set(false)
        paddleRunning.set(true)
        paddleThread = Thread({
            try {
                when (settings.keyerMode) {
                    KeyerMode.STRAIGHT -> straightKeyLoop()
                    KeyerMode.IAMBIC_A, KeyerMode.IAMBIC_B -> iambicLoop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Paddle error", e)
            } finally {
                forceKeyUp()
                paddleRunning.set(false)
            }
        }, "CWPaddle").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Straight key: key follows the paddle while it is held down */
    private fun straightKeyLoop() {
        var down = false
        while (!paddleStop.get() && !abortRequested.get()) {
            val pressed = dotPaddle || dashPaddle
            if (pressed) {
                if (!down) { keyDown(); down = true }
                sleepMs(1)
            } else {
                break
            }
        }
        if (down) keyUp()
    }

    /**
     * Iambic A/B keyer.
     *
     *  - one paddle closed → that element
     *  - both paddles squeezed → alternate dot/dash (A and B)
     *  - Iambic B additionally remembers an opposite paddle that was closed
     *    *during* the current element and sends one more element for it.
     */
    private fun iambicLoop() {
        val dot = dotNs()
        var lastWasDash = false
        var pendingIsDash: Boolean? = null

        while (!abortRequested.get() && !paddleStop.get()) {
            val dotDown  = dotPaddle
            val dashDown = dashPaddle

            val nextIsDash: Boolean? = when {
                dotDown && dashDown -> !lastWasDash          // squeeze → alternate
                dotDown            -> false
                dashDown           -> true
                else               -> pendingIsDash
            }
            pendingIsDash = null
            if (nextIsDash == null) break

            keyDown()
            val end = System.nanoTime() + if (nextIsDash) dot * 3 else dot
            waitUntil(end)
            keyUp()
            lastWasDash = nextIsDash

            // Iambic B: latch the opposite paddle if it is closed during this element
            if (settings.keyerMode == KeyerMode.IAMBIC_B) {
                val otherDown = if (nextIsDash) dotPaddle else dashPaddle
                if (otherDown) pendingIsDash = !nextIsDash
            }

            if (abortRequested.get() || paddleStop.get()) break
            waitUntil(end + dot)                          // one unit between elements

            if (!dotPaddle && !dashPaddle && pendingIsDash == null) break
        }
        forceKeyUp()
    }

    // ── Sidetone ──────────────────────────────────────────────────────────────

    private fun buildSineBuffer() {
        if (sineBuffer != null) return
        val freq = settings.sidetoneFreqHz.coerceIn(300, 1200).toDouble()
        val vol  = (settings.sidetoneVolume.coerceIn(0f, 1f) * Short.MAX_VALUE).toInt()
        sineBuffer = ShortArray(chunkSamples) { i ->
            (vol * sin(2.0 * PI * freq * i / SAMPLE_RATE)).toInt().toShort()
        }
    }

    private fun startAudioTrack() {
        releaseAudio()
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val wantBytes = max(minBuf, chunkSamples * 2 * 3)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(wantBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack init failed", e)
            audioTrack = null
        }
    }

    /**
     * Tone thread: keeps AudioTrack fed with short chunks of sine or silence,
     * selected by the key state. Short chunks keep the sidetone close to the
     * RF keying instead of trailing the audio buffer.
     */
    private fun startToneThread() {
        toneThread = Thread({
            val silence = ShortArray(chunkSamples)
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val track = audioTrack
                    if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                        val buf = if (sidetoneOn && keyState) sineBuffer ?: silence else silence
                        track.write(buf, 0, buf.size)
                    } else {
                        Thread.sleep(10)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt(); break
                } catch (e: Exception) {
                    Log.w(TAG, "ToneThread: ${e.message}")
                    sleepMs(10)
                }
            }
        }, "CWTone").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    private fun releaseAudio() {
        try { audioTrack?.pause(); audioTrack?.flush(); audioTrack?.release() }
        catch (e: Exception) { /* ignore */ }
        audioTrack = null
    }
}
