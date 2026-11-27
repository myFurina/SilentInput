# SilentInput

[English](README.md) | **简体中文**

适用于 **Linux（Wayland / X11 / 无桌面纯终端）** 的远程输入工具：PC 端由 **Rust** 后台服务与基于 **Iced** 的桌面控制界面组成（不依赖 Python 或 GTK 运行时），手机端为 **Android（Jetpack Compose）** 客户端，提供键盘、触控板与文本输入功能。

> [!NOTE]
> 本项目基于 **GNU GPL-3.0** 协议开源，仅在本仓库 [Releases 页面](https://github.com/myFurina/SilentInput/releases) 提供官方发布包。

## 主要功能

- **三种连接方式**：
  - **USB（`adb reverse`）**：通过 `adb reverse tcp:53318 tcp:53318` 进行本地免密转发，后台通过 ADB TCP 协议自动监听设备。若在 `systemd --user` 下长期使用非默认 `ANDROID_ADB_SERVER_PORT`，需写入 `~/.config/environment.d/adb.conf` 或通过 `systemctl --user edit silentinput.service` 配置 `Environment=...`（终端 `export` 对后台服务不生效，`systemctl --user set-environment` 仅在当前会话有效）。
  - **局域网（`WebSocket / WSS` + `UDP`）**：`53318/tcp` 用于控制指令、认证与文本传输；`53318/udp` 作为可选的 AES-256-GCM 加密二进制通道传输指针、滚轮与多指触控数据（含 2 帧 FEC 丢包恢复，被防火墙拦截时自动回退至 `TCP_NODELAY` WebSocket）。支持二维码扫码配对（`silentinput qr` 或桌面 GUI）、常用主机管理、Android Keystore 密钥存储、TOFU 证书指纹校验与 TLS 开关。
  - **蓝牙（`RFCOMM`）**：系统完成一次蓝牙配对后即可在 App 内直连，不依赖蓝牙网络共享。

- **触控板与移动调参**：
  - **虚拟多指触摸板（`uinput` MT Protocol B）**：在 Linux 上创建 `400mm × 400mm` 虚拟触摸板设备，支持双指滚动、双指捏合缩放及三指桌面手势，并支持自动或手动回退至相对鼠标模式（`REL_WHEEL_HI_RES` 高分辨率滚轮、双击拖拽、三指快捷键与垂直惯性滚动）。
  - **分项调参面板**：提供 4 组预设（**精准 / 均衡 / 疾速 / 直出**）与三大分类共 18 项参数：
    - **基础**：指针速度、滚动速度、快滑加速、自适应防抖平滑、起始死区、落指合并窗口。
    - **曲线**：纵向比例（Y/X 轴倍率）、低速微操精度、加速阈值、加速幂次曲线、最高倍率上限、指针滑行惯性。
    - **校正**：手动握持旋转（`±30°`）、直线吸附、边缘防误触宽度（展开面板时显示保护区边界）、前瞻速度预测（`0–16ms`）、滚轮惯性阻尼、轻点容差。
    - 另提供 **防甩尾** 与 **滚轮锁轴** 开关。
  - **360° 重力纠偏** *（手动开关控制）*：开启后通过重力传感器（`TYPE_GRAVITY`，不支持的设备回退至低通滤波加速度计）按 `-180° ~ +180°` 旋转整块触控板坐标系，使单指移动、双指滚动/捏合及三指手势与实际物理朝向保持一致。面板显示实时角度，触控区点阵与方向箭头同步旋转；平放桌面时保持当前角度不变，退入后台自动注销监听。

- **键盘页**：常用快捷键、可配置输入法切换键、可锁定修饰键（`Ctrl` / `Alt` / `Shift` / `Win`）、方向键、可折叠迷你全键盘（`A–Z` / `0–9` / `F1–F12` / 导航键）以及横屏全键盘模式。

- **文本页**：支持三种输入方式——**终端**（`Ctrl+Shift+V`）、**应用**（`Ctrl+V` 并在发送后恢复剪贴板原内容）与 **直打**（Wayland / X11 / 无桌面纯终端逐键发送），避免宿主机输入法对长文本产生截断或转写影响；内置可配置快捷编辑键条、常用短语与可选内嵌触控区。

- **语言与外观选项**：支持**简体中文**与**英文**（「跟随系统」下中文系统使用简体中文，其余系统语言回退为英文）、深色模式与静音模式（关闭触感振动）。

## 安装手机端 APK

1. 前往 [Releases 页面](https://github.com/myFurina/SilentInput/releases)。
2. 下载 **`app-release.apk`** 并安装（使用固定签名密钥，后续版本可直接覆盖更新）。

## 安装电脑端（Linux）

### 方式一：发行版原生包（`amd64` / `arm64`）

不依赖 Python 或 GTK 运行时，从 [Releases 页面](https://github.com/myFurina/SilentInput/releases) 下载对应安装包：

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

### 方式二：安装脚本（兼容 Bluefin / Silverblue 等不可变系统）

脚本会自动识别发行版与架构：Debian/Ubuntu/Fedora 安装对应的 `.deb` / `.rpm` 包；`ostree` 不可变系统或 Arch/openSUSE 解压预编译的 `silentinput-linux-*.tar.gz` 二进制归档（无需 Cargo 编译环境或重启），并协助检查安装 `wl-clipboard` / `xclip` / `adb` 与 `uinput` 权限规则：

```bash
curl -fsSL https://raw.githubusercontent.com/myFurina/SilentInput/main/pc-daemon/install.sh | bash
```

或在本地仓库目录执行：

```bash
cd pc-daemon
bash install.sh
```

### 常用 CLI 命令

```bash
silentinput status              # 查看运行状态、IP、端口与配对密钥
silentinput qr                  # 在终端输出局域网配对二维码
silentinput bluetooth           # 查看本机蓝牙适配器与已配对设备
silentinput start               # 启动后台服务
silentinput stop                # 停止后台服务
silentinput restart             # 重启后台服务
silentinput token               # 查看配对密钥（--reset 重新生成，--qr 显示二维码）
silentinput tls on|off          # 开关 WSS TLS 传输加密
silentinput autostart on|off    # 开关 systemd --user 开机自启
silentinput logs                # 实时查看服务日志
silentinput gui                 # 打开桌面控制界面
silentinput reset               # 恢复默认配置并重启服务
silentinput uninstall           # 卸载（包管理器安装时输出对应卸载命令）
```

如需一并清理配置文件、配对密钥与 TLS 证书：

```bash
rm -rf ~/.config/silentinput
```

## 开源协议

本项目基于 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 协议开源。
