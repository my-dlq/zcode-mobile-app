package ai.zcode.remote.ui.settings

import ai.zcode.remote.R
import ai.zcode.remote.data.model.TaskReminderSound
import ai.zcode.remote.data.model.TaskReminderTone
import ai.zcode.remote.databinding.DialogNotificationTonePickerBinding
import ai.zcode.remote.databinding.ItemNotificationToneBinding
import android.content.ContentResolver
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

internal class NotificationTonePicker(
    context: Context,
    private val selected: TaskReminderSound,
    private val inheritedSound: Uri?,
    private val onPreview: (TaskReminderSound) -> Unit,
    private val onSelected: (TaskReminderSound) -> Unit,
) : BottomSheetDialog(context, R.style.Theme_ZCodeRemote_BottomSheetDialog) {
    private val binding = DialogNotificationTonePickerBinding.inflate(layoutInflater)
    private val presets = TaskReminderTone.entries.filterNot { it == TaskReminderTone.ORIGINAL }
    private var catalogue: List<TaskReminderSound> = presets
    private val adapter = ToneAdapter()

    init {
        val height = (context.resources.displayMetrics.heightPixels * 0.78f).toInt()
        behavior.maxHeight = height
        setContentView(binding.root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        binding.title.requestFocus()
        binding.close.setOnClickListener { dismiss() }
        binding.toneList.adapter = adapter
        binding.search.doAfterTextChanged {
            filterTones()
            binding.toneList.setSelection(0)
        }
        setOnShowListener {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            filterTones()
            loadCatalogue()
        }
    }

    private fun loadCatalogue() {
        val appContext = context.applicationContext
        Thread({
            val available = runCatching { systemTones(appContext) }.getOrDefault(emptyList())
            Handler(Looper.getMainLooper()).post {
                if (!isShowing) return@post
                val saved = selected as? TaskReminderSound.SystemTone
                val tones = if (saved != null && available.none { it.source == saved.source }) listOf(saved) + available else available
                catalogue = presets + tones
                binding.loadStatus.setText(R.string.notif_reminder_no_system_tones)
                binding.loadStatus.isVisible = available.isEmpty()
                filterTones()
            }
        }, "NotificationToneLoader").start()
    }

    private fun filterTones() {
        val query = binding.search.text.toString().trim()
        adapter.items = catalogue.filter { it.title(context).contains(query, ignoreCase = true) }
        binding.emptyState.isVisible = adapter.items.isEmpty()
    }

    private inner class ToneAdapter : BaseAdapter() {
        var items: List<TaskReminderSound> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): TaskReminderSound = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = if (convertView == null) {
                ItemNotificationToneBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                    .also { it.root.tag = it }
            } else {
                convertView.tag as ItemNotificationToneBinding
            }
            val sound = getItem(position)
            val name = sound.title(context)
            row.toneTitle.text = name
            row.toneTitle.isChecked = when {
                sound !is TaskReminderSound.SystemTone -> selected == sound
                selected is TaskReminderSound.SystemTone -> selected.source == sound.source
                selected == TaskReminderTone.ORIGINAL -> inheritedSound == sound.source
                else -> false
            }
            row.toneTitle.setOnClickListener {
                onSelected(sound)
                dismiss()
            }
            row.preview.contentDescription = context.getString(R.string.notif_reminder_preview_named, name)
            row.preview.setOnClickListener { onPreview(sound) }
            return row.root
        }
    }

    companion object {
        fun systemTones(context: Context): List<TaskReminderSound.SystemTone> {
            val manager = RingtoneManager(context).apply { setType(RingtoneManager.TYPE_NOTIFICATION) }
            return manager.cursor?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)?.trim()
                        val uri = manager.getRingtoneUri(cursor.position)
                        if (!title.isNullOrEmpty() && uri != null &&
                            (uri.scheme == ContentResolver.SCHEME_CONTENT || uri.scheme == ContentResolver.SCHEME_ANDROID_RESOURCE)
                        ) {
                            add(TaskReminderSound.SystemTone(uri, title))
                        }
                    }
                }.distinctBy { it.source }
            }.orEmpty()
        }
    }
}
