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
import java.io.IOException

class ScreenRecordingService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d("ScreenRecordingService", "MediaProjection stopped")
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
            try {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                mediaProjection?.registerCallback(mediaProjectionCallback, null)
                startRecording()
            } catch (e: Exception) {
                Log.e("ScreenRecordingService", "Failed to initialize MediaProjection", e)
                stopSelf()
            }
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
                try {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                } catch (e: IllegalStateException) {
                    Log.e("ScreenRecordingService", "Failed to set audio source", e)
                }
            }

            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile("${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/screen_recording_${System.currentTimeMillis()}.mp4")

            try {
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            } catch (e: IllegalStateException) {
                Log.e("ScreenRecordingService", "Failed to set video encoder", e)
                stopSelf()
                return
            }

            if (hasAudioPermission) {
                try {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                } catch (e: IllegalStateException) {
                    Log.e("ScreenRecordingService", "Failed to set audio encoder", e)
                }
            }

            try {
                setVideoEncodingBitRate(8 * 1000 * 1000)
                setVideoFrameRate(30)
                val displayMetrics = resources.displayMetrics
                setVideoSize(displayMetrics.widthPixels, displayMetrics.heightPixels)
            } catch (e: IllegalStateException) {
                Log.e("ScreenRecordingService", "Failed to set video parameters", e)
                stopSelf()
                return
            }

            try {
                prepare()
            } catch (e: IOException) {
                Log.e("ScreenRecordingService", "MediaRecorder prepare failed", e)
                stopSelf()
                return
            }
        }

        val displayMetrics = resources.displayMetrics
        try {
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
        } catch (e: Exception) {
            Log.e("ScreenRecordingService", "Failed to create VirtualDisplay", e)
            stopSelf()
            return
        }

        try {
            mediaRecorder?.start()
        } catch (e: IllegalStateException) {
            Log.e("ScreenRecordingService", "MediaRecorder start failed", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e("ScreenRecordingService", "Error stopping MediaRecorder", e)
        }
        mediaRecorder?.release()
        virtualDisplay?.release()
        mediaProjection?.unregisterCallback(mediaProjectionCallback)
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e("ScreenRecordingService", "Error stopping MediaProjection", e)
        }
        mediaProjection = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}