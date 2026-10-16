package com.aistudio.dieselstationsms.kxmpzq

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * استقبال SMS يتم عبر SmsReceiver؛ لا حاجة لخادم HTTP أو خدمة foreground دائمة.
 * أبقيت الصنف للتوافق مع الإصدارات المثبتة التي قد تحتوي على مرجع للخدمة.
 */
class SMSService : Service() {
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SMSService compatibility stub started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SMSService"
    }
}
