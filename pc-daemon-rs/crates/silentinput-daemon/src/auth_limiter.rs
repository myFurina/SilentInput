use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

pub const AUTH_MAX_FAILURES: u32 = 5;
pub const AUTH_LOCKOUT_SECS: u64 = 60;
pub const AUTH_FAILURE_TTL_SECS: u64 = 600;
pub const AUTH_FAILURE_MAX_ENTRIES: usize = 1024;

#[derive(Debug, Clone, Copy)]
struct FailureEntry {
    count: u32,
    locked_until: Option<Instant>,
    last_updated: Instant,
}

#[derive(Debug, Default)]
pub struct AuthRateLimiter {
    entries: Mutex<HashMap<String, FailureEntry>>,
}

impl AuthRateLimiter {
    pub fn new() -> Self {
        Self::default()
    }

    fn prune_locked(map: &mut HashMap<String, FailureEntry>, now: Instant) {
        let ttl = Duration::from_secs(AUTH_FAILURE_TTL_SECS);
        map.retain(|_, entry| match entry.locked_until {
            Some(until) => now < until,
            None => now.saturating_duration_since(entry.last_updated) < ttl,
        });
    }

    pub fn is_locked(&self, peer_ip: &str) -> bool {
        self.is_locked_at(peer_ip, Instant::now())
    }

    pub fn is_locked_at(&self, peer_ip: &str, now: Instant) -> bool {
        if peer_ip.is_empty() {
            return false;
        }
        let mut map = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        Self::prune_locked(&mut map, now);
        map.get(peer_ip)
            .and_then(|e| e.locked_until)
            .is_some_and(|until| now < until)
    }

    /// Record a failed auth attempt; returns `true` if peer just crossed lockout threshold.
    pub fn record_failure(&self, peer_ip: &str) -> bool {
        self.record_failure_at(peer_ip, Instant::now())
    }

    pub fn record_failure_at(&self, peer_ip: &str, now: Instant) -> bool {
        if peer_ip.is_empty() {
            return false;
        }
        let mut map = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        Self::prune_locked(&mut map, now);

        if !map.contains_key(peer_ip) {
            while map.len() >= AUTH_FAILURE_MAX_ENTRIES {
                if let Some(oldest_ip) = map
                    .iter()
                    .min_by_key(|(_, e)| e.last_updated)
                    .map(|(k, _)| k.clone())
                {
                    map.remove(&oldest_ip);
                } else {
                    break;
                }
            }
        }

        let prev_count = map.get(peer_ip).map(|e| e.count).unwrap_or(0);
        let new_count = prev_count + 1;
        if new_count >= AUTH_MAX_FAILURES {
            map.insert(
                peer_ip.to_string(),
                FailureEntry {
                    count: new_count,
                    locked_until: Some(now + Duration::from_secs(AUTH_LOCKOUT_SECS)),
                    last_updated: now,
                },
            );
            true
        } else {
            map.insert(
                peer_ip.to_string(),
                FailureEntry {
                    count: new_count,
                    locked_until: None,
                    last_updated: now,
                },
            );
            false
        }
    }

    pub fn clear(&self, peer_ip: &str) {
        if !peer_ip.is_empty() {
            let mut map = self.entries.lock().unwrap_or_else(|e| e.into_inner());
            map.remove(peer_ip);
        }
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.entries.lock().unwrap_or_else(|e| e.into_inner()).len()
    }
}
