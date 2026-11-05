package ai.zcode.remote.ui.main

import ai.zcode.remote.R
import ai.zcode.remote.data.model.UpdateInfo
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.ui.settings.SettingsActivity
import ai.zcode.remote.utils.ThemeHelper
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.color.MaterialColors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 使用独立 debug 包验证真实更新弹窗，不访问下载或安装接口。 */
@RunWith(AndroidJUnit4::class)
class UpdateDialogMarkdownTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun withDialog(notes: String, verify: (UpdateDialog, TextView) -> Unit) {
        check(context.packageName.endsWith(".debug"))
        val host = instrumentation.startActivitySync(
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as SettingsActivity
        val dialog = UpdateDialog.newInstance(UpdateInfo(
            "v0.3.4", "0.3.4", "https://example.invalid/update.apk", 8 * 1024 * 1024, notes
        ))
        try {
            instrumentation.runOnMainSync {
                dialog.showNow(host.supportFragmentManager, "markdown-update")
            }
            instrumentation.waitForIdleSync()
            // 等待弹窗切换稳定，避免手势坐标及截图落在窗口进场动画中。
            instrumentation.uiAutomation.waitForIdle(400, 4_000)
            verify(dialog, dialog.requireDialog().findViewById(R.id.tvReleaseNotes))
        } finally {
            instrumentation.runOnMainSync {
                if (dialog.isAdded) dialog.dismissNow()
                host.finish()
            }
        }
    }

    @Test fun markdownProducesRichTextInsteadOfLiteralSyntax() = withDialog(MARKDOWN) { _, view ->
        instrumentation.runOnMainSync {
            val text = view.text as Spanned
            assertTrue(text.contains("v0.3.4 更新说明"))
            assertFalse(text.contains("## "))
            assertFalse(text.contains("**Markdown**"))
            assertFalse(text.contains("`APK`"))
            assertFalse(text.contains("~~旧样式~~"))
            val spans = text.getSpans(0, text.length, Any::class.java).map { it.javaClass.simpleName }.toSet()
            for (type in listOf("HeadingSpan", "StrongEmphasisSpan", "EmphasisSpan", "BulletListItemSpan",
                "OrderedListItemSpan", "BlockQuoteSpan", "CodeSpan", "CodeBlockSpan", "LinkSpan",
                "StrikethroughSpan", "TaskListSpan", "TableRowSpan")) {
                assertTrue("缺少渲染样式：$type，实际 $spans", type in spans)
            }
            assertNotNull(view.movementMethod)
        }
    }

    @Test fun emptyAndPlainNotesRemainCompatible() {
        withDialog("   ") { _, view -> assertEquals(View.GONE, view.visibility) }
        withDialog("修复通知提醒，支持直接覆盖安装。") { _, view ->
            assertEquals("修复通知提醒，支持直接覆盖安装。", view.text.toString())
        }
    }

    @Test fun linksOnlyLaunchWebAddresses() = withDialog(
        "[网站](https://example.invalid) [网页](http://example.invalid) " +
            "[脚本](javascript:alert%281%29) [文件](file:///sdcard/example.txt)"
    ) { _, view ->
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addDataScheme("http")
            addDataScheme("https")
            addDataScheme("javascript")
            addDataScheme("file")
        }
        // 拦截所有跳转，只检查是否正确分派，不实际打开网站。
        val monitor = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            instrumentation.runOnMainSync {
                val text = view.text as Spanned
                val links = text.getSpans(0, text.length, ClickableSpan::class.java)
                assertEquals(4, links.size)
                links.forEach { it.onClick(view) }
            }
            assertEquals(2, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun longNotesCanScrollAndKeepActionsVisible() = withDialog(
        MARKDOWN + "\n\n" + (1..30).joinToString("\n") { "- 第 $it 项：更新说明可以持续向下滚动查看。" }
    ) { dialog, _ ->
        val scroll = dialog.requireDialog().findViewById<UpdateNotesScrollView>(R.id.scrollReleaseNotes)
        val bounds = Rect()
        instrumentation.runOnMainSync {
            assertTrue(scroll.canScrollVertically(1))
            assertTrue(scroll.getGlobalVisibleRect(bounds))
            assertTrue(dialog.requireDialog().findViewById<View>(R.id.btnUpdateNow).isShown)
        }
        val down = SystemClock.uptimeMillis()
        for (step in 0..12) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val y = bounds.bottom - 12f - (bounds.height() - 24f) * step / 12
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
            event.recycle()
            SystemClock.sleep(20)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertTrue("手势应滚动更新说明", scroll.scrollY > 0) }
    }

    @Test fun rendersWithAllThreeAppThemes() {
        val settings = AppSettingsRepository.getInstance(context)
        val original = settings.getThemeMode()
        val originalNight = AppCompatDelegate.getDefaultNightMode()
        try {
            for (mode in AppSettingsRepository.ThemeMode.values()) {
                instrumentation.runOnMainSync {
                    settings.setThemeMode(mode)
                    AppCompatDelegate.setDefaultNightMode(ThemeHelper.nightModeFor(mode))
                }
                withDialog(MARKDOWN) { _, view ->
                    instrumentation.runOnMainSync {
                        assertEquals(MaterialColors.getColor(view, R.attr.zcTextSecondary), view.currentTextColor)
                        assertEquals(MaterialColors.getColor(view, R.attr.zcPrimary), view.linkTextColors.defaultColor)
                    }
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    assertNotNull(screenshot)
                    val file = File(context.getExternalFilesDir(null), "update-markdown-${mode.value}.png")
                    file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    screenshot.recycle()
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                settings.setThemeMode(original)
                AppCompatDelegate.setDefaultNightMode(originalNight)
            }
        }
    }

    private companion object {
        val MARKDOWN = """
            ## v0.3.4 更新说明

            ### 优化

            - 更新说明支持 **Markdown**，可以查看 *重点内容* 和 `APK` 信息。
            - ~~旧样式~~ 已优化，长内容可滚动阅读。

            ### 修复

            1. 修复通知展示。
            2. 保留更新操作。

            > 支持覆盖安装，保留连接和设置。

            [查看完整说明](https://example.invalid/releases)

            ```text
            versionName = "0.3.4"
            ```

            - [x] 支持富文本
            - [ ] 更多优化

            | 项目 | 状态 |
            | --- | --- |
            | 更新说明 | 已优化 |
        """.trimIndent()
    }
}
