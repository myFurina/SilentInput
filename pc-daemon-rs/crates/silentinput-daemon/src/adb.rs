use std::process::Stdio;
use std::time::Duration;

use tokio::process::Command;

pub async fn maintain_adb_reverse(port: u16) {
    loop {
        check_and_reverse(port).await;
        tokio::time::sleep(Duration::from_secs(3)).await;
    }
}

async fn check_and_reverse(port: u16) {
    let devices_out = tokio::time::timeout(
        Duration::from_secs(2),
        Command::new("adb")
            .arg("devices")
            .stdin(Stdio::null())
            .stderr(Stdio::null())
            .output(),
    )
    .await;

    let Ok(Ok(out)) = devices_out else {
        return;
    };
    if !out.status.success() {
        return;
    }

    let stdout = String::from_utf8_lossy(&out.stdout);
    let has_device = stdout.lines().any(|line| {
        let trimmed = line.trim();
        !trimmed.is_empty() && !trimmed.starts_with("List of") && trimmed.contains("\tdevice")
    });
    if !has_device {
        return;
    }

    let rev_out = tokio::time::timeout(
        Duration::from_secs(2),
        Command::new("adb")
            .args(["reverse", "--list"])
            .stdin(Stdio::null())
            .stderr(Stdio::null())
            .output(),
    )
    .await;

    let target_spec = format!("tcp:{port}");
    if let Ok(Ok(rev)) = rev_out {
        if String::from_utf8_lossy(&rev.stdout).contains(&target_spec) {
            return;
        }
    }

    let _ = tokio::time::timeout(
        Duration::from_secs(2),
        Command::new("adb")
            .args(["reverse", &target_spec, &target_spec])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status(),
    )
    .await;
    println!("[SilentInput] Auto-configured adb reverse tcp:{port} for USB device.");
}
