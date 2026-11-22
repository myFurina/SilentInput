use std::env;
use std::path::PathBuf;
use std::process::Command;

use clap::{Parser, Subcommand};
use silentinput_core::config::{
    generate_token_hex, get_token_status_text, load_config, regenerate_token, save_config,
    set_tls_enabled, unix_timestamp_secs, SilentInputConfig,
};
use silentinput_core::i18n::resolve_gui_is_zh;
use silentinput_core::logs::{select_log_source, LogSource};
use silentinput_core::process::{
    get_all_host_ips, get_connected_usb_devices, get_package_uninstall_command,
    is_autostart_enabled, is_daemon_running, is_system_package_install, service_restart,
    service_start, service_stop, set_autostart_enabled, spawn_detached_binary,
};

#[derive(Parser)]
#[command(
    name = "silentinput",
    version,
    about = "SilentInput PC Companion CLI",
    disable_help_subcommand = true
)]
struct Cli {
    #[command(subcommand)]
    command: Option<Commands>,
}

#[derive(Subcommand)]
enum Commands {
    #[command(visible_alias = "up", about = "启动后台输入接收服务")]
    Start,
    #[command(visible_alias = "down", about = "停止后台服务")]
    Stop,
    #[command(visible_alias = "reload", about = "重启后台服务")]
    Restart,
    #[command(visible_alias = "info", about = "查看运行状态、IP、端口及配对密钥")]
    Status,
    #[command(visible_alias = "bt", about = "查看电脑蓝牙名称、MAC 地址及配对指引")]
    Bluetooth,
    #[command(about = "查看或管理配对密钥")]
    Token {
        #[arg(long)]
        reset: bool,
        #[arg(long)]
        expire: Option<String>,
    },
    #[command(about = "查看或切换 TLS 传输加密 (on/off)")]
    Tls { state: Option<String> },
    #[command(about = "查看或切换开机自动启动 (on/off)")]
    Autostart { state: Option<String> },
    #[command(about = "实时跟踪后台服务运行日志")]
    Logs,
    #[command(about = "打开图形控制中心界面")]
    Gui,
    #[command(about = "安装到系统 (开机自启、桌面图标、CLI 命令)")]
    Install {
        #[arg(trailing_var_arg = true, allow_hyphen_values = true)]
        args: Vec<String>,
    },
    #[command(about = "从系统中卸载并清理")]
    Uninstall {
        #[arg(trailing_var_arg = true, allow_hyphen_values = true)]
        args: Vec<String>,
    },
    #[command(visible_alias = "v", about = "查看版本信息")]
    Version,
    #[command(about = "恢复出厂默认配置并重启服务")]
    Reset,
    #[command(about = "显示帮助信息")]
    Help,
}

fn print_help() {
    println!(
        r#"
SilentInput 管理命令:
  silentinput start       - 启动后台输入接收服务
  silentinput stop        - 停止后台服务
  silentinput restart     - 重启后台服务
  silentinput status      - 查看运行状态、IP、端口及配对密钥
  silentinput bluetooth   - 查看电脑蓝牙名称、MAC 地址及配对指引
  silentinput token       - 查看当前安全配对密钥 (--reset 可重新生成)
  silentinput tls         - 查看或切换 TLS 传输加密 (on/off)
  silentinput autostart   - 查看或切换开机自动启动 (on/off)
  silentinput reset       - 恢复出厂默认配置并重启服务
  silentinput logs        - 实时跟踪后台服务运行日志
  silentinput gui         - 打开桌面控制中心界面
  silentinput install     - 安装到系统 (开机自启、桌面图标、CLI 命令)
  silentinput uninstall   - 从系统中完全卸载并清理
"#
    );
}

fn cmd_start() {
    let (ok, msg) = service_start();
    if ok {
        println!("✅ {msg}");
    } else {
        eprintln!("❌ {msg}");
    }
}

fn cmd_stop() {
    let (_, msg) = service_stop();
    println!("🛑 {msg}");
}

fn cmd_restart() {
    let (ok, msg) = service_restart();
    if ok {
        println!("✅ {msg}");
    } else {
        eprintln!("❌ {msg}");
    }
}

fn cmd_status() {
    let config = load_config();
    let (running, pid) = is_daemon_running();
    let is_zh = resolve_gui_is_zh(&config.gui_language);

    println!("=========================================");
    println!("        SilentInput PC Companion        ");
    println!("=========================================");
    if running {
        let pid_info = if pid > 0 {
            format!(" (PID: {pid})")
        } else {
            String::new()
        };
        println!(" 服务状态:    🟢 运行中{pid_info}");
    } else {
        println!(" 服务状态:    ⚪ 已停止");
    }

    println!(" 监听端口:    {}", config.port);

    let mode = config.access_control_mode.as_str();
    let mode_desc = match mode {
        "whitelist" => "白名单模式 (仅允许名单内 IP)",
        "blacklist" => "黑名单模式 (拒绝名单内 IP，其余允许)",
        "all" => "允许所有网络 (0.0.0.0)",
        "localhost_only" => "仅本机 USB (127.0.0.1)",
        other => other,
    };
    println!(" 访问控制:    {mode_desc}");

    if mode == "whitelist" {
        let desc = if config.whitelist.is_empty() {
            "空 (拒绝所有非本机 IP)".to_string()
        } else {
            config.whitelist.join(", ")
        };
        println!(" IP 白名单:   {desc}");
    } else if mode == "blacklist" {
        let desc = if config.blacklist.is_empty() {
            "空".to_string()
        } else {
            config.blacklist.join(", ")
        };
        println!(" IP 黑名单:   {desc}");
    }

    let host_ips = get_all_host_ips();
    if host_ips.is_empty() {
        println!(" 电脑 IP (局域网): 未检测到有效 IP");
    } else {
        println!(" 电脑 IP (局域网):");
        for hip in host_ips {
            println!("   • {hip}");
        }
    }

    let usb_devs = get_connected_usb_devices();
    if usb_devs.is_empty() {
        println!(" USB 有线:    未检测到连接设备");
    } else {
        println!(" USB 有线:    🔌 已连接设备 ({})", usb_devs.join(", "));
    }

    let expire_desc = get_token_status_text(&config, is_zh);
    println!(" 配对密钥:    🔑 {}", config.auth_token);
    println!(" 密钥有效期:  {expire_desc}");
    println!(" 无线验证:    需输入配对密钥");
    println!(
        " 流量加密:    {}",
        if config.enable_tls {
            "🔒 TLS 传输加密 (WSS)"
        } else {
            "明文传输 (WS)"
        }
    );
    println!(" USB 模式:    物理线连接自动信任");
    println!(" 蓝牙信道:    📶 RFCOMM Channel 1 (SPP 标准串口)");
    let autostart_str = if is_autostart_enabled() {
        "🟢 已开启"
    } else {
        "⚪ 已关闭"
    };
    println!(" 开机自启:    {autostart_str}");
    println!("=========================================");
}

fn cmd_token(reset: bool, expire: Option<String>) {
    let mut config = load_config();
    if reset {
        let new_token = regenerate_token();
        println!("✅ 已生成新的随机配对密钥: {new_token}");
        println!("提示：如果后台服务正在运行，请执行 'silentinput restart' 使新密钥生效。");
    } else if let Some(raw_mode) = expire {
        let mode = raw_mode.trim().to_ascii_lowercase();
        if !matches!(mode.as_str(), "never" | "24h" | "7d" | "1h") {
            println!("❌ 无效的有效期模式。可选值: never (不过期), 24h (24小时), 7d (7天), 1h (1小时)");
            return;
        }
        config.token_expire_mode = mode.clone();
        config.token_created_at = unix_timestamp_secs();
        let _ = save_config(&config);
        println!("✅ 已设置配对密钥有效期模式为: {mode}");
    } else {
        let expire_desc = get_token_status_text(&config, true);
        println!("=========================================");
        println!(" 当前配对密钥: 🔑 {}", config.auth_token);
        println!(" 密钥有效期:   {expire_desc}");
        println!("=========================================");
        println!("常用管理选项:");
        println!("  silentinput token --reset              - 立即轮换并生成新的随机密钥");
        println!("  silentinput token --expire never       - 设置为永不过期");
        println!("  silentinput token --expire 24h         - 设置为 24 小时自动刷新轮换");
        println!("  silentinput token --expire 7d          - 设置为 7 天自动刷新轮换");
        println!("  silentinput token --expire 1h          - 设置为 1 小时临时有效");
    }
}

fn cmd_tls(state: Option<String>) {
    let config = load_config();
    let Some(raw) = state else {
        let state_str = if config.enable_tls {
            "🟢 已开启 (严格 WSS 传输加密，手机端需同步开启)"
        } else {
            "⚪ 已关闭 (明文 WS，手机端需同步关闭)"
        };
        println!("当前 TLS 传输加密: {state_str}");
        println!("用法:");
        println!("  silentinput tls on     - 启用 TLS 传输加密 (默认)");
        println!("  silentinput tls off    - 停用 TLS 传输加密 (明文传输)");
        println!("\n注: 为防止中间人攻击与降级攻击，手机端与电脑端必须保持相同的加密设置。");
        return;
    };

    match raw.trim().to_ascii_lowercase().as_str() {
        "on" | "enable" | "true" | "1" => {
            set_tls_enabled(true);
            println!("✅ 已开启 TLS 传输加密 (WSS)。正在重启服务生效...");
            cmd_restart();
        }
        "off" | "disable" | "false" | "0" => {
            set_tls_enabled(false);
            println!("⚠️ 已关闭 TLS 传输加密 (明文 WS)。正在重启服务生效...");
            cmd_restart();
        }
        other => eprintln!("❌ 未知参数: {other}。请使用 on 或 off。"),
    }
}

fn cmd_autostart(state: Option<String>) {
    let Some(raw) = state else {
        let enabled = is_autostart_enabled();
        let state_str = if enabled {
            "🟢 已启用 (登录桌面时自动启动)"
        } else {
            "⚪ 已关闭 (仅手动启动)"
        };
        println!("当前开机自启状态: {state_str}");
        println!("用法:");
        println!("  silentinput autostart on   - 启用开机自启");
        println!("  silentinput autostart off  - 关闭开机自启");
        return;
    };

    let (ok, msg) = match raw.trim().to_ascii_lowercase().as_str() {
        "on" | "enable" | "true" | "1" => set_autostart_enabled(true, true),
        "off" | "disable" | "false" | "0" => set_autostart_enabled(false, true),
        other => {
            eprintln!("❌ 未知参数: {other}。请使用 on 或 off。");
            return;
        }
    };
    if ok {
        println!("✅ {msg}");
    } else {
        eprintln!("❌ {msg}");
    }
}

fn cmd_logs() {
    match select_log_source() {
        LogSource::Journald { unit } => {
            println!("正在查看 systemd 服务日志: {unit} (按 Ctrl+C 退出)");
            let _ = Command::new("journalctl")
                .args(["--user", "-u", &unit, "-n", "30", "-f", "--no-pager"])
                .status();
        }
        LogSource::File(log_file) => {
            if !log_file.exists() {
                println!("暂无日志文件 ({})", log_file.display());
                return;
            }
            println!("正在查看日志: {} (按 Ctrl+C 退出)", log_file.display());
            let _ = Command::new("tail")
                .args(["-n", "30", "-f"])
                .arg(&log_file)
                .status();
        }
    }
}

fn cmd_gui() {
    match spawn_detached_binary("silentinput-gui") {
        Ok(()) => println!("🖥️ 已打开 SilentInput 控制中心。"),
        Err(e) => eprintln!("❌ 打开控制中心失败: {e}"),
    }
}

fn find_helper_script(name: &str) -> Option<PathBuf> {
    if let Ok(exe) = env::current_exe() {
        if let Some(dir) = exe.parent() {
            let candidate = dir.join(name);
            if candidate.exists() {
                return Some(candidate);
            }
        }
    }
    if let Some(home) = env::var_os("HOME") {
        let local_share = PathBuf::from(home)
            .join(".local")
            .join("share")
            .join("silentinput")
            .join(name);
        if local_share.exists() {
            return Some(local_share);
        }
    }
    None
}

fn cmd_install(args: Vec<String>) {
    if let Some(script) = find_helper_script("install.sh") {
        let _ = Command::new("bash").arg(script).args(args).status();
    } else {
        println!("ℹ️ 当前通过系统安装包或独立二进制部署，无需手动执行 install.sh。");
    }
}

fn cmd_uninstall(args: Vec<String>) {
    let pkg_cmd = get_package_uninstall_command();
    if is_system_package_install() || pkg_cmd.is_some() {
        let hint = pkg_cmd.unwrap_or("sudo apt remove silentinput  # 或 sudo dnf remove silentinput");
        println!("ℹ️ 当前 SilentInput 通过系统包管理器安装，请执行以下命令卸载：");
        println!("   {hint}");
        return;
    }
    if let Some(script) = find_helper_script("uninstall.sh") {
        let _ = Command::new("bash").arg(script).args(args).status();
    } else {
        let (_, msg) = service_stop();
        println!("🛑 {msg}");
        eprintln!("ℹ️ 未找到 uninstall.sh，已停止服务；如需移除二进制请删除 ~/.local/bin/silentinput*。");
    }
}

fn cmd_bluetooth() {
    println!("=========================================");
    println!("         SilentInput 蓝牙状态            ");
    println!("=========================================");
    match Command::new("bluetoothctl").arg("show").output() {
        Ok(out) => {
            let text = String::from_utf8_lossy(&out.stdout);
            let mut name = "未知".to_string();
            let mut addr = "未知".to_string();
            let mut powered = "未知".to_string();
            for line in text.lines() {
                let line = line.trim();
                if let Some(rest) = line.strip_prefix("Name:") {
                    name = rest.trim().to_string();
                } else if line.starts_with("Controller") {
                    let parts: Vec<&str> = line.split_whitespace().collect();
                    if parts.len() >= 2 {
                        addr = parts[1].to_string();
                    }
                } else if let Some(rest) = line.strip_prefix("Powered:") {
                    powered = if rest.to_ascii_lowercase().contains("yes") {
                        "🟢 已开启".to_string()
                    } else {
                        "🔴 已关闭".to_string()
                    };
                }
            }
            println!(" 蓝牙控制器:  {powered}");
            println!(" 电脑蓝牙名:  {name}");
            println!(" 蓝牙 MAC:    {addr}");
            println!(" 伴侣信道:    RFCOMM Channel 1 (SPP 标准串口)");
            println!("\n💡 配对指引:");
            println!("  1. 在手机系统设置 -> 蓝牙中，搜索并连接配对这台电脑。");
            println!("  2. 打开 SilentInput 手机 App，在设置面板切换至「蓝牙」一键连接即可！");
        }
        Err(e) => {
            println!(" 查询蓝牙状态失败: {e}");
        }
    }
    println!("=========================================");
}

fn cmd_reset() {
    let default_cfg = SilentInputConfig {
        auth_token: generate_token_hex(4),
        token_created_at: unix_timestamp_secs(),
        ..SilentInputConfig::default()
    };
    if let Err(e) = save_config(&default_cfg) {
        eprintln!("❌ 恢复出厂默认配置失败: {e}");
        return;
    }
    println!("=========================================");
    println!("       已恢复出厂默认配置 (最小权限模式)   ");
    println!("=========================================");
    println!(" 访问控制模式: 🔒 本地模式 (localhost_only, 权限最小)");
    println!(" 密钥有效期:   ⏱️  1小时 (1h, 最短有效，自动轮换)");
    println!(" 默认服务端口: 53318");
    println!(" 传输加密:     TLS 开启 (WSS)");
    println!(" USB 免密:     已开启 (仅限本机 127.0.0.1)");
    println!(" 白名单网段:   127.0.0.1, 10.*, 192.168.*, 100.*, 172.16.0.0/12");
    println!(" 新配对密钥:   {}", default_cfg.auth_token);
    println!("=========================================");
    println!("正在重启后台服务使新配置生效...");
    let (ok, msg) = service_restart();
    if ok {
        println!("✅ {msg}");
    } else {
        println!("⚠️ 请手动运行 silentinput restart 重启服务");
    }
}

fn main() {
    let cli = Cli::parse();
    match cli.command {
        None => {
            cmd_status();
            print_help();
        }
        Some(Commands::Start) => cmd_start(),
        Some(Commands::Stop) => cmd_stop(),
        Some(Commands::Restart) => cmd_restart(),
        Some(Commands::Status) => cmd_status(),
        Some(Commands::Bluetooth) => cmd_bluetooth(),
        Some(Commands::Token { reset, expire }) => cmd_token(reset, expire),
        Some(Commands::Tls { state }) => cmd_tls(state),
        Some(Commands::Autostart { state }) => cmd_autostart(state),
        Some(Commands::Logs) => cmd_logs(),
        Some(Commands::Gui) => cmd_gui(),
        Some(Commands::Install { args }) => cmd_install(args),
        Some(Commands::Uninstall { args }) => cmd_uninstall(args),
        Some(Commands::Version) => println!("silentinput {}", env!("CARGO_PKG_VERSION")),
        Some(Commands::Reset) => cmd_reset(),
        Some(Commands::Help) => print_help(),
    }
}
