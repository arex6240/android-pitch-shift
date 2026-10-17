package com.realtime.pitchshifter

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class AudioCaptureService : Service() {

    interface MetricsListener {
        fun onMetricsUpdated(
            isCapturing: Boolean,
            engineRunning: Boolean,
            signalPercent: Int,
            totalSamples: Long,
            processedFrames: Long,
            errorMessage: String?
        )
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_UPDATE_PITCH = "action_update_pitch"
        const val ACTION_UPDATE_GAIN = "action_update_gain"
        const val EXTRA_PITCH = "extra_pitch"
        const val EXTRA_GAIN = "extra_gain"

        private const val TAG = "AudioCaptureService"
        private const val CHANNEL_ID = "pitch_shifter_channel"
        private const val NOTIFICATION_ID = 1
        private const val CHANNELS = 2

        @Volatile
        var currentInstance: AudioCaptureService? = null
            private set

        @Volatile
        var metricsListener: MetricsListener? = null

        // In-memory fallback for MediaProjection token across IPC
        @Volatile
        var capturedResultCode: Int = Activity.RESULT_CANCELED

        @Volatile
        var capturedData: Intent? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isCapturing = false
    private var captureThread: Thread? = null

    @Volatile
    private var currentPitch: Float = 4.0f // Default +4 semitones for unmistakable pitch shift

    @Volatile
    private var currentGain: Float = 1.5f

    private var totalCapturedSamples: Long = 0
    private var lastErrorMessage: String? = null

    // JNI methods
    private external fun startNativeAudioEngine(sampleRate: Int, channels: Int): Boolean
    private external fun setNativePitch(semitones: Float)
    private external fun setNativeGain(gain: Float)
    private external fun getNativeProcessedFrames(): Long
    private external fun isNativeEngineRunning(): Boolean
    private external fun processAudioBuffer(buffer: ShortArray, size: Int)
    private external fun stopNativeAudioEngine()

    init {
        System.loadLibrary("pitchshifter_engine")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_PITCH) {
            val pitch = intent.getFloatExtra(EXTRA_PITCH, currentPitch)
            setPitch(pitch)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_UPDATE_GAIN) {
            val gain = intent.getFloatExtra(EXTRA_GAIN, currentGain)
            setGain(gain)
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // In Android, Activity.RESULT_OK is -1, and RESULT_CANCELED is 0.
        // Retrieve result code from intent or fallback to in-memory static token
        val resultCode = if (intent?.hasExtra(EXTRA_RESULT_CODE) == true) {
            intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        } else {
            capturedResultCode
        }

        val data = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } catch (e: Exception) {
                null
            }
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }) ?: capturedData

        // Validation: must be Activity.RESULT_OK (-1) and data intent must be present
        if (resultCode != Activity.RESULT_OK || data == null) {
            lastErrorMessage = "Screen capture permission not granted (resultCode=$resultCode, data=${if (data != null) "valid" else "null"})."
            Log.e(TAG, lastErrorMessage!!)
            notifyMetrics(0)
            stopSelf()
            return START_NOT_STICKY
        }

        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            lastErrorMessage = "Failed to obtain MediaProjection. Permission was denied or revoked by system."
            Log.e(TAG, lastErrorMessage!!)
            notifyMetrics(0)
            stopSelf()
            return START_NOT_STICKY
        }

        // Required on Android 14+ before using MediaProjection
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by system")
                lastErrorMessage = "MediaProjection stopped by system."
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        startAudioCapture()
        return START_NOT_STICKY
    }

    fun setPitch(semitones: Float) {
        currentPitch = semitones
        setNativePitch(semitones)
        Log.i(TAG, "Pitch updated to $semitones semitones")
    }

    fun setGain(gain: Float) {
        currentGain = gain
        setNativeGain(gain)
        Log.i(TAG, "Gain updated to ${gain}x")
    }

    private fun startAudioCapture() {
        try {
            // Exclude our own UID so our Oboe playback doesn't get captured back (prevents feedback loop!)
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid())
                .build()

            var chosenSampleRate = 48000
            var record: AudioRecord? = null

            // Try 48kHz first, fallback to 44.1kHz if device prefers it
            for (rate in intArrayOf(48000, 44100)) {
                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()

                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = if (minBuf > 0) minBuf * 4 else 8192

                try {
                    val candidate = AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(bufferSize)
                        .build()

                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        record = candidate
                        chosenSampleRate = rate
                        Log.i(TAG, "AudioRecord initialized successfully at $rate Hz")
                        break
                    } else {
                        candidate.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to initialize AudioRecord at $rate Hz", e)
                }
            }

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                lastErrorMessage = "AudioRecord failed to initialize (Hardware capture unsupported or blocked by system)."
                Log.e(TAG, lastErrorMessage!!)
                notifyMetrics(0)
                stopSelf()
                return
            }
            audioRecord = record

            // Start native engine
            val engineStarted = startNativeAudioEngine(chosenSampleRate, CHANNELS)
            if (!engineStarted) {
                lastErrorMessage = "Native audio output stream failed to start."
                Log.e(TAG, lastErrorMessage!!)
                notifyMetrics(0)
                stopSelf()
                return
            }

            // Apply initial pitch & gain
            setNativePitch(currentPitch)
            setNativeGain(currentGain)

            // Start capture
            audioRecord?.startRecording()
            isCapturing = true
            totalCapturedSamples = 0
            lastErrorMessage = null

            val actualBufferSize = AudioRecord.getMinBufferSize(
                chosenSampleRate,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).let { if (it > 0) it * 4 else 8192 }

            captureThread = Thread({
                val buffer = ShortArray(actualBufferSize / 2)
                var lastUiUpdate = System.currentTimeMillis()
                var sumSquares = 0.0
                var sampleCountInInterval = 0

                while (isCapturing) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        totalCapturedSamples += read
                        processAudioBuffer(buffer, read)

                        // Calculate RMS for visual signal meter
                        for (i in 0 until read) {
                            val sample = buffer[i].toDouble() / 32768.0
                            sumSquares += sample * sample
                        }
                        sampleCountInInterval += read

                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate >= 100) {
                            val rms = if (sampleCountInInterval > 0) {
                                sqrt(sumSquares / sampleCountInInterval)
                            } else 0.0

                            // Convert RMS to percentage (0..100)
                            val percent = (rms * 250).toInt().coerceIn(0, 100)
                            notifyMetrics(percent)

                            sumSquares = 0.0
                            sampleCountInInterval = 0
                            lastUiUpdate = now
                        }
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord read returned error code: $read")
                    }
                }
            }, "AudioCaptureThread")
            captureThread?.start()

            Log.i(TAG, "Audio capture and pitch shifter pipeline running successfully at $chosenSampleRate Hz")
            notifyMetrics(0)
        } catch (e: Exception) {
            lastErrorMessage = "Exception starting capture: ${e.message}"
            Log.e(TAG, lastErrorMessage!!, e)
            notifyMetrics(0)
            stopSelf()
        }
    }

    private fun notifyMetrics(signalPercent: Int) {
        val listener = metricsListener ?: return
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            listener.onMetricsUpdated(
                isCapturing = isCapturing,
                engineRunning = if (isCapturing) isNativeEngineRunning() else false,
                signalPercent = signalPercent,
                totalSamples = totalCapturedSamples,
                processedFrames = if (isCapturing) getNativeProcessedFrames() else 0,
                errorMessage = lastErrorMessage
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentInstance = null
        isCapturing = false

        capturedResultCode = Activity.RESULT_CANCELED
        capturedData = null

        captureThread?.join(1500)
        captureThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null

        stopNativeAudioEngine()

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaProjection", e)
        }
        mediaProjection = null

        notifyMetrics(0)
        Log.i(TAG, "Audio capture service destroyed")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Audio capture notification channel"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pitch Shifter Active")
            .setContentText("Capturing and pitch-shifting system audio...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
