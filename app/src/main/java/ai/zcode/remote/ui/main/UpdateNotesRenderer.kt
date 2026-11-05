package ai.zcode.remote.ui.main

import ai.zcode.remote.R
import ai.zcode.remote.utils.ToastUtils
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.movement.MovementMethodPlugin

/** 更新说明只生成原生文本样式，不执行 HTML 或自动加载远程图片。 */
internal object UpdateNotesRenderer {
    fun render(view: TextView, markdown: String) {
        val primary = MaterialColors.getColor(view, R.attr.zcPrimary)
        val textColor = view.currentTextColor
        val surface = MaterialColors.getColor(view, R.attr.zcBgSurfaceElevated)
        val border = MaterialColors.getColor(view, R.attr.zcBorderColor)
        val density = view.resources.displayMetrics.density

        Markwon.builder(view.context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create { theme ->
                theme.tableBorderColor(border)
                    .tableHeaderRowBackgroundColor(surface)
                    .tableOddRowBackgroundColor(0)
                    .tableCellPadding((4 * density).toInt())
            })
            .usePlugin(TaskListPlugin.create(primary, textColor, surface))
            // 链接点击和原有的长说明滚动共用 MovementMethod，表格内链接也能响应。
            .usePlugin(MovementMethodPlugin.create(TableAwareMovementMethod.create()))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.linkColor(primary)
                        .codeTextColor(textColor)
                        .codeBackgroundColor(surface)
                        .codeBlockBackgroundColor(surface)
                        .blockQuoteColor(border)
                        .thematicBreakColor(border)
                        .blockMargin((16 * density).toInt())
                        .headingTextSizeMultipliers(floatArrayOf(1.35f, 1.2f, 1.1f, 1f, 1f, 1f))
                }

                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { clicked, link ->
                        val uri = Uri.parse(link)
                        // Release 正文来自远端，只允许明确的网页链接跳转到外部浏览器。
                        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                            return@linkResolver
                        }
                        try {
                            clicked.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (_: ActivityNotFoundException) {
                            ToastUtils.show(clicked.context, clicked.context.getString(R.string.toast_no_browser))
                        }
                    }
                }
            })
            .build()
            .setMarkdown(view, markdown)
    }
}
