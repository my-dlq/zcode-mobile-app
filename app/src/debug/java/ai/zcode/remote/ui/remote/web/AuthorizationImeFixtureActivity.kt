package ai.zcode.remote.ui.remote.web

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout
import android.webkit.WebView
import ai.zcode.remote.data.model.ConnectionLoginSpace
import java.util.concurrent.CountDownLatch

/** 真正附着到 Window 的空白测试容器，不启动产品 Activity。 */
class AuthorizationImeFixtureActivity : Activity() {
    val ready = CountDownLatch(1)
    lateinit var content: FrameLayout
        private set
    var opener: WebView? = null
    var transport: WebView? = null
    var authorization: WebView? = null
    var closeController: (() -> Unit)? = null
    lateinit var loginSpace: ConnectionLoginSpace
    var profileSupported: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = FrameLayout(this)
        setContentView(content)
        ready.countDown()
    }

    override fun onDestroy() {
        closeController?.invoke()
        opener?.apply {
            (parent as? android.view.ViewGroup)?.removeView(this)
            stopLoading()
            destroy()
        }
        super.onDestroy()
    }
}
