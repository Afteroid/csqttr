//! Passive counters only: no probes, packet modifications or delivery decisions.
//! Socket acceptance is NOT peer delivery. No network-loss percentage is inferred.
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

pub const PREFIX: &str = "__CSQTT_TRAFFIC__|";
pub const PERIOD: Duration = Duration::from_secs(5);
static ENABLED: AtomicBool = AtomicBool::new(false);

#[derive(Clone, Copy)]
#[repr(usize)]
pub enum Metric {
    DispatchBytes,
    NoWorkerDrops,
    WrapDrops,
    FragmentPoolDrops,
    UdpSocketBytes,
    UdpSocketPackets,
    UdpSendDrops,
    UdpClosingDrops,
    UdpWouldBlock,
    UdpWaitUs,
    UdpWaitMaxUs,
    TurnEnqueueErrors,
    TurnQueueFull,
    TurnQueueWaitUs,
    TurnQueueWaitMaxUs,
    TurnClosingDrops,
    RelayBytes,
    RelayPackets,
    RelayQueueDrops,
    RelayPoolDrops,
    AuthDrops,
    ReplayDrops,
    NonRtpDrops,
    NonIpDrops,
    DownlinkQueueDrops,
    TunWriteDrops,
    TunWouldBlock,
    TunTransientErrors,
}
const N: usize = Metric::TunTransientErrors as usize + 1;
const NAMES: [&str; N] = [
    "dispatch_bytes",
    "no_worker_drops",
    "wrap_drops",
    "fragment_pool_drops",
    "udp_socket_bytes",
    "udp_socket_packets",
    "udp_send_drops",
    "udp_closing_drops",
    "udp_would_block",
    "udp_wait_us",
    "udp_wait_max_us",
    "turn_enqueue_errors",
    "turn_queue_full",
    "turn_queue_wait_us",
    "turn_queue_wait_max_us",
    "turn_closing_drops",
    "relay_bytes",
    "relay_packets",
    "relay_queue_drops",
    "relay_pool_drops",
    "auth_drops",
    "replay_drops",
    "non_rtp_drops",
    "non_ip_drops",
    "downlink_queue_drops",
    "tun_write_drops",
    "tun_would_block",
    "tun_transient_errors",
];
static COUNTERS: [AtomicU64; N] = [const { AtomicU64::new(0) }; N];

pub fn init_from_env() {
    ENABLED.store(
        std::env::var("CSQTT_TRAFFIC_DIAGNOSTICS").as_deref() == Ok("1"),
        Ordering::Relaxed,
    );
}

#[inline]
pub fn add(metric: Metric, value: u64) {
    if ENABLED.load(Ordering::Relaxed) {
        COUNTERS[metric as usize].fetch_add(value, Ordering::Relaxed);
    }
}

/// Only constructed on a slow path; no per-packet clock reads on the fast path.
pub struct WaitTimer(Option<Instant>, Metric, Metric);
impl WaitTimer {
    pub fn new(sum: Metric, max: Metric) -> Self {
        Self(ENABLED.load(Ordering::Relaxed).then(Instant::now), sum, max)
    }
}
impl Drop for WaitTimer {
    fn drop(&mut self) {
        if let Some(started) = self.0 {
            let us = started.elapsed().as_micros().min(u64::MAX as u128) as u64;
            COUNTERS[self.1 as usize].fetch_add(us, Ordering::Relaxed);
            COUNTERS[self.2 as usize].fetch_max(us, Ordering::Relaxed);
        }
    }
}

#[derive(Default)]
pub struct Reporter {
    previous: [u64; N],
    previous_up: i64,
    previous_down: i64,
    sequence: u64,
}
impl Reporter {
    pub fn emit(&mut self, elapsed: Duration, up: i64, down: i64, active: i32) {
        if !ENABLED.load(Ordering::Relaxed) {
            return;
        }
        let current = std::array::from_fn(|i| COUNTERS[i].load(Ordering::Relaxed));
        if current == self.previous && up == self.previous_up && down == self.previous_down {
            return;
        }
        let payload = self.sample(current, elapsed, up, down, active);
        crate::log_error!("{}{}", PREFIX, payload);
    }

    fn sample(
        &mut self,
        current: [u64; N],
        elapsed: Duration,
        up: i64,
        down: i64,
        active: i32,
    ) -> serde_json::Value {
        self.sequence += 1;
        let mut counters = serde_json::Map::new();
        for (i, name) in NAMES.iter().enumerate() {
            // Maxima are lifetime high water marks; all other fields are window deltas.
            let value = if name.ends_with("_max_us") {
                current[i]
            } else {
                current[i].saturating_sub(self.previous[i])
            };
            counters.insert((*name).into(), value.into());
        }
        let payload = serde_json::json!({
            "sequence": self.sequence,
            "time_ms": SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64,
            "elapsed_ms": elapsed.as_millis().max(1) as u64,
            "active": active,
            "app_up_bytes": up.saturating_sub(self.previous_up).max(0),
            "app_down_bytes": down.saturating_sub(self.previous_down).max(0),
            "counters": counters,
        });
        self.previous = current;
        self.previous_up = up;
        self.previous_down = down;
        payload
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn windows_use_deltas_real_elapsed_and_do_not_call_backpressure_loss() {
        let mut r = Reporter::default();
        let mut c = [0; N];
        c[Metric::UdpSocketBytes as usize] = 1000;
        c[Metric::TurnQueueFull as usize] = 4;
        c[Metric::UdpWaitMaxUs as usize] = 300;
        let a = r.sample(c, Duration::from_millis(5200), 900, 600, 70);
        assert_eq!(a["elapsed_ms"], 5200);
        assert_eq!(a["counters"]["udp_send_drops"], 0);
        assert_eq!(a["counters"]["turn_queue_full"], 4);
        c[Metric::UdpSocketBytes as usize] += 200;
        let b = r.sample(c, Duration::from_millis(5010), 1050, 650, 70);
        assert_eq!(b["counters"]["udp_socket_bytes"], 200);
        assert_eq!(b["counters"]["turn_queue_full"], 0);
        assert_eq!(b["counters"]["udp_wait_max_us"], 300);
        assert_eq!(b["app_up_bytes"], 150);
        assert_eq!(b["app_down_bytes"], 50);
        assert_eq!(b["sequence"], 2);
        assert!(b.get("network_loss_percent").is_none());
    }
}
