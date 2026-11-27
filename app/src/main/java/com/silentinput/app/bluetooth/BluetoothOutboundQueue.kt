package com.silentinput.app.bluetooth

import com.silentinput.app.model.ProtocolPayloads
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
    private var pendingScrollHiRes = false
    private var hasPendingScroll = false

    fun enqueueControl(payload: String) {
        synchronized(moveLock) {
            flushPendingMoveLocked()
            flushPendingScrollLocked()
            queue.offer(payload)
        }
    }

    fun enqueueMove(dx: Float, dy: Float) {
        synchronized(moveLock) {
            flushPendingScrollLocked()
            pendingMoveDx += dx
            pendingMoveDy += dy
            hasPendingMove = true
        }
    }

    fun enqueueScroll(wheel: Float, hiRes: Boolean) {
        synchronized(moveLock) {
            flushPendingMoveLocked()
            if (hasPendingScroll && pendingScrollHiRes != hiRes) {
                flushPendingScrollLocked()
            }
            pendingScrollWheel += wheel
            pendingScrollHiRes = hiRes
            hasPendingScroll = true
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
            pendingScrollHiRes = false
            hasPendingScroll = false
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
                hiResWheel = pendingScrollHiRes
            )
            pendingScrollWheel = 0f
            pendingScrollHiRes = false
            hasPendingScroll = false
            return payload
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
                    hiResWheel = pendingScrollHiRes
                )
            )
            pendingScrollWheel = 0f
            pendingScrollHiRes = false
            hasPendingScroll = false
        }
    }
}
