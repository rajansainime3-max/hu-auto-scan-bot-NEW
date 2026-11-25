package com.v2bot.huautoscan

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

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

    private fun findLabelRect(allNodes: List<AccessibilityNodeInfo>, labelText: String): Rect? {
        for (n in allNodes) {
            val txt = ((n.text?.toString() ?: "") + " " + (n.contentDescription?.toString() ?: "")).trim()
            if (txt.equals(labelText, ignoreCase = true) || txt.contains(labelText, ignoreCase = true)) {
                // Prefer smaller leaf label nodes over giant parent containers
                val r = Rect()
                n.getBoundsInScreen(r)
                if (!r.isEmpty && r.height() < 220) {
                    return r
                }
            }
        }
        return null
    }

    /**
     * Finds the EditText closest vertically (centerY) to the given label, on the right side.
     * Never falls back to arbitrary EditTexts (like PO NUMBER or BILL NO).
     */
    private fun findEditTextForLabel(allNodes: List<AccessibilityNodeInfo>, labelText: String): AccessibilityNodeInfo? {
        val labelRect = findLabelRect(allNodes, labelText) ?: return null
        val targetY = labelRect.centerY()
        var bestEdit: AccessibilityNodeInfo? = null
        var bestDist = Int.MAX_VALUE

        for (n in allNodes) {
            val cls = n.className?.toString() ?: ""
            if (n.isEditable || cls.contains("EditText", ignoreCase = true)) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.isEmpty) continue
                val dist = abs(r.centerY() - targetY)
                // Side-by-side layout: EditText is at same Y (within 90px) or just below (within 130px)
                if (dist <= 130 && dist < bestDist) {
                    bestDist = dist
                    bestEdit = n
                }
            }
        }
        return bestEdit
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

            // Already selected on screen?
            for (n in allNodes) {
                if ((n.text?.toString() ?: "").contains(poNo, ignoreCase = true)) {
                    onDone()
                    return
                }
            }

            val poRect = findLabelRect(allNodes, "PO NUMBER")
            if (poRect != null) {
                val sw = resources.displayMetrics.widthPixels
                var bestDropdown: AccessibilityNodeInfo? = null
                var bestDist = Int.MAX_VALUE
                for (n in allNodes) {
                    if (!n.isClickable) continue
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (r.isEmpty) continue
                    val dist = abs(r.centerY() - poRect.centerY())
                    if (dist <= 90 && r.centerX() > sw * 0.35f && r.height() < 200 && dist < bestDist) {
                        bestDist = dist
                        bestDropdown = n
                    }
                }
                if (bestDropdown != null) {
                    bestDropdown.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Handler(Looper.getMainLooper()).postDelayed({
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
                    }, 500L)
                    return
                }
            }
        } catch (_: Exception) {
        }
        onDone()
    }

    private fun setNodeTextReliably(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return true
        }
        // Fallback: Clipboard + ACTION_PASTE
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("hu_bot", text))
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) {
            false
        }
    }

    fun fillBoxHuScan(boxNo: String): Boolean {
        if (boxNo.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        try {
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectNodes(root, allNodes)

            var edit = findEditTextForLabel(allNodes, "BOX/HU SCAN")
                ?: findEditTextForLabel(allNodes, "BOX/HU")
                ?: findEditTextForLabel(allNodes, "HU SCAN")

            // Fallback: pick the EditText directly above EAN SCAN EditText
            if (edit == null) {
                val eanEdit = findEditTextForLabel(allNodes, "EAN SCAN")
                if (eanEdit != null) {
                    val eanRect = Rect()
                    eanEdit.getBoundsInScreen(eanRect)
                    val editsAbove = allNodes.filter { n ->
                        val cls = n.className?.toString() ?: ""
                        if (!(n.isEditable || cls.contains("EditText", ignoreCase = true))) return@filter false
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        !r.isEmpty && r.bottom <= eanRect.top + 10
                    }.sortedBy { n ->
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        r.top
                    }
                    if (editsAbove.isNotEmpty()) {
                        edit = editsAbove.last()
                    }
                }
            }

            if (edit != null) {
                return setNodeTextReliably(edit, boxNo)
            }
        } catch (_: Exception) {
        }
        return false
    }

    fun ensureMixArticleState(shouldBeChecked: Boolean) {
        val root = rootInActiveWindow ?: return
        try {
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectNodes(root, allNodes)
            val mixRect = findLabelRect(allNodes, "MIX ARTICLE") ?: return
            var bestCb: AccessibilityNodeInfo? = null
            var bestDist = Int.MAX_VALUE
            for (n in allNodes) {
                if (n.isCheckable) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (r.isEmpty) continue
                    val dist = abs(r.centerY() - mixRect.centerY())
                    if (dist <= 90 && dist < bestDist) {
                        bestDist = dist
                        bestCb = n
                    }
                }
            }
            if (bestCb != null && bestCb.isChecked != shouldBeChecked) {
                bestCb.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        } catch (_: Exception) {
        }
    }

    fun inputEanBarcode(barcode: String, fallbackX: Float, fallbackY: Float, onResult: (Boolean, String) -> Unit) {
        if (barcode.isBlank()) {
            performTap(fallbackX, fallbackY) { ok ->
                onResult(ok, "TargetTap (${fallbackX.toInt()},${fallbackY.toInt()})")
            }
            return
        }

        val root = rootInActiveWindow
        if (root != null) {
            try {
                val allNodes = mutableListOf<AccessibilityNodeInfo>()
                collectNodes(root, allNodes)
                val eanEdit = findEditTextForLabel(allNodes, "EAN SCAN")
                if (eanEdit != null) {
                    eanEdit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, barcode)
                    }
                    val ok = eanEdit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    if (ok) {
                        onResult(true, "EAN $barcode")
                        return
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Only if EAN SCAN EditText wasn't found, tap user's Red EAN Target (never touch PO/BILL!)
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
