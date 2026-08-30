package ai.zcode.remote.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import ai.zcode.remote.R
import ai.zcode.remote.data.model.RemoteConnection
import ai.zcode.remote.databinding.ItemConnectionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConnectionAdapter(
    private var items: List<RemoteConnection>,
    private val onConnectClick: (RemoteConnection) -> Unit,
    private val onSettingsClick: (RemoteConnection) -> Unit,
    private val onEditClick: (RemoteConnection) -> Unit,
    private val onDeleteClick: (RemoteConnection) -> Unit,
    private val onSetDefaultClick: (RemoteConnection, Boolean) -> Unit
) : RecyclerView.Adapter<ConnectionAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun updateData(newItems: List<RemoteConnection>) {
        this.items = newItems
        notifyDataSetChanged()
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
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemConnectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RemoteConnection) {
            binding.tvDeviceName.text = item.name
            binding.tvDeviceUrl.text = item.url
            binding.tvDefaultBadge.visibility = if (item.isDefault) View.VISIBLE else View.GONE

            val formattedTime = dateFormat.format(Date(item.lastConnectedTime))
            binding.tvLastConnectedLabel.text = "上次连接"
            binding.tvLastConnectedTime.text = formattedTime

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

            val defaultTitle = if (item.isDefault) {
                anchorView.context.getString(R.string.action_cancel_default)
            } else {
                anchorView.context.getString(R.string.action_set_default)
            }

            menu.add(0, 1, 0, defaultTitle)
            menu.add(0, 2, 1, anchorView.context.getString(R.string.action_edit))
            menu.add(0, 3, 2, anchorView.context.getString(R.string.action_delete))

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> {
                        onSetDefaultClick(item, !item.isDefault)
                        true
                    }
                    2 -> {
                        onEditClick(item)
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
    }
}
