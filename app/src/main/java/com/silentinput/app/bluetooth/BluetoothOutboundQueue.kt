package com.silentinput.app.bluetooth

import com.silentinput.app.model.ProtocolPayloads
import com.silentinput.app.model.TouchpadContact
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe outbound queue for Bluetooth SPP frames that coalesces consecutive
 * relative mouse moves and two-finger scrolls while preserving strict chronological
 * order across control frames (clicks, mouse press/release, keystrokes, and pastes).
 */
class BluetoothOutboundQueue {
    private val queue = ConcurrentLinkedQueue<String>()
    private val moveLock = Any()
    private var pendingMoveDx = 0f
    private var pendingMoveDy = 0f
    private var hasPendingMove = false
    private var pendingScrollWheel = 0f
    private var pendingScrollHWheel = 0f
    private var pendingScrollHiRes = false
    private var hasPendingScroll = false
    private var pendingTouchpad: List<TouchpadContact>? = null

    fun enqueueControl(payload: String) {
        synchronized(moveLock) {
            flushPendingMoveLocked()
            flushPendingScrollLocked()
            flushPendingTouchpadLocked()
            queue.offer(payload)
        }
    }

    fun enqueueMove(dx: Float, dy: Float) {
        synchronized(moveLock) {
            flushPendingScrollLocked()
            flushPendingTouchpadLocked()
            pendingMoveDx += dx
            pendingMoveDy += dy
            hasPendingMove = true
        }
    }

    fun enqueueScroll(wheel: Float, hiRes: Boolean, hwheel: Float = 0f) {
        synchronized(moveLock) {
            flushPendingMoveLocked()
            flushPendingTouchpadLocked()
            if (hasPendingScroll && pendingScrollHiRes != hiRes) {
                flushPendingScrollLocked()
            }
            pendingScrollWheel += wheel
            pendingScrollHWheel += hwheel
            pendingScrollHiRes = hiRes
            hasPendingScroll = true
        }
    }

    fun enqueueTouchpad(contacts: List<TouchpadContact>, isTransition: Boolean) {
        synchronized(moveLock) {
            flushPendingMoveLocked()
            flushPendingScrollLocked()
            if (isTransition) {
                flushPendingTouchpadLocked()
                queue.offer(ProtocolPayloads.buildTouchpadJson(contacts))
            } else {
                pendingTouchpad = contacts
            }
        }
    }

    fun pollNext(): String? {
        queue.poll()?.let { return it }
        synchronized(moveLock) {
            return pollNextLocked()
        }
    }

    fun drainBatch(maxItems: Int = 16): List<String> {
        if (maxItems <= 0) return emptyList()
        val batch = ArrayList<String>(maxItems.coerceAtMost(16))
        while (batch.size < maxItems) {
            val item = queue.poll() ?: break
            batch.add(item)
        }
        if (batch.size >= maxItems) return batch
        synchronized(moveLock) {
            while (batch.size < maxItems) {
                val item = pollNextLocked() ?: break
                batch.add(item)
            }
        }
        return batch
    }

    fun clear() {
        synchronized(moveLock) {
            queue.clear()
            pendingMoveDx = 0f
            pendingMoveDy = 0f
            hasPendingMove = false
            pendingScrollWheel = 0f
            pendingScrollHWheel = 0f
            pendingScrollHiRes = false
            hasPendingScroll = false
            pendingTouchpad = null
        }
    }

    private fun pollNextLocked(): String? {
        queue.poll()?.let { return it }
        if (hasPendingMove) {
            val payload = ProtocolPayloads.buildMouseJson(pendingMoveDx, pendingMoveDy, action = "move")
            pendingMoveDx = 0f
            pendingMoveDy = 0f
            hasPendingMove = false
            return payload
        }
        if (hasPendingScroll) {
            val payload = ProtocolPayloads.buildMouseJson(
                dx = 0f,
                dy = 0f,
                action = "scroll",
                wheel = pendingScrollWheel,
                hiResWheel = pendingScrollHiRes,
                hwheel = pendingScrollHWheel
            )
            pendingScrollWheel = 0f
            pendingScrollHWheel = 0f
            pendingScrollHiRes = false
            hasPendingScroll = false
            return payload
        }
        pendingTouchpad?.let { contacts ->
            pendingTouchpad = null
            return ProtocolPayloads.buildTouchpadJson(contacts)
        }
        return null
    }

    private fun flushPendingMoveLocked() {
        if (hasPendingMove) {
            queue.offer(ProtocolPayloads.buildMouseJson(pendingMoveDx, pendingMoveDy, action = "move"))
            pendingMoveDx = 0f
            pendingMoveDy = 0f
            hasPendingMove = false
        }
    }

    private fun flushPendingScrollLocked() {
        if (hasPendingScroll) {
            queue.offer(
                ProtocolPayloads.buildMouseJson(
                    dx = 0f,
                    dy = 0f,
                    action = "scroll",
                    wheel = pendingScrollWheel,
                    hiResWheel = pendingScrollHiRes,
                    hwheel = pendingScrollHWheel
                )
            )
            pendingScrollWheel = 0f
            pendingScrollHWheel = 0f
            pendingScrollHiRes = false
            hasPendingScroll = false
        }
    }

    private fun flushPendingTouchpadLocked() {
        pendingTouchpad?.let { contacts ->
            queue.offer(ProtocolPayloads.buildTouchpadJson(contacts))
            pendingTouchpad = null
        }
    }
}
