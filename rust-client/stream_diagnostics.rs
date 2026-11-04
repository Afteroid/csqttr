//! Local, bounded diagnostics. Never changes packets, deadlines, or retry decisions.
use std::{sync::atomic::{AtomicU64, Ordering}, time::{Instant, SystemTime, UNIX_EPOCH}};

pub const PREFIX: &str = "__CSQTT_STREAM__|";
static NEXT_ATTEMPT: AtomicU64 = AtomicU64::new(1);
static NEXT_EVENT: AtomicU64 = AtomicU64::new(1);

pub struct StreamDiagnostics {
    enabled: bool,
    worker: usize,
    attempt: u64,
    turn: String,
    transport: String,
    stage: &'static str,
    stage_started: Instant,
}

impl StreamDiagnostics {
    pub fn new(enabled: bool, worker: usize, turn: String) -> Self {
        Self { enabled, worker, attempt: NEXT_ATTEMPT.fetch_add(1, Ordering::Relaxed),
            turn, transport: "unknown".into(), stage: "select_turn", stage_started: Instant::now() }
    }

    pub fn endpoint(&mut self, turn: String, transport: &str) {
        self.turn = turn;
        self.transport = transport.to_owned();
    }

    pub fn begin(&mut self, stage: &'static str) {
        self.stage = stage;
        self.stage_started = Instant::now();
        self.record("start", "");
    }

    fn payload(&self, status: &str, reason: &str) -> serde_json::Value {
        serde_json::json!({
            "worker": self.worker, "attempt": self.attempt,
            "sequence": NEXT_EVENT.fetch_add(1, Ordering::Relaxed),
            "time_ms": SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64,
            "elapsed_ms": self.stage_started.elapsed().as_millis() as u64,
            "turn": self.turn.chars().take(256).collect::<String>(),
            "transport": self.transport, "stage": self.stage, "status": status,
            "reason": reason.chars().take(512).collect::<String>(),
        })
    }

    pub fn record(&self, status: &str, reason: &str) {
        if self.enabled {
            // Diagnostic queue, not the control-event queue used by READY/CONFIG.
            crate::log_error!("{}{}", PREFIX, self.payload(status, reason));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reconnects_and_workers_have_distinct_attempts_and_events() {
        let a = StreamDiagnostics::new(false, 7, "91.231.135.130:19302".into());
        let b = StreamDiagnostics::new(false, 7, "95.163.34.156:19302".into());
        let c = StreamDiagnostics::new(false, 8, "91.231.135.130:19302".into());
        let a = a.payload("error", "timeout");
        let b = b.payload("error", "timeout");
        let c = c.payload("error", "timeout");
        assert_ne!(a["attempt"], b["attempt"]);
        assert_ne!(a["sequence"], c["sequence"]);
        assert_ne!(a["turn"], b["turn"]);
        assert!(a["time_ms"].as_u64().unwrap() > 0);
    }
    #[test]
    fn failure_retains_stage_address_and_bounded_reason() {
        let mut d = StreamDiagnostics::new(false, 70, "turn:example.invalid:19302".into());
        d.endpoint("192.0.2.1:19302".into(), "UDP");
        d.begin("dtls");
        let p = d.payload("timeout", &"я".repeat(2000));
        assert_eq!(p["stage"], "dtls");
        assert_eq!(p["worker"], 70);
        assert_eq!(p["turn"], "192.0.2.1:19302");
        assert_eq!(p["reason"].as_str().unwrap().chars().count(), 512);
        assert!(p.get("password").is_none());
    }
}
