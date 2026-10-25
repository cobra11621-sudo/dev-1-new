package com.cobra.dev1new.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cobra.dev1new.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Records a user-started KTX trip locally; it never uploads location data. */
class KtxRouteRecorderService : Service() {
    private lateinit var store: KtxRouteStore
    private var manager: LocationManager? = null
    private var listener: LocationListener? = null

    override fun onCreate() {
        super.onCreate()
        store = KtxRouteStore(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> store.startNew()
            ACTION_STOP -> {
                store.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (!store.status().recording) return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, notification())
        subscribe()
        return START_STICKY
    }

    override fun onDestroy() {
        listener?.let { active -> manager?.let { runCatching { it.removeUpdates(active) } } }
        listener = null
        super.onDestroy()
    }

    private fun subscribe() {
        if (listener != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val active = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (store.appendIfFarEnough(location)) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                }
            }
        }
        manager = locationManager
        listener = active
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(provider, 2_000L, 0f, active, Looper.getMainLooper())
                }
            }
        }
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_transit)
        .setContentTitle("KTX 경로 기록 중")
        .setContentText("약 200m마다 위치 저장 · ${store.status().pointCount}개 기록")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "KTX 경로 기록", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null); enableVibration(false); setShowBadge(false)
                }
            )
        }
    }

    companion object {
        const val ACTION_START = "com.cobra.dev1new.action.START_KTX_ROUTE_RECORDING"
        const val ACTION_STOP = "com.cobra.dev1new.action.STOP_KTX_ROUTE_RECORDING"
        private const val CHANNEL_ID = "dev1native_ktx_route_recording"
        private const val NOTIFICATION_ID = 4210
        fun start(context: Context) {
            KtxRouteStore(context).startNew()
            ContextCompat.startForegroundService(context, Intent(context, KtxRouteRecorderService::class.java))
        }
        fun stop(context: Context) {
            KtxRouteStore(context).stop()
            context.startService(Intent(context, KtxRouteRecorderService::class.java).setAction(ACTION_STOP))
        }
    }
}

data class KtxRouteRecordStatus(val recording: Boolean, val pointCount: Int, val file: File?)

class KtxRouteStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("ktx_route_recorder", Context.MODE_PRIVATE)
    private val output = File(context.filesDir, "ktx_route_recording.json")

    fun status() = KtxRouteRecordStatus(prefs.getBoolean("recording", false), prefs.getInt("point_count", 0), output.takeIf(File::exists))

    fun startNew() {
        val root = JSONObject().put("format", "dev1-new-ktx-route-v1")
            .put("sample_distance_meters", SAMPLE_DISTANCE_METERS)
            .put("started_at_epoch_millis", System.currentTimeMillis()).put("points", JSONArray())
        output.writeText(root.toString())
        prefs.edit().clear().putBoolean("recording", true).putInt("point_count", 0).apply()
    }

    fun stop() = prefs.edit().putBoolean("recording", false).apply()

    fun appendIfFarEnough(location: Location): Boolean {
        if (!status().recording) return false
        val lastLat = prefs.getString("last_lat", null)?.toDoubleOrNull()
        val lastLon = prefs.getString("last_lon", null)?.toDoubleOrNull()
        if (lastLat != null && lastLon != null) {
            val distance = FloatArray(1)
            Location.distanceBetween(lastLat, lastLon, location.latitude, location.longitude, distance)
            if (distance[0] < SAMPLE_DISTANCE_METERS) return false
        }
        val root = runCatching { JSONObject(output.readText()) }.getOrElse { return false }
        val point = JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
            .put("epoch_millis", location.time)
            // Keep the raw Android source facts. This lets us compare an
            // outbound and return trip instead of guessing why speed was absent.
            .put("provider", location.provider ?: JSONObject.NULL)
            .put("has_speed", location.hasSpeed())
            .put("speed_kmh", if (location.hasSpeed()) location.speed * 3.6 else JSONObject.NULL)
            .put("accuracy_meters", if (location.hasAccuracy()) location.accuracy else JSONObject.NULL)
        root.getJSONArray("points").put(point)
        output.writeText(root.toString())
        prefs.edit().putString("last_lat", location.latitude.toString()).putString("last_lon", location.longitude.toString())
            .putInt("point_count", root.getJSONArray("points").length()).apply()
        return true
    }

    companion object { const val SAMPLE_DISTANCE_METERS = 200f }
}
