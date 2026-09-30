package ai.zcode.remote.ui.remote.event

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ai.zcode.remote.R
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.data.repository.ConnectionRepository
import ai.zcode.remote.ui.remote.RemoteControlActivity
import org.json.JSONObject

/** Delivery does not acknowledge an offer: only a user click stops subsequent reminders. */
object ClaimCampaignNotifier {
    private const val CHANNEL = "zcode_claim_campaigns"
    private const val PREFS = "zcode_claim_campaigns"
    private const val EXTRA_KEY = "claim_campaign_key"
    private const val EXTRA_SOURCE = "claim_campaign_source"

    @Synchronized
    fun receive(context: Context, sourceId: String, deviceName: String, body: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val deliveredAt = runCatching { JSONObject(body).optLong("serverTime", 0L) }.getOrDefault(0L)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "活动领取提醒", NotificationManager.IMPORTANCE_HIGH))
        }
        for (campaign in ClaimCampaign.parse(body)) {
            val key = "$sourceId|${campaign.key}"
            if (prefs.getBoolean(key, false)) continue
            // A mirrored/replayed copy of the same response is not a new server delivery.
            if (deliveredAt > 0 && prefs.getLong("delivery|$key", 0L) >= deliveredAt) continue
            if (!AppSettingsRepository.getInstance(context).isNotificationEnabled() ||
                !NotificationManagerCompat.from(context).areNotificationsEnabled()) continue
            val connection = ConnectionRepository.getInstance(context).getAllConnections()
                .firstOrNull { it.id == sourceId || it.url == sourceId }
                ?: ConnectionRepository.getInstance(context).getAllConnections().firstOrNull { it.name == deviceName }
                ?: continue
            val launch = Intent(context, RemoteControlActivity::class.java).apply {
                putExtra(RemoteControlActivity.EXTRA_URL, connection.url)
                putExtra(RemoteControlActivity.EXTRA_NAME, connection.name)
                putExtra(EXTRA_KEY, campaign.key)
                putExtra(EXTRA_SOURCE, sourceId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(context, key.hashCode(), launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${campaign.title} · 领取活动")
                .setContentText("${campaign.detail} · $deviceName")
                .setStyle(NotificationCompat.BigTextStyle().bigText("${campaign.detail} · $deviceName"))
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(false)
                .setAutoCancel(true)
                .build()
            runCatching { manager.notify(key.hashCode(), notification) }
                .onSuccess {
                    if (deliveredAt > 0) prefs.edit().putLong("delivery|$key", deliveredAt).apply()
                    Log.i("ZCodeClaim", "campaign notification posted: ${campaign.id}")
                }
                .onFailure { Log.w("ZCodeClaim", "campaign notification failed", it) }
        }
    }

    @Synchronized
    fun acknowledge(context: Context, sourceId: String, campaignKey: String) {
        if (sourceId.isBlank() || campaignKey.isBlank()) return
        val key = "$sourceId|$campaignKey"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, true).apply()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(key.hashCode())
    }

    fun acknowledgeIntent(context: Context, intent: Intent) {
        acknowledge(context, intent.getStringExtra(EXTRA_SOURCE).orEmpty(), intent.getStringExtra(EXTRA_KEY).orEmpty())
    }
}
