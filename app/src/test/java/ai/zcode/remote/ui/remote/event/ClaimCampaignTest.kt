package ai.zcode.remote.ui.remote.event

import org.junit.Assert.*
import org.junit.Test

class ClaimCampaignTest {
    private val delivery = """{
        "scope":"account-device", "serverTime":123,
        "deliveries":[{"campaign_id":"campaign-1","resource_position":"banner",
        "banner":{"background":{"args":{"zcode_plan":{"name":"ZCode Trust Build",
        "entitlements":[{"show_name":"GLM-5.3-Flash","grant_units":100000000,"unit_type":"token"}]}}},
        "buttons":[{"action":{"type":"close"}},{"action":{"type":"claim_zcode_plan","args":{"plan_id":"trust-plan"}}}]}}]}
    """.trimIndent()

    @Test fun `real delivery exposes the advertised benefit and stable account identity`() {
        val campaign = ClaimCampaign.parse(delivery).single()
        assertEquals("ZCode Trust Build", campaign.title)
        assertEquals("GLM-5.3-Flash 100,000,000 tokens", campaign.detail)
        assertEquals("account-device|campaign-1|trust-plan", campaign.key)
    }

    @Test fun `redelivery is parsed and another account receives a distinct identity`() {
        val first = ClaimCampaign.parse(delivery).single()
        val next = ClaimCampaign.parse(delivery.replace("123", "456")).single()
        assertEquals(first, next)
        assertEquals(1, ClaimCampaign.parse(delivery).size)
        assertNotEquals(first.key, ClaimCampaign.parse(delivery.replace("account-device", "another-account")).single().key)
    }

    @Test fun `unrelated marketing and unscoped malformed data cannot create reminders`() {
        assertTrue(ClaimCampaign.parse(delivery.replace("claim_zcode_plan", "open_url")).isEmpty())
        assertTrue(ClaimCampaign.parse(delivery.replace("account-device", "")).isEmpty())
        assertTrue(ClaimCampaign.parse("not json").isEmpty())
        assertTrue(ClaimCampaign.parse("{}").isEmpty())
    }

    @Test fun `invalid entitlement still leaves a usable claim entry`() {
        val campaign = ClaimCampaign.parse(delivery.replace("100000000", "-1")).single()
        assertEquals("活动权益可领取", campaign.detail)
    }

    @Test fun `notification snapshot preserves exact activity and display without another query`() {
        val campaign = ClaimCampaign.parse(delivery).single()
        assertEquals(campaign, ClaimCampaign.fromJson(campaign.toJson()))
        val escaped = campaign.copy(title = "活动\"标题", detail = "模型\n100,000,000 tokens")
        assertEquals(escaped, ClaimCampaign.fromJson(escaped.toJson()))
    }

    @Test fun `invalid notification snapshot cannot open a claim prompt`() {
        assertNull(ClaimCampaign.fromJson("not json"))
        assertNull(ClaimCampaign.fromJson("{}"))
        assertNull(ClaimCampaign.fromJson(ClaimCampaign.parse(delivery).single().copy(planId = "").toJson()))
        assertNull(ClaimCampaign.fromJson("x".repeat(32 * 1024 + 1)))
    }
}
