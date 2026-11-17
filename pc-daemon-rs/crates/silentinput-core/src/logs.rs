use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::UNIX_EPOCH;

use crate::paths::{SilentInputPaths, SYSTEMD_SERVICE_NAME};
use crate::process::has_active_systemd_at;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LogSource {
    Journald { unit: String },
    File(PathBuf),
}

/// Parse the leading Unix timestamp from `journalctl -o short-unix` output.
pub fn parse_short_unix_timestamp_secs(output: &str) -> Option<f64> {
    let last_line = output.lines().rev().find(|l| !l.trim().is_empty())?;
    let token = last_line.split_whitespace().next()?;
    let ts: f64 = token.parse().ok()?;
    (ts.is_finite() && ts > 0.0).then_some(ts)
}

/// Decide whether the local `silentinput.log` file should be preferred over journald.
pub fn should_prefer_log_file(
    log_file_mtime_secs: Option<f64>,
    journal_latest_secs: Option<f64>,
) -> bool {
    match (log_file_mtime_secs, journal_latest_secs) {
        (Some(file_ts), Some(journal_ts)) => file_ts > journal_ts,
        (Some(_), None) => true,
        (None, _) => false,
    }
}

fn file_mtime_secs(path: &Path) -> Option<f64> {
    let modified = std::fs::metadata(path).ok()?.modified().ok()?;
    modified
        .duration_since(UNIX_EPOCH)
        .ok()
        .map(|d| d.as_secs_f64())
}

fn query_journal_latest_timestamp_secs(unit: &str) -> Option<Option<f64>> {
    let out = Command::new("journalctl")
        .args(["--user", "-u", unit, "-n", "1", "-q", "-o", "short-unix", "--no-pager"])
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&out.stdout);
    Some(parse_short_unix_timestamp_secs(&text))
}

/// Choose between systemd journald (`silentinput.service`) and local `silentinput.log`.
pub fn select_log_source_at(paths: &SilentInputPaths) -> LogSource {
    let log_file = paths.log_file();
    if !has_active_systemd_at(paths) {
        return LogSource::File(log_file);
    }

    let Some(journal_ts) = query_journal_latest_timestamp_secs(SYSTEMD_SERVICE_NAME) else {
        return LogSource::File(log_file);
    };

    if should_prefer_log_file(file_mtime_secs(&log_file), journal_ts) {
        LogSource::File(log_file)
    } else {
        LogSource::Journald {
            unit: SYSTEMD_SERVICE_NAME.to_string(),
        }
    }
}

/// Select log source for the current user's environment.
pub fn select_log_source() -> LogSource {
    select_log_source_at(&SilentInputPaths::system())
}

/// Read up to the trailing 32 KiB of `log_path` and return the last `max_lines` lines.
pub fn read_log_tail_from_file(log_path: &Path, is_zh: bool, max_lines: usize) -> String {
    if !log_path.exists() {
        return if is_zh {
            "暂无日志".to_string()
        } else {
            "No logs yet".to_string()
        };
    }

    let Ok(mut file) = File::open(log_path) else {
        return if is_zh {
            format!("读取日志失败: {}", log_path.display())
        } else {
            format!("Failed to open log file: {}", log_path.display())
        };
    };

    const TAIL_BYTES: u64 = 32 * 1024;
    if let Ok(meta) = file.metadata() {
        let size = meta.len();
        if size > TAIL_BYTES {
            let _ = file.seek(SeekFrom::Start(size - TAIL_BYTES));
        }
    }

    let mut raw = Vec::new();
    if let Err(err) = file.read_to_end(&mut raw) {
        return if is_zh {
            format!("读取日志失败: {err}")
        } else {
            format!("Failed to read logs: {err}")
        };
    }

    let text = String::from_utf8_lossy(&raw);
    let lines: Vec<&str> = text.lines().collect();
    if lines.is_empty() {
        return if is_zh {
            "日志为空".to_string()
        } else {
            "Log is empty".to_string()
        };
    }
    let start = lines.len().saturating_sub(max_lines);
    lines[start..].join("\n")
}

/// Read the latest `max_lines` log lines from journald or `silentinput.log` for `paths`.
pub fn read_log_tail_at(paths: &SilentInputPaths, is_zh: bool, max_lines: usize) -> String {
    match select_log_source_at(paths) {
        LogSource::Journald { unit } => {
            if let Ok(out) = Command::new("journalctl")
                .args([
                    "--user",
                    "-u",
                    &unit,
                    "-n",
                    &max_lines.to_string(),
                    "-q",
                    "--no-pager",
                ])
                .stdin(Stdio::null())
                .output()
            {
                if out.status.success() {
                    let stdout = String::from_utf8_lossy(&out.stdout);
                    let trimmed = stdout.trim_end();
                    if !trimmed.is_empty() {
                        return trimmed.to_string();
                    }
                    return if is_zh {
                        "暂无日志".to_string()
                    } else {
                        "No logs yet".to_string()
                    };
                }
            }
            read_log_tail_from_file(&paths.log_file(), is_zh, max_lines)
        }
        LogSource::File(log_path) => read_log_tail_from_file(&log_path, is_zh, max_lines),
    }
}
