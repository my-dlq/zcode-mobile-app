package ai.zcode.remote.utils

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {
    private fun release(vararg names: String): String = """{
        "tag_name":"v0.3.3","assets":[${names.joinToString(",") {
            """{"name":"$it","browser_download_url":"https://example.invalid/$it","size":10}"""
        }}]
    }"""

    @Test fun `debug asset before release cannot become the update`() {
        val info = UpdateChecker.parseRelease(release("zcode-mobile-app-v0.3.3-debug.apk", "zcode-mobile-app-v0.3.3.apk"))
        assertEquals("https://example.invalid/zcode-mobile-app-v0.3.3.apk", info?.downloadUrl)
    }

    @Test fun `debug only release offers no update`() {
        assertNull(UpdateChecker.parseRelease(release("zcode-mobile-app-v0.3.3-debug.apk")))
    }

    @Test fun `wrong version and arbitrary apk cannot become the update`() {
        assertNull(UpdateChecker.parseRelease(release("zcode-mobile-app-v0.3.2.apk", "other.apk")))
    }

    @Test fun `release tag and asset case remain compatible`() {
        assertNotNull(UpdateChecker.parseRelease(release("ZCODE-MOBILE-APP-V0.3.3.APK").replace("v0.3.3\"", "V0.3.3\"")))
    }
}
