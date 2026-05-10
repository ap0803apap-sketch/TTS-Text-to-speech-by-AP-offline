package com.ap.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.*

class TtsService : Service(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var isExporting = false
    private lateinit var localBroadcastManager: LocalBroadcastManager

    private var isTtsInitialized = false
    private var pendingIntent: Intent? = null

    private val tempFiles = mutableListOf<File>()
    private var totalChunks = 0
    private var chunksSynthesized = 0

    private var appName: String = ""
    private var exportStartTime: Long = 0
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationBuilder: NotificationCompat.Builder

    // --- NEW: Handler for periodic progress updates ---
    private val progressHandler = Handler(Looper.getMainLooper())
    private lateinit var progressRunnable: Runnable
    // -------------------------------------------------

    companion object {
        var isRunning = false
        const val ACTION_START_EXPORT = "com.ap.tts.action.START_EXPORT"
        const val ACTION_STOP_EXPORT = "com.ap.tts.action.STOP_EXPORT"
        const val NOTIFICATION_COMPLETE = "com.ap.tts.notification.COMPLETE"
        const val NOTIFICATION_ERROR = "com.ap.tts.notification.ERROR"
        const val EXTRA_TEXT = "com.ap.tts.extra.TEXT"
        const val EXTRA_PITCH = "com.ap.tts.extra.PITCH"
        const val EXTRA_SPEED = "com.ap.tts.extra.SPEED"
        const val EXTRA_VOLUME = "com.ap.tts.extra.VOLUME"
        const val EXTRA_LANGUAGE = "com.ap.tts.extra.LANGUAGE"
        const val EXTRA_VOICE_NAME = "com.ap.tts.extra.VOICE_NAME"
        const val EXTRA_MESSAGE = "com.ap.tts.extra.MESSAGE"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "TtsServiceChannel"

        const val NOTIFICATION_PROGRESS = "com.ap.tts.notification.PROGRESS"
        const val EXTRA_PROGRESS = "com.ap.tts.extra.PROGRESS"
        // --- MODIFIED: New extra for formatted time string ---
        const val EXTRA_TIME_REMAINING_FORMATTED = "com.ap.tts.extra.TIME_REMAINING_FORMATTED"
    }

    override fun onCreate() {
        super.onCreate()
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        appName = getString(R.string.app_name)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        tts = TextToSpeech(this, this)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        notificationBuilder = createNotificationBuilder("Preparing export...")
        startForeground(NOTIFICATION_ID, notificationBuilder.build())

        when (intent?.action) {
            ACTION_START_EXPORT -> {
                this.pendingIntent = intent
                processPendingExport()
            }
            ACTION_STOP_EXPORT -> stopExport("Export cancelled by user.")
        }
        return START_NOT_STICKY
    }

    private fun processPendingExport() {
        if (isTtsInitialized && pendingIntent != null) {
            handleStartExport(pendingIntent!!)
            pendingIntent = null
        }
    }

    private fun handleStartExport(intent: Intent) {
        if (isExporting) return
        isExporting = true
        tempFiles.clear()

        val textToExport = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val pitch = intent.getFloatExtra(EXTRA_PITCH, 1.0f)
        val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
        val language = intent.getSerializableExtra(EXTRA_LANGUAGE) as? Locale
        val voiceName = intent.getStringExtra(EXTRA_VOICE_NAME)

        tts.setPitch(pitch)
        tts.setSpeechRate(speed)
        if (language != null) { tts.language = language }
        if (voiceName != null) {
            tts.voices?.find { it.name == voiceName }?.let { tts.voice = it }
        }

        startSynthesis(textToExport)
    }

    private fun startSynthesis(textToExport: String) {
        val chunks = splitTextIntoChunks(textToExport)
        totalChunks = chunks.size
        chunksSynthesized = 0

        if (totalChunks == 0) {
            sendError("No text to synthesize.")
            return
        }

        exportStartTime = System.currentTimeMillis()

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { /* Not needed */ }

            // --- MODIFIED: onDone now just increments the counter. The handler does the updates. ---
            override fun onDone(utteranceId: String?) {
                chunksSynthesized++
                if (chunksSynthesized >= totalChunks) {
                    stopProgressUpdater() // Stop the handler before merging files
                    val finalOutputFile = getFinalOutputFile()
                    if (finalOutputFile != null) {
                        mergeWavFiles(tempFiles, finalOutputFile)
                    } else {
                        sendError("Could not create final output file.")
                        cleanupTempFiles()
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                sendError("TTS synthesis failed for a chunk.")
                cleanupTempFiles()
            }
        })

        // --- NEW: Start the periodic progress updater ---
        setupProgressUpdater()

        chunks.forEachIndexed { index, chunk ->
            try {
                val tempFile = File.createTempFile("chunk_${index}_", ".wav", cacheDir)
                tempFiles.add(tempFile)
                val utteranceId = "chunk_$index"
                tts.synthesizeToFile(chunk, Bundle(), tempFile, utteranceId)
            } catch (e: IOException) {
                sendError("Could not create temporary file for synthesis.")
                cleanupTempFiles()
                return@forEachIndexed
            }
        }
    }

    // --- NEW: Sets up a runnable to fire every second to update progress ---
    private fun setupProgressUpdater() {
        progressRunnable = Runnable {
            if (!isExporting) return@Runnable

            val progress = if (totalChunks > 0) (chunksSynthesized * 100) / totalChunks else 0
            val elapsedTime = System.currentTimeMillis() - exportStartTime

            val estimatedTotalTime = if (chunksSynthesized > 0) {
                (elapsedTime.toDouble() / chunksSynthesized * totalChunks).toLong()
            } else {
                -1L // Indicates unknown until the first chunk is done
            }

            val remainingTimeMillis = if (estimatedTotalTime > 0) estimatedTotalTime - elapsedTime else -1L
            val remainingTimeSeconds = if (remainingTimeMillis > 0) remainingTimeMillis / 1000 else 0

            val formattedTime = formatTime(remainingTimeSeconds)
            val progressText = "Progress: $progress%, Time left: $formattedTime"

            updateNotificationAndBroadcast(progress, progressText)

            // Reschedule if the export is not yet complete
            if (chunksSynthesized < totalChunks) {
                progressHandler.postDelayed(this.progressRunnable, 1000)
            }
        }
        // Start the updater immediately
        progressHandler.post(progressRunnable)
    }

    // --- NEW: Stops the periodic progress updater ---
    private fun stopProgressUpdater() {
        if (this::progressRunnable.isInitialized) {
            progressHandler.removeCallbacks(progressRunnable)
        }
    }

    // --- NEW: Helper function to format seconds into "Xm Ys" format ---
    private fun formatTime(totalSeconds: Long): String {
        if (totalSeconds < 0) return "Calculating..."
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            "${minutes}m ${seconds}s"
        } else {
            "${seconds}s"
        }
    }

    // --- MODIFIED: This function now accepts the pre-formatted progress string ---
    private fun updateNotificationAndBroadcast(progress: Int, progressText: String) {
        // Update notification
        notificationBuilder.setContentText(progressText)
            .setProgress(100, progress, false)
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())

        // Broadcast progress to MainActivity
        val intent = Intent(NOTIFICATION_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_TIME_REMAINING_FORMATTED, progressText)
        }
        localBroadcastManager.sendBroadcast(intent)
    }

    private fun splitTextIntoChunks(text: String): List<String> {
        val chunks = mutableListOf<String>()
        var remainingText = text
        val maxLen = TextToSpeech.getMaxSpeechInputLength() - 1

        while (remainingText.isNotEmpty()) {
            if (remainingText.length <= maxLen) {
                chunks.add(remainingText)
                break
            }
            var splitIndex = maxLen
            val lastPunctuation = remainingText.substring(0, maxLen).lastIndexOfAny(charArrayOf('.', '!', '?'))
            if (lastPunctuation != -1) {
                splitIndex = lastPunctuation + 1
            }
            chunks.add(remainingText.substring(0, splitIndex))
            remainingText = remainingText.substring(splitIndex)
        }
        return chunks
    }

    private fun getFinalOutputFile(): File? {
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val appDir = File(musicDir, appName)
        if (!appDir.exists() && !appDir.mkdirs()) {
            Log.e("TtsService", "Failed to create directory: ${appDir.absolutePath}")
            return null
        }
        val fileName = "TTS_Audio_${System.currentTimeMillis()}.wav"
        return File(appDir, fileName)
    }

    private fun mergeWavFiles(files: List<File>, output: File) {
        var totalDataLen: Long = 0
        val data = mutableListOf<ByteArray>()

        files.forEach { file ->
            try {
                FileInputStream(file).use { fis ->
                    fis.skip(44) // Skip WAV header
                    val content = fis.readBytes()
                    data.add(content)
                    totalDataLen += content.size
                }
            } catch (e: IOException) {
                sendError("Failed to read temporary file.")
                cleanupTempFiles()
                return
            }
        }

        try {
            FileOutputStream(output).use { fos ->
                val firstFileHeader = FileInputStream(files[0]).use { it.readBytes().take(44).toByteArray() }
                fos.write(firstFileHeader)
                data.forEach { fos.write(it) }
            }

            RandomAccessFile(output, "rw").use { raf ->
                raf.seek(4)
                raf.write(intToByteArray((totalDataLen + 36).toInt()))
                raf.seek(40)
                raf.write(intToByteArray(totalDataLen.toInt()))
            }
            sendCompletion("Single audio file saved to Music/$appName folder.")
        } catch (e: IOException) {
            sendError("Failed to merge audio files.")
        } finally {
            cleanupTempFiles()
        }
    }

    private fun cleanupTempFiles() {
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }

    private fun intToByteArray(data: Int): ByteArray {
        return ByteArray(4) { i -> (data shr (i * 8)).toByte() }
    }

    private fun sendCompletion(message: String) {
        stopProgressUpdater()
        val intent = Intent(NOTIFICATION_COMPLETE).apply { putExtra(EXTRA_MESSAGE, message) }
        localBroadcastManager.sendBroadcast(intent)
        stopForeground(true)
        stopSelf()
    }

    private fun sendError(message: String) {
        stopProgressUpdater()
        val intent = Intent(NOTIFICATION_ERROR).apply { putExtra(EXTRA_MESSAGE, message) }
        localBroadcastManager.sendBroadcast(intent)
        stopForeground(true)
        stopSelf()
    }

    private fun stopExport(message: String) {
        if (isExporting) {
            tts.stop()
        }
        cleanupTempFiles()
        sendError(message)
    }

    override fun onDestroy() {
        stopProgressUpdater()
        if (this::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsInitialized = true
            processPendingExport()
        } else {
            sendError("TTS engine initialization failed.")
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "TTS Export Service", NotificationManager.IMPORTANCE_DEFAULT)
            serviceChannel.description = "Shows progress for TTS audio export"
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotificationBuilder(text: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Exporting Audio")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOnlyAlertOnce(true) // Prevents sound/vibration on every update
            .setProgress(100, 0, true)
    }
}
