package ai.zcode.remote.utils

import android.content.Context
import android.widget.Toast

object ToastUtils {
    private var toast: Toast? = null

    fun show(context: Context?, message: String) {
        if (context == null) return
        toast?.cancel()
        toast = Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT)
        toast?.show()
    }

    fun showLong(context: Context?, message: String) {
        if (context == null) return
        toast?.cancel()
        toast = Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG)
        toast?.show()
    }
}
