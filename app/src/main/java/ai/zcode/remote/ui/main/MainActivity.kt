
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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
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

        // singleTask 已移除（改 standard 以保住后台 Remote 的 WebView）。
        // 需要手动单实例去重 + launcher 切回恢复：
        // - 从 Remote 返回列表启动的本实例：清理旧 Main 实例，保留自己
        // - launcher 切回（用户上次停留在远程页）：自删，让栈顶 Remote 自然显示
        val fromRemoteReturn = intent.getBooleanExtra(EXTRA_FROM_REMOTE, false)
        val remoteAlive = RemoteControlActivity.hasLiveInstance()
        if (!fromRemoteReturn && intent.action == Intent.ACTION_MAIN &&
            intent.data == null && remoteAlive && lastVisiblePage == "remote"
        ) {
            // 用户上次停留在远程页时切出：新 Main 自删，恢复显示远程页
            finish()
            return
        }
        // 单实例去重：清理栈中旧的 Main（可能被 Remote 或其他页面压住）
        current?.takeIf { it !== this }?.finish()
        current = this

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ConnectionRepository.getInstance(this)
        updateFlow = UpdateCheckFlow(this)

        initRecyclerView()
        initListeners()
        handleIncomingIntent(intent)
        updateFlow.check(manual = false)

        // 进程被系统回收后从 launcher 恢复：intent 无 data 且无存活远程页
        // → 自动重新打开远程页并跳转到上次的任务会话
        if (savedInstanceState == null && intent.data == null &&
            intent.action == Intent.ACTION_MAIN && !remoteAlive
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
        lastVisiblePage = "main"
        refreshList()
        checkClipboardForZCodeUrl()
    }

    override fun onDestroy() {
        if (current === this) current = null
        mainMenuPopup?.dismiss()
        mainMenuPopup = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    companion object {
        /** 从远程页"返回列表"启动 Main 时的标记（standard 模式下区分来源）。 */
        const val EXTRA_FROM_REMOTE = "extra_from_remote"

        /** 唯一的 MainActivity 实例引用（standard 模式单实例去重）。 */
        @Volatile
        private var current: MainActivity? = null

        /** 上次前台页面（main/remote），供 launcher 切回时恢复。 */
        @Volatile
        private var lastVisiblePage: String = "main"

        /** 各页面在 onResume 时更新"当前可见页面"。 */
        fun markVisiblePage(page: String) {
            lastVisiblePage = page
        }
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
            items = mutableListOf(),
            onConnectClick = { connection ->
                repository.updateLastConnected(connection.id)
                // 正常点击连接不传 taskId：停留在任务列表页，让用户自己选会话
                RemoteControlActivity.start(
                    this, connection.url, connection.name,
                    startInSettingsMode = false
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
            },
            onStartDrag = { viewHolder ->
                itemTouchHelper?.startDrag(viewHolder)
            },
            onOrderChanged = { orderedIds ->
                repository.saveOrder(orderedIds)
            }
        )

        binding.rvConnections.layoutManager = LinearLayoutManager(this)
        binding.rvConnections.adapter = adapter

        // 长按拖动排序：用户手动调整连接顺序，APP 不自动重排
        // 现代拖动样式：选中项抬高+阴影+微放大，结束后回弹归位
        itemTouchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean {
                    adapter.onItemMove(viewHolder.adapterPosition, target.adapterPosition)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    when (actionState) {
                        ItemTouchHelper.ACTION_STATE_DRAG -> {
                            viewHolder?.itemView?.let { v ->
                                // 抬高 + 阴影 + 轻微放大，呈现"被拿起来"的浮起效果
                                v.animate()
                                    .translationZ(12f * resources.displayMetrics.density)
                                    .scaleX(1.02f)
                                    .scaleY(1.02f)
                                    .setDuration(150)
                                    .start()
                            }
                        }
                    }
                }

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ) {
                    super.clearView(recyclerView, viewHolder)
                    // 回弹归位：阴影与缩放复位
                    viewHolder.itemView.animate()
                        .translationZ(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .start()
                    adapter.onMoveFinished()
                }

                override fun isLongPressDragEnabled(): Boolean = true // ItemTouchHelper 自动处理长按
            }
        )
        itemTouchHelper?.attachToRecyclerView(binding.rvConnections)
    }

    private var itemTouchHelper: ItemTouchHelper? = null

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
            binding.tvDragSortHint.visibility = View.GONE
            binding.tvConnectionCount.text = "共 0 台设备"
        } else {
            binding.connectionHeader.visibility = View.VISIBLE
            binding.layoutEmpty.visibility = View.GONE
            binding.cardConnectionList.visibility = View.VISIBLE
            binding.tvDragSortHint.visibility = View.VISIBLE
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
}
