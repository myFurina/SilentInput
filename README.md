# SilentInput

**English** | [简体中文](README.zh-CN.md)

A remote input companion for **Linux (Wayland / GNOME)** that turns your Android phone into a silent wireless/wired keyboard, clipboard text injector, and touchpad for your PC.

> [!NOTE]
> SilentInput is completely free and open-source under the **GNU GPL-3.0** license. The only official distribution channel is this repository's [Releases](https://github.com/myFurina/SilentInput/releases) page. Beware of paid resellers or modified third-party builds.

## Features

- **Three Connection Modes**:
  - **USB (`adb reverse`)**: Local passwordless connection forwarded via `adb reverse tcp:53318 tcp:53318` (the daemon checks and maintains forwarding every 3 seconds when running).
  - **LAN (`WebSocket / WSS`)**: Default port `53318`, with pairing token authentication, silent first-use TLS certificate fingerprint pinning (TOFU), and toggleable TLS encryption.
  - **Bluetooth (`RFCOMM`)**: Direct in-app connection after pairing once in system Bluetooth settings — no Bluetooth tethering required.
- **Keyboard Tab**: Common shortcut keys, lockable modifiers (`Ctrl` / `Alt` / `Shift` / `Win`), arrow keys, and a collapsible compact full keyboard (letters, numbers, `F1–F12`, and navigation keys).
- **Touchpad Tab**: Single-finger cursor movement, two-finger scrolling, and left/right clicks injected on the PC via kernel `uinput`.
- **Text Tab**: Sends text through `wl-copy` to the system clipboard followed by a paste shortcut (switchable between terminal `Ctrl+Shift+V` and standard `Ctrl+V`), preventing host IMEs from swallowing or mangling characters; includes custom quick phrases and an optional collapsible bottom touchpad strip.

## Android App Installation

1. Open the [Releases](https://github.com/myFurina/SilentInput/releases) page.
2. Download the latest **`app-release.apk`** and install it (signed with a fixed release key so future versions upgrade cleanly in place).

## Linux PC Companion Installation

### Option 1: Native Linux Packages (Recommended)

Download the matching package for your distro from [Releases](https://github.com/myFurina/SilentInput/releases):

* **Debian / Ubuntu**:
  ```bash
  sudo apt install ./silentinput_*.deb
  systemctl --user enable --now silentinput
  # Uninstall: sudo apt remove silentinput
  ```
* **Fedora / RHEL**:
  ```bash
  sudo dnf install ./silentinput-*.rpm
  systemctl --user enable --now silentinput
  # Uninstall: sudo dnf remove silentinput
  ```
* **Arch Linux**:
  ```bash
  sudo pacman -U ./silentinput-*.pkg.tar.zst
  systemctl --user enable --now silentinput
  # Uninstall: sudo pacman -R silentinput
  ```

### Option 2: Smart Installer Script (Supports Immutable Distros such as Bluefin / Silverblue)

No git clone required — the installer detects your distro automatically (uses `.deb`/`.rpm` or system package managers where available, and automatically provisions an isolated Python venv with `evdev` on `ostree` / immutable desktops without requiring a reboot):

```bash
curl -fsSL https://raw.githubusercontent.com/myFurina/SilentInput/main/pc-daemon/install.sh | bash
```

Or run locally from a cloned repository:

```bash
cd pc-daemon
bash install.sh
```

### Common CLI Commands

```bash
silentinput status      # Show daemon status, IP, port, and pairing token
silentinput bluetooth   # Show local Bluetooth adapter & paired devices
silentinput start       # Start the companion daemon
silentinput stop        # Stop the companion daemon
silentinput restart     # Restart the companion daemon
silentinput token       # View pairing token (pass --reset to rotate)
silentinput tls on|off  # Toggle WSS TLS encryption
silentinput logs        # Stream daemon logs
silentinput gui         # Open the GTK4 / Libadwaita control center
silentinput uninstall   # Uninstall (or show package-manager remove hint)
```

To also remove pairing tokens, TLS certificates, and configuration files:

```bash
rm -rf ~/.config/silentinput
```

## License

Released under the [GNU General Public License v3.0](LICENSE).
