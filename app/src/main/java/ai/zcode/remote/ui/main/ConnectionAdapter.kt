package ai.zcode.remote.ui.main

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ai.zcode.remote.R
import ai.zcode.remote.data.model.RemoteConnection
import ai.zcode.remote.databinding.ItemConnectionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConnectionAdapter(
    private var items: MutableList<RemoteConnection>,
    private val onConnectClick: (RemoteConnection) -> Unit,
    private val onSettingsClick: (RemoteConnection) -> Unit,
    private val onEditClick: (RemoteConnection) -> Unit,
    private val onDeleteClick: (RemoteConnection) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {},
    private val onOrderChanged: (List<String>) -> Unit = {},
) : RecyclerView.Adapter<ConnectionAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun updateData(newItems: List<RemoteConnection>) {
        this.items = newItems.toMutableList()
        notifyDataSetChanged()
    }

    /** ItemTouchHelper 拖动回调：交换两个位置的元素。 */
    fun onItemMove(fromPosition: Int, toPosition: Int) {
        val item = items.removeAt(fromPosition)
        items.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
    }

    /** ItemTouchHelper 拖动结束：通知外部保存新顺序。 */
    fun onMoveFinished() {
        onOrderChanged(items.map { it.id })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConnectionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position == items.lastIndex)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemConnectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RemoteConnection, isLastItem: Boolean) {
            binding.tvDeviceName.text = item.name
            binding.tvDeviceUrl.text = item.url
            binding.tvDeviceUrl.visibility = View.GONE

            val formattedTime = dateFormat.format(Date(item.lastConnectedTime))
            binding.tvLastConnectedLabel.visibility = View.GONE
            binding.tvLastConnectedTime.text = formattedTime
            binding.btnSettings.visibility = View.GONE
            binding.btnConnect.visibility = View.GONE
            binding.viewConnectionStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    binding.root.context,
                    if (item.isConnected) R.color.status_connected else R.color.status_disconnected
                )
            )
            binding.connectionDivider.visibility = if (isLastItem) View.GONE else View.VISIBLE

            binding.root.setOnClickListener {
                onConnectClick(item)
            }

            binding.btnSettings.setOnClickListener {
                onSettingsClick(item)
            }

            binding.btnConnect.setOnClickListener {
                onConnectClick(item)
            }

            binding.btnMore.setOnClickListener { view ->
                showPopupMenu(view, item)
            }
        }

        private fun showPopupMenu(anchorView: View, item: RemoteConnection) {
            val popup = PopupMenu(anchorView.context, anchorView)
            val menu = popup.menu

            menu.add(0, 1, 0, anchorView.context.getString(R.string.action_edit))
            menu.add(0, 2, 1, anchorView.context.getString(R.string.action_copy_url))
            menu.add(0, 3, 2, anchorView.context.getString(R.string.action_delete))

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> {
                        onEditClick(item)
                        true
                    }
                    2 -> {
                        copyUrlToClipboard(anchorView, item)
                        true
                    }
                    3 -> {
                        onDeleteClick(item)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        /** 复制连接地址到剪贴板并提示。 */
        private fun copyUrlToClipboard(anchorView: View, item: RemoteConnection) {
            val context = anchorView.context
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("url", item.url)
            )
            android.widget.Toast.makeText(
                context, context.getString(R.string.toast_url_copied), android.widget.Toast.LENGTH_SHORT
            ).show()
        }

    }
}
