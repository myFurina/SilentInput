package com.silentinput.app.bluetooth

import com.silentinput.app.model.ProtocolPayloads
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe outbound queue for Bluetooth SPP frames that coalesces consecutive
 * relative mouse moves while preserving strict chronological order across control
 * frames (clicks, mouse press/release, scrolls, keystrokes, and pastes).
 */
class BluetoothOutboundQueue {
    private val queue = ConcurrentLinkedQueue<String>()
    private val moveLock = Any()
    private var pendingMoveDx = 0f
    private var pendingMoveDy = 0f
    private var hasPendingMove = false

    fun enqueueControl(payload: String) {
        synchronized(moveLock) {
            flushPendingMoveLocked()
            queue.offer(payload)
        }
    }

    fun enqueueMove(dx: Float, dy: Float) {
        synchronized(moveLock) {
            pendingMoveDx += dx
            pendingMoveDy += dy
            hasPendingMove = true
        }
    }

    fun pollNext(): String? {
        queue.poll()?.let { return it }
        synchronized(moveLock) {
            queue.poll()?.let { return it }
            if (hasPendingMove) {
                val payload = ProtocolPayloads.buildMouseJson(pendingMoveDx, pendingMoveDy, action = "move")
                pendingMoveDx = 0f
                pendingMoveDy = 0f
                hasPendingMove = false
                return payload
            }
            return null
        }
    }

    fun clear() {
        synchronized(moveLock) {
            queue.clear()
            pendingMoveDx = 0f
            pendingMoveDy = 0f
            hasPendingMove = false
        }
    }

    private fun flushPendingMoveLocked() {
        if (hasPendingMove) {
            queue.offer(ProtocolPayloads.buildMouseJson(pendingMoveDx, pendingMoveDy, action = "move"))
            pendingMoveDx = 0f
            pendingMoveDy = 0f
            hasPendingMove = false
        }
    }
}
