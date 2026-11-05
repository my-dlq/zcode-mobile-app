package ai.zcode.remote.ui.remote

import android.content.Context
import androidx.appcompat.app.AlertDialog
import ai.zcode.remote.R
import ai.zcode.remote.data.model.RemoteConnection
import ai.zcode.remote.data.repository.ConnectionRepository
import ai.zcode.remote.service.KeepAliveService
import ai.zcode.remote.ui.remote.event.ClaimCampaignNotifier
import ai.zcode.remote.ui.remote.event.TaskNotifier
import ai.zcode.remote.ui.remote.web.ConnectionWebProfile

/** 只说明已知的登录隔离状态，账号身份以远程网页为准，不猜测用户名。 */
object ConnectionLoginDialog {
    fun show(context: Context, connectionId: String) {
        val connection = ConnectionRepository.getInstance(context).findById(connectionId) ?: return
        val supported = runCatching { ConnectionWebProfile.isSupported() }.getOrDefault(false)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.connection_login_title)
            .setMessage(context.getString(
                if (supported) R.string.connection_login_isolated else R.string.connection_login_shared,
                connection.name,
            ))
            .setPositiveButton(R.string.login_space_understood, null)
        if (supported) {
            dialog.setNeutralButton(R.string.connection_reset_login) { _, _ ->
                confirmReset(context, connectionId)
            }
        }
        dialog.show()
    }

    private fun confirmReset(context: Context, connectionId: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.connection_reset_login)
            .setMessage(R.string.connection_reset_login_confirm)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.connection_reset_login) { _, _ ->
                val repository = ConnectionRepository.getInstance(context)
                val old = repository.findById(connectionId) ?: return@setPositiveButton
                val updated = repository.resetLogin(connectionId) ?: return@setPositiveButton
                stopOldSession(context, old)
                RemoteControlActivity.start(context, updated.url, updated.name, connectionId = updated.id)
            }
            .show()
    }

    fun stopOldSession(context: Context, connection: RemoteConnection) {
        RemoteControlActivity.closeConnection(connection.id, connection.url)
        KeepAliveService.forgetConnection(connection.id, connection.url, connection.loginSpace.sourceId)
        for (source in setOf(connection.id, connection.loginSpace.sourceId)) {
            TaskNotifier.discardConnection(context, source, connection.url)
            ClaimCampaignNotifier.discardSource(context, source)
        }
    }
}
