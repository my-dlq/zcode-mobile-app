package ai.zcode.remote.ui.main

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ai.zcode.remote.R
import ai.zcode.remote.data.model.RemoteConnection
import ai.zcode.remote.data.repository.ConnectionRepository
import ai.zcode.remote.databinding.ActivityMainBinding
import ai.zcode.remote.ui.remote.RemoteControlActivity
import ai.zcode.remote.ui.scan.QrScanActivity
import ai.zcode.remote.utils.ToastUtils
import ai.zcode.remote.utils.UrlParser

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ConnectionRepository
    private lateinit var adapter: ConnectionAdapter
    private var clipboardUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ConnectionRepository.getInstance(this)

        initRecyclerView()
        initListeners()
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        checkClipboardForZCodeUrl()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
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
                RemoteControlActivity.start(this, connection.url, connection.name, startInSettingsMode = false)
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
            onSetDefaultClick = { connection, isDefault ->
                repository.setDefaultConnection(connection.id, isDefault)
                refreshList()
                ToastUtils.show(
                    this,
                    if (isDefault) "已设为默认连接" else "已取消默认连接"
                )
            }
        )

        binding.rvConnections.layoutManager = LinearLayoutManager(this)
        binding.rvConnections.adapter = adapter
    }

    private fun initListeners() {
        binding.btnScanQr.setOnClickListener {
            QrScanActivity.start(this)
        }

        binding.btnAddManual.setOnClickListener {
            showAddDialog()
        }

        binding.btnEmptyScan.setOnClickListener {
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
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.rvConnections.visibility = View.GONE
            binding.tvConnectionCount.text = "共 0 台设备"
        } else {
            binding.layoutEmpty.visibility = View.GONE
            binding.rvConnections.visibility = View.VISIBLE
            binding.tvConnectionCount.text = "共 ${list.size} 台设备"
        }
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
