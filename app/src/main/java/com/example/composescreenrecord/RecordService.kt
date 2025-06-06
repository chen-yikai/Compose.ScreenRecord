package com.example.composescreenrecord

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

data class RecordState(
    val isRecording: Boolean = false,
    val startRecording: Long = 0L
)

fun timeFormatter(time: Long): String {
    val timeInSeconds = time / 1000
    val minutes = timeInSeconds / 60
    val seconds = timeInSeconds % 60
    return "${minutes.toString().padStart(2,'0')}:${seconds.toString().padStart(2,'0')}"
}

class ScreenRecordingService : Service() {
    companion object {
        val _recordState = MutableStateFlow(RecordState())
        val recordState: StateFlow<RecordState> = _recordState
    }

    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            _recordState.update { it.copy(isRecording = false) }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = createNotificationChannel()

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screen Recording")
            .setContentText("Recording in progress...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val resultCode =
            intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode == Activity.RESULT_OK && data != null) {
            val mediaProjectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(mediaProjectionCallback, null)
            startRecording()
        } else {
            Log.e("ScreenRecordingService", "Invalid MediaProjection data")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel(): String {
        val channelId = "ScreenRecordingChannel"
        val channel = NotificationChannel(
            channelId,
            "Screen Recording Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notification for screen recording service"
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        return channelId
    }

    private fun startRecording() {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        mediaRecorder = MediaRecorder(this).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)

            if (hasAudioPermission) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }

            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile("${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/screen_recording_${System.currentTimeMillis()}.mp4")
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            if (hasAudioPermission) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }

            setVideoEncodingBitRate(8 * 1000 * 1000)
            setVideoFrameRate(30)
            val displayMetrics = resources.displayMetrics
            setVideoSize(displayMetrics.widthPixels, displayMetrics.heightPixels)
            prepare()
        }

        val displayMetrics = resources.displayMetrics
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecorder",
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder?.surface,
            null,
            null
        )
        _recordState.update {
            it.copy(
                isRecording = true,
                startRecording = System.currentTimeMillis()
            )
        }
        mediaRecorder?.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        _recordState.update {
            it.copy(isRecording = false, startRecording = 0L)
        }
        mediaRecorder?.stop()
        mediaRecorder?.release()
        virtualDisplay?.release()
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}