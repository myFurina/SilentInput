#!/bin/bash
# SilentInput Clean Uninstallation Script

SCRIPT_DIR="$(dirname "$(realpath "$0")")"
BIN_DIR="$HOME/.local/bin"
APP_DIR="$HOME/.local/share/applications"
ICON_FILE="$HOME/.local/share/icons/hicolor/scalable/apps/com.silentinput.controlcenter.svg"
ICON_PNG_FILE="$HOME/.local/share/icons/hicolor/128x128/apps/com.silentinput.controlcenter.png"
ICON_BASE_DIR="$HOME/.local/share/icons/hicolor"
DATA_DIR="$HOME/.local/share/silentinput"
SYSTEMD_USER_DIR="$HOME/.config/systemd/user"
CONFIG_DIR="$HOME/.config/silentinput"

PURGE=0
for arg in "$@"; do
    case "$arg" in
        --purge) PURGE=1 ;;
    esac
done

echo "========================================="
echo "       正在卸载 SilentInput PC 伴侣       "
echo "========================================="

# 1. 停止运行中的服务
echo "🛑 正在停止 SilentInput 服务..."
if [ -x "$SCRIPT_DIR/silentinput" ]; then
    python3 "$SCRIPT_DIR/silentinput" stop 2>/dev/null || true
fi

# 2. 停用并移除 systemd 用户服务
if command -v systemctl >/dev/null 2>&1; then
    systemctl --user stop silentinput.service 2>/dev/null || true
    systemctl --user disable silentinput.service 2>/dev/null || true
fi

if [ -f "$SYSTEMD_USER_DIR/silentinput.service" ]; then
    rm -f "$SYSTEMD_USER_DIR/silentinput.service"
    echo "🗑️ 已移除 systemd 服务文件: $SYSTEMD_USER_DIR/silentinput.service"
fi
rm -f "$SYSTEMD_USER_DIR/default.target.wants/silentinput.service"
rm -f "$HOME/.config/autostart/com.silentinput.daemon.desktop"
if command -v systemctl >/dev/null 2>&1; then
    systemctl --user daemon-reload 2>/dev/null || true
fi

# 3. 移除 CLI 命令与本地 venv/在线源码缓存
if [ -L "$BIN_DIR/silentinput" ] || [ -f "$BIN_DIR/silentinput" ]; then
    rm -f "$BIN_DIR/silentinput"
    echo "🗑️ 已移除命令: $BIN_DIR/silentinput"
fi

if [ -d "$DATA_DIR" ]; then
    rm -rf "$DATA_DIR"
    echo "🗑️ 已移除本地运行环境与缓存: $DATA_DIR"
fi

# 4. 移除桌面图标与 SVG 图标
for desktop_file in \
    "$APP_DIR/com.silentinput.controlcenter.desktop" \
    "$APP_DIR/silentinput.desktop" \
    "$APP_DIR/silentinput-toggle.desktop"; do
    if [ -f "$desktop_file" ]; then
        rm -f "$desktop_file"
        echo "🗑️ 已移除桌面快捷方式: $desktop_file"
    fi
done

for icon_file in "$ICON_FILE" "$ICON_PNG_FILE"; do
    if [ -f "$icon_file" ]; then
        rm -f "$icon_file"
        echo "🗑️ 已移除图标: $icon_file"
    fi
done

if [ -d "$ICON_BASE_DIR" ]; then
    command -v gtk4-update-icon-cache >/dev/null 2>&1 && gtk4-update-icon-cache -f -t "$ICON_BASE_DIR" >/dev/null 2>&1 || true
    command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -f -t "$ICON_BASE_DIR" >/dev/null 2>&1 || true
fi

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database -q "$APP_DIR" >/dev/null 2>&1 || true
fi

# 5. 可选彻底清理配置与证书
if [ "$PURGE" -eq 1 ] && [ -d "$CONFIG_DIR" ]; then
    rm -rf "$CONFIG_DIR"
    echo "🗑️ 已彻底删除配置、日志与证书目录: $CONFIG_DIR"
fi

echo ""
echo "========================================="
echo "       ✅ SilentInput 已完全卸载！       "
echo "========================================="
if [ "$PURGE" -eq 0 ]; then
    echo "配置与日志保存在 ~/.config/silentinput/"
    echo "如需彻底清除配置文件，可运行: bash \"$0\" --purge 或 rm -rf ~/.config/silentinput"
fi
echo "========================================="
