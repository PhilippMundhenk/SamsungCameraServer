package org.mundhenk.cambackup.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** In-process log shared between the service and the activity. */
object LogBus {
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(message: String) {
        val line = "${fmt.format(Date())} $message"
        Log.i("CamBackup", message)
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        main.post { listeners.forEach { it(line) } }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    fun addListener(l: (String) -> Unit) = listeners.add(l)
    fun removeListener(l: (String) -> Unit) = listeners.remove(l)
}
