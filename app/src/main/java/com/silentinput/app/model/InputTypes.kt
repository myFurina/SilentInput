package com.silentinput.app.model

enum class ConnectionMode(val wireValue: String) {
    USB("usb"),
    LAN("lan"),
    BLUETOOTH("bluetooth");

    companion object {
        fun fromWire(value: String?): ConnectionMode {
            return values().firstOrNull { it.wireValue == value } ?: USB
        }
    }
}

enum class SendTargetMode(val wireValue: String) {
    TERMINAL("terminal"),
    GUI("gui"),
    KEYS("keys");

    companion object {
        fun fromWire(value: String?): SendTargetMode {
            return values().firstOrNull { it.wireValue == value } ?: TERMINAL
        }
    }
}

enum class MouseButton(val wireValue: String) {
    LEFT("left"),
    RIGHT("right")
}

enum class MouseAction(val wireValue: String) {
    MOVE("move"),
    SCROLL("scroll"),
    CLICK_LEFT("click_left"),
    CLICK_RIGHT("click_right"),
    DOWN_LEFT("down_left"),
    UP_LEFT("up_left");

    companion object {
        fun click(button: MouseButton): MouseAction {
            return when (button) {
                MouseButton.LEFT -> CLICK_LEFT
                MouseButton.RIGHT -> CLICK_RIGHT
            }
        }
    }
}
