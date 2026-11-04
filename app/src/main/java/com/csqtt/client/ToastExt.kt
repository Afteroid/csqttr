package com.csqtt.client

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Toast

private object TransientMessage {
    val handler = Handler(Looper.getMainLooper())
    var current: Toast? = null
    var dismiss: Runnable? = null
    const val DURATION_MS = 3_000L
}

// Keep the length argument source-compatible; transient messages now use three seconds.
@Suppress("UNUSED_PARAMETER")
fun Context.showRaisedToast(message: String, length: Int = Toast.LENGTH_SHORT) {
    val context = applicationContext
    TransientMessage.handler.post {
        TransientMessage.dismiss?.let { TransientMessage.handler.removeCallbacks(it) }
        TransientMessage.current?.cancel()
        val toast = Toast.makeText(context, message, Toast.LENGTH_LONG)
        toast.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, (180 * context.resources.displayMetrics.density).toInt())
        TransientMessage.current = toast
        toast.show()
        val dismiss = Runnable {
            toast.cancel()
            if (TransientMessage.current === toast) {
                TransientMessage.current = null
                TransientMessage.dismiss = null
            }
        }
        TransientMessage.dismiss = dismiss
        TransientMessage.handler.postDelayed(dismiss, TransientMessage.DURATION_MS)
    }
}
