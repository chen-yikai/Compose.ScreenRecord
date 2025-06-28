package com.example.composescreenrecord

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.hardware.display.*
import android.media.*
import android.media.projection.*
import android.os.*
import android.util.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*

data class RecordState(
    val isRecording: Boolean = false,
    val startRecording: Long = 0L
)

fun timeFormatter(time: Long): String {
    val timeInSeconds = time / 1000
    val minutes = timeInSeconds / 60
    val seconds = timeInSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}

class ScreenRecordingService : Service() {
    companion object {
        private val _recordState = MutableStateFlow(RecordState())
        val recordState: StateFlow<RecordState> = _recordState
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var recordingJob: Job? = null

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopRecording()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = createNotificationChannel()
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screen Recording")
            .setContentText("Recording in progress...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val resultCode =
            intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode == Activity.RESULT_OK && data != null) {
            val projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(mediaProjectionCallback, null)
            startRecording()
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel(): String {
        val id = "ScreenRecordingChannel"
        val channel =
            NotificationChannel(id, "Screen Recording", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return id
    }

    private fun startRecording() {
        val displayMetrics = Resources.getSystem().displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels
        val dpi = resources.displayMetrics.densityDpi
        val outputFile =
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "recorded_${System.currentTimeMillis()}.mp4"
            )

        val videoFormat = MediaFormat.createVideoFormat("video/avc", width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, width * height * 5)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        videoEncoder = MediaCodec.createEncoderByType("video/avc").apply {
            configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        val inputSurface = videoEncoder!!.createInputSurface()
        videoEncoder!!.start()

        val audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
        }

        audioEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm").apply {
            configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val audioInFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(44100)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            44100,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(audioInFormat)
            .setBufferSizeInBytes(minBuffer)
            .build()

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        audioRecord?.startRecording()

        _recordState.update {
            it.copy(
                isRecording = true,
                startRecording = System.currentTimeMillis()
            )
        }

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val videoInfo = MediaCodec.BufferInfo()
            val audioInfo = MediaCodec.BufferInfo()
            val audioBuffer = ByteArray(minBuffer)

            while (isActive) {
                val outIndex = videoEncoder!!.dequeueOutputBuffer(videoInfo, 10000)
                if (outIndex >= 0) {
                    val encodedData = videoEncoder!!.getOutputBuffer(outIndex)!!
                    if ((videoInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (!muxerStarted) {
                            videoTrackIndex = muxer!!.addTrack(videoEncoder!!.outputFormat)
                            audioTrackIndex = muxer!!.addTrack(audioEncoder!!.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                        }
                        muxer!!.writeSampleData(videoTrackIndex, encodedData, videoInfo)
                    }
                    videoEncoder!!.releaseOutputBuffer(outIndex, false)
                }

                val read = audioRecord!!.read(audioBuffer, 0, audioBuffer.size)
                if (read > 0) {
                    val inputIndex = audioEncoder!!.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = audioEncoder!!.getInputBuffer(inputIndex)!!
                        inputBuffer.clear()
                        val length = minOf(read, inputBuffer.capacity())
                        inputBuffer.put(audioBuffer, 0, length)
                        audioEncoder!!.queueInputBuffer(
                            inputIndex,
                            0,
                            length,
                            System.nanoTime() / 1000,
                            0
                        )
                    }
                }

                var outAudioIndex = audioEncoder!!.dequeueOutputBuffer(audioInfo, 0)
                while (outAudioIndex >= 0) {
                    val outBuf = audioEncoder!!.getOutputBuffer(outAudioIndex)!!
                    if ((audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted) {
                        muxer!!.writeSampleData(audioTrackIndex, outBuf, audioInfo)
                    }
                    audioEncoder!!.releaseOutputBuffer(outAudioIndex, false)
                    outAudioIndex = audioEncoder!!.dequeueOutputBuffer(audioInfo, 0)
                }
            }
        }
    }

    private fun stopRecording() {
        _recordState.update { it.copy(isRecording = false, startRecording = 0L) }
        recordingJob?.cancel()

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioEncoder?.stop()
            audioEncoder?.release()
            videoEncoder?.stop()
            videoEncoder?.release()
            virtualDisplay?.release()
            mediaProjection?.stop()
            muxer?.stop()
            muxer?.release()
        } catch (e: Exception) {
            Log.e("ScreenRecording", "Error stopping", e)
        }

        stopSelf()
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
