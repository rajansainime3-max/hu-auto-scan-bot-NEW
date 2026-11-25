package com.v2bot.huautoscan

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

    /**
     * Checks or unchecks MIX ARTICLE checkbox on Vendor HU Process screen.
     * Multiple articles in box -> checked; single article -> unchecked.
     */

    fun fillBoxHuScan(boxNo: String): Boolean {
        if (boxNo.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        try {
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectNodes(root, allNodes)

            var boxLabelRect: Rect? = null
            var eanLabelTop = Int.MAX_VALUE
            for (n in allNodes) {
                val txt = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
                if (txt.contains("BOX/HU SCAN", ignoreCase = true)) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (!r.isEmpty) boxLabelRect = r
                } else if (txt.contains("EAN SCAN", ignoreCase = true)) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (!r.isEmpty && r.top < eanLabelTop) eanLabelTop = r.top
                }
            }
            if (boxLabelRect == null) return false

            val bandTop = boxLabelRect.top - 40
            val bandBottom = if (eanLabelTop in (bandTop + 20)..(bandTop + 400)) eanLabelTop else (boxLabelRect.bottom + 200)
            for (n in allNodes) {
                val cls = n.className?.toString() ?: ""
                if (n.isEditable || cls.contains("EditText", ignoreCase = true)) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (!r.isEmpty && r.centerY() in bandTop..bandBottom) {
                        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, boxNo)
                        }
                        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    fun selectPoNumberIfNeeded(poNo: String, onDone: () -> Unit) {
        if (poNo.isBlank()) {
            onDone()
            return
        }
        val root = rootInActiveWindow
        if (root == null) {
            onDone()
            return
        }
        try {
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectNodes(root, allNodes)
            // If PO No is already visible on screen (already selected), skip!
            for (n in allNodes) {
                val txt = n.text?.toString() ?: ""
                if (txt.contains(poNo, ignoreCase = true)) {
                    onDone()
                    return
                }
            }
            // Otherwise find PO NUMBER row and click dropdown
            var poRect: Rect? = null
            var billTop = Int.MAX_VALUE
            for (n in allNodes) {
                val txt = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
                if (txt.contains("PO NUMBER", ignoreCase = true)) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (!r.isEmpty) poRect = r
                } else if (txt.contains("BILL NO", ignoreCase = true)) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (!r.isEmpty && r.top < billTop) billTop = r.top
                }
            }
            if (poRect != null) {
                val bandTop = poRect.top - 30
                val bandBottom = if (billTop in (bandTop + 20)..(bandTop + 350)) billTop else (poRect.bottom + 180)
                val sw = resources.displayMetrics.widthPixels
                for (n in allNodes) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (!r.isEmpty && r.centerY() in bandTop..bandBottom && r.centerX() > sw * 0.4f && n.isClickable) {
                        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            val popRoot = rootInActiveWindow
                            if (popRoot != null) {
                                val popNodes = mutableListOf<AccessibilityNodeInfo>()
                                collectNodes(popRoot, popNodes)
                                for (pn in popNodes) {
                                    if ((pn.text?.toString() ?: "").contains(poNo, ignoreCase = true)) {
                                        pn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                        break
                                    }
                                }
                            }
                            onDone()
                        }, 450L)
                        return
                    }
                }
            }
        } catch (_: Exception) {
        }
        onDone()
    }

    fun ensureMixArticleState(shouldBeChecked: Boolean) {
        val root = rootInActiveWindow ?: return
        try {
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectNodes(root, allNodes)

            var mixLabelRect: Rect? = null
            for (n in allNodes) {
                val txt = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
                if (txt.contains("MIX ARTICLE", ignoreCase = true)) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (!r.isEmpty) {
                        mixLabelRect = r
                        break
                    }
                }
            }
            if (mixLabelRect == null) return

            val bandTop = mixLabelRect.top - 40
            val bandBottom = mixLabelRect.bottom + 40
            for (n in allNodes) {
                if (n.isCheckable) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (!r.isEmpty && r.centerY() in bandTop..bandBottom) {
                        if (n.isChecked != shouldBeChecked) {
                            n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        return
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Types the 8-digit barcode directly into EAN SCAN EditText (INPUT/Scan).
     * If barcode is blank, falls back to tapping the barcode icon.
     */
    fun inputEanBarcode(barcode: String, fallbackX: Float, fallbackY: Float, onResult: (Boolean, String) -> Unit) {
        if (barcode.isBlank()) {
            trySmartTapEanIcon(fallbackX, fallbackY, onResult)
            return
        }

        val root = rootInActiveWindow
        if (root != null) {
            try {
                val allNodes = mutableListOf<AccessibilityNodeInfo>()
                collectNodes(root, allNodes)

                var eanLabelRect: Rect? = null
                var scannedQtyTop = Int.MAX_VALUE
                for (n in allNodes) {
                    val txt = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
                    if (txt.contains("EAN SCAN", ignoreCase = true)) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (!r.isEmpty) eanLabelRect = r
                    } else if (txt.contains("SCANNED QTY", ignoreCase = true)) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (!r.isEmpty && r.top < scannedQtyTop) scannedQtyTop = r.top
                    }
                }

                // Find EditText inside EAN SCAN band (supports both stacked and side-by-side layout!)
                val bandTop = if (eanLabelRect != null) eanLabelRect.top - 50 else 0
                val bandBottom = if (eanLabelRect != null) {
                    if (scannedQtyTop in (eanLabelRect.top + 10)..(eanLabelRect.top + 400)) scannedQtyTop else (eanLabelRect.bottom + 200)
                } else Int.MAX_VALUE

                var targetEdit: AccessibilityNodeInfo? = null
                for (n in allNodes) {
                    val cls = n.className?.toString() ?: ""
                    if (n.isEditable || cls.contains("EditText", ignoreCase = true)) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (!r.isEmpty && r.centerY() in bandTop..bandBottom) {
                            targetEdit = n
                            break
                        }
                    }
                }

                if (targetEdit != null) {
                    targetEdit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, barcode)
                    }
                    val ok = targetEdit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    if (ok) {
                        onResult(true, "Typed $barcode")
                        return
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Fallback to icon tap if EditText wasn't accessible
        trySmartTapEanIcon(fallbackX, fallbackY, onResult)
    }

    fun trySmartTapEanIcon(fallbackX: Float, fallbackY: Float, onResult: (Boolean, String) -> Unit) {
        val root = rootInActiveWindow
        if (root != null) {
            try {
                val allNodes = mutableListOf<AccessibilityNodeInfo>()
                collectNodes(root, allNodes)

                var eanLabelRect: Rect? = null
                var scannedQtyTop = Int.MAX_VALUE
                for (n in allNodes) {
                    val txt = (n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")
                    if (txt.contains("EAN SCAN", ignoreCase = true)) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (!r.isEmpty) eanLabelRect = r
                    } else if (txt.contains("SCANNED QTY", ignoreCase = true)) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (!r.isEmpty && r.top < scannedQtyTop) scannedQtyTop = r.top
                    }
                }

                if (eanLabelRect != null) {
                    val bandTop = eanLabelRect.top - 40
                    val bandBottom = if (scannedQtyTop in (bandTop + 20)..(bandTop + 400)) scannedQtyTop else (bandTop + 240)
                    val screenWidth = resources.displayMetrics.widthPixels

                    var bestNode: AccessibilityNodeInfo? = null
                    var bestRight = -1
                    for (n in allNodes) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (r.isEmpty) continue
                        val cy = r.centerY()
                        if (cy in bandTop..bandBottom && r.centerX() > (screenWidth * 0.65f)) {
                            if (n.isClickable || (n.className?.toString()?.contains("Image", true) == true)) {
                                if (r.right > bestRight && r.width() < screenWidth * 0.45f) {
                                    bestRight = r.right
                                    bestNode = n
                                }
                            }
                        }
                    }

                    if (bestNode != null) {
                        val targetRect = Rect()
                        bestNode.getBoundsInScreen(targetRect)
                        if (bestNode.isClickable && bestNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            onResult(true, "NodeClick (${targetRect.centerX()},${targetRect.centerY()})")
                            return
                        }
                        performTap(targetRect.centerX().toFloat(), targetRect.centerY().toFloat()) { ok ->
                            onResult(ok, "AutoTap (${targetRect.centerX()},${targetRect.centerY()})")
                        }
                        return
                    }
                }
            } catch (_: Exception) {
            }
        }

        performTap(fallbackX, fallbackY) { ok ->
            onResult(ok, "TargetTap (${fallbackX.toInt()},${fallbackY.toInt()})")
        }
    }

    private fun collectNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, out)
        }
    }

    fun performTap(x: Float, y: Float, onResult: ((Boolean) -> Unit)? = null) {
        val safeX = x.coerceAtLeast(1f)
        val safeY = y.coerceAtLeast(1f)
        val path = Path().apply {
            moveTo(safeX, safeY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 100L)
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
