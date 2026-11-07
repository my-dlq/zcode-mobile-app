package ai.zcode.remote.ui.remote.web

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceSidebarViewportTest {
    @Test
    fun landscapePhoneGetsWideSidebarLayout() {
        assertEquals(
            "width=768, initial-scale=0.9661458, maximum-scale=0.9661458, user-scalable=no",
            WorkspaceSidebarViewport.content(742, 100, eligible = true),
        )
    }

    @Test
    fun portraitPhoneKeepsMobileLayout() {
        assertEquals(
            "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no",
            WorkspaceSidebarViewport.content(379, 100, eligible = false),
        )
    }

    @Test
    fun alreadyWideLayoutKeepsPageZoom() {
        assertEquals(
            "width=800, initial-scale=0.95, maximum-scale=0.95, user-scalable=no",
            WorkspaceSidebarViewport.content(760, 95, eligible = true),
        )
    }
}
