
package ai.zcode.remote.ui.main

import androidx.appcompat.app.AppCompatActivity

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import ai.zcode.remote.R
import ai.zcode.remote.data.model.RemoteConnection
import ai.zcode.remote.data.repository.ConnectionRepository
import ai.zcode.remote.databinding.ActivityMainBinding
import ai.zcode.remote.databinding.PopupMainMenuBinding
import ai.zcode.remote.ui.about.AboutActivity
import ai.zcode.remote.ui.remote.RemoteControlActivity
import ai.zcode.remote.ui.scan.QrScanActivity
import ai.zcode.remote.ui.settings.SettingsActivity
import ai.zcode.remote.ui.security.SecuritySession
import ai.zcode.remote.ui.security.SecurityVerifyActivity
import ai.zcode.remote.data.repository.AppSettingsRepository
import ai.zcode.remote.utils.ToastUtils
import ai.zcode.remote.utils.UrlParser

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ConnectionRepository
    private lateinit var adapter: ConnectionAdapter
    private var clipboardUrl: String? = null
    private lateinit var updateFlow: UpdateCheckFlow
    private var mainMenuPopup: PopupWindow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppSettingsRepository.getInstance(this).isSecurityVerificationEnabled() &&
            !SecuritySession.isUnlocked
        ) {
            SecurityVerifyActivity.start(this, setupMode = false)
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ConnectionRepository.getInstance(this)
        updateFlow = UpdateCheckFlow(this)

        initRecyclerView()
        initListeners()
        handleIncomingIntent(intent)
        updateFlow.check(manual = false)

        // 进程被系统回收后从 launcher 恢复：intent 无 data 且存在活跃连接
        // → 自动重新打开远程页并跳转到上次的任务会话
        if (savedInstanceState == null && intent.data == null &&
            intent.action == Intent.ACTION_MAIN
        ) {
            val lastUrl = repository.getLastActiveUrl()
            if (lastUrl != null) {
                val lastName = repository.getLastActiveName() ?: ""
                val lastTaskId = repository.getLastActiveTaskId()
                ai.zcode.remote.ui.remote.RemoteControlActivity.start(
                    this, lastUrl, lastName, taskId = lastTaskId
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        checkClipboardForZCodeUrl()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 从桌面图标切回（launcher intent 无 data）：若任务栈中本 Activity 之上
        // 还有其他页面（如远程控制页），直接 finish 让栈顶页面自然显示；
        // 若栈中只有首页则正常处理（finish 会把用户弹回桌面）
        if (intent.action == Intent.ACTION_MAIN && intent.data == null && hasActivityAbove()) {
            finish()
            return
        }
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /** 检查任务栈中本 Activity 之上是否还有其他 Activity。 */
    private fun hasActivityAbove(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val tasks = am.appTasks ?: return false
        for (task in tasks) {
            val info = task.taskInfo ?: continue
            // 栈顶 Activity 不是自己 → 说明上面有其他页面
            val top = info.topActivity ?: continue
            if (top.className != javaClass.name && packageName in top.packageName) {
                return true
            }
        }
        return false
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null) {
            val url = data.toString()
            val parsed = UrlParser.parse(url)
            if (!UrlParser.isValidRemoteConnection(parsed)) {
                ToastUtils.show(this, getString(R.string.error_invalid_url))
                return
            }
            val connection = RemoteConnection(
                name = parsed.suggestedName,
                url = parsed.originalUrl,
                mid = parsed.mid,
                sid = parsed.sid
            )
            repository.saveConnection(connection)
            RemoteControlActivity.start(this, connection.url, connection.name)
        }
    }

    private fun initRecyclerView() {
        adapter = ConnectionAdapter(
            items = emptyList(),
            onConnectClick = { connection ->
                repository.updateLastConnected(connection.id)
                RemoteControlActivity.start(
                    this, connection.url, connection.name,
                    startInSettingsMode = false,
                    taskId = connection.lastTaskId
                )
            },
            onSettingsClick = { connection ->
                repository.updateLastConnected(connection.id)
                RemoteControlActivity.start(this, connection.url, connection.name, startInSettingsMode = true)
                ToastUtils.show(this, "正在以配置中心模式连接...")
            },
            onEditClick = { connection ->
                showEditDialog(connection)
            },
            onDeleteClick = { connection ->
                showDeleteConfirmDialog(connection)
            }
        )

        binding.rvConnections.layoutManager = LinearLayoutManager(this)
        binding.rvConnections.adapter = adapter
    }

    private fun initListeners() {
        binding.btnMenu.setOnClickListener {
            showMainMenu()
        }

        binding.layoutEmpty.setOnClickListener {
            QrScanActivity.start(this)
        }

        binding.btnQuickConnect.setOnClickListener {
            clipboardUrl?.let { url ->
                val parsed = UrlParser.parse(url)
                if (!UrlParser.isValidRemoteConnection(parsed)) {
                    ToastUtils.show(this, getString(R.string.error_invalid_url))
                    return@setOnClickListener
                }
                val conn = RemoteConnection(
                    name = parsed.suggestedName,
                    url = parsed.originalUrl,
                    mid = parsed.mid,
                    sid = parsed.sid
                )
                repository.saveConnection(conn)
                RemoteControlActivity.start(this, conn.url, conn.name)
            }
        }
    }

    private fun refreshList() {
        val list = repository.getAllConnections()
        adapter.updateData(list)

        if (list.isEmpty()) {
            binding.connectionHeader.visibility = View.GONE
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.cardConnectionList.visibility = View.GONE
            binding.tvConnectionCount.text = "共 0 台设备"
        } else {
            binding.connectionHeader.visibility = View.VISIBLE
            binding.layoutEmpty.visibility = View.GONE
            binding.cardConnectionList.visibility = View.VISIBLE
            binding.tvConnectionCount.text = "共 ${list.size} 台设备"
        }
    }

    private fun showMainMenu() {
        mainMenuPopup?.dismiss()

        val menuBinding = PopupMainMenuBinding.inflate(layoutInflater)
        val popupWidth = resources.getDimensionPixelSize(R.dimen.main_menu_width)
        val popup = PopupWindow(
            menuBinding.root,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = resources.displayMetrics.density * 12f
            isOutsideTouchable = true
            setBackgroundDrawable(ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_menu_popup))
            setOnDismissListener {
                if (mainMenuPopup === this) mainMenuPopup = null
            }
        }

        menuBinding.menuItemScan.setOnClickListener {
            popup.dismiss()
            QrScanActivity.start(this)
        }
        menuBinding.menuItemAddress.setOnClickListener {
            popup.dismiss()
            showAddDialog()
        }
        menuBinding.menuItemSettings.setOnClickListener {
            popup.dismiss()
            SettingsActivity.start(this)
        }
        menuBinding.menuItemAbout.setOnClickListener {
            popup.dismiss()
            AboutActivity.start(this)
        }

        mainMenuPopup = popup
        popup.showAsDropDown(binding.btnMenu, -popupWidth + binding.btnMenu.width, 8)
    }

    private fun checkClipboardForZCodeUrl() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!clipText.isNullOrBlank() && UrlParser.isLikelyZCodeUrl(clipText)) {
                clipboardUrl = clipText
                binding.tvClipboardSnippet.text = clipText
                binding.cardClipboardPrompt.visibility = View.VISIBLE
            } else {
                clipboardUrl = null
                binding.cardClipboardPrompt.visibility = View.GONE
            }
        } catch (e: Exception) {
            binding.cardClipboardPrompt.visibility = View.GONE
        }
    }

    private fun showAddDialog() {
        AddConnectionDialog.newInstance(null) {
            refreshList()
        }.show(supportFragmentManager, "AddConnectionDialog")
    }

    private fun showEditDialog(connection: RemoteConnection) {
        AddConnectionDialog.newInstance(connection) {
            refreshList()
        }.show(supportFragmentManager, "EditConnectionDialog")
    }

    private fun showDeleteConfirmDialog(connection: RemoteConnection) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(getString(R.string.confirm_delete_msg, connection.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                repository.deleteConnection(connection.id)
                refreshList()
                ToastUtils.show(this, "已删除")
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroy() {
        mainMenuPopup?.dismiss()
        mainMenuPopup = null
        super.onDestroy()
    }
}
