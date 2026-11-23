package com.v2bot.huautoscan

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class AutoTapAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoTapAccessibilityService? = null
            private set

        @Volatile
        var onEmergencyStopRequested: (() -> Unit)? = null

        fun isServiceActive(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        onEmergencyStopRequested?.invoke()
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            onEmergencyStopRequested?.invoke()
        }
        return super.onKeyEvent(event)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun performTap(x: Float, y: Float, onResult: ((Boolean) -> Unit)? = null) {
        val safeX = x.coerceAtLeast(1f)
        val safeY = y.coerceAtLeast(1f)
        val path = Path().apply {
            moveTo(safeX, safeY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onResult?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onResult?.invoke(false)
                }
            },
            null
        )

        if (!dispatched) {
            onResult?.invoke(false)
        }
    }
}
