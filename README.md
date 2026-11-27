# SilentInput

**English** | [简体中文](README.zh-CN.md)

Remote input tool for **Linux (Wayland / X11 / headless TTY)** consisting of a **Rust** PC daemon with an **Iced** desktop GUI (no Python or GTK runtime dependencies) and an **Android (Jetpack Compose)** client for keyboard input, pointer/touchpad control, and text transmission.

> [!NOTE]
> SilentInput is open-source software licensed under **GNU GPL-3.0**. Official builds are published only on this repository's [Releases](https://github.com/myFurina/SilentInput/releases) page.

## Features

- **Connection Modes**:
  - **USB (`adb reverse`)**: Local connection forwarded via `adb reverse tcp:53318 tcp:53318` with automatic ADB device tracking. If using a non-default `ANDROID_ADB_SERVER_PORT` under `systemd --user`, set it persistently in `~/.config/environment.d/adb.conf` or via `systemctl --user edit silentinput.service` (`Environment=...`), since shell `export` and `systemctl --user set-environment` do not persist across reboots.
  - **LAN (`WebSocket / WSS` + `UDP`)**: Port `53318/tcp` for control, authentication, and text transmission, paired with an optional `53318/udp` AES-256-GCM encrypted binary channel for pointer/wheel/touchpad frames (with 2-frame FEC recovery and automatic fallback to `TCP_NODELAY` WebSocket if UDP is blocked). Supports QR code pairing (`silentinput qr` or the desktop GUI), saved host entries, Android Keystore token storage, TOFU certificate fingerprint pinning, and toggleable TLS encryption.
  - **Bluetooth (`RFCOMM`)**: Connects directly in the app after system Bluetooth pairing, without requiring Bluetooth tethering.

- **Touchpad & Movement Tuning**:
  - **Virtual Multi-Touch Touchpad (`uinput` MT Protocol B)**: Emulates a `400mm × 400mm` virtual touchpad for two-finger scrolling, pinch-to-zoom, and native three-finger gestures, with automatic fallback to relative mouse mode (`REL_WHEEL_HI_RES`, double-tap drag, three-finger shortcuts, and vertical scroll momentum).
  - **Parameter Tuning Panel**: Includes 4 presets (**Precise**, **Balanced**, **Fast**, **Raw**) and 18 adjustable parameters organized into 3 tabs:
    - **Basic**: Pointer Speed, Scroll Speed, Acceleration, One-Euro Smoothing, Touchdown Deadzone, Multi-Touch Merge Window.
    - **Curve**: Vertical Ratio (Y/X), Low-Speed Precision, Acceleration Threshold, Acceleration Curve, Speed Cap, Pointer Coasting Inertia.
    - **Adjust**: Manual Grip Rotation (`±30°`), Angle Snapping, Edge Palm Guard (with border preview when the panel is open), Velocity Prediction (`0–16ms`), Scroll Fling Brake, Tap Wobble Tolerance.
    - Includes toggles for **Anti-Whip** (multi-finger liftoff lock) and **Axis Lock** (scroll axis lock).
  - **360° Auto-Tilt Rotation** *(optional toggle)*: Uses the gravity sensor (`TYPE_GRAVITY`, or accelerometer fallback) to rotate the trackpad coordinate plane (`-180°` to `+180°`), applying the same rotation to single-finger movement, two-finger scroll/pinch, and three-finger swipes. Displays the live angle, rotates the internal background grid and direction marker, holds the current orientation when placed flat on a surface, and unregisters the sensor listener when backgrounded.

- **Keyboard Tab**: Common shortcuts, configurable IME switch key, lockable modifier keys (`Ctrl` / `Alt` / `Shift` / `Win`), arrow keys, collapsible mini keyboard (`A–Z`, `0–9`, `F1–F12`, navigation), and a landscape full-keyboard layout.

- **Text Tab**: Supports three input methods — **Terminal** (`Ctrl+Shift+V`), **App** (`Ctrl+V` with clipboard backup/restore), and **Direct Typing** (keycode injection for Wayland / X11 / headless TTY) — to avoid host IME interference on multi-character text, along with configurable quick-edit buttons, saved phrases, and an optional inline touchpad.

- **Localization & Display Options**: Supports **English** and **Simplified Chinese** (system default uses Chinese when the OS language is `zh` and falls back to English for other locales), Dark Mode, and Silent Mode (disables haptic feedback).

## Android App Installation

1. Open [Releases](https://github.com/myFurina/SilentInput/releases).
2. Download and install **`app-release.apk`** (signed with a consistent release key for in-place upgrades).

## Linux PC Daemon Installation

### Option 1: Distribution Packages (`amd64` / `arm64`)

No Python or GTK runtime dependencies required. Download the package matching your distribution from [Releases](https://github.com/myFurina/SilentInput/releases):

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

### Option 2: Install Script (Supports Immutable Distributions such as Bluefin / Silverblue)

Detects the current distribution and CPU architecture: installs `.deb` or `.rpm` packages on Debian/Ubuntu/Fedora, extracts the prebuilt `silentinput-linux-*.tar.gz` binary archive on `ostree`-based immutable systems or Arch/openSUSE (without requiring Cargo or a reboot), and checks/installs `wl-clipboard`, `xclip`, `adb`, and `uinput` udev rules:

```bash
curl -fsSL https://raw.githubusercontent.com/myFurina/SilentInput/main/pc-daemon/install.sh | bash
```

Or run from a local repository clone:

```bash
cd pc-daemon
bash install.sh
```

### CLI Commands

```bash
silentinput status              # Show daemon status, IPs, ports, and pairing token
silentinput qr                  # Print LAN QR pairing code in terminal
silentinput bluetooth           # Show local Bluetooth adapter and paired devices
silentinput start               # Start the daemon
silentinput stop                # Stop the daemon
silentinput restart             # Restart the daemon
silentinput token               # Show pairing token (--reset to regenerate, --qr for QR)
silentinput tls on|off          # Enable or disable WSS TLS encryption
silentinput autostart on|off    # Enable or disable systemd --user autostart
silentinput logs                # Stream daemon logs
silentinput gui                 # Open the desktop GUI window
silentinput reset               # Reset config to defaults and restart daemon
silentinput uninstall           # Uninstall (or print package-manager removal command)
```

To remove configuration files, tokens, and TLS certificates:

```bash
rm -rf ~/.config/silentinput
```

## License

Licensed under [GNU General Public License v3.0](LICENSE).
