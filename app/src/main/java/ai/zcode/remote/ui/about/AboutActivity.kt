
package ai.zcode.remote.ui.about

import androidx.appcompat.app.AppCompatActivity

import ai.zcode.remote.BuildConfig
import ai.zcode.remote.R
import ai.zcode.remote.databinding.ActivityAboutBinding
import ai.zcode.remote.ui.main.UpdateCheckFlow
import ai.zcode.remote.utils.ToastUtils
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * 关于页：应用信息、版本号、检查更新入口、GitHub 项目主页跳转。
 * 检查更新逻辑复用 UpdateCheckFlow（与 MainActivity 启动静默检查同一条链路）。
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private lateinit var updateFlow: UpdateCheckFlow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateFlow = UpdateCheckFlow(this)

        binding.tvAboutVersion.text = getString(
            R.string.about_version_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )

        binding.btnBack.setOnClickListener { finish() }

        binding.rowCheckUpdate.setOnClickListener {
            updateFlow.check(manual = true)
        }

        binding.rowGithub.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
            } catch (e: Exception) {
                ToastUtils.show(this, getString(R.string.toast_no_browser))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        updateFlow.shutdown()
    }

    companion object {
        private const val GITHUB_URL = "https://github.com/my-dlq/zcode-mobile-app"

        fun start(context: Context) {
            context.startActivity(Intent(context, AboutActivity::class.java))
        }
    }
}
