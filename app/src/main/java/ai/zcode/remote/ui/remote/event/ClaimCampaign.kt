package ai.zcode.remote.ui.remote.event

import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/** Only claimable plan deliveries are accepted; unrelated marketing content is ignored. */
data class ClaimCampaign(val id: String, val scope: String, val planId: String, val title: String, val detail: String) {
    val key: String get() = "$scope|$id|$planId"

    companion object {
        fun parse(body: String): List<ClaimCampaign> = runCatching {
            if (body.length > 256 * 1024) return emptyList()
            val root = JSONObject(body)
            val scope = root.optString("scope")
            val deliveries = root.optJSONArray("deliveries") ?: return emptyList()
            (0 until deliveries.length()).mapNotNull { index ->
                val delivery = deliveries.optJSONObject(index) ?: return@mapNotNull null
                val banner = delivery.optJSONObject("banner") ?: delivery.optJSONObject("popup")
                    ?: return@mapNotNull null
                val buttons = banner.optJSONArray("buttons") ?: return@mapNotNull null
                val action = (0 until buttons.length()).mapNotNull {
                    buttons.optJSONObject(it)?.optJSONObject("action")
                }.firstOrNull { it.optString("type") == "claim_zcode_plan" } ?: return@mapNotNull null
                val planId = action.optJSONObject("args")?.optString("plan_id").orEmpty()
                val id = delivery.optString("campaign_id")
                if (id.isBlank() || planId.isBlank() || scope.isBlank()) return@mapNotNull null
                val resource = banner.optJSONObject("background") ?: banner.optJSONObject("hero")
                val plan = resource?.optJSONObject("args")?.optJSONObject("zcode_plan")
                val benefits = plan?.optJSONArray("entitlements")
                val detail = (0 until (benefits?.length() ?: 0)).mapNotNull benefit@ {
                    val benefit = benefits?.optJSONObject(it) ?: return@benefit null
                    val amount = benefit.optDouble("grant_units", 0.0)
                    if (!amount.isFinite() || amount <= 0) return@benefit null
                    val unit = if (benefit.optString("unit_type") == "token") "tokens" else benefit.optString("unit_type")
                    "${benefit.optString("show_name")} ${NumberFormat.getNumberInstance(Locale.US).format(amount)} $unit"
                }.joinToString(" · ")
                ClaimCampaign(id, scope, planId, plan?.optString("name").orEmpty().ifBlank { "可领取活动" },
                    detail.ifBlank { "活动权益可领取" })
            }.distinctBy { it.key }
        }.getOrDefault(emptyList())
    }
}
