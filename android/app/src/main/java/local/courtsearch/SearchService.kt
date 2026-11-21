package local.courtsearch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** Keeps a statewide search running when the phone is locked or the app is in the background. */
class SearchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val generation = AtomicInteger()

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, "Court search", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val last = intent?.getStringExtra(EXTRA_LAST).orEmpty()
        val first = intent?.getStringExtra(EXTRA_FIRST).orEmpty()
        val middle = intent?.getStringExtra(EXTRA_MIDDLE).orEmpty()
        if (last.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(0, 1, "Starting…")
        val controller = (application as CourtSearchApp).search
        val token = generation.incrementAndGet()
        job?.cancel()
        job = scope.launch {
            try {
                controller.run(last, first, middle) { done, total, court ->
                    val text = if (court.isBlank()) "Searching…" else "Searching $court"
                    startInForeground(done, total, "$text ($done of $total)")
                }
            } catch (_: CancellationException) {
                controller.markStopped()
            } catch (exc: Exception) {
                controller.fail(exc.message ?: "The search failed.")
            } finally {
                if (token == generation.get()) {
                    ServiceCompat.stopForeground(this@SearchService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(done: Int, total: Int, text: String) {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Searching Virginia courts")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), done, done == 0)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val EXTRA_LAST = "last"
        const val EXTRA_FIRST = "first"
        const val EXTRA_MIDDLE = "middle"
        private const val CHANNEL = "search"
        private const val NOTIFICATION_ID = 7
    }
}
