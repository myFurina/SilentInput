#!/bin/bash
# SilentInput Native Installer (Debian/Ubuntu, Fedora, Arch, openSUSE, Bluefin/Silverblue)
set -e

REPO="myFurina/SilentInput"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd || pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." 2>/dev/null && pwd || pwd)"
RS_DIR="$REPO_ROOT/pc-daemon-rs"
BIN_DIR="$HOME/.local/bin"
APP_DIR="$HOME/.local/share/applications"
ICON_DIR="$HOME/.local/share/icons/hicolor/scalable/apps"
DATA_DIR="$HOME/.local/share/silentinput"
SYSTEMD_USER_DIR="$HOME/.config/systemd/user"

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
    local pattern="$1"
    local dest="$2"
    local url
    url="$(curl -fsSL -H "User-Agent: SilentInput-Installer" "https://api.github.com/repos/$REPO/releases/latest" \
        | tr ',' '\n' \
        | grep '"browser_download_url"' \
        | sed -E 's/.*"browser_download_url"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/' \
        | grep -E "$pattern" \
        | head -n 1 || true)"
    if [ -z "$url" ]; then
        return 1
    fi
    echo "⬇️  正在下载: $url"
    curl -fL --progress-bar "$url" -o "$dest"
}

detect_arch() {
    case "$(uname -m)" in
        x86_64|amd64) echo "amd64" ;;
        aarch64|arm64) echo "arm64" ;;
        *) echo "amd64" ;;
    esac
}

echo "========================================="
echo "       正在安装 SilentInput PC 伴侣       "
echo "========================================="

ARCH="$(detect_arch)"
RPM_ARCH="x86_64"
[ "$ARCH" = "arm64" ] && RPM_ARCH="aarch64"

# Remote one-line execution (without local Rust workspace/binaries)
if [ ! -d "$RS_DIR" ] && [ ! -x "$SCRIPT_DIR/silentinput-daemon" ]; then
    IS_IMMUTABLE=0
    [ -e /run/ostree-booted ] && IS_IMMUTABLE=1
    TMP_DIR="$(mktemp -d)"
    trap 'rm -rf "$TMP_DIR"' EXIT

    if [ "$IS_IMMUTABLE" -eq 0 ] && command -v apt-get >/dev/null 2>&1; then
        TMP_DEB="$TMP_DIR/silentinput-latest.deb"
        if download_latest_release_asset "_${ARCH}\\.deb$" "$TMP_DEB"; then
            echo "📦 检测到 Debian/Ubuntu ($ARCH)，正在通过 apt 安装..."
            sudo apt-get update
            sudo apt-get install -y "$TMP_DEB"
            echo "✅ 安装完成！请运行 'systemctl --user enable --now silentinput' 启动服务，或执行 'silentinput status'。"
            exit 0
        fi
    elif [ "$IS_IMMUTABLE" -eq 0 ] && command -v dnf >/dev/null 2>&1; then
        TMP_RPM="$TMP_DIR/silentinput-latest.rpm"
        if download_latest_release_asset "\\.${RPM_ARCH}\\.rpm$" "$TMP_RPM"; then
            echo "📦 检测到 Fedora/RHEL ($RPM_ARCH)，正在通过 dnf 安装..."
            sudo dnf install -y "$TMP_RPM"
            echo "✅ 安装完成！请运行 'systemctl --user enable --now silentinput' 启动服务，或执行 'silentinput status'。"
            exit 0
        fi
    fi

    # Prebuilt standalone tarball for Bluefin / Silverblue / Arch / openSUSE (no cargo needed)
    TMP_BUNDLE="$TMP_DIR/silentinput-linux-${ARCH}.tar.gz"
    if download_latest_release_asset "silentinput-linux-${ARCH}\\.tar\\.gz$" "$TMP_BUNDLE"; then
        echo "📦 正在部署预编译原生二进制包 ($ARCH) 到 $DATA_DIR/bundle ..."
        rm -rf "$DATA_DIR/bundle"
        mkdir -p "$DATA_DIR/bundle"
        tar -xzf "$TMP_BUNDLE" -C "$DATA_DIR/bundle"
        rm -rf "$TMP_DIR"
        trap - EXIT
        exec bash "$DATA_DIR/bundle/install.sh" "$@"
    fi

    echo "⬇️  未找到预编译 Release 包，正在拉取最新源码到 $DATA_DIR/src ..."
    TMP_TAR="$TMP_DIR/silentinput-main.tar.gz"
    rm -rf "$DATA_DIR/src"
    mkdir -p "$DATA_DIR/src"
    curl -fL --progress-bar "https://github.com/$REPO/archive/refs/heads/main.tar.gz" -o "$TMP_TAR"
    tar -xzf "$TMP_TAR" -C "$DATA_DIR/src" --strip-components=1
    rm -rf "$TMP_DIR"
    trap - EXIT
    exec bash "$DATA_DIR/src/pc-daemon/install.sh" "$@"
fi

# 1. Resolve or compile Rust binaries (silentinput, silentinput-daemon, silentinput-gui)
RELEASE_DIR="$RS_DIR/target/release"
BIN_CLI=""
BIN_DAEMON=""
BIN_GUI=""

if [ -x "$RELEASE_DIR/silentinput" ] && [ -x "$RELEASE_DIR/silentinput-daemon" ] && [ -x "$RELEASE_DIR/silentinput-gui" ]; then
    BIN_CLI="$RELEASE_DIR/silentinput"
    BIN_DAEMON="$RELEASE_DIR/silentinput-daemon"
    BIN_GUI="$RELEASE_DIR/silentinput-gui"
elif [ -x "$SCRIPT_DIR/silentinput" ] && [ -x "$SCRIPT_DIR/silentinput-daemon" ] && [ -x "$SCRIPT_DIR/silentinput-gui" ]; then
    BIN_CLI="$SCRIPT_DIR/silentinput"
    BIN_DAEMON="$SCRIPT_DIR/silentinput-daemon"
    BIN_GUI="$SCRIPT_DIR/silentinput-gui"
elif [ -f "$RS_DIR/Cargo.toml" ] && command -v cargo >/dev/null 2>&1; then
    echo "🦀 正在编译原生 Rust 二进制 (release 模式)..."
    cargo build --release --workspace --manifest-path "$RS_DIR/Cargo.toml"
    BIN_CLI="$RELEASE_DIR/silentinput"
    BIN_DAEMON="$RELEASE_DIR/silentinput-daemon"
    BIN_GUI="$RELEASE_DIR/silentinput-gui"
else
    echo "❌ 未找到预编译二进制或 Cargo 构建环境。请先安装 Rust toolchain 或使用发布包 (.deb/.rpm) 安装。" >&2
    exit 1
fi

# 2. Check optional CLI tools (wl-clipboard / adb)
MISSING_WLCOPY=0
MISSING_ADB=0
command -v wl-copy >/dev/null 2>&1 || MISSING_WLCOPY=1
command -v adb >/dev/null 2>&1 || MISSING_ADB=1

if [ "$MISSING_WLCOPY" -eq 1 ] || [ "$MISSING_ADB" -eq 1 ]; then
    echo "🔍 检测到部分可选/辅助工具尚未安装："
    [ "$MISSING_WLCOPY" -eq 1 ] && echo "   - wl-clipboard (Wayland 剪贴板中文注入)"
    [ "$MISSING_ADB" -eq 1 ] && echo "   - adb / android-tools (USB 有线直连模式)"

    if [ -e /run/ostree-booted ] && command -v brew >/dev/null 2>&1; then
        BREW_PKGS=""
        [ "$MISSING_WLCOPY" -eq 1 ] && BREW_PKGS="$BREW_PKGS wl-clipboard"
        [ "$MISSING_ADB" -eq 1 ] && BREW_PKGS="$BREW_PKGS android-platform-tools"
        if confirm_or_auto "是否使用 brew 安装辅助工具 ($BREW_PKGS )？"; then
            brew install $BREW_PKGS || true
        fi
    elif command -v apt-get >/dev/null 2>&1; then
        if confirm_or_auto "是否通过 apt 安装 wl-clipboard 与 adb？"; then
            sudo apt-get update && sudo apt-get install -y wl-clipboard adb
        fi
    elif command -v dnf >/dev/null 2>&1; then
        if confirm_or_auto "是否通过 dnf 安装 wl-clipboard 与 android-tools？"; then
            sudo dnf install -y wl-clipboard android-tools
        fi
    elif command -v pacman >/dev/null 2>&1; then
        if confirm_or_auto "是否通过 pacman 安装 wl-clipboard 与 android-tools？"; then
            sudo pacman -S --needed --noconfirm wl-clipboard android-tools
        fi
    elif command -v zypper >/dev/null 2>&1; then
        if confirm_or_auto "是否通过 zypper 安装 wl-clipboard 与 android-tools？"; then
            sudo zypper install -y wl-clipboard android-tools
        fi
    fi
fi

# 3. Clean legacy Python venv if upgrading from older version
rm -rf "$DATA_DIR/venv"

# 4. Install native binaries to ~/.local/bin (remove existing symlinks first to avoid overwriting symlink targets)
mkdir -p "$BIN_DIR" "$APP_DIR" "$ICON_DIR" "$SYSTEMD_USER_DIR" "$DATA_DIR"
rm -f "$BIN_DIR/silentinput" "$BIN_DIR/silentinput-daemon" "$BIN_DIR/silentinput-gui"
cp -f "$BIN_CLI" "$BIN_DIR/silentinput"
cp -f "$BIN_DAEMON" "$BIN_DIR/silentinput-daemon"
cp -f "$BIN_GUI" "$BIN_DIR/silentinput-gui"
chmod 755 "$BIN_DIR/silentinput" "$BIN_DIR/silentinput-daemon" "$BIN_DIR/silentinput-gui"
[ -f "$SCRIPT_DIR/uninstall.sh" ] && cp -f "$SCRIPT_DIR/uninstall.sh" "$DATA_DIR/uninstall.sh" && chmod +x "$DATA_DIR/uninstall.sh"
echo "✅ 已安装原生程序至: $BIN_DIR/{silentinput,silentinput-daemon,silentinput-gui}"

# 5. Install desktop icon and entry
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
Name=SilentInput Control Center
Name[zh_CN]=SilentInput 控制中心
Comment=SilentInput PC companion service and settings
Comment[zh_CN]=静音输入伴侣 PC 服务与设置
Exec=$BIN_DIR/silentinput-gui
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

# 6. Install systemd user unit
cat > "$SYSTEMD_USER_DIR/silentinput.service" << EOF
[Unit]
Description=SilentInput PC Companion Daemon
After=network-online.target graphical-session-pre.target bluetooth.target

[Service]
Type=simple
ExecStart=$BIN_DIR/silentinput-daemon
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
EOF
chmod 644 "$SYSTEMD_USER_DIR/silentinput.service"
echo "✅ 已生成 systemd 用户服务: $SYSTEMD_USER_DIR/silentinput.service"

# 7. Configure /dev/uinput udev rule if missing write permission
if [ ! -w /dev/uinput ] && [ -f "$SCRIPT_DIR/packaging/60-silentinput-uinput.rules" ]; then
    echo "⚠️ 当前用户对 /dev/uinput 无写权限，触控板与快捷键需要 udev 授权。"
    if command -v sudo >/dev/null 2>&1 && confirm_or_auto "是否写入 /etc/udev/rules.d/60-silentinput-uinput.rules 自动授权当前用户访问 /dev/uinput？"; then
        sudo cp "$SCRIPT_DIR/packaging/60-silentinput-uinput.rules" /etc/udev/rules.d/60-silentinput-uinput.rules
        sudo udevadm control --reload-rules >/dev/null 2>&1 || true
        sudo udevadm trigger /dev/uinput >/dev/null 2>&1 || true
    fi
fi

# 8. Enable and start service (auto-generates config & TLS certs on startup)
if command -v systemctl >/dev/null 2>&1 && systemctl --user status >/dev/null 2>&1; then
    systemctl --user daemon-reload
    systemctl --user enable silentinput.service
    systemctl --user restart silentinput.service
    echo "✅ systemd 服务已启用并启动。"
else
    "$BIN_DIR/silentinput" restart
fi

# 9. Firewall reminder
if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
    if ! firewall-cmd --list-ports 2>/dev/null | grep -q "53318/tcp" || ! firewall-cmd --list-ports 2>/dev/null | grep -q "53318/udp"; then
        echo ""
        echo "ℹ️ 提示: 检测到 firewalld 正在运行，若局域网手机无法连接或需启用低延迟 UDP 鼠标，请放行端口:"
        echo "   sudo firewall-cmd --add-port=53318/tcp --add-port=53318/udp --permanent && sudo firewall-cmd --reload"
    fi
fi

echo ""
echo "========================================="
echo "          🎉 SilentInput 安装完成！       "
echo "========================================="
"$BIN_DIR/silentinput" status
echo "========================================="
echo "常用指令："
echo "  silentinput status           - 查看运行状态、IP 及配对密钥"
echo "  silentinput gui              - 打开图形控制中心"
echo "  silentinput restart          - 重启服务"
echo "  silentinput uninstall        - 卸载服务与程序"
echo "========================================="
