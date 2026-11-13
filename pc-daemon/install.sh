#!/bin/bash
# SilentInput Smart Installer (Debian/Ubuntu, Fedora, Arch, openSUSE, Bluefin/Silverblue)
set -e

REPO="myFurina/SilentInput"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd || pwd)"
BIN_DIR="$HOME/.local/bin"
APP_DIR="$HOME/.local/share/applications"
ICON_DIR="$HOME/.local/share/icons/hicolor/scalable/apps"
DATA_DIR="$HOME/.local/share/silentinput"
VENV_DIR="$DATA_DIR/venv"
SYSTEMD_USER_DIR="$HOME/.config/systemd/user"
PYTHON_BIN="$(command -v python3 || echo /usr/bin/python3)"

AUTO_YES=0
if [ ! -t 0 ]; then
    AUTO_YES=1
fi
for arg in "$@"; do
    case "$arg" in
        -y|--yes)
            AUTO_YES=1
            ;;
        --uninstall)
            if [ -x "$SCRIPT_DIR/uninstall.sh" ]; then
                exec bash "$SCRIPT_DIR/uninstall.sh" "$@"
            fi
            echo "请运行 silentinput uninstall 或 sudo apt remove silentinput / sudo dnf remove silentinput"
            exit 0
            ;;
    esac
done

confirm_or_auto() {
    local prompt="$1"
    if [ "$AUTO_YES" -eq 1 ]; then
        return 0
    fi
    printf "%s [Y/n]: " "$prompt"
    read -r ans || true
    case "$ans" in
        [nN]|[nN][oO]) return 1 ;;
        *) return 0 ;;
    esac
}

download_latest_release_asset() {
    local ext="$1"
    local dest="$2"
    local url
    url="$("$PYTHON_BIN" - "$REPO" "$ext" << 'PYEOF'
import json, sys, urllib.request
repo, ext = sys.argv[1], sys.argv[2]
req = urllib.request.Request(
    f"https://api.github.com/repos/{repo}/releases/latest",
    headers={"User-Agent": "SilentInput-Installer"},
)
with urllib.request.urlopen(req, timeout=15) as resp:
    data = json.load(resp)
for asset in data.get("assets", []):
    name = asset.get("name", "")
    if name.endswith(ext):
        print(asset.get("browser_download_url", ""))
        break
PYEOF
)"
    if [ -z "$url" ]; then
        return 1
    fi
    echo "⬇️  正在下载: $url"
    curl -fL --progress-bar "$url" -o "$dest"
}

# Standalone remote execution (e.g. curl -fsSL ... | bash)
if [ ! -f "$SCRIPT_DIR/silentinput-daemon.py" ]; then
    echo "========================================="
    echo "      SilentInput 在线自动安装程序       "
    echo "========================================="
    IS_IMMUTABLE=0
    [ -e /run/ostree-booted ] && IS_IMMUTABLE=1
    TMP_DIR="$(mktemp -d)"
    trap 'rm -rf "$TMP_DIR"' EXIT

    if [ "$IS_IMMUTABLE" -eq 0 ] && command -v apt-get >/dev/null 2>&1; then
        TMP_DEB="$TMP_DIR/silentinput-latest.deb"
        if download_latest_release_asset ".deb" "$TMP_DEB"; then
            echo "📦 检测到 Debian/Ubuntu 系统，正在通过 apt 安装（自动拉取依赖）..."
            sudo apt-get update
            sudo apt-get install -y "$TMP_DEB"
            rm -f "$TMP_DEB"
            echo "✅ 安装完成！请运行 'systemctl --user enable --now silentinput' 启动服务，或执行 'silentinput status'。"
            exit 0
        fi
    elif [ "$IS_IMMUTABLE" -eq 0 ] && command -v dnf >/dev/null 2>&1; then
        TMP_RPM="$TMP_DIR/silentinput-latest.rpm"
        if download_latest_release_asset ".rpm" "$TMP_RPM"; then
            echo "📦 检测到 Fedora/RHEL 系统，正在通过 dnf 安装（自动拉取依赖）..."
            sudo dnf install -y "$TMP_RPM"
            rm -f "$TMP_RPM"
            echo "✅ 安装完成！请运行 'systemctl --user enable --now silentinput' 启动服务，或执行 'silentinput status'。"
            exit 0
        fi
    fi

    echo "⬇️  正在拉取最新 SilentInput 源码包到 $DATA_DIR/src ..."
    TMP_TAR="$TMP_DIR/silentinput-main.tar.gz"
    rm -rf "$DATA_DIR/src"
    mkdir -p "$DATA_DIR/src"
    curl -fL --progress-bar "https://github.com/$REPO/archive/refs/heads/main.tar.gz" -o "$TMP_TAR"
    tar -xzf "$TMP_TAR" -C "$DATA_DIR/src" --strip-components=2 --wildcards "*/pc-daemon/*" "*/pc-daemon/**/*" 2>/dev/null || {
        TMP_EXTRACT="$TMP_DIR/silentinput-extract"
        mkdir -p "$TMP_EXTRACT"
        tar -xzf "$TMP_TAR" -C "$TMP_EXTRACT"
        cp -r "$TMP_EXTRACT"/*/pc-daemon/* "$DATA_DIR/src/"
    }
    rm -rf "$TMP_DIR"
    trap - EXIT
    exec bash "$DATA_DIR/src/install.sh" "$@"
fi

echo "========================================="
echo "       正在安装 SilentInput PC 伴侣       "
echo "========================================="

# 1. 自动检查并补齐系统 / Python 依赖
IS_IMMUTABLE=0
[ -e /run/ostree-booted ] && IS_IMMUTABLE=1

check_missing_deps() {
    MISSING_EVDEV=0
    MISSING_GI=0
    MISSING_WLCOPY=0
    MISSING_ADB=0
    "$PYTHON_BIN" -c "import evdev" >/dev/null 2>&1 || MISSING_EVDEV=1
    "$PYTHON_BIN" -c "import gi; gi.require_version('Gtk','4.0'); gi.require_version('Adw','1')" >/dev/null 2>&1 || MISSING_GI=1
    command -v wl-copy >/dev/null 2>&1 || MISSING_WLCOPY=1
    command -v adb >/dev/null 2>&1 || MISSING_ADB=1
}

check_missing_deps

if [ "$MISSING_EVDEV" -eq 1 ] || [ "$MISSING_GI" -eq 1 ] || [ "$MISSING_WLCOPY" -eq 1 ] || [ "$MISSING_ADB" -eq 1 ]; then
    echo "🔍 检测到部分运行依赖尚未安装："
    [ "$MISSING_EVDEV" -eq 1 ] && echo "   - python3-evdev (内核虚拟键盘与触控板驱动)"
    [ "$MISSING_GI" -eq 1 ] && echo "   - PyGObject / GTK4 / LibAdwaita (图形控制中心)"
    [ "$MISSING_WLCOPY" -eq 1 ] && echo "   - wl-clipboard (Wayland 中文剪贴板注入)"
    [ "$MISSING_ADB" -eq 1 ] && echo "   - adb / android-tools (USB 有线直连模式)"

    if [ "$IS_IMMUTABLE" -eq 1 ]; then
        echo "ℹ️ 检测到不可变桌面系统 (ostree / Bluefin / Silverblue)。"
        if [ "$MISSING_EVDEV" -eq 1 ]; then
            echo "🐍 正在用户目录创建隔离 Python 环境 ($VENV_DIR) 并自动安装 evdev（无需重启）..."
            mkdir -p "$DATA_DIR"
            if [ ! -x "$VENV_DIR/bin/python3" ]; then
                "$PYTHON_BIN" -m venv --system-site-packages "$VENV_DIR"
            fi
            "$VENV_DIR/bin/pip" install --quiet --upgrade pip evdev
            PYTHON_BIN="$VENV_DIR/bin/python3"
        fi
        if { [ "$MISSING_WLCOPY" -eq 1 ] || [ "$MISSING_ADB" -eq 1 ]; } && command -v brew >/dev/null 2>&1; then
            BREW_PKGS=""
            [ "$MISSING_WLCOPY" -eq 1 ] && BREW_PKGS="$BREW_PKGS wl-clipboard"
            [ "$MISSING_ADB" -eq 1 ] && BREW_PKGS="$BREW_PKGS android-platform-tools"
            if confirm_or_auto "是否使用 brew 自动安装缺失工具 ($BREW_PKGS )？"; then
                brew install $BREW_PKGS || true
            fi
        fi
    elif command -v apt-get >/dev/null 2>&1; then
        if confirm_or_auto "是否自动通过 apt 安装缺失依赖？"; then
            sudo apt-get update
            sudo apt-get install -y python3-evdev python3-gi gir1.2-gtk-4.0 gir1.2-adw-1 wl-clipboard adb
        fi
    elif command -v dnf >/dev/null 2>&1; then
        if confirm_or_auto "是否自动通过 dnf 安装缺失依赖？"; then
            sudo dnf install -y python3-evdev python3-gobject gtk4 libadwaita wl-clipboard android-tools
        fi
    elif command -v pacman >/dev/null 2>&1; then
        if confirm_or_auto "是否自动通过 pacman 安装缺失依赖？"; then
            sudo pacman -S --needed --noconfirm python-evdev python-gobject gtk4 libadwaita wl-clipboard android-tools
        fi
    elif command -v zypper >/dev/null 2>&1; then
        if confirm_or_auto "是否自动通过 zypper 安装缺失依赖？"; then
            sudo zypper install -y python3-evdev python3-gobject gtk4 typelib-1_0-Adw-1 wl-clipboard android-tools
        fi
    fi
elif [ -x "$VENV_DIR/bin/python3" ]; then
    PYTHON_BIN="$VENV_DIR/bin/python3"
fi

# 2. 确保目录存在
mkdir -p "$BIN_DIR"
mkdir -p "$APP_DIR"
mkdir -p "$ICON_DIR"
mkdir -p "$SYSTEMD_USER_DIR"

# 3. 设置权限
chmod +x "$SCRIPT_DIR/silentinput"
chmod +x "$SCRIPT_DIR/silentinput-daemon.py"
chmod +x "$SCRIPT_DIR/silentinput-gui.py"
[ -f "$SCRIPT_DIR/uninstall.sh" ] && chmod +x "$SCRIPT_DIR/uninstall.sh"

# 4. 创建 CLI 命令（若使用 venv 则生成调用 venv Python 的启动器，否则建软链）
if [ -x "$VENV_DIR/bin/python3" ] && [ "$PYTHON_BIN" = "$VENV_DIR/bin/python3" ]; then
    rm -f "$BIN_DIR/silentinput"
    cat > "$BIN_DIR/silentinput" << EOF
#!/bin/sh
exec "$VENV_DIR/bin/python3" "$SCRIPT_DIR/silentinput" "\$@"
EOF
    chmod +x "$BIN_DIR/silentinput"
else
    ln -sf "$SCRIPT_DIR/silentinput" "$BIN_DIR/silentinput"
fi
echo "✅ 已配置 CLI 命令: $BIN_DIR/silentinput"

# 5. 初始化配对密钥与 TLS 自签名证书
"$PYTHON_BIN" -c "import sys; sys.path.insert(0, '$SCRIPT_DIR'); import silentinput_config; silentinput_config.ensure_certificates(); silentinput_config.load_config()"
echo "✅ 已初始化本地配对密钥与 TLS 自签名证书"

# 6. 安装桌面图标与应用入口
ICON_BASE_DIR="$HOME/.local/share/icons/hicolor"
ICON_PNG_DIR="$ICON_BASE_DIR/128x128/apps"
ICON_VALUE="input-keyboard"
if [ -f "$SCRIPT_DIR/packaging/silentinput.svg" ]; then
    mkdir -p "$ICON_DIR" "$ICON_PNG_DIR"
    cp -f "$SCRIPT_DIR/packaging/silentinput.svg" "$ICON_DIR/com.silentinput.controlcenter.svg"
    if [ ! -f "$ICON_BASE_DIR/index.theme" ] && [ -f "/usr/share/icons/hicolor/index.theme" ]; then
        cp -f "/usr/share/icons/hicolor/index.theme" "$ICON_BASE_DIR/index.theme"
    fi
    if command -v magick >/dev/null 2>&1; then
        magick -background none "$ICON_DIR/com.silentinput.controlcenter.svg" -resize 128x128 "$ICON_PNG_DIR/com.silentinput.controlcenter.png" 2>/dev/null || true
    elif command -v rsvg-convert >/dev/null 2>&1; then
        rsvg-convert -w 128 -h 128 "$ICON_DIR/com.silentinput.controlcenter.svg" -o "$ICON_PNG_DIR/com.silentinput.controlcenter.png" 2>/dev/null || true
    fi
    command -v gtk4-update-icon-cache >/dev/null 2>&1 && gtk4-update-icon-cache -f -t "$ICON_BASE_DIR" >/dev/null 2>&1 || true
    command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -f -t "$ICON_BASE_DIR" >/dev/null 2>&1 || true
    ICON_VALUE="com.silentinput.controlcenter"
fi

rm -f "$APP_DIR/silentinput.desktop"
cat > "$APP_DIR/com.silentinput.controlcenter.desktop" << EOF
[Desktop Entry]
Name=SilentInput 控制中心
Name[en]=SilentInput Control Center
Comment=静音输入伴侣 PC 服务与设置
Exec="$PYTHON_BIN" "$SCRIPT_DIR/silentinput-gui.py"
Icon=$ICON_VALUE
Terminal=false
Type=Application
StartupWMClass=com.silentinput.controlcenter
Categories=Utility;Settings;HardwareSettings;
Keywords=keyboard;mouse;input;silent;
EOF
chmod 644 "$APP_DIR/com.silentinput.controlcenter.desktop"
update-desktop-database -q "$APP_DIR" >/dev/null 2>&1 || true
echo "✅ 已创建桌面应用图标: SilentInput 控制中心"

# 7. 配置 systemd 用户服务
cat > "$SYSTEMD_USER_DIR/silentinput.service" << EOF
[Unit]
Description=SilentInput PC Companion Daemon
After=network-online.target graphical-session-pre.target bluetooth.target

[Service]
Type=simple
ExecStart="$PYTHON_BIN" -u "$SCRIPT_DIR/silentinput-daemon.py"
Restart=on-failure
RestartSec=3
Environment="PYTHONUNBUFFERED=1"

[Install]
WantedBy=default.target
EOF
chmod 644 "$SYSTEMD_USER_DIR/silentinput.service"
echo "✅ 已生成 systemd 用户服务: $SYSTEMD_USER_DIR/silentinput.service"

# 8. 配置 /dev/uinput 权限规则（若当前无写权限）
if [ ! -w /dev/uinput ] && [ -f "$SCRIPT_DIR/packaging/60-silentinput-uinput.rules" ]; then
    echo "⚠️ 当前用户对 /dev/uinput 无写权限，触控板与快捷键需要 udev 授权。"
    if command -v sudo >/dev/null 2>&1 && confirm_or_auto "是否写入 /etc/udev/rules.d/60-silentinput-uinput.rules 自动授权当前用户访问 /dev/uinput？"; then
        sudo cp "$SCRIPT_DIR/packaging/60-silentinput-uinput.rules" /etc/udev/rules.d/60-silentinput-uinput.rules
        sudo udevadm control --reload-rules >/dev/null 2>&1 || true
        sudo udevadm trigger /dev/uinput >/dev/null 2>&1 || true
    fi
fi

# 9. 启用并启动服务
if command -v systemctl >/dev/null 2>&1 && systemctl --user status >/dev/null 2>&1; then
    systemctl --user daemon-reload
    systemctl --user enable silentinput.service
    systemctl --user restart silentinput.service
    echo "✅ systemd 服务已启用并启动。"
else
    "$PYTHON_BIN" "$SCRIPT_DIR/silentinput" restart
fi

# 10. 防火墙提醒
if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
    if ! firewall-cmd --list-ports 2>/dev/null | grep -q "53318/tcp"; then
        echo ""
        echo "ℹ️ 提示: 检测到 firewalld 正在运行，若局域网手机无法连接，请放行端口:"
        echo "   sudo firewall-cmd --add-port=53318/tcp --permanent && sudo firewall-cmd --reload"
    fi
fi

echo ""
echo "========================================="
echo "          🎉 SilentInput 安装完成！       "
echo "========================================="
"$PYTHON_BIN" "$SCRIPT_DIR/silentinput" status
echo "========================================="
echo "常用指令："
echo "  silentinput status           - 查看运行状态、IP 及配对密钥"
echo "  silentinput gui              - 打开图形控制中心"
echo "  silentinput restart          - 重启服务"
echo "  silentinput uninstall        - 卸载服务与脚本"
echo "========================================="
