package tw.pp.kazi.util

import android.util.Log

object Logger {
    private const val TAG = "Kazi"

    fun d(msg: String) {
        Log.d(TAG, msg)
        LogBuffer.append(LogBuffer.Level.D, msg)
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        LogBuffer.append(LogBuffer.Level.I, msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
        LogBuffer.append(LogBuffer.Level.W, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        LogBuffer.append(LogBuffer.Level.E, msg, t)
    }
}
