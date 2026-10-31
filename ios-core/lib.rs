// ios-core/lib.rs
//
// iOS static library crate root, synced with `rust-client` **2.5.1**.
//
// Модули подключаются напрямую из rust-client/ и shared/ через #[path].
// Пути считаются ОТ ЭТОГО ФАЙЛА (ios-core/lib.rs):
//   ../rust-client/*.rs  ->  csqttr-core/rust-client/*.rs
//   ../shared/*.rs       ->  csqttr-core/shared/*.rs
//
// ВАЖНО: dtls, spsc_ring и wire_protocol должны быть на crate-корне,
// потому что dispatcher.rs/session.rs/turn.rs/obfs.rs ссылаются на них
// как crate::wire_protocol, crate::spsc_ring, crate::dtls.
#![allow(dead_code, unused_imports)]

// ----- shared/ (crate-level modules) -----
#[path = "../shared/dtls.rs"]
pub mod dtls;
#[path = "../shared/spsc_ring.rs"]
pub mod spsc_ring;
#[path = "../shared/wire_protocol.rs"]
pub mod wire_protocol;

// ----- rust-client/ (crate-level modules) -----
#[path = "../rust-client/auth.rs"]
mod auth;
#[path = "../rust-client/captcha.rs"]
mod captcha;
#[path = "../rust-client/captcha_slider.rs"]
mod captcha_slider;
#[path = "../rust-client/cpu_task.rs"]
mod cpu_task;
#[path = "../rust-client/direct_relay.rs"]
pub mod direct_relay;
#[path = "../rust-client/dispatcher.rs"]
mod dispatcher;
#[path = "../rust-client/dns.rs"]
mod dns;
#[path = "../rust-client/events.rs"]
mod events;
#[path = "../rust-client/logging.rs"]
mod logging;
#[path = "../rust-client/namegen.rs"]
mod namegen;
#[path = "../rust-client/obfs.rs"]
mod obfs;
#[path = "../rust-client/packet.rs"]
mod packet;
#[path = "../rust-client/profiles.rs"]
mod profiles;
#[path = "../rust-client/protocol.rs"]
mod protocol;
#[path = "../rust-client/repair.rs"]
mod repair;
#[path = "../rust-client/session.rs"]
mod session;
#[path = "../rust-client/sni.rs"]
mod sni;
#[path = "../rust-client/srtp_mode.rs"]
mod srtp_mode;
#[path = "../rust-client/stats.rs"]
mod stats;
#[path = "../rust-client/stun_codec.rs"]
mod stun_codec;
#[path = "../rust-client/tun.rs"]
mod tun;
#[path = "../rust-client/turn.rs"]
mod turn;
#[path = "../rust-client/turn_core.rs"]
mod turn_core;
#[path = "../rust-client/turn_endpoint.rs"]
mod turn_endpoint;
#[path = "../rust-client/turn_ip_pool.rs"]
mod turn_ip_pool;
#[path = "../rust-client/turn_stream.rs"]
mod turn_stream;
#[path = "../rust-client/vk_js_calls.rs"]
mod vk_js_calls;
#[path = "../rust-client/worker.rs"]
mod worker;
#[path = "../rust-client/wrap.rs"]
mod wrap;

use std::collections::VecDeque;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
use std::sync::{Arc, LazyLock, Mutex};

// ===== Логирование через C-ABI =====

static LOG_BUFFER: LazyLock<Mutex<VecDeque<String>>> =
    LazyLock::new(|| Mutex::new(VecDeque::with_capacity(1024)));

static LOG_CALLBACK: Mutex<Option<unsafe extern "C" fn(*const c_char)>> = Mutex::new(None);

#[unsafe(no_mangle)]
pub extern "C" fn csqttr_set_log_callback(callback: unsafe extern "C" fn(*const c_char)) {
    let mut guard = LOG_CALLBACK.lock().unwrap();
    *guard = Some(callback);
}

#[unsafe(no_mangle)]
pub extern "C" fn csqttr_get_logs() -> *const c_char {
    let guard = LOG_BUFFER.lock().unwrap();
    let logs: String = guard.iter().map(|s| format!("{}\n", s)).collect();
    CString::new(logs).unwrap().into_raw()
}

#[unsafe(no_mangle)]
pub extern "C" fn csqttr_free_logs(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr));
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn csqttr_clear_logs() {
    let mut guard = LOG_BUFFER.lock().unwrap();
    guard.clear();
}

pub fn push_log(line: String) {
    if let Ok(callback_guard) = LOG_CALLBACK.lock() {
        if let Some(cb) = *callback_guard {
            if let Ok(cstr) = CString::new(line.clone()) {
                unsafe {
                    cb(cstr.as_ptr());
                }
            }
        }
    }
    let mut guard = LOG_BUFFER.lock().unwrap();
    if guard.len() >= 1024 {
        guard.pop_front();
    }
    guard.push_back(line);
}

// ===== Аргументы =====

#[derive(Debug, Clone)]
pub struct CsqttConfig {
    pub peer: String,
    pub vk: String,
    pub password: String,
    pub listen: String,
    pub workers: i32,
    pub vk_hash_mode: String,
    pub vk_auth_mode: String,
    pub captcha_mode: String,
    pub fingerprint: String,
    pub client_ids: String,
    pub obfs: String,
    pub turn_transport: String,
    pub srtp_mode: String,
    pub generation: u64,
    pub salt: String,
    pub turn: String,
    pub custom_turn_ips: String,
    pub port: String,
    pub validate_vk_hashes: bool,
    pub tun_uds: String,
    pub domain_routing: bool,
    pub exclude_ru_zones: bool,
    pub template_domains: bool,
    pub domain_whitelist: bool,
    pub direct_domains: String,
    pub ip_routing: bool,
    pub ip_whitelist: bool,
    pub direct_ips: String,
    pub exact_workers: bool,
}

impl CsqttConfig {
    /// Строит вектор CLI-аргументов, идентичный тому, что в `rust-client/main.rs`
    /// 2.5.1 (без флагов, которые в iOS-ядре не имеют смысла — `--domains-file`,
    /// `--runtime-threads`, `--device-id`, `--allow-hash-redistribution`).
    fn to_args_vec(&self) -> Vec<String> {
        let mut args = Vec::new();
        args.push(format!("--peer={}", self.peer));
        args.push(format!("--vk={}", self.vk));
        if !self.password.is_empty() {
            args.push(format!("--password={}", self.password));
        }
        args.push(format!("--listen={}", self.listen));
        args.push(format!("--workers={}", self.workers));
        if !self.vk_hash_mode.is_empty() && self.vk_hash_mode != "manual" {
            args.push(format!("--vk-hash-mode={}", self.vk_hash_mode));
        }
        if !self.vk_auth_mode.is_empty() && self.vk_auth_mode != "vkcalls" {
            args.push(format!("--vk-auth-mode={}", self.vk_auth_mode));
        }
        if !self.captcha_mode.is_empty() && self.captcha_mode != "auto" {
            args.push(format!("--captcha-mode={}", self.captcha_mode));
        }
        if !self.fingerprint.is_empty() && self.fingerprint != "chrome" {
            args.push(format!("--fingerprint={}", self.fingerprint));
        }
        if !self.client_ids.is_empty() {
            args.push(format!("--client-ids={}", self.client_ids));
        }
        if !self.obfs.is_empty() && self.obfs != "unified" {
            args.push(format!("--obfs={}", self.obfs));
        }
        if !self.turn_transport.is_empty() && self.turn_transport != "udp" {
            args.push(format!("--turn-transport={}", self.turn_transport));
        }
        if !self.srtp_mode.is_empty() && self.srtp_mode != "auto" {
            args.push(format!("--srtp-mode={}", self.srtp_mode));
        }
        if self.generation != 0 {
            args.push(format!("--gen={}", self.generation));
        }
        if !self.salt.is_empty() {
            args.push(format!("--salt={}", self.salt));
        }
        if !self.turn.is_empty() {
            args.push(format!("--turn={}", self.turn));
        }
        if !self.custom_turn_ips.is_empty() {
            args.push(format!("--custom-turn-ips={}", self.custom_turn_ips));
        }
        if !self.port.is_empty() {
            args.push(format!("--port={}", self.port));
        }
        if self.validate_vk_hashes {
            args.push("--validate-vk-hashes".to_string());
        }
        if !self.tun_uds.is_empty() {
            args.push(format!("--tun-uds={}", self.tun_uds));
        }
        if self.domain_routing {
            args.push("--domain-routing".to_string());
        }
        if self.exclude_ru_zones {
            args.push("--exclude-ru-zones".to_string());
        }
        if self.template_domains {
            args.push("--template-domains".to_string());
        }
        if self.domain_whitelist {
            args.push("--domain-whitelist".to_string());
        }
        if !self.direct_domains.is_empty() {
            args.push(format!("--direct-domains={}", self.direct_domains));
        }
        if self.ip_routing {
            args.push("--ip-routing".to_string());
        }
        if self.ip_whitelist {
            args.push("--ip-whitelist".to_string());
        }
        if !self.direct_ips.is_empty() {
            args.push(format!("--direct-ips={}", self.direct_ips));
        }
        if self.exact_workers {
            args.push("--exact-workers".to_string());
        }
        args
    }
}

// ===== Основная логика (зеркало main.rs 2.5.1) =====

const MAX_WORKERS: usize = 100;
/// В iOS-ядре нет `--runtime-threads`, поэтому дефолт совпадает с
/// `runtime_worker_threads_with_limit(workers, 0, available)` из main.rs 2.5.1,
/// но `available` фиксируем консервативно (iOS обычно даёт 4–6 perf-ядер).
const IOS_DEFAULT_RUNTIME_THREADS: usize = 4;
const STREAMS_PER_RUNTIME_WORKER: usize = 8;

fn normalize_worker_count(requested: usize) -> usize {
    if requested <= 5 {
        return 5;
    }
    requested.clamp(worker::WORKERS_PER_GROUP, MAX_WORKERS) / worker::WORKERS_PER_GROUP
        * worker::WORKERS_PER_GROUP
}

fn runtime_worker_threads(requested_workers: usize) -> usize {
    normalize_worker_count(requested_workers)
        .div_ceil(STREAMS_PER_RUNTIME_WORKER)
        .clamp(1, IOS_DEFAULT_RUNTIME_THREADS)
}

/// 2.5.1: Auto-JS требует stdin-канал для `VK_JS_BOOTSTRAP` и `CredentialBroker`,
/// которых в static lib нет. Явно отклоняем, чтобы не запускать сломанный путь.
fn reject_auto_js(vk_hash_mode: &str, vk_auth_mode: &str) -> Result<(), anyhow::Error> {
    let js_hash_mode = vk_hash_mode == "auto_js";
    let js_auth_mode = vk_auth_mode == "auto_js";
    if js_hash_mode || js_auth_mode {
        anyhow::bail!(
            "[iOS] Режим Auto JS (vk_hash_mode=auto_js или vk_auth_mode=auto_js) \
             не поддерживается в статической библиотеке — используйте manual/vkcalls/legacy"
        );
    }
    Ok(())
}

async fn run_client(config: CsqttConfig) -> Result<(), anyhow::Error> {
    use auth::VkAuth;
    use captcha::CaptchaSolver;
    use dispatcher::Dispatcher;
    use events::Events;
    use obfs::ObfsMode;
    use packet::{PacketPool, packet_pool_size};
    use repair::RepairState;
    use session::ShutdownCoordinator;
    use srtp_mode::SrtpMode;
    use stats::Stats;
    use tokio_util::sync::CancellationToken;
    use turn_endpoint::TurnTransportMode;
    use turn_ip_pool::{TurnIpPool, parse_custom_turn_ips};
    use worker::{
        GroupContext, PauseGate, RuntimeParams, WORKER_START_INTERVAL, WORKERS_PER_GROUP,
        WorkerStartPacer, parse_hashes, run_groups,
    };
    use wrap::derive_wrap_key;

    reject_auto_js(&config.vk_hash_mode, &config.vk_auth_mode)?;

    if config.peer.is_empty() || config.vk.is_empty() {
        anyhow::bail!("[iOS] Нужны peer и хеши VK");
    }
    if config.password.is_empty() {
        anyhow::bail!("[iOS] Нужен пароль");
    }

    let peer = crate::dns::resolve_socket(&config.peer).await?;
    let mode = ObfsMode::parse(&config.obfs)?;
    let turn_transport = TurnTransportMode::parse(&config.turn_transport)?;
    let srtp_mode = SrtpMode::parse(&config.srtp_mode)?;
    let wrap_key = derive_wrap_key(&config.password)?;
    let client_wrap_key = wrap_key;
    let session_profile = profiles::random_profile(&config.fingerprint);

    let hashes: Vec<_> = parse_hashes(&config.vk).into_iter().take(6).collect();

    if hashes.is_empty() {
        anyhow::bail!("[КЛИЕНТ] Нет хешей VK");
    }

    let workers = if config.exact_workers {
        (config.workers as usize).clamp(3, MAX_WORKERS)
    } else {
        normalize_worker_count(config.workers as usize)
    };
    let groups = workers.div_ceil(WORKERS_PER_GROUP);
    let cancel = CancellationToken::new();
    let captcha = CaptchaSolver::new(&config.captcha_mode, cancel.clone());
    // В iOS нет env-переменных; события отключены по умолчанию.
    let events = Events::new(false);

    let client_ids: Vec<_> = config
        .client_ids
        .split(',')
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .map(str::to_owned)
        .collect();

    let auth = std::sync::Arc::new(VkAuth::new(
        &config.vk_auth_mode,
        session_profile,
        &client_ids,
        captcha.clone(),
        None,
    ));

    let stats = std::sync::Arc::new(Stats::default());
    let paused = std::sync::Arc::new(PauseGate::new());
    let pool = PacketPool::new(packet_pool_size(MAX_WORKERS));

    let tun_uds = (!config.tun_uds.is_empty()).then_some(config.tun_uds.clone());

    // `template_domains` использует `include_str!("../domains.txt")` из sni.rs,
    // поэтому domains.txt собирается в бинарник — файлового I/O не требуется.
    let mut direct_domains = crate::sni::parse_direct_domains(&config.direct_domains);
    direct_domains.truncate(1024);
    let ip_rules = crate::sni::parse_ip_rules(&config.direct_ips);
    let domain_routing = dispatcher::DomainRoutingConfig {
        enabled: config.domain_routing,
        exclude_ru_zones: config.exclude_ru_zones,
        template_domains: config.template_domains,
        direct_domains: std::sync::Arc::new(direct_domains),
        is_whitelist: config.domain_whitelist,
        ip_routing_enabled: config.ip_routing,
        ip_is_whitelist: config.ip_whitelist,
        ip_rules: std::sync::Arc::new(ip_rules),
    };

    let (dispatcher, local_port) = Dispatcher::start_with_domain_routing(
        &config.listen,
        tun_uds,
        pool.clone(),
        stats.clone(),
        cancel.clone(),
        domain_routing,
        workers,
    )
    .await?;

    let local_port: std::sync::Arc<str> = std::sync::Arc::from(local_port);

    let raw_turn_ips = if !config.custom_turn_ips.is_empty() {
        &config.custom_turn_ips
    } else {
        &config.turn
    };
    let turn_ip_pool = std::sync::Arc::new(TurnIpPool::new(
        parse_custom_turn_ips(raw_turn_ips),
        MAX_WORKERS,
    ));

    let params_workers = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(workers));
    let scale_notify = std::sync::Arc::new(tokio::sync::Notify::new());
    let params = std::sync::Arc::new(RuntimeParams {
        peer,
        turn_host: (!config.turn.is_empty()).then(|| std::sync::Arc::from(config.turn.as_str())),
        turn_port: (!config.port.is_empty()).then(|| std::sync::Arc::from(config.port.as_str())),
        turn_ip_pool,
        turn_transport,
        srtp_mode,
        hashes: hashes.into(),
        wrap_key,
        client_wrap_key,
        mode,
        generation: config.generation,
        salt: std::sync::Arc::from(config.salt.as_str()),
        local_port: local_port.clone(),
        password: std::sync::Arc::from(config.password.as_str()),
        workers: params_workers,
        configured_workers: workers,
    });

    push_log(format!(
        "[КЛИЕНТ] Воркеров: {workers} (групп: {groups}, по {WORKERS_PER_GROUP})"
    ));

    let repair = RepairState::new(MAX_WORKERS);
    let (config_tx, mut config_rx) = tokio::sync::mpsc::channel::<String>(32);
    let config_events = events.clone();
    let config_task = tokio::spawn(async move {
        let mut last_config = None;
        while let Some(config) = config_rx.recv().await {
            if last_config.as_deref() == Some(config.as_str()) {
                continue;
            }
            if let Some(value) = config.strip_prefix("TUNCONF:") {
                dns::mark_tunnel_active();
                let mut fields = value.splitn(3, ':');
                let ip = fields.next().unwrap_or_default();
                let dns_server = fields.next().unwrap_or_default();
                push_log(format!("[КЛИЕНТ] Tunnel IP: {ip}/32 | DNS: {dns_server}"));
            }
            config_events.config(&config);
            last_config = Some(config);
        }
    });

    let context = std::sync::Arc::new(GroupContext {
        params,
        auth,
        dispatcher: dispatcher.clone(),
        pool,
        stats,
        events: events.clone(),
        paused,
        config_tx,
        start_pacer: std::sync::Arc::new(WorkerStartPacer::new(WORKER_START_INTERVAL)),
        credential_pacer: std::sync::Arc::new(tokio::sync::Mutex::new(())),
        // 2.5.1: Auto-JS полностью выпилен из iOS-ядра, ready_credential_tx=None.
        ready_credential_tx: None,
        server_stream_repair: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
        config_sent: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
        config_in_flight: std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false)),
        repair,
        shutdown: std::sync::Arc::new(ShutdownCoordinator::new()),
        cancel: cancel.clone(),
        scale_notify,
    });

    let groups_future = run_groups(context);
    tokio::pin!(groups_future);
    let groups_completed = tokio::select! {
        _ = &mut groups_future => true,
        _ = cancel.cancelled() => false,
    };
    cancel.cancel();
    if !groups_completed {
        groups_future.await;
    }
    dispatcher.shutdown().await;
    config_task.abort();
    let _ = config_task.await;

    Ok(())
}

// ===== C-ABI: проверка хешей =====

/// Колбэк для валидации хешей VK. Вызывается один раз на каждый хеш
/// (в том же порядке, что и во входной строке `vk`).
///
/// `status`:
///   0 = valid, 1 = invalid, 2 = unavailable.
/// `code` — STUN/error_code (для invalid), 0 иначе.
/// `message` — валидная NUL-terminated C-строка (никогда не NULL).
pub type CsqttHashCheckCallback =
    unsafe extern "C" fn(hash: *const c_char, status: c_int, code: i64, message: *const c_char);

/// Синхронно проверяет список VK-хешей и вызывает `callback` на каждый.
/// Возвращает 0 при успешном проходе, отрицательное число при ошибке
/// (например, runtime не удалось создать или хеши пусты).
#[unsafe(no_mangle)]
pub extern "C" fn csqttr_validate_vk_hashes(
    vk: *const c_char,
    fingerprint: *const c_char,
    client_ids: *const c_char,
    callback: Option<CsqttHashCheckCallback>,
) -> c_int {
    let Some(callback) = callback else {
        push_log("[iOS] validate_vk_hashes: callback is NULL".to_string());
        return -3;
    };
    let vk = unsafe { cstr_to_string(vk) };
    let fingerprint = unsafe { cstr_to_string(fingerprint) };
    let client_ids_raw = unsafe { cstr_to_string(client_ids) };

    let hashes: Vec<_> = worker::parse_hashes(&vk).into_iter().take(6).collect();
    if hashes.is_empty() {
        push_log("[iOS] validate_vk_hashes: нет хешей".to_string());
        return -2;
    }
    let client_ids: Vec<_> = client_ids_raw
        .split(',')
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .map(str::to_owned)
        .collect();

    let rt = match tokio::runtime::Builder::new_multi_thread()
        .worker_threads(1)
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(error) => {
            push_log(format!("[iOS] validate_vk_hashes: runtime: {error}"));
            return -1;
        }
    };

    rt.block_on(async move {
        let results =
            auth::check_vk_hashes(&fingerprint, &client_ids, &hashes).await;
        for (hash, result) in results {
            let (status, code, message) = match result {
                auth::VkHashCheck::Valid => (0i32, 0i64, String::new()),
                auth::VkHashCheck::Invalid { code, message } => (1, code, message),
                auth::VkHashCheck::Unavailable { message } => (2, 0, message),
            };
            let Ok(hash_c) = CString::new(hash) else {
                continue;
            };
            let Ok(msg_c) = CString::new(message) else {
                continue;
            };
            unsafe {
                callback(hash_c.as_ptr(), status, code, msg_c.as_ptr());
            }
        }
    });

    0
}

// ===== C-ABI экспорт =====

/// CSQTTR 2.5.2 extended ABI: 29 arguments. The LaLune compatibility wrapper below uses the shared 20-argument interface
/// (не существуют как CLI-флаги в `rust-client/main.rs` 2.5.1).
#[unsafe(no_mangle)]
pub extern "C" fn csqttr_run_extended(
    peer: *const c_char,
    vk: *const c_char,
    password: *const c_char,
    listen: *const c_char,
    workers: c_int,
    vk_hash_mode: *const c_char,
    vk_auth_mode: *const c_char,
    captcha_mode: *const c_char,
    fingerprint: *const c_char,
    client_ids: *const c_char,
    obfs: *const c_char,
    turn_transport: *const c_char,
    srtp_mode: *const c_char,
    generation: u64,
    salt: *const c_char,
    turn: *const c_char,
    custom_turn_ips: *const c_char,
    port: *const c_char,
    validate_vk_hashes: bool,
    tun_uds: *const c_char,
    domain_routing: bool,
    exclude_ru_zones: bool,
    template_domains: bool,
    domain_whitelist: bool,
    direct_domains: *const c_char,
    ip_routing: bool,
    ip_whitelist: bool,
    direct_ips: *const c_char,
    exact_workers: bool,
) -> c_int {
    let config = CsqttConfig {
        peer: unsafe { cstr_to_string(peer) },
        vk: unsafe { cstr_to_string(vk) },
        password: unsafe { cstr_to_string(password) },
        listen: unsafe { cstr_to_string(listen) },
        workers,
        vk_hash_mode: unsafe { cstr_to_string(vk_hash_mode) },
        vk_auth_mode: unsafe { cstr_to_string(vk_auth_mode) },
        captcha_mode: unsafe { cstr_to_string(captcha_mode) },
        fingerprint: unsafe { cstr_to_string(fingerprint) },
        client_ids: unsafe { cstr_to_string(client_ids) },
        obfs: unsafe { cstr_to_string(obfs) },
        turn_transport: unsafe { cstr_to_string(turn_transport) },
        srtp_mode: unsafe { cstr_to_string(srtp_mode) },
        generation,
        salt: unsafe { cstr_to_string(salt) },
        turn: unsafe { cstr_to_string(turn) },
        custom_turn_ips: unsafe { cstr_to_string(custom_turn_ips) },
        port: unsafe { cstr_to_string(port) },
        validate_vk_hashes,
        tun_uds: unsafe { cstr_to_string(tun_uds) },
        domain_routing,
        exclude_ru_zones,
        template_domains,
        domain_whitelist,
        direct_domains: unsafe { cstr_to_string(direct_domains) },
        ip_routing,
        ip_whitelist,
        direct_ips: unsafe { cstr_to_string(direct_ips) },
        exact_workers,
    };

    push_log("[iOS] Starting client (csqttr-core 2.5.2)".to_string());

    let runtime_threads = runtime_worker_threads(config.workers as usize);
    let rt = match tokio::runtime::Builder::new_multi_thread()
        .worker_threads(runtime_threads)
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(e) => {
            push_log(format!("[iOS] Runtime error: {}", e));
            return -1;
        }
    };

    rt.block_on(async {
        match run_client(config).await {
            Ok(()) => {
                push_log("[iOS] Client finished successfully".to_string());
                0
            }
            Err(e) => {
                push_log(format!("[iOS] Error: {}", e));
                -3
            }
        }
    })
}


/// Compatibility C ABI matching LaLune's existing CSQTT tunnel interface.
/// CSQTTR-specific routing options are disabled by default; the common
/// connection settings (including password) are passed through unchanged.
#[unsafe(no_mangle)]
pub extern "C" fn csqttr_run(
    peer: *const c_char,
    vk: *const c_char,
    password: *const c_char,
    listen: *const c_char,
    workers: c_int,
    _device_id: *const c_char,
    vk_hash_mode: *const c_char,
    vk_auth_mode: *const c_char,
    captcha_mode: *const c_char,
    fingerprint: *const c_char,
    client_ids: *const c_char,
    obfs: *const c_char,
    turn_transport: *const c_char,
    generation: u64,
    salt: *const c_char,
    turn: *const c_char,
    port: *const c_char,
    _allow_hash_redistribution: bool,
    validate_vk_hashes: bool,
    tun_uds: *const c_char,
) -> c_int {
    csqttr_run_extended(
        peer, vk, password, listen, workers,
        vk_hash_mode, vk_auth_mode, captcha_mode, fingerprint,
        client_ids, obfs, turn_transport, std::ptr::null(),
        generation, salt, turn, std::ptr::null(), port,
        validate_vk_hashes, tun_uds,
        false, false, false, false, std::ptr::null(),
        false, false, std::ptr::null(), false,
    )
}

#[unsafe(no_mangle)]
pub extern "C" fn csqttr_stop() -> c_int {
    push_log("[iOS] Stop called".to_string());
    0
}

#[unsafe(no_mangle)]
pub extern "C" fn csqttr_status() -> c_int {
    1
}

// ===== Хелперы =====

unsafe fn cstr_to_string(ptr: *const c_char) -> String {
    if ptr.is_null() {
        return String::new();
    }
    match CStr::from_ptr(ptr).to_str() {
        Ok(s) => s.to_string(),
        Err(_) => String::new(),
    }
}
