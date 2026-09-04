package ai.zcode.remote.ui.main

import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import ai.zcode.remote.R
import ai.zcode.remote.data.model.RemoteConnection
import ai.zcode.remote.data.repository.ConnectionRepository
import ai.zcode.remote.databinding.DialogAddConnectionBinding
import ai.zcode.remote.utils.ToastUtils
import ai.zcode.remote.utils.UrlParser

class AddConnectionDialog : DialogFragment() {

    private var _binding: DialogAddConnectionBinding? = null
    private val binding get() = _binding!!

    private var editingConnection: RemoteConnection? = null
    private var onSavedListener: ((RemoteConnection) -> Unit)? = null

    fun setEditingConnection(connection: RemoteConnection?) {
        this.editingConnection = connection
    }

    fun setOnSavedListener(listener: (RemoteConnection) -> Unit) {
        this.onSavedListener = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogAddConnectionBinding.inflate(LayoutInflater.from(context))

        val dialog = AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        initView()
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun initView() {
        val isEdit = editingConnection != null
        binding.tvDialogTitle.text = getString(
            if (isEdit) R.string.dialog_edit_title else R.string.dialog_add_title
        )

        if (isEdit) {
            val conn = editingConnection!!
            binding.etName.setText(conn.name)
            binding.etUrl.setText(conn.url)
        } else {
            // 尝试读取剪贴板智能填充
            tryAutoFillFromClipboard()
        }

        binding.btnPasteUrl.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!clipText.isNullOrBlank()) {
                binding.etUrl.setText(clipText)
                handleUrlChanged(clipText)
            } else {
                ToastUtils.show(requireContext(), "剪贴板为空")
            }
        }

        binding.etUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handleUrlChanged(s?.toString()?.trim() ?: "")
            }
        })

        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnCancel.setOnClickListener { dismiss() }

        binding.btnSave.setOnClickListener {
            saveAndDone()
        }
    }

    private fun tryAutoFillFromClipboard() {
        try {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (!clipText.isNullOrBlank() && UrlParser.isLikelyZCodeUrl(clipText)) {
                binding.etUrl.setText(clipText)
                handleUrlChanged(clipText)
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun handleUrlChanged(rawUrl: String) {
        if (rawUrl.isBlank()) return
        val currentName = binding.etName.text?.toString()?.trim() ?: ""
        val parsed = UrlParser.parse(rawUrl)
        if (currentName.isEmpty() || currentName == "我的远程设备" || currentName == "ZCode 设备") {
            if (parsed.suggestedName.isNotBlank()) {
                binding.etName.setText(parsed.suggestedName)
            }
        }
    }

    private fun saveAndDone() {
        val rawUrl = binding.etUrl.text?.toString()?.trim() ?: ""
        var name = binding.etName.text?.toString()?.trim() ?: ""
        if (rawUrl.isEmpty()) {
            ToastUtils.show(requireContext(), getString(R.string.error_invalid_url))
            return
        }

        val parsed = UrlParser.parse(rawUrl)
        if (!UrlParser.isValidRemoteConnection(parsed)) {
            ToastUtils.show(requireContext(), getString(R.string.error_invalid_url))
            return
        }
        if (name.isEmpty()) {
            name = parsed.suggestedName
        }

        val repository = ConnectionRepository.getInstance(requireContext())
        val connection = editingConnection?.apply {
            this.name = name
            this.url = parsed.originalUrl
            this.mid = parsed.mid
            this.sid = parsed.sid
        } ?: RemoteConnection(
            name = name,
            url = parsed.originalUrl,
            mid = parsed.mid,
            sid = parsed.sid
        )

        repository.saveConnection(connection)
        onSavedListener?.invoke(connection)
        ToastUtils.show(requireContext(), "保存成功")
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance(
            connection: RemoteConnection? = null,
            onSaved: ((RemoteConnection) -> Unit)? = null
        ): AddConnectionDialog {
            return AddConnectionDialog().apply {
                setEditingConnection(connection)
                onSaved?.let { setOnSavedListener(it) }
            }
        }
    }
}
