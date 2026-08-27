package looksee.angelll.com.models

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.kotlin.core.Amplify
import com.google.gson.Gson
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AutoUploadState(
    val isUploading: Boolean = false,
    val isPaused: Boolean = false,
    val currentlyUploadingId: UUID? = null,
    val currentUploadProgress: Double = 0.0,
    val completedItems: Int = 0,
    val totalItems: Int = 0,
    val currentLabel: String? = null,
)

/**
 * WorkManager-backed Android translation of AutoUploadManager.swift.
 *
 * Call [autoStartIfPossible] at app startup and after successfully archiving new media. WorkManager
 * keeps the request durable and waits for a connected network, including across process restarts.
 */
class AutoUploadManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val pauseStore = SharedPreferencesAutoUploadPauseStore(appContext)

    val state: StateFlow<AutoUploadState> = AutoUploadRuntime.state

    init {
        AutoUploadRuntime.setPaused(pauseStore.isPaused())
    }

    fun startProcessing() {
        pauseStore.setPaused(false)
        AutoUploadRuntime.setPaused(false)
        enqueue(expedited = true)
    }

    /** Clears a user pause and immediately retries the durable upload queue. */
    fun forceRetry() = startProcessing()

    /** The active item may finish; the worker checks this flag before advancing to the next item. */
    fun stopProcessing() {
        pauseStore.setPaused(true)
        AutoUploadRuntime.setPaused(true)
    }

    fun autoStartIfPossible() {
        if (!pauseStore.isPaused()) enqueue(expedited = false)
    }

    private fun enqueue(expedited: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val builder = OneTimeWorkRequestBuilder<AutoUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
        if (expedited) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            builder.build(),
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "looksee-auto-upload"
        const val WORK_TAG = "looksee-auto-upload"

        @Volatile
        private var sharedInstance: AutoUploadManager? = null

        fun shared(context: Context): AutoUploadManager =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: AutoUploadManager(context).also { sharedInstance = it }
            }
    }
}

class AutoUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val dependencies = AutoUploadDependencies.create(appContext) { data ->
        setProgress(data)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        dependencies.notifier.foregroundInfo(AutoUploadRuntime.state.value)

    override suspend fun doWork(): Result {
        try {
            setForeground(getForegroundInfo())
        } catch (_: IllegalStateException) {
            return Result.retry()
        }

        AutoUploadRuntime.setUploading(true)
        val result = try {
            dependencies.engine.process()
        } finally {
            AutoUploadRuntime.finish()
        }

        return when (result) {
            is AutoUploadRunResult.Completed,
            is AutoUploadRunResult.Paused,
            AutoUploadRunResult.NotAuthenticated,
            AutoUploadRunResult.SubscriptionRequired,
            AutoUploadRunResult.TokensUnavailable,
            -> Result.success()

            is AutoUploadRunResult.Retry -> {
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure(failureData(result.cause))
                }
            }

            is AutoUploadRunResult.Failed -> Result.failure(failureData(result.cause))
        }
    }

    private fun failureData(error: Throwable) = workDataOf(
        OUTPUT_ERROR to (error.message ?: error::class.java.simpleName),
    )

    companion object {
        const val OUTPUT_ERROR = "error"
        const val PROGRESS_MEDIA_ID = "mediaId"
        const val PROGRESS_LABEL = "label"
        const val PROGRESS_FRACTION = "progress"
        const val PROGRESS_COMPLETED = "completedItems"
        const val PROGRESS_TOTAL = "totalItems"
        private const val MAX_RETRY_ATTEMPTS = 5
    }
}

private data class AutoUploadDependencies(
    val engine: AutoUploadQueueEngine,
    val notifier: AndroidAutoUploadNotifier,
) {
    companion object {
        fun create(
            context: Context,
            progressWriter: suspend (Data) -> Unit,
        ): AutoUploadDependencies {
            val appContext = context.applicationContext
            val notifier = AndroidAutoUploadNotifier(appContext)
            val pauseStore = SharedPreferencesAutoUploadPauseStore(appContext)
            val archiveManager = OfflineMediaManager.shared(appContext)
            val workerEvents = WorkManagerAutoUploadEvents(notifier, progressWriter)
            return AutoUploadDependencies(
                engine = AutoUploadQueueEngine(
                    archive = OfflineMediaAutoUploadArchive(archiveManager),
                    sessionProvider = AmplifyAutoUploadSessionProvider(),
                    positiveUploader = UploadServiceAutoUploader(UploadService(appContext)),
                    negativeUploader = HardNegativeServiceAutoUploader(
                        HardNegativeUploadService(),
                    ),
                    pauseGate = pauseStore,
                    events = workerEvents,
                ),
                notifier = notifier,
            )
        }
    }
}

private object AutoUploadRuntime {
    private val _state = MutableStateFlow(AutoUploadState())
    val state: StateFlow<AutoUploadState> = _state.asStateFlow()

    fun setUploading(uploading: Boolean) {
        _state.value = _state.value.copy(isUploading = uploading)
    }

    fun setPaused(paused: Boolean) {
        _state.value = _state.value.copy(isPaused = paused)
    }

    fun update(progress: AutoUploadItemProgress) {
        _state.value = _state.value.copy(
            isUploading = true,
            currentlyUploadingId = progress.mediaId,
            currentUploadProgress = progress.overallProgress,
            completedItems = progress.completedItems,
            totalItems = progress.totalItems,
            currentLabel = progress.label,
        )
    }

    fun finish() {
        _state.value = _state.value.copy(
            isUploading = false,
            currentlyUploadingId = null,
            currentUploadProgress = 0.0,
            completedItems = 0,
            totalItems = 0,
            currentLabel = null,
        )
    }
}

private class SharedPreferencesAutoUploadPauseStore(context: Context) : AutoUploadPauseGate {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun isPaused(): Boolean = preferences.getBoolean(PAUSED_KEY, false)

    fun setPaused(paused: Boolean) {
        preferences.edit().putBoolean(PAUSED_KEY, paused).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "looksee_auto_upload"
        const val PAUSED_KEY = "isPaused"
    }
}

private class OfflineMediaAutoUploadArchive(
    private val manager: OfflineMediaManager,
) : AutoUploadArchive {
    override fun pendingItems(): List<ArchivedMedia> =
        manager.archivedItems.value.sortedBy(ArchivedMedia::dateSaved)

    override fun positiveFile(media: ArchivedMedia): File = manager.getFile(media)

    override fun negativeFile(media: ArchivedMedia): File? = manager.getNegativeVideoFile(media)

    override suspend fun delete(media: ArchivedMedia) = manager.deleteArchive(media)
}

private class UploadServiceAutoUploader(
    private val service: UploadService,
) : AutoUploadPositiveUploader {
    override suspend fun upload(
        media: ArchivedMedia,
        file: File,
        session: AutoUploadSession,
        label: String,
        landmarkId: String,
        onProgress: suspend (Double) -> Unit,
    ): PositiveSubmissionResult = coroutineScope {
        val progressJob = launch {
            service.progress.collect { onProgress(it) }
        }
        try {
            service.upload(
                userEmail = session.userEmail,
                idToken = session.idToken,
                label = label,
                landmarkId = landmarkId,
                landmarkLabel = label,
                shortDescription = media.savedDescription,
                userDescription = media.savedUserDescription,
                latitude = media.latitude,
                longitude = media.longitude,
                horizontalAccuracy = 10.0,
                videoFiles = if (media.isVideo) listOf(file) else emptyList(),
                imageJpegData = if (media.isVideo) {
                    null
                } else {
                    withContext(Dispatchers.IO) { file.readBytes() }
                },
            )
        } finally {
            progressJob.cancel()
        }
    }
}

private class HardNegativeServiceAutoUploader(
    private val service: HardNegativeUploadService,
) : AutoUploadNegativeUploader {
    override suspend fun upload(
        file: File,
        landmarkId: String,
        idToken: String,
        onProgress: suspend (Double) -> Unit,
    ) = coroutineScope {
        val progressJob = launch {
            service.progress.collect { onProgress(it) }
        }
        try {
            service.upload(
                landmarkId = landmarkId,
                idToken = idToken,
                video = CapturedNegativeVideo(file),
            )
        } finally {
            progressJob.cancel()
        }
        Unit
    }
}

private class WorkManagerAutoUploadEvents(
    private val notifier: AndroidAutoUploadNotifier,
    private val progressWriter: suspend (Data) -> Unit,
) : AutoUploadEvents {
    override suspend fun onProgress(progress: AutoUploadItemProgress) {
        AutoUploadRuntime.update(progress)
        progressWriter(
            workDataOf(
                AutoUploadWorker.PROGRESS_MEDIA_ID to progress.mediaId.toString(),
                AutoUploadWorker.PROGRESS_LABEL to progress.label,
                AutoUploadWorker.PROGRESS_FRACTION to progress.overallProgress,
                AutoUploadWorker.PROGRESS_COMPLETED to progress.completedItems,
                AutoUploadWorker.PROGRESS_TOTAL to progress.totalItems,
            ),
        )
        notifier.updateForeground(AutoUploadRuntime.state.value)
    }

    override fun onUploadSucceeded(label: String) {
        notifier.success(label)
    }

    override fun onLimit(title: String, body: String) {
        notifier.limit(title, body)
    }
}

private class AmplifyAutoUploadSessionProvider(
    private val statsClient: AutoUploadStatsClient = UrlConnectionAutoUploadStatsClient(),
) : AutoUploadSessionProvider {
    override suspend fun fetchSession(): AutoUploadSession? {
        val authSession = Amplify.Auth.fetchAuthSession() as? AWSCognitoAuthSession
            ?: return null
        if (!authSession.isSignedIn) return null

        val idToken = authSession.userPoolTokensResult.value?.idToken.orEmpty()
        if (idToken.isBlank()) return null
        val claims = decodeJwtClaims(idToken) ?: return null
        val userEmail = claims.optString("email").trim()
        val userId = claims.optString("sub").trim()
        if (userEmail.isEmpty() || userId.isEmpty()) return null

        val stats = statsClient.fetch(userId)
        return AutoUploadSession(
            userEmail = userEmail,
            idToken = idToken,
            hasActiveSubscription = stats.hasActiveSubscription ||
                stats.tier == "business" || stats.stripeSubscriptionId.isNotBlank(),
            tokenBalance = stats.tokenBalance,
        )
    }

    private fun decodeJwtClaims(token: String): JSONObject? = try {
        val payload = token.split('.').getOrNull(1) ?: return null
        val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        JSONObject(decoded.toString(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }
}

private data class AutoUploadUsageStats(
    val tokenBalance: Int = 0,
    val hasActiveSubscription: Boolean = false,
    val tier: String = "",
    val stripeSubscriptionId: String = "",
)

private interface AutoUploadStatsClient {
    suspend fun fetch(userId: String): AutoUploadUsageStats
}

private class UrlConnectionAutoUploadStatsClient(
    private val gson: Gson = Gson(),
) : AutoUploadStatsClient {
    override suspend fun fetch(userId: String): AutoUploadUsageStats = withContext(Dispatchers.IO) {
        val connection = URL(USER_STATS_URL).openConnection() as HttpURLConnection
        try {
            val body = gson.toJson(mapOf("userId" to userId)).toByteArray(Charsets.UTF_8)
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val responseStream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val responseBody = responseStream?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) throw AutoUploadStatsException(code, responseBody)
            gson.fromJson(responseBody, AutoUploadUsageStats::class.java)
                ?: throw IllegalStateException("The usage response was empty.")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val USER_STATS_URL =
            "https://7gmn5z3uf2.execute-api.us-east-1.amazonaws.com/dev/LookSeeGetUserStats"
        const val TIMEOUT_MILLIS = 60_000
    }
}

internal class AutoUploadStatsException(
    val statusCode: Int,
    responseBody: String,
) : Exception("Usage request failed with HTTP $statusCode: $responseBody")

private class AndroidAutoUploadNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        createChannels()
    }

    fun foregroundInfo(state: AutoUploadState): ForegroundInfo {
        val notification = foregroundNotification(state)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    fun updateForeground(state: AutoUploadState) {
        notifyIfAllowed(FOREGROUND_NOTIFICATION_ID, foregroundNotification(state))
    }

    fun success(label: String) {
        notifyIfAllowed(
            label.hashCode(),
            NotificationCompat.Builder(context, RESULTS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("LookSee Upload Complete! 🎉")
                .setContentText(
                    "Your offline media for '$label' was successfully synced. (1 Token consumed).",
                )
                .setAutoCancel(true)
                .setContentIntent(launchIntent())
                .build(),
        )
    }

    fun limit(title: String, body: String) {
        notifyIfAllowed(
            LIMIT_NOTIFICATION_ID,
            NotificationCompat.Builder(context, RESULTS_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(launchIntent())
                .build(),
        )
    }

    private fun foregroundNotification(state: AutoUploadState): Notification {
        val progress = (state.currentUploadProgress.coerceIn(0.0, 1.0) * 100).toInt()
        val text = state.currentLabel?.let { "Uploading $it" } ?: "Preparing offline uploads"
        return NotificationCompat.Builder(context, UPLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Syncing LookSee media")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress, state.currentlyUploadingId == null)
            .setContentIntent(launchIntent())
            .build()
    }

    private fun launchIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notifyIfAllowed(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            manager.notify(id, notification)
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val systemManager = context.getSystemService(NotificationManager::class.java)
        systemManager.createNotificationChannel(
            NotificationChannel(
                UPLOAD_CHANNEL_ID,
                "Offline uploads",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        systemManager.createNotificationChannel(
            NotificationChannel(
                RESULTS_CHANNEL_ID,
                "Upload results",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private companion object {
        const val UPLOAD_CHANNEL_ID = "looksee_auto_upload_progress"
        const val RESULTS_CHANNEL_ID = "looksee_auto_upload_results"
        const val FOREGROUND_NOTIFICATION_ID = 41_101
        const val LIMIT_NOTIFICATION_ID = 41_102
    }
}
