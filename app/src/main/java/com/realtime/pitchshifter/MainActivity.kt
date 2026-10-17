package com.realtime.pitchshifter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), AudioCaptureService.MetricsListener {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvEngineInfo: TextView
    private lateinit var pbAudioLevel: ProgressBar
    private lateinit var tvStats: TextView
    private lateinit var tvPitchValue: TextView
    private lateinit var sbPitch: SeekBar
    private lateinit var tvGainValue: TextView
    private lateinit var sbGain: SeekBar

    private var currentPitchSemitones = 4.0f
    private var currentGain = 1.5f

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
            if (audioGranted) {
                launchScreenCapture()
            } else {
                Toast.makeText(this, "RECORD_AUDIO permission is required for pitch shifting", Toast.LENGTH_LONG).show()
            }
        }

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                startCaptureService(result.resultCode, result.data!!)
            } else {
                Toast.makeText(this, "Screen capture permission was cancelled", Toast.LENGTH_SHORT).show()
                updateUiState(isCapturing = false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        btnStart = findViewById(R.id.btnStartCapture)
        btnStop = findViewById(R.id.btnStopCapture)
        tvStatus = findViewById(R.id.tvStatus)
        tvEngineInfo = findViewById(R.id.tvEngineInfo)
        pbAudioLevel = findViewById(R.id.pbAudioLevel)
        tvStats = findViewById(R.id.tvStats)
        tvPitchValue = findViewById(R.id.tvPitchValue)
        sbPitch = findViewById(R.id.sbPitch)
        tvGainValue = findViewById(R.id.tvGainValue)
        sbGain = findViewById(R.id.sbGain)

        // Pitch slider: 0..24 maps to -12..+12 semitones
        sbPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val semitones = (progress - 12).toFloat()
                currentPitchSemitones = semitones
                updatePitchLabel(semitones)
                if (fromUser) {
                    applyPitch(semitones)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Volume Boost slider: 0..25 maps to 0.5x..3.0x (0.5 + progress * 0.1)
        sbGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val gain = 0.5f + (progress * 0.1f)
                currentGain = gain
                tvGainValue.text = String.format("Volume Boost: %.1fx", gain)
                if (fromUser) {
                    applyGain(gain)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Quick buttons
        findViewById<Button>(R.id.btnDeepVoice).setOnClickListener {
            setPitchProgress(-6)
        }
        findViewById<Button>(R.id.btnNormalPitch).setOnClickListener {
            setPitchProgress(0)
        }
        findViewById<Button>(R.id.btnHighPitch).setOnClickListener {
            setPitchProgress(4)
        }
        findViewById<Button>(R.id.btnChipmunk).setOnClickListener {
            setPitchProgress(12)
        }

        btnStart.setOnClickListener {
            checkAndRequestPermissions()
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, AudioCaptureService::class.java))
            updateUiState(isCapturing = false)
        }

        // Set initial labels
        updatePitchLabel(currentPitchSemitones)
        tvGainValue.text = String.format("Volume Boost: %.1fx", currentGain)
    }

    override fun onResume() {
        super.onResume()
        AudioCaptureService.metricsListener = this
        val isRunning = AudioCaptureService.currentInstance != null
        updateUiState(isRunning)
    }

    override fun onPause() {
        super.onPause()
        AudioCaptureService.metricsListener = null
    }

    private fun setPitchProgress(semitones: Int) {
        sbPitch.progress = semitones + 12
        currentPitchSemitones = semitones.toFloat()
        applyPitch(currentPitchSemitones)
    }

    private fun updatePitchLabel(semitones: Float) {
        val sign = if (semitones > 0) "+" else ""
        val noteDescription = when (semitones.toInt()) {
            -12 -> " (One Octave Down)"
            -7 -> " (Fifth Down)"
            -6 -> " (Deep Voice)"
            0 -> " (Original Pitch)"
            2 -> " (Whole Tone Up)"
            4 -> " (Major Third Up)"
            7 -> " (Fifth Up)"
            12 -> " (Chipmunk / Octave Up)"
            else -> ""
        }
        tvPitchValue.text = String.format("Pitch Shift: %s%.1f semitones%s", sign, semitones, noteDescription)
    }

    private fun applyPitch(semitones: Float) {
        AudioCaptureService.currentInstance?.setPitch(semitones) ?: run {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_UPDATE_PITCH
                putExtra(AudioCaptureService.EXTRA_PITCH, semitones)
            }
            startService(intent)
        }
    }

    private fun applyGain(gain: Float) {
        AudioCaptureService.currentInstance?.setGain(gain) ?: run {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_UPDATE_GAIN
                putExtra(AudioCaptureService.EXTRA_GAIN, gain)
            }
            startService(intent)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val allGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            launchScreenCapture()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun launchScreenCapture() {
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        AudioCaptureService.capturedResultCode = resultCode
        AudioCaptureService.capturedData = data

        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            putExtra(AudioCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioCaptureService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        updateUiState(isCapturing = true)
    }

    private fun updateUiState(isCapturing: Boolean) {
        btnStart.isEnabled = !isCapturing
        btnStop.isEnabled = isCapturing
        if (!isCapturing) {
            tvStatus.text = "Status: Stopped"
            tvStatus.setTextColor(Color.GRAY)
            tvEngineInfo.text = "Engine: Offline"
            pbAudioLevel.progress = 0
            tvStats.text = "Captured: 0 samples | Processed: 0 frames"
        }
    }

    override fun onMetricsUpdated(
        isCapturing: Boolean,
        engineRunning: Boolean,
        signalPercent: Int,
        totalSamples: Long,
        processedFrames: Long,
        errorMessage: String?
    ) {
        btnStart.isEnabled = !isCapturing
        btnStop.isEnabled = isCapturing

        if (errorMessage != null) {
            tvStatus.text = "Status: Error - $errorMessage"
            tvStatus.setTextColor(Color.RED)
            tvEngineInfo.text = "Check Android 14+ permissions and select 'Entire screen'"
            return
        }

        if (isCapturing) {
            tvStatus.text = "Status: 🟢 Capturing System Audio"
            tvStatus.setTextColor(Color.parseColor("#2E7D32"))
            tvEngineInfo.text = if (engineRunning) {
                "Engine: 🟢 Oboe Output Stream Active (Shared Mode, 48kHz Stereo)"
            } else {
                "Engine: 🟡 Starting Oboe Stream..."
            }
            pbAudioLevel.progress = signalPercent
            tvStats.text = String.format(
                "Captured: %,d samples | Processed: %,d frames (Level: %d%%)",
                totalSamples, processedFrames, signalPercent
            )
        } else {
            updateUiState(isCapturing = false)
        }
    }
}
