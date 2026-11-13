# SilentInput

[English](README.md) | **简体中文**

适用于 Linux（Wayland / GNOME）的手机远程输入工具，通过手机端向电脑发送长文本、按键组合与触控操作。

> [!NOTE]
> 本项目完全免费且基于 **GPL-3.0** 协议开源，唯一官方发布渠道为本仓库 [Releases 页面](https://github.com/myFurina/SilentInput/releases)。如遇到付费售卖、加广告或闭源二次打包版本，均非官方发布。

## 主要功能

- **三种连接方式**：
  - **USB（adb reverse）**：通过 `adb reverse tcp:53318 tcp:53318` 转发，本地免密直连（daemon 启动后每 3 秒自动检查配置）。
  - **局域网（WebSocket / WSS）**：默认端口 `53318`，支持配对密钥校验、首次连接静默固定证书指纹（TOFU）与 TLS 加密开关。
  - **蓝牙（RFCOMM）**：系统完成一次蓝牙配对后即可在 App 内直连，不依赖蓝牙网络共享。
- **键盘页**：常用按键、可锁定修饰键（`Ctrl` / `Alt` / `Shift` / `Win`）、方向键，以及可折叠的迷你全键盘（字母、数字、`F1–F12` 与导航键）。
- **触控板页**：单指移动、双指滚动、左右键点击，PC 端通过内核 `uinput` 注入事件。
- **文本页**：通过 `wl-copy` 写入系统剪贴板并发送粘贴组合键（支持终端 `Ctrl+Shift+V` 与普通应用 `Ctrl+V` 切换），避免电脑端中文输入法吞字或转成拼音；支持自定义短语及可选的底部折叠触控区。

## 安装手机端 APK

1. 前往 Releases 页面：[`https://github.com/myFurina/SilentInput/releases`](https://github.com/myFurina/SilentInput/releases)
2. 下载最新版本的 **`app-release.apk`** 并直接安装（固定签名发布，后续版本可直接覆盖升级）。

## 安装电脑端（Linux）

### 方式一：原生安装包（推荐，自动拉取依赖并支持干净卸载）

前往 [Releases 页面](https://github.com/myFurina/SilentInput/releases) 下载对应安装包：

* **Debian / Ubuntu**：
  ```bash
  sudo apt install ./silentinput_*.deb
  systemctl --user enable --now silentinput
  # 卸载：sudo apt remove silentinput
  ```
* **Fedora / RHEL**：
  ```bash
  sudo dnf install ./silentinput-*.rpm
  systemctl --user enable --now silentinput
  # 卸载：sudo dnf remove silentinput
  ```
* **Arch Linux**：
  ```bash
  sudo pacman -U ./silentinput-*.pkg.tar.zst
  systemctl --user enable --now silentinput
  # 卸载：sudo pacman -R silentinput
  ```

### 方式二：在线一键脚本 / 通用 Linux（兼容 Bluefin 等不可变系统）

无需克隆仓库，直接运行即可自动识别发行版（Debian/Ubuntu/Fedora 走包管理器；Bluefin/Silverblue 等不可变系统自动创建隔离 venv 安装 `evdev` 且无需重启）：

```bash
curl -fsSL https://raw.githubusercontent.com/myFurina/SilentInput/main/pc-daemon/install.sh | bash
```

或者在仓库内手动执行：

```bash
cd pc-daemon
bash install.sh
```

### 常用命令

```bash
silentinput status      # 查看运行状态、IP、端口与配对密钥
silentinput bluetooth   # 查看本机蓝牙信息及配对状态
silentinput start       # 启动服务
silentinput stop        # 停止服务
silentinput restart     # 重启服务
silentinput token       # 查看配对密钥（加 --reset 重新生成）
silentinput tls on|off  # 开关 TLS 加密
silentinput logs        # 查看服务日志
silentinput gui         # 打开图形控制中心
silentinput uninstall   # 卸载（包管理器安装时提示对应 remove 命令）
```

如需连同密钥、证书与配置文件一并删除：

```bash
rm -rf ~/.config/silentinput
```

## 开源协议

本项目采用 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 协议开源。
