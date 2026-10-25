#!/bin/bash

set -Eeuo pipefail

export DEBIAN_FRONTEND=noninteractive
export TERM="${TERM:-xterm}"

readonly SCRIPT_VERSION="2.5.0"
readonly LOG_FILE="${CSQTT_INSTALL_LOG_FILE:-/var/log/csqtt-install.log}"
readonly PEER_PORT="${CSQTT_PEER_PORT:-46000}"
readonly SSH_PORT="${CSQTT_SSH_PORT:-22}"
WEB_PORT="${CSQTT_WEB_PORT:-46002}"
readonly LE_HTTP_PORT="80"
LE_RENEW_BEFORE_SECONDS="259200"
readonly DEPLOY_MODE="${CSQTT_DEPLOY_MODE:-systemd}"
readonly CSQTT_IFACE="csqtt1"
readonly CSQTT_CONFIG_DIR="${CSQTT_CONFIG_DIR:-/etc/csqtt}"
readonly CSQTT_LE_ROOT="${CSQTT_LE_ROOT:-/etc/letsencrypt}"
readonly CSQTT_LE_CERTBOT="${CSQTT_LE_CERTBOT:-/opt/csqtt-certbot/bin/certbot}"
readonly CSQTT_LE_VENV="${CSQTT_LE_VENV:-/opt/csqtt-certbot}"
readonly CSQTT_LE_STATE_FILE="${CSQTT_LE_STATE_FILE:-${CSQTT_CONFIG_DIR}/letsencrypt.env}"
readonly CSQTT_LE_RENEW_HELPER="${CSQTT_LE_RENEW_HELPER:-/usr/local/lib/csqtt-letsencrypt-renew.sh}"
readonly CSQTT_LE_SERVICE="csqtt-letsencrypt.service"
readonly CSQTT_LE_TIMER="csqtt-letsencrypt.timer"
readonly CSQTT_DATABASE_FILE="csqtt.db"
readonly CSQTT_DATABASE_WAL_FILE="csqtt.db-wal"
readonly CSQTT_DATABASE_SHM_FILE="csqtt.db-shm"
readonly CSQTT_LEGACY_MIGRATION_JSON="passwords.json"
readonly CSQTT_LEGACY_MIGRATION_IMPORTED_JSON="passwords.json.imported"
readonly CSQTT_ENV_FILE="${CSQTT_CONFIG_DIR}/csqtt.env"
readonly CSQTT_DEPLOY_OVERRIDES_FILE="${CSQTT_CONFIG_DIR}/deploy-overrides.json"
readonly CSQTT_RUNTIME_ARCHIVE_DIR="${CSQTT_CONFIG_DIR}/runtime-archive"
readonly UPLOAD_BINARY="/tmp/.csqtt-upload-server"
readonly UPLOAD_ENV_FILE="${CSQTT_UPLOAD_ENV_FILE:-/tmp/.csqtt-upload-web.env}"
readonly UPLOAD_OVERRIDES_FILE="/tmp/.csqtt-upload-overrides.json"
readonly CSQTT_SYSCTL_FILE="/etc/sysctl.d/99-csqtt.conf"
readonly CSQTT_UDP_SYSCTL_FILE="/etc/sysctl.d/99-csqtt-udp-buffers.conf"
readonly IPT_COMMENT="CSQTT_MANAGED"
readonly CSQTT_DOCKER_IMAGE="csqtt:2.5.0"
readonly CSQTT_DOCKER_CONTAINER="csqtt"
readonly CSQTT_DOCKER_PREREQ_SERVICE="csqtt-docker-prereq.service"
readonly CSQTT_DOCKER_PREREQ_HELPER="/usr/local/lib/csqtt/docker-prereq.sh"
readonly CSQTT_RUNTIME_DIR="/run/csqtt"
readonly CSQTT_DOCKER_NETWORK_READY="${CSQTT_RUNTIME_DIR}/docker-network.ready"
readonly XT_WAIT="${CSQTT_XT_WAIT:-2}"
readonly START_STABILITY_SECONDS="${CSQTT_START_STABILITY_SECONDS:-4}"
readonly DOCKER_BUILD_TIMEOUT_SECONDS="${CSQTT_DOCKER_BUILD_TIMEOUT_SECONDS:-180}"
readonly EXIT_INVALID_ARGUMENT=2
readonly EXIT_PREFLIGHT_FAILED=20
readonly EXIT_CUTOVER_FAILED=30

SYSTEMD_NEEDS_RELOAD=0
DEPLOY_PHASE="initial"
DOCKER_CONTEXT_DIR=""
CSQTT_DOCKER_CANDIDATE_IMAGE=""
# Заполняются resolve_panel_mode() в начале install: ip|domain, хост панели.
PANEL_MODE=""
PANEL_HOST=""

validate_port() {
    local name="$1" value="$2"
    case "$value" in
        ''|*[!0-9]*) die "$name должен быть числом от 1 до 65535, получено: $value" ;;
    esac
    if [ "$value" -lt 1 ] || [ "$value" -gt 65535 ]; then
        die "$name должен быть в диапазоне 1..65535, получено: $value"
    fi
}

validate_positive_seconds() {
    local name="$1" value="$2"
    case "$value" in
        ''|*[!0-9]*) die "$name должен быть положительным числом секунд, получено: $value" ;;
    esac
    if [ "$value" -lt 1 ] || [ "$value" -gt 900 ]; then
        die "$name должен быть в диапазоне 1..900, получено: $value"
    fi
}

validate_distinct_network_ports() {
    [ "$PEER_PORT" != "$SSH_PORT" ] || die "CSQTT_PEER_PORT не может совпадать с SSH-портом"
    [ "$PEER_PORT" != "$WEB_PORT" ] || die "CSQTT_PEER_PORT не может совпадать с WEB-портом"
    [ "$PEER_PORT" != "$LE_HTTP_PORT" ] || die "CSQTT_PEER_PORT не может совпадать с HTTP-портом"
    [ "$WEB_PORT" != "$SSH_PORT" ] || die "CSQTT_WEB_PORT не может совпадать с SSH-портом"
}

C_GREEN=''; C_YELLOW=''; C_RED=''
C_CYAN='';  C_BOLD='';      C_NC=''

log_info()  { echo -e "${C_GREEN}[✓]${C_NC} $*" | tee -a "$LOG_FILE"; }
log_warn()  { echo -e "${C_YELLOW}[!]${C_NC} $*" | tee -a "$LOG_FILE"; }
log_error() { echo -e "${C_RED}[✗]${C_NC} $*" | tee -a "$LOG_FILE"; }
log_step()  { echo -e "${C_CYAN}[►]${C_NC} ${C_BOLD}$*${C_NC}" | tee -a "$LOG_FILE"; }

default_exit_code() {
    case "$DEPLOY_PHASE" in
        validation) printf '%s' "$EXIT_INVALID_ARGUMENT" ;;
        preflight) printf '%s' "$EXIT_PREFLIGHT_FAILED" ;;
        cutover|activation) printf '%s' "$EXIT_CUTOVER_FAILED" ;;
        *) printf '%s' 1 ;;
    esac
}

die() {
    local message="$1" code="${2:-$(default_exit_code)}"
    log_error "$message"
    printf 'CSQTT_DEPLOY_ERROR|%s|%s\n' "$DEPLOY_PHASE" "$message"
    cleanup_deploy_uploads
    exit "$code"
}

csqtt_systemd_unit_exists() {
    command -v systemctl >/dev/null 2>&1 || return 1
    local state
    state="$(systemctl show --value -p LoadState csqtt 2>/dev/null || true)"
    [ -n "$state" ] && [ "$state" != "not-found" ]
}

csqtt_systemd_unit_is_managed() {
    csqtt_systemd_unit_exists || return 1
    local unit
    unit="$(systemctl cat csqtt 2>/dev/null || true)"
    printf '%s\n' "$unit" | grep -Eq '(/usr/local/bin/csqtt|/usr/local/lib/csqtt/)'
}

csqtt_docker_container_exists() {
    command -v docker >/dev/null 2>&1 && docker inspect "$CSQTT_DOCKER_CONTAINER" >/dev/null 2>&1
}

docker_container_runs_csqtt() {
    local container="$1" container_id proc pid cgroup executable command_line details
    docker inspect "$container" >/dev/null 2>&1 || return 1
    details="$(docker inspect --format '{{.Name}}|{{.Config.Image}}|{{json .Config.Entrypoint}}|{{json .Config.Cmd}}|{{range .Mounts}}{{.Source}}:{{.Destination}}|{{end}}' "$container" 2>/dev/null || true)"
    case "$details" in
        *'|csqtt|'*|*'|csqtt:'*|*'|csqtt-docker-'*|*'/usr/local/bin/csqtt'*|*":${CSQTT_CONFIG_DIR}"*) return 0 ;;
    esac
    if docker top "$container" -eo pid,args 2>/dev/null | awk '
NR > 1 && $0 ~ /(^|[[:space:]])\/usr\/local\/bin\/csqtt([[:space:]]|$)/ { found = 1 }
END { exit !found }
'; then
        return 0
    fi
    container_id="$(docker inspect --format '{{.Id}}' "$container" 2>/dev/null || true)"
    [ -n "$container_id" ] || return 1
    for proc in /proc/[0-9]*; do
        pid="${proc##*/}"
        cgroup="$(cat "$proc/cgroup" 2>/dev/null || true)"
        case "$cgroup" in
            *"docker-${container_id}.scope"*|*"/docker/${container_id}"*) ;;
            *) continue ;;
        esac
        executable="$(readlink "$proc/exe" 2>/dev/null || true)"
        case "$executable" in
            /usr/local/bin/csqtt|'/usr/local/bin/csqtt (deleted)') return 0 ;;
        esac
        command_line="$({ tr '\0' ' ' < "$proc/cmdline"; } 2>/dev/null || true)"
        case " $command_line " in
            *" /usr/local/bin/csqtt "*|*" --config-dir ${CSQTT_CONFIG_DIR} "*) return 0 ;;
        esac
    done
    return 1
}

csqtt_persistent_state_exists() {
    [ -f "$CSQTT_ENV_FILE" ] || [ -f "${CSQTT_CONFIG_DIR}/${CSQTT_DATABASE_FILE}" ] || \
        [ -f "${CSQTT_CONFIG_DIR}/${CSQTT_LEGACY_MIGRATION_JSON}" ] || \
        [ -f "${CSQTT_CONFIG_DIR}/${CSQTT_LEGACY_MIGRATION_IMPORTED_JSON}" ] || \
        [ -f "${CSQTT_CONFIG_DIR}/web_cert.pem" ] || [ -f "${CSQTT_CONFIG_DIR}/web_key.pem" ]
}

peer_port_listeners() {
    ss -H -lunp "sport = :${PEER_PORT}" 2>/dev/null || true
}

peer_port_listener_pids() {
    peer_port_listeners | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -un || true
}

docker_container_for_pid() {
    local pid="$1" cgroup container_id
    command -v docker >/dev/null 2>&1 || return 1
    cgroup="$(cat "/proc/$pid/cgroup" 2>/dev/null || true)"
    container_id="$(printf '%s\n' "$cgroup" | sed -nE 's#.*docker-([0-9a-f]{12,64})\.scope.*#\1#p; s#.*/docker/([0-9a-f]{12,64}).*#\1#p' | head -n 1)"
    [ -n "$container_id" ] || return 1
    docker inspect "$container_id" >/dev/null 2>&1 || return 1
    printf '%s\n' "$container_id"
}

csqtt_process_is_owned() {
    local pid="$1" executable command_line
    case "$pid" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ -r "/proc/$pid/cmdline" ] || return 1
    executable="$(readlink "/proc/$pid/exe" 2>/dev/null || true)"
    case "$executable" in
        /usr/local/bin/csqtt|'/usr/local/bin/csqtt (deleted)'|/usr/local/lib/csqtt/*|'/usr/local/lib/csqtt/'*' (deleted)') return 0 ;;
    esac
    command_line="$({ tr '\0' ' ' < "/proc/$pid/cmdline"; } 2>/dev/null || true)"
    case " $command_line " in
        *" --config-dir ${CSQTT_CONFIG_DIR} "*|*" /usr/local/bin/csqtt "*|*" /usr/local/lib/csqtt/"*) ;;
        *) return 1 ;;
    esac
    return 0
}

csqtt_process_owns_peer_port() { csqtt_process_is_owned "$1"; }

all_csqtt_process_pids() {
    local proc pid
    for proc in /proc/[0-9]*; do
        pid="${proc##*/}"
        csqtt_process_is_owned "$pid" && printf '%s\n' "$pid"
    done
    return 0
}

csqtt_peer_port_is_owned() {
    local pid
    while IFS= read -r pid; do
        csqtt_process_owns_peer_port "$pid" && return 0
    done < <(peer_port_listener_pids)
    return 1
}

force_stop_csqtt_processes() {
    local pid attempt found
    for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16; do
        found=0
        while IFS= read -r pid; do
            found=1
            log_warn "Принудительное завершение CSQTT: PID $pid"
            if [ "$attempt" -le 2 ]; then
                kill -TERM "$pid" 2>/dev/null || true
            else
                kill -KILL "$pid" 2>/dev/null || true
            fi
        done < <(all_csqtt_process_pids)
        if [ "$found" -eq 0 ]; then
            return 0
        fi
        sleep 0.2
    done
    return 1
}

force_release_peer_port() {
    local attempt pid container found
    for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
        [ -z "$(peer_port_listeners)" ] && return 0
        found=0
        while IFS= read -r pid; do
            [ -n "$pid" ] || continue
            found=1
            container="$(docker_container_for_pid "$pid" 2>/dev/null || true)"
            if [ -n "$container" ]; then
                log_warn "Принудительное удаление Docker runtime на UDP/${PEER_PORT}: ${container:0:12}"
                docker update --restart=no "$container" >/dev/null 2>&1 || true
                docker rm -f "$container" >/dev/null 2>&1 || true
            elif [ "$attempt" -le 2 ]; then
                log_warn "Принудительное завершение runtime на UDP/${PEER_PORT}: PID $pid"
                kill -TERM "$pid" 2>/dev/null || true
            else
                log_warn "Принудительное завершение runtime на UDP/${PEER_PORT}: PID $pid"
                kill -KILL "$pid" 2>/dev/null || true
            fi
        done < <(peer_port_listener_pids)
        if [ "$found" -eq 0 ]; then
            log_error "UDP/${PEER_PORT} занят runtime без доступного PID"
            peer_port_listeners | tee -a "$LOG_FILE" >&2
            return 1
        fi
        sleep 0.2
    done
    peer_port_listeners | tee -a "$LOG_FILE" >&2
    return 1
}

assert_peer_port_is_available() {
    local listeners
    listeners="$(peer_port_listeners)"
    [ -z "$listeners" ] && return 0
    log_warn "UDP/${PEER_PORT} занят; выполняется принудительное освобождение"
    force_release_peer_port || die "Не удалось принудительно освободить UDP/${PEER_PORT}"
    [ -z "$(peer_port_listeners)" ] || die "UDP/${PEER_PORT} остался занят после принудительной очистки"
}

web_port_listeners() {
    ss -ltnH "sport = :${WEB_PORT}" 2>/dev/null || true
}

# Порт панели занимает чужой процесс: принудительного освобождения здесь нет,
# в отличие от assert_peer_port_is_available. Проверка стоит перед стартом
# панели и покрывает обе ветки — systemd и docker.
assert_web_port_is_available() {
    local listeners
    listeners="$(web_port_listeners)"
    [ -z "$listeners" ] && return 0
    log_warn "TCP/${WEB_PORT} занят другим процессом:"
    printf '%s\n' "$listeners" | tee -a "$LOG_FILE" >&2
    die "Порт ${WEB_PORT} занят другим процессом; освободите его и повторите установку"
}

archive_csqtt_docker_state() {
    local container="$1" container_id archive_dir source base copied=0
    container_id="$(docker inspect --format '{{.Id}}' "$container" 2>/dev/null || true)"
    [ -n "$container_id" ] || return 0
    archive_dir="${CSQTT_RUNTIME_ARCHIVE_DIR}/docker-${container_id:0:12}-$(date +%s)"
    umask 077
    mkdir -p "$archive_dir" || die "Не удалось подготовить архив состояния Docker CSQTT"
    for source in /etc/csqtt/csqtt.db /etc/csqtt/csqtt.db-wal /etc/csqtt/csqtt.db-shm /etc/csqtt/passwords.json /etc/csqtt/passwords.json.imported; do
        base="${source##*/}"
        if docker cp "${container}:${source}" "${archive_dir}/${base}" >/dev/null 2>&1; then
            chmod 600 "${archive_dir}/${base}" 2>/dev/null || true
            copied=1
        fi
    done
    if [ "$copied" -eq 1 ]; then
        log_info "Состояние Docker CSQTT сохранено: $archive_dir"
    else
        rmdir "$archive_dir" >/dev/null 2>&1 || true
    fi
}

stop_and_archive_csqtt_docker_container() {
    local container="$1" name running
    docker inspect "$container" >/dev/null 2>&1 || return 0
    name="$(docker inspect --format '{{.Name}}' "$container" 2>/dev/null || printf '%s' "$container")"
    name="${name#/}"
    docker update --restart=no "$container" >/dev/null 2>&1 || true
    running="$(docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null || printf false)"
    if [ "$running" = "true" ]; then
        timeout 10 docker stop --time 5 "$container" >/dev/null 2>&1 || true
        if [ "$(docker inspect --format '{{.State.Running}}' "$container" 2>/dev/null || printf false)" = "true" ]; then
            docker kill "$container" >/dev/null 2>&1 || true
        fi
    fi
    archive_csqtt_docker_state "$container"
    timeout 10 docker rm -f "$container" >/dev/null 2>&1 || \
        die "Не удалось остановить старый Docker-контейнер CSQTT"
}

remove_all_csqtt_docker_containers() {
    command -v docker >/dev/null 2>&1 || return 0
    local container name
    while IFS= read -r container; do
        [ -n "$container" ] || continue
        docker_container_runs_csqtt "$container" || continue
        name="$(docker inspect --format '{{.Name}}' "$container" 2>/dev/null || printf '%s' "$container")"
        name="${name#/}"
        log_info "Остановка Docker runtime CSQTT: $name"
        stop_and_archive_csqtt_docker_container "$container"
    done < <(docker ps -aq --no-trunc 2>/dev/null || true)
}

systemd_unit_runs_csqtt() {
    local unit="$1" content
    content="$(systemctl cat "$unit" 2>/dev/null || true)"
    if printf '%s\n' "$content" | grep -Eq '(/usr/local/bin/csqtt|/usr/local/lib/csqtt/)'; then
        return 0
    fi
    return 1
}

list_csqtt_systemd_units() {
    command -v systemctl >/dev/null 2>&1 || return 0
    local unit
    while IFS= read -r unit; do
        case "$unit" in
            *.service) ;;
            *) continue ;;
        esac
        case "$unit" in
            "$CSQTT_DOCKER_PREREQ_SERVICE"|"$CSQTT_LE_SERVICE") continue ;;
        esac
        if systemd_unit_runs_csqtt "$unit"; then
            printf '%s\n' "$unit"
        fi
    done < <({
        systemctl list-units --type=service --all --no-legend --plain 2>/dev/null || true
        systemctl list-unit-files --type=service --no-legend --plain 2>/dev/null || true
    } | awk '{print $1}' | sort -u)
    return 0
}

stop_csqtt_docker_prereq() {
    command -v systemctl >/dev/null 2>&1 || return 0
    local requires_link="/etc/systemd/system/docker.service.requires/${CSQTT_DOCKER_PREREQ_SERVICE}"
    local wants_link="/etc/systemd/system/docker.service.wants/${CSQTT_DOCKER_PREREQ_SERVICE}"
    if [ -e "$requires_link" ] || [ -L "$requires_link" ]; then
        rm -f -- "$requires_link"
        SYSTEMD_NEEDS_RELOAD=1
    fi
    if [ -e "$wants_link" ] || [ -L "$wants_link" ]; then
        rm -f -- "$wants_link"
        SYSTEMD_NEEDS_RELOAD=1
    fi
    systemctl disable "$CSQTT_DOCKER_PREREQ_SERVICE" >/dev/null 2>&1 || true
    if [ "$SYSTEMD_NEEDS_RELOAD" -eq 1 ]; then
        systemctl daemon-reload || die "systemctl daemon-reload завершился ошибкой при отключении Docker prerequisites"
        SYSTEMD_NEEDS_RELOAD=0
    fi
    if systemctl is-active --quiet "$CSQTT_DOCKER_PREREQ_SERVICE"; then
        log_info "Остановка Docker prerequisite CSQTT: $CSQTT_DOCKER_PREREQ_SERVICE"
        timeout 10 systemctl stop "$CSQTT_DOCKER_PREREQ_SERVICE" >/dev/null 2>&1 || true
        if systemctl is-active --quiet "$CSQTT_DOCKER_PREREQ_SERVICE"; then
            timeout 2 systemctl kill --kill-who=all --signal=SIGKILL "$CSQTT_DOCKER_PREREQ_SERVICE" >/dev/null 2>&1 || true
        fi
        if systemctl is-active --quiet "$CSQTT_DOCKER_PREREQ_SERVICE"; then
            die "Не удалось остановить Docker prerequisite CSQTT: $CSQTT_DOCKER_PREREQ_SERVICE"
        fi
    fi
}

stop_all_running_csqtt_systemd_units() {
    command -v systemctl >/dev/null 2>&1 || return 0
    local unit
    while IFS= read -r unit; do
        [ -n "$unit" ] || continue
        log_info "Остановка systemd runtime CSQTT: $unit"
        if ! timeout 10 systemctl stop "$unit" >/dev/null 2>&1; then
            log_warn "systemctl stop завершился с ошибкой для $unit; проверяется фактическое состояние"
        fi
        if systemctl is-active --quiet "$unit"; then
            timeout 2 systemctl kill --kill-who=all --signal=SIGKILL "$unit" >/dev/null 2>&1 || true
        fi
        if systemctl is-active --quiet "$unit"; then
            die "Не удалось остановить systemd runtime CSQTT: $unit"
        fi
        systemctl disable "$unit" >/dev/null 2>&1 || true
    done < <(list_csqtt_systemd_units)
    return 0
}

remove_all_csqtt_systemd_units() {
    command -v systemctl >/dev/null 2>&1 || return 0
    local unit fragment
    while IFS= read -r unit; do
        [ -n "$unit" ] || continue
        fragment="$(systemctl show --value -p FragmentPath "$unit" 2>/dev/null || true)"
        case "$fragment" in
            /etc/systemd/system/*)
                rm -f -- "$fragment"
                rm -rf -- "${fragment}.d"
                rm -f -- "/etc/systemd/system/multi-user.target.wants/${unit}"
                SYSTEMD_NEEDS_RELOAD=1
                ;;
        esac
    done < <(list_csqtt_systemd_units)
    return 0
}

prog() { echo "CSQTT_PROGRESS|$1|$2"; }

run_timed() {
    local label="$1" started=$SECONDS
    shift
    "$@"
    log_info "Время этапа «$label»: $((SECONDS - started))с"
}

check_root() {
    if [ "$(id -u)" -ne 0 ]; then
        die "Скрипт должен быть запущен от root. Если sudo отсутствует, зайдите под root и запустите: bash $0 $*"
    fi
}

OS_ID="" ; PKG_MGR=""

detect_os() {
    log_step "Определение операционной системы..."
    if [ ! -f /etc/os-release ]; then
        die "Файл /etc/os-release не найден."
    fi
    . /etc/os-release
    OS_ID="${ID:-unknown}"
    case "$OS_ID" in
        ubuntu|debian|linuxmint|pop)     PKG_MGR="apt" ;;
        centos|rhel|rocky|almalinux|oracle|ol) PKG_MGR="yum"
            command -v dnf &>/dev/null && PKG_MGR="dnf" ;;
        fedora)                          PKG_MGR="dnf" ;;
        arch|manjaro|endeavouros)        PKG_MGR="pacman" ;;
        *)
            case " ${ID_LIKE:-} " in
                *" debian "*) PKG_MGR="apt" ;;
                *" rhel "*|*" fedora "*)
                    PKG_MGR="yum"
                    command -v dnf &>/dev/null && PKG_MGR="dnf"
                    ;;
                *" arch "*) PKG_MGR="pacman" ;;
                *) die "Неподдерживаемый дистрибутив: $OS_ID" ;;
            esac
            ;;
    esac
    log_info "ОС: ${PRETTY_NAME:-$OS_ID} | PM: $PKG_MGR"
}

pkg_update_done=0

pkg_update() {
    [ "$pkg_update_done" = "1" ] && return 0
    log_step "Обновление индексов пакетов..."
    case "$PKG_MGR" in
        apt)
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -y >>"$LOG_FILE" 2>&1 || log_warn "apt update завершился с ошибкой, пробую продолжить"
            ;;
        dnf)    dnf makecache -y >>"$LOG_FILE" 2>&1 || true ;;
        yum)    yum makecache -y >>"$LOG_FILE" 2>&1 || true ;;
        pacman) : ;;
    esac
    pkg_update_done=1
}

pkg_install() {
    [ "$#" -eq 0 ] && return 0
    case "$PKG_MGR" in
        apt)
            export DEBIAN_FRONTEND=noninteractive
            apt-get install -y -qq --no-install-recommends "$@" >>"$LOG_FILE" 2>&1
            ;;
        dnf)    dnf install -y "$@" >>"$LOG_FILE" 2>&1 ;;
        yum)    yum install -y "$@" >>"$LOG_FILE" 2>&1 ;;
        pacman) pacman -Syu --noconfirm --needed "$@" >>"$LOG_FILE" 2>&1 ;;
    esac
}

pkg_install_with_refresh() {
    pkg_install "$@" && return 0
    pkg_update
    pkg_install "$@"
}

install_prerequisites() {
    prog 0.10 "Пакеты..."
    log_step "Проверка базовых зависимостей..."

    local ip_package procps_package
    case "$PKG_MGR" in
        apt)     ip_package="iproute2"; procps_package="procps" ;;
        dnf|yum) ip_package="iproute";  procps_package="procps-ng" ;;
        pacman)  ip_package="iproute2"; procps_package="procps-ng" ;;
    esac

    local -a packages=()
    local need_iptables=0
    command -v ip >/dev/null 2>&1 || packages+=("$ip_package")
    command -v curl >/dev/null 2>&1 || packages+=("curl")
    command -v modprobe >/dev/null 2>&1 || packages+=("kmod")
    if ! command -v sysctl >/dev/null 2>&1; then
        packages+=("$procps_package")
    fi
    command -v iptables >/dev/null 2>&1 || need_iptables=1

    if [ "$PKG_MGR" = "pacman" ] && [ "$need_iptables" -eq 1 ]; then
        packages+=("iptables")
        need_iptables=0
    fi

    if [ "${#packages[@]}" -eq 0 ] && [ "$need_iptables" -eq 0 ]; then
        log_info "Все системные зависимости уже установлены — обновление пакетов не требуется"
        return 0
    fi

    pkg_update
    if [ "${#packages[@]}" -gt 0 ]; then
        pkg_install "${packages[@]}" || die "Не удалось установить обязательные пакеты: ${packages[*]}"
    fi

    if [ "$need_iptables" -eq 1 ]; then
        case "$PKG_MGR:$OS_ID" in
            dnf:fedora)
                pkg_install iptables-nft || pkg_install iptables || \
                    die "Не удалось установить iptables/iptables-nft"
                ;;
            dnf:*|yum:*)
                pkg_install iptables || pkg_install iptables-nft || \
                    die "Не удалось установить iptables/iptables-nft"
                ;;
            *)
                pkg_install iptables || die "Не удалось установить обязательный пакет iptables"
                ;;
        esac
    fi
}

require_runtime_tools() {
    command -v ip >/dev/null 2>&1 || die "Команда ip не найдена. Установите iproute2/iproute."
    command -v iptables >/dev/null 2>&1 || die "Команда iptables не найдена. Она обязательна для TUN и NAT."
    command -v sysctl >/dev/null 2>&1 || die "Команда sysctl не найдена. Установите procps/procps-ng."
    if [ "$DEPLOY_MODE" = "systemd" ]; then
        command -v systemctl >/dev/null 2>&1 || die "systemctl не найден. Для native-установки нужен VPS с systemd."
    fi
    case "$(uname -m)" in
        x86_64|amd64) log_info "Архитектура VPS: amd64" ;;
        aarch64|arm64) log_info "Архитектура VPS: ARM64" ;;
        armv7l|armv7|armhf) log_info "Архитектура VPS: ARM32" ;;
        *) die "Архитектура VPS $(uname -m) не поддерживается. Поддерживаются: x86_64, aarch64, armv7l." ;;
    esac
}

detect_wan_interface() {
    local iface fallback=""
    while IFS= read -r iface; do
        iface="${iface%%@*}"
        [ -n "$iface" ] || continue
        [ -n "$fallback" ] || fallback="$iface"
        if ! is_ignored_wan_interface "$iface" && ip link show "$iface" >/dev/null 2>&1; then
            echo "$iface"
            return 0
        fi
    done <<EOF
$(ip -o -4 route show default 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="dev") {print $(i+1); break}}')
EOF
    while IFS= read -r iface; do
        iface="${iface%%@*}"
        [ -n "$iface" ] || continue
        [ -n "$fallback" ] || fallback="$iface"
        if ! is_ignored_wan_interface "$iface" && ip link show "$iface" >/dev/null 2>&1; then
            echo "$iface"
            return 0
        fi
    done <<EOF
$(ip -o -4 addr show scope global 2>/dev/null | awk '{sub(/@.*/, "", $2); print $2}')
EOF
    while IFS= read -r iface; do
        iface="${iface%%@*}"
        [ -n "$iface" ] || continue
        [ "$iface" = "lo" ] && continue
        [ -n "$fallback" ] || fallback="$iface"
        if ! is_ignored_wan_interface "$iface" && ip link show "$iface" >/dev/null 2>&1; then
            echo "$iface"
            return 0
        fi
    done <<EOF
$(ls /sys/class/net/ 2>/dev/null || true)
EOF
    [ -n "$fallback" ] && echo "$fallback"
}

is_ignored_wan_interface() {
    local iface="${1%%@*}"
    case "$iface" in
        ""|lo|"$CSQTT_IFACE"|csqtp*|tun*|tap*|wg*|warp*|Warp*|WARP*|CloudflareWARP*|tailscale*|zt*|docker*|br-*|veth*|cni*|flannel*|virbr*|podman*|kube*|dummy*|ifb*)
            return 0
            ;;
    esac
    return 1
}

detect_firewall() {
    command -v iptables >/dev/null 2>&1 || \
        die "iptables не найден: без него сервер не сможет создать рабочий TUN/NAT."
    local ver
    ver="$(iptables --version 2>/dev/null || echo unknown)"
    log_info "Firewall backend: iptables (${ver})"
}

ipt_add_or_ensure() {
    local table="$1" chain="$2"
    shift 2
    local -a targs=()
    [ "$table" = "filter" ] || targs=(-t "$table")
    iptables -w "$XT_WAIT" "${targs[@]}" -C "$chain" "$@" >/dev/null 2>&1 || \
        iptables -w "$XT_WAIT" "${targs[@]}" -I "$chain" 1 "$@" >>"$LOG_FILE" 2>&1
}

fw_add_input_udp() {
    local port="$1"
    ipt_add_or_ensure filter INPUT -p udp --dport "$port" -m comment --comment "$IPT_COMMENT" -j ACCEPT || \
        die "Не удалось открыть ${port}/udp в iptables"
}

fw_add_input_tcp() {
    local port="$1"
    ipt_add_or_ensure filter INPUT -p tcp --dport "$port" -m comment --comment "$IPT_COMMENT" -j ACCEPT || \
        die "Не удалось открыть ${port}/tcp в iptables"
}

# Правила firewall только добавляются, поэтому порт, который панель больше не
# использует, закрывается явно. Цикл ограничен: `iptables -D` возвращает неуспех,
# когда правила нет, а на чистой машине это постоянное состояние — неограниченный
# цикл там завис бы. Перечисление — через `-S INPUT`, как в delete_marked_rules.
fw_remove_input_tcp() {
    local port="$1" attempt listing
    for attempt in 1 2 3 4 5 6 7 8; do
        listing="$(iptables -w "$XT_WAIT" -S INPUT 2>/dev/null || true)"
        printf '%s\n' "$listing" | \
            grep -Eq -- "^-A INPUT .*--dport ${port}([[:space:]]|$).*${IPT_COMMENT}" || return 0
        if iptables -w "$XT_WAIT" -D INPUT -p tcp --dport "$port" \
            -m comment --comment "$IPT_COMMENT" -j ACCEPT >>"$LOG_FILE" 2>&1; then
            return 0
        fi
    done
    log_warn "Не удалось закрыть TCP/${port} в iptables за 8 попыток: правило могло остаться"
    return 1
}

# В доменном режиме панель уезжает на 443, в IP-режиме — обратно на 46002.
fw_close_unused_panel_port() {
    case "${PANEL_MODE:-}" in
        domain) fw_remove_input_tcp "46002" || true ;;
        ip) fw_remove_input_tcp "443" || true ;;
    esac
}

fw_add_forward() {
    ipt_add_or_ensure filter INPUT -i "$CSQTT_IFACE" -s "10.66.67.0/24" -m comment --comment "$IPT_COMMENT" -j ACCEPT || \
        die "Не удалось установить INPUT -i $CSQTT_IFACE"
    ipt_add_or_ensure filter FORWARD -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT || true
    ipt_add_or_ensure filter FORWARD -i "$CSQTT_IFACE" -m comment --comment "$IPT_COMMENT" -j ACCEPT || \
        die "Не удалось установить FORWARD -i $CSQTT_IFACE"
    ipt_add_or_ensure filter FORWARD -o "$CSQTT_IFACE" -m comment --comment "$IPT_COMMENT" -j ACCEPT || \
        die "Не удалось установить FORWARD -o $CSQTT_IFACE"
}

fw_add_masquerade() {
    local iface="$1" subnet="$2"
    if [ -n "$iface" ]; then
        iptables -w "$XT_WAIT" -t nat -C POSTROUTING -s "$subnet" -o "$iface" -m comment --comment "$IPT_COMMENT" -j MASQUERADE >/dev/null 2>&1 || \
            iptables -w "$XT_WAIT" -t nat -A POSTROUTING -s "$subnet" -o "$iface" -m comment --comment "$IPT_COMMENT" -j MASQUERADE >>"$LOG_FILE" 2>&1 || true
        local wan_ip
        wan_ip="$(ip -4 -o addr show dev "$iface" scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -n 1)"
        if [ -n "$wan_ip" ]; then
            iptables -w "$XT_WAIT" -t nat -C POSTROUTING -s "$subnet" -o "$iface" -m comment --comment "$IPT_COMMENT" -j SNAT --to-source "$wan_ip" >/dev/null 2>&1 || \
                iptables -w "$XT_WAIT" -t nat -I POSTROUTING 1 -s "$subnet" -o "$iface" -m comment --comment "$IPT_COMMENT" -j SNAT --to-source "$wan_ip" >>"$LOG_FILE" 2>&1 || true
        fi
    fi
    iptables -w "$XT_WAIT" -t nat -C POSTROUTING -s "$subnet" ! -o "$CSQTT_IFACE" -m comment --comment "$IPT_COMMENT" -j MASQUERADE >/dev/null 2>&1 || \
        iptables -w "$XT_WAIT" -t nat -A POSTROUTING -s "$subnet" ! -o "$CSQTT_IFACE" -m comment --comment "$IPT_COMMENT" -j MASQUERADE >>"$LOG_FILE" 2>&1 || \
        die "Не удалось установить NAT MASQUERADE"
}

fw_add_mss_clamping() {
    local subnet="$1"
    ipt_add_or_ensure mangle FORWARD -s "$subnet" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu || \
        die "Не удалось установить исходящий TCP MSS clamping"
    ipt_add_or_ensure mangle FORWARD -d "$subnet" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu || \
        die "Не удалось установить входящий TCP MSS clamping"
}

delete_marked_rules() {
    local table="$1" chain="$2" marker="$3" i numbers number
    local -a targs=()
    [ "$table" = "filter" ] || targs=(-t "$table")
    for i in 1 2 3 4 5 6 7 8; do
        numbers="$(iptables -w "$XT_WAIT" "${targs[@]}" -S "$chain" 2>/dev/null | awk -v chain="$chain" -v marker="$marker" '
$1 == "-A" && $2 == chain {
    n++
    if (index($0, marker)) hit[++h] = n
}
END {
    for (i = h; i >= 1; i--) print hit[i]
}
')" || return 0
        [ -n "$numbers" ] || break
        while IFS= read -r number; do
            [ -n "$number" ] && iptables -w "$XT_WAIT" "${targs[@]}" -D "$chain" "$number" >/dev/null 2>&1 || true
        done <<EOF
$numbers
EOF
    done
}

cleanup_csqtt_netfilter_rules() {
    local table chain marker
    for marker in "$IPT_COMMENT" CSQTT_MIRRORED CSQTT_TPROXY CSQTT_LOCAL_SOCKS CSQTT_LOCAL_SOCKS_MARK CSQTT_SOCKS CSQTT_CASCADE_NO_QUIC; do
        for table in filter nat mangle; do
            for chain in INPUT FORWARD PREROUTING POSTROUTING OUTPUT; do
                delete_marked_rules "$table" "$chain" "$marker"
            done
        done
    done
}

secure_persistent_state() {
    [ -d "$CSQTT_CONFIG_DIR" ] || return 0
    chmod 700 "$CSQTT_CONFIG_DIR" 2>/dev/null || true
    [ -d "$CSQTT_RUNTIME_ARCHIVE_DIR" ] && chmod 700 "$CSQTT_RUNTIME_ARCHIVE_DIR" 2>/dev/null || true

    local file
    for file in \
        "$CSQTT_DATABASE_FILE" \
        "$CSQTT_DATABASE_WAL_FILE" \
        "$CSQTT_DATABASE_SHM_FILE" \
        "$CSQTT_LEGACY_MIGRATION_JSON" \
        "$CSQTT_LEGACY_MIGRATION_IMPORTED_JSON"; do
        [ -f "$CSQTT_CONFIG_DIR/$file" ] && chmod 600 "$CSQTT_CONFIG_DIR/$file" 2>/dev/null || true
    done
    [ -f "$CSQTT_ENV_FILE" ] && chmod 600 "$CSQTT_ENV_FILE" 2>/dev/null || true
    [ -f "$CSQTT_DEPLOY_OVERRIDES_FILE" ] && chmod 600 "$CSQTT_DEPLOY_OVERRIDES_FILE" 2>/dev/null || true
    [ -f "$CSQTT_CONFIG_DIR/web_cert.pem" ] && chmod 644 "$CSQTT_CONFIG_DIR/web_cert.pem" 2>/dev/null || true
    [ -f "$CSQTT_CONFIG_DIR/web_key.pem" ] && chmod 600 "$CSQTT_CONFIG_DIR/web_key.pem" 2>/dev/null || true
    [ -f "$CSQTT_LE_STATE_FILE" ] && chmod 600 "$CSQTT_LE_STATE_FILE" 2>/dev/null || true
}

ensure_csqtt_directory() {
    local path="$1" mode="$2"
    if [ -L "$path" ] || [ ! -d "$path" ]; then
        rm -rf -- "$path" || die "Не удалось очистить путь CSQTT: $path"
    fi
    mkdir -p -- "$path" || die "Не удалось подготовить каталог CSQTT: $path"
    chmod "$mode" "$path" || die "Не удалось установить права на каталог CSQTT: $path"
}

# Каталог данных нельзя пересоздавать: владелец мог вынести его на другой диск
# симлинком, и rm -rf по ссылке уничтожил бы саму ссылку, оставив данные «в
# стороне». Здесь ничего не удаляется никогда.
ensure_persistent_directory() {
    local path="$1" mode="$2" resolved
    if [ -L "$path" ]; then
        resolved="$(readlink -f -- "$path" 2>/dev/null || true)"
        [ -n "$resolved" ] || \
            die "Не удалось разрешить символическую ссылку каталога данных CSQTT: $path"
        [ -d "$resolved" ] || \
            die "Символическая ссылка каталога данных CSQTT ведёт в несуществующий путь: $path -> $resolved"
        path="$resolved"
    fi
    if [ -e "$path" ] && [ ! -d "$path" ]; then
        die "Путь каталога данных CSQTT существует, но это не каталог: $path"
    fi
    mkdir -p -- "$path" || die "Не удалось подготовить каталог CSQTT: $path"
    chmod "$mode" "$path" || die "Не удалось установить права на каталог CSQTT: $path"
}

clear_runtime_config_preserving_database() {
    local entry base
    ensure_persistent_directory "$CSQTT_CONFIG_DIR" 700
    shopt -s nullglob dotglob
    for entry in "$CSQTT_CONFIG_DIR"/*; do
        base="${entry##*/}"
        case "$base" in
            "$CSQTT_DATABASE_FILE"|"$CSQTT_DATABASE_WAL_FILE"|"$CSQTT_DATABASE_SHM_FILE"|"$CSQTT_LEGACY_MIGRATION_JSON"|"$CSQTT_LEGACY_MIGRATION_IMPORTED_JSON"|web_cert.pem|web_key.pem|letsencrypt-ip.env|letsencrypt.env|runtime-archive) continue ;;
        esac
        rm -rf -- "$entry"
    done
    shopt -u nullglob dotglob
    secure_persistent_state
}

remove_managed_work_dir() {
    local path="$1"
    case "$path" in
        /tmp/csqtt-docker.*)
            rm -rf -- "$path"
            ;;
        "")
            ;;
        *)
            log_warn "Отказ от удаления неожиданного временного пути: $path"
            return 1
            ;;
    esac
}

cleanup_deploy_uploads() {
    local docker_context="$DOCKER_CONTEXT_DIR"
    DOCKER_CONTEXT_DIR=""
    remove_managed_work_dir "$docker_context" || true
    if command -v docker >/dev/null 2>&1 && [ -n "$CSQTT_DOCKER_CANDIDATE_IMAGE" ]; then
        docker image rm "$CSQTT_DOCKER_CANDIDATE_IMAGE" >/dev/null 2>&1 || true
        CSQTT_DOCKER_CANDIDATE_IMAGE=""
    fi
    rm -f -- "$UPLOAD_BINARY" "$UPLOAD_ENV_FILE" "$UPLOAD_OVERRIDES_FILE"
}

validate_candidate_environment() {
    local candidate_env="$1" candidate_overrides="$2"
    grep -Eq '^[[:space:]]*(export[[:space:]]+)?CSQTT_WEB_USER=.+$' "$candidate_env" || \
        die "В переданной WEB-конфигурации отсутствует CSQTT_WEB_USER" "$EXIT_PREFLIGHT_FAILED"
    grep -Eq '^[[:space:]]*(export[[:space:]]+)?CSQTT_WEB_PASS=.+$' "$candidate_env" || \
        die "В переданной WEB-конфигурации отсутствует CSQTT_WEB_PASS" "$EXIT_PREFLIGHT_FAILED"
    grep -q '"main_password"' "$candidate_overrides" || \
        die "В deploy-конфигурации отсутствует main_password" "$EXIT_PREFLIGHT_FAILED"
}

prepare_uploaded_release() {
    DEPLOY_PHASE="preflight"
    [ -f "$UPLOAD_BINARY" ] && [ -s "$UPLOAD_BINARY" ] || \
        die "Новый бинарник не загружен или пуст" "$EXIT_PREFLIGHT_FAILED"
    [ -f "$UPLOAD_ENV_FILE" ] && [ -s "$UPLOAD_ENV_FILE" ] || \
        die "Одноразовая WEB-конфигурация не загружена" "$EXIT_PREFLIGHT_FAILED"
    [ -f "$UPLOAD_OVERRIDES_FILE" ] && [ -s "$UPLOAD_OVERRIDES_FILE" ] || \
        die "Одноразовая deploy-конфигурация не загружена" "$EXIT_PREFLIGHT_FAILED"

    chmod 0755 "$UPLOAD_BINARY" || die "Не удалось подготовить загруженный бинарник csqtt" "$EXIT_PREFLIGHT_FAILED"
    chmod 0600 "$UPLOAD_ENV_FILE" "$UPLOAD_OVERRIDES_FILE" || \
        die "Не удалось защитить загруженную конфигурацию" "$EXIT_PREFLIGHT_FAILED"
    validate_candidate_environment "$UPLOAD_ENV_FILE" "$UPLOAD_OVERRIDES_FILE"
    [ -x "$UPLOAD_BINARY" ] || die "Загруженный бинарник CSQTT не исполняемый" "$EXIT_PREFLIGHT_FAILED"
    log_info "Загруженный бинарник csqtt проверен"
    log_info "Загруженный бинарник и конфигурация проверены до остановки текущего сервиса"
}

finish_deployment() {
    if command -v docker >/dev/null 2>&1 && [ -n "$CSQTT_DOCKER_CANDIDATE_IMAGE" ]; then
        docker image rm "$CSQTT_DOCKER_CANDIDATE_IMAGE" >/dev/null 2>&1 || \
            log_warn "Не удалось снять временный Docker tag $CSQTT_DOCKER_CANDIDATE_IMAGE"
    fi
    cleanup_deploy_uploads
}

cleanup_legacy_proxy_interfaces() {
    local iface
    for iface in $(ip -o link show 2>/dev/null | awk -F': ' '{print $2}' | grep '^csqtp' || true); do
        timeout 2 ip link del "$iface" 2>/dev/null || true
    done
}

remove_csqtt_tun_interface() {
    local attempt waited=0
    if ! ip link show "$CSQTT_IFACE" >/dev/null 2>&1; then
        return 0
    fi

    log_warn "Обнаружен остаточный TUN-интерфейс $CSQTT_IFACE; выполняется восстановление runtime"
    for attempt in 1 2 3 4; do
        if timeout 2 ip link del "$CSQTT_IFACE" >>"$LOG_FILE" 2>&1; then
            waited=0
            while [ "$waited" -lt 20 ]; do
                if ! ip link show "$CSQTT_IFACE" >/dev/null 2>&1; then
                    log_info "Остаточный TUN-интерфейс $CSQTT_IFACE удалён"
                    return 0
                fi
                sleep 0.1
                waited=$((waited + 1))
            done
        fi
        sleep 0.2
    done

    ip -d link show "$CSQTT_IFACE" 2>&1 | tee -a "$LOG_FILE" >&2 || true
    return 1
}

csqtt_cleanup() {
    prog 0.15 "Очистка..."
    echo "🧹 Переключение со старой установки CSQTT..."

    # Таймер продления живёт рядом с хелпером и переживает снос runtime: старый
    # юнит не попадает в list_csqtt_systemd_units (там только *.service за
    # вычетом LE), а rm -rf /usr/local/lib/csqtt не трогает сам хелпер. Пока идёт
    # переключение, он может выстрелить и сразиться за порт 80 либо потратить
    # выпуск. Останавливаем до любых изменений; включает его обратно
    # install_letsencrypt_renewal_timer в конце установки.
    stop_letsencrypt_renewal_timer

    local had_old_install=0 managed_systemd_unit=0
    if csqtt_systemd_unit_is_managed; then
        managed_systemd_unit=1
    fi
    if [ -e /usr/local/bin/csqtt ] || [ -d /usr/local/lib/csqtt ] || \
       [ "$managed_systemd_unit" -eq 1 ] || csqtt_docker_container_exists || \
       csqtt_persistent_state_exists || ip link show "$CSQTT_IFACE" >/dev/null 2>&1 || \
       csqtt_peer_port_is_owned; then
        had_old_install=1
    fi

    remove_all_csqtt_docker_containers

    if command -v systemctl >/dev/null 2>&1; then
        stop_csqtt_docker_prereq
        stop_all_running_csqtt_systemd_units
    fi

    force_stop_csqtt_processes || die "Не удалось завершить процессы CSQTT"
    remove_all_csqtt_systemd_units
    [ -e "/etc/systemd/system/${CSQTT_DOCKER_PREREQ_SERVICE}" ] && SYSTEMD_NEEDS_RELOAD=1
    rm -f "/etc/systemd/system/${CSQTT_DOCKER_PREREQ_SERVICE}"
    rm -f "/etc/systemd/system/docker.service.requires/${CSQTT_DOCKER_PREREQ_SERVICE}"
    rm -f "/etc/systemd/system/docker.service.wants/${CSQTT_DOCKER_PREREQ_SERVICE}"
    if [ "$SYSTEMD_NEEDS_RELOAD" -eq 1 ] && command -v systemctl >/dev/null 2>&1; then
        systemctl daemon-reload || die "systemctl daemon-reload завершился ошибкой после удаления старого runtime"
        SYSTEMD_NEEDS_RELOAD=0
    fi
    rm -f /usr/local/bin/csqtt
    rm -rf /usr/local/lib/csqtt
    clear_runtime_config_preserving_database

    assert_peer_port_is_available

    cleanup_legacy_proxy_interfaces
    remove_csqtt_tun_interface || die "Не удалось освободить TUN-интерфейс $CSQTT_IFACE; переключение отменено"
    if [ "$had_old_install" -eq 1 ]; then
        cleanup_csqtt_netfilter_rules || true
    fi

    echo "✓ Старый runtime удалён; SQLite/legacy JSON state сохранён"
}

setup_sysctl() {
    prog 0.25 "Sysctl..."
    echo "⚙️  Настройка сетевых параметров..."

    modprobe tcp_bbr 2>/dev/null || true
    if [ -d /etc/modules-load.d ]; then
        echo "tcp_bbr" > /etc/modules-load.d/bbr.conf 2>/dev/null || true
    fi

    echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true
    echo 2 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true
    echo 2 > /proc/sys/net/ipv4/conf/default/rp_filter 2>/dev/null || true
    echo 0 > /proc/sys/net/ipv6/conf/all/disable_ipv6 2>/dev/null || true
    echo 0 > /proc/sys/net/ipv6/conf/default/disable_ipv6 2>/dev/null || true
    echo 1 > /proc/sys/net/ipv6/conf/all/forwarding 2>/dev/null || true
    echo 1 > /proc/sys/net/ipv6/conf/default/forwarding 2>/dev/null || true
    echo 2 > /proc/sys/net/ipv6/conf/all/accept_ra 2>/dev/null || true
    echo 2 > /proc/sys/net/ipv6/conf/default/accept_ra 2>/dev/null || true
    for dev in /proc/sys/net/ipv6/conf/*; do
        if [ -d "$dev" ]; then
            echo 0 > "$dev/disable_ipv6" 2>/dev/null || true
            echo 2 > "$dev/accept_ra" 2>/dev/null || true
        fi
    done
    mkdir -p /etc/sysctl.d
    cat > "$CSQTT_SYSCTL_FILE" << 'SYSEOF'
net.ipv4.ip_forward = 1
net.ipv4.conf.all.rp_filter = 2
net.ipv4.conf.default.rp_filter = 2
net.ipv6.conf.all.disable_ipv6 = 0
net.ipv6.conf.default.disable_ipv6 = 0
net.ipv6.conf.all.forwarding = 1
net.ipv6.conf.default.forwarding = 1
net.ipv6.conf.all.accept_ra = 2
net.ipv6.conf.default.accept_ra = 2
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
net.core.netdev_max_backlog = 10000
net.core.somaxconn = 65535
net.ipv4.tcp_rmem = 4096 131072 67108864
net.ipv4.tcp_wmem = 4096 131072 67108864
net.ipv4.tcp_mtu_probing = 1
net.ipv4.tcp_slow_start_after_idle = 0
net.ipv4.tcp_window_scaling = 1
net.ipv4.tcp_sack = 1
net.ipv4.tcp_timestamps = 1
net.ipv4.tcp_fastopen = 3
SYSEOF

    cat > "$CSQTT_UDP_SYSCTL_FILE" << 'SYSEOF'
net.core.rmem_max = 67108864
net.core.wmem_max = 67108864
SYSEOF

    sysctl -p "$CSQTT_SYSCTL_FILE" >>"$LOG_FILE" 2>&1 || log_warn "Не удалось применить обязательные параметры маршрутизации"
    sysctl -p "$CSQTT_UDP_SYSCTL_FILE" >>"$LOG_FILE" 2>&1 || log_warn "Ядро ограничило лимиты сетевых буферов; сервер продолжит работу с доступными значениями"

    [ "$(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null || echo 0)" = "1" ] || \
        die "IPv4 forwarding невозможно включить на этом VPS"

    echo "✓ Sysctl настроен (IPv4/IPv6 forwarding + BBR + буферы)"
}

tune_cpu_mitigations() {
    if [ -f /etc/default/grub ]; then
        if ! grep -q "mitigations=off" /etc/default/grub; then
            sed -i 's/\(GRUB_CMDLINE_LINUX_DEFAULT="[^"]*\)/\1 mitigations=off nowatchdog/' /etc/default/grub 2>/dev/null || true
            update-grub >>"$LOG_FILE" 2>&1 || grub2-mkconfig -o /boot/grub2/grub.cfg >>"$LOG_FILE" 2>&1 || true
            log_info "Оптимизация CPU: mitigations=off добавлено в GRUB"
        fi
    fi
}

setup_nat_and_firewall() {
    prog 0.40 "NAT + Firewall..."
    echo "🛡  Настройка NAT и фаервола..."

    local iface
    iface=$(detect_wan_interface)

    if [ -z "$iface" ]; then
        die "Не удалось определить WAN-интерфейс для NAT подсети 10.66.67.0/24"
    fi
    ip link show "$iface" >/dev/null 2>&1 || die "Определённый WAN-интерфейс $iface не существует"

    log_info "WAN-интерфейс: $iface"
    sysctl -w "net.ipv4.conf.${iface}.rp_filter=2" >>"$LOG_FILE" 2>&1 || true
    sysctl -w "net.ipv6.conf.${iface}.disable_ipv6=0" >>"$LOG_FILE" 2>&1 || true
    sysctl -w "net.ipv6.conf.${iface}.accept_ra=2" >>"$LOG_FILE" 2>&1 || true
    sysctl -w "net.ipv6.conf.${iface}.forwarding=1" >>"$LOG_FILE" 2>&1 || true
    command -v netplan >/dev/null 2>&1 && netplan apply >>"$LOG_FILE" 2>&1 || true

    local gw_ip
    gw_ip="$(ip -4 route show default 2>/dev/null | awk '/default/ {for(i=1;i<=NF;i++) if($i=="via") {print $(i+1); break}}')"
    if [ -n "$gw_ip" ]; then
        ip route replace "$gw_ip" dev "$iface" scope link >>"$LOG_FILE" 2>&1 || true
    fi

    fw_close_unused_panel_port
    fw_add_input_udp "$PEER_PORT"
    fw_add_input_tcp "$WEB_PORT"
    fw_add_input_tcp "$LE_HTTP_PORT"

    fw_add_forward

    fw_add_masquerade "$iface" "10.66.67.0/24"
    
    fw_add_mss_clamping "10.66.67.0/24"

    echo "✓ NAT: MASQUERADE на $iface для 10.66.67.0/24"
    echo "✓ Порты CSQTT: ${PEER_PORT}/udp(PEER), ${WEB_PORT}/tcp(WEB: $(panel_web_mode_label)), ${LE_HTTP_PORT}/tcp(LE)"
    echo "✓ TCP MSS Clamping включен"
}

write_network_helper() {
    local target="$1" stale_web_port=""
    case "${PANEL_MODE:-}" in
        domain) stale_web_port="46002" ;;
        ip) stale_web_port="443" ;;
    esac
    cat > "$target" << NETEOF
#!/bin/sh
set -eu
    PEER_PORT="$PEER_PORT"
WEB_PORT="$WEB_PORT"
STALE_WEB_PORT="$stale_web_port"
LE_HTTP_PORT="$LE_HTTP_PORT"
CSQTT_IFACE="$CSQTT_IFACE"
IPT_COMMENT="$IPT_COMMENT"
SUBNET="10.66.67.0/24"
SUBNET6="fd66:67::/64"
XT_WAIT="$XT_WAIT"
CSQTT_RUNTIME_DIR="$CSQTT_RUNTIME_DIR"
NETWORK_READY_FILE="$CSQTT_DOCKER_NETWORK_READY"

command -v ip >/dev/null 2>&1 || exit 20
command -v iptables >/dev/null 2>&1 || exit 21
[ -w /proc/sys/net/ipv4/ip_forward ] && echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true
[ -w /proc/sys/net/ipv6/conf/all/forwarding ] && echo 1 > /proc/sys/net/ipv6/conf/all/forwarding 2>/dev/null || true
[ -w /proc/sys/net/ipv4/conf/all/rp_filter ] && echo 2 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null || true
[ -w /proc/sys/net/ipv4/conf/default/rp_filter ] && echo 2 > /proc/sys/net/ipv4/conf/default/rp_filter 2>/dev/null || true
[ -w /proc/sys/net/ipv4/tcp_notsent_lowat ] && echo 131072 > /proc/sys/net/ipv4/tcp_notsent_lowat 2>/dev/null || true

is_ignored_wan_interface() {
    ignored_iface="\${1%%@*}"
    case "\$ignored_iface" in
        ""|lo|"\$CSQTT_IFACE"|csqtp*|tun*|tap*|wg*|warp*|Warp*|WARP*|CloudflareWARP*|tailscale*|zt*|docker*|br-*|veth*|cni*|flannel*|virbr*|podman*|kube*|dummy*|ifb*)
            return 0
            ;;
    esac
    return 1
}

detect_wan_interface() {
    fallback=""
    for iface in \$(ip -o -4 route show default 2>/dev/null | awk '{for(i=1;i<=NF;i++) if(\$i=="dev") {print \$(i+1); break}}'); do
        iface="\${iface%%@*}"
        [ -n "\$iface" ] || continue
        [ -n "\$fallback" ] || fallback="\$iface"
        if ! is_ignored_wan_interface "\$iface" && ip link show "\$iface" >/dev/null 2>&1; then
            echo "\$iface"
            return 0
        fi
    done
    for iface in \$(ip -o -4 addr show scope global 2>/dev/null | awk '{sub(/@.*/, "", \$2); print \$2}'); do
        iface="\${iface%%@*}"
        [ -n "\$iface" ] || continue
        [ -n "\$fallback" ] || fallback="\$iface"
        if ! is_ignored_wan_interface "\$iface" && ip link show "\$iface" >/dev/null 2>&1; then
            echo "\$iface"
            return 0
        fi
    done
    for iface in \$(ls /sys/class/net/ 2>/dev/null || true); do
        iface="\${iface%%@*}"
        [ -n "\$iface" ] || continue
        [ "\$iface" = "lo" ] && continue
        [ -n "\$fallback" ] || fallback="\$iface"
        if ! is_ignored_wan_interface "\$iface" && ip link show "\$iface" >/dev/null 2>&1; then
            echo "\$iface"
            return 0
        fi
    done
    [ -n "\$fallback" ] && echo "\$fallback"
}

WAN_IFACE=""
network_waited=0
while [ "\$network_waited" -lt 90 ]; do
    candidate_iface=\$(detect_wan_interface || true)
    if [ -n "\$candidate_iface" ] && ip link show "\$candidate_iface" >/dev/null 2>&1 && \
       ip -o -4 route show default 2>/dev/null | grep -q .; then
        WAN_IFACE="\$candidate_iface"
        break
    fi
    network_waited=\$((network_waited + 1))
    sleep 1
done
[ -n "\$WAN_IFACE" ] || exit 22
[ -w "/proc/sys/net/ipv4/conf/\$WAN_IFACE/rp_filter" ] && echo 2 > "/proc/sys/net/ipv4/conf/\$WAN_IFACE/rp_filter" 2>/dev/null || true
[ -w "/proc/sys/net/ipv4/conf/\$CSQTT_IFACE/rp_filter" ] && echo 2 > "/proc/sys/net/ipv4/conf/\$CSQTT_IFACE/rp_filter" 2>/dev/null || true

# Для /32 сетей с onlink-шлюзом обеспечиваем явный host route к шлюзу
gw=\$(ip -4 route show default 2>/dev/null | awk '/default/ {for(i=1;i<=NF;i++) if(\$i=="via") {print \$(i+1); break}}')
if [ -n "\$gw" ]; then
    ip route replace "\$gw" dev "\$WAN_IFACE" scope link 2>/dev/null || true
fi

if ip link show "\$CSQTT_IFACE" >/dev/null 2>&1; then
    ip link set dev "\$CSQTT_IFACE" txqueuelen 1000 2>/dev/null || true
fi

ipt() { iptables -w "\$XT_WAIT" "\$@"; }
# Порт, который в текущем режиме больше не используется, закрывается явно: без
# этого docker-режим пережил бы переключение со старым правилом на месте. Строки
# условны на момент генерации (STALE_WEB_PORT пуст в неизвестном режиме), цикл
# ограничен 8 попытками, синтаксис — POSIX: сгенерированный скрипт это /bin/sh.
if [ -n "\$STALE_WEB_PORT" ]; then
    stale_attempt=1
    while [ "\$stale_attempt" -le 8 ]; do
        stale_listing=\$(ipt -S INPUT 2>/dev/null || true)
        printf '%s\\n' "\$stale_listing" | \
            grep -Eq -- "^-A INPUT .*--dport \${STALE_WEB_PORT}([[:space:]]|\$).*\$IPT_COMMENT" || break
        if ipt -D INPUT -p tcp --dport "\$STALE_WEB_PORT" -m comment --comment "\$IPT_COMMENT" -j ACCEPT >/dev/null 2>&1; then
            break
        fi
        stale_attempt=\$((stale_attempt + 1))
    done
fi
ipt -C INPUT -p udp --dport "\$PEER_PORT" -m comment --comment "\$IPT_COMMENT" -j ACCEPT 2>/dev/null || ipt -I INPUT -p udp --dport "\$PEER_PORT" -m comment --comment "\$IPT_COMMENT" -j ACCEPT
ipt -C INPUT -p tcp --dport "\$WEB_PORT" -m comment --comment "\$IPT_COMMENT" -j ACCEPT 2>/dev/null || ipt -I INPUT -p tcp --dport "\$WEB_PORT" -m comment --comment "\$IPT_COMMENT" -j ACCEPT
ipt -C INPUT -p tcp --dport "\$LE_HTTP_PORT" -m comment --comment "\$IPT_COMMENT" -j ACCEPT 2>/dev/null || ipt -I INPUT -p tcp --dport "\$LE_HTTP_PORT" -m comment --comment "\$IPT_COMMENT" -j ACCEPT
ipt -C INPUT -i "\$CSQTT_IFACE" -s "\$SUBNET" -m comment --comment "\$IPT_COMMENT" -j ACCEPT 2>/dev/null || ipt -I INPUT -i "\$CSQTT_IFACE" -s "\$SUBNET" -m comment --comment "\$IPT_COMMENT" -j ACCEPT
ipt -C FORWARD -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || ipt -I FORWARD 1 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
ipt -C FORWARD -i "\$CSQTT_IFACE" -m comment --comment "\$IPT_COMMENT" -j ACCEPT 2>/dev/null || ipt -I FORWARD -i "\$CSQTT_IFACE" -m comment --comment "\$IPT_COMMENT" -j ACCEPT
ipt -C FORWARD -o "\$CSQTT_IFACE" -m comment --comment "\$IPT_COMMENT" -j ACCEPT 2>/dev/null || ipt -I FORWARD -o "\$CSQTT_IFACE" -m comment --comment "\$IPT_COMMENT" -j ACCEPT
ipt -t nat -C POSTROUTING -s "\$SUBNET" -o "\$WAN_IFACE" -m comment --comment "\$IPT_COMMENT" -j MASQUERADE 2>/dev/null || ipt -t nat -A POSTROUTING -s "\$SUBNET" -o "\$WAN_IFACE" -m comment --comment "\$IPT_COMMENT" -j MASQUERADE || true
ipt -t nat -C POSTROUTING -s "\$SUBNET" ! -o "\$CSQTT_IFACE" -m comment --comment "\$IPT_COMMENT" -j MASQUERADE 2>/dev/null || ipt -t nat -A POSTROUTING -s "\$SUBNET" ! -o "\$CSQTT_IFACE" -m comment --comment "\$IPT_COMMENT" -j MASQUERADE
WAN_IP="\$(ip -4 -o addr show dev "\$WAN_IFACE" scope global 2>/dev/null | awk '{print \$4}' | cut -d/ -f1 | head -n 1)"
if [ -n "\$WAN_IP" ]; then
    ipt -t nat -C POSTROUTING -s "\$SUBNET" -o "\$WAN_IFACE" -m comment --comment "\$IPT_COMMENT" -j SNAT --to-source "\$WAN_IP" 2>/dev/null || \
    ipt -t nat -I POSTROUTING 1 -s "\$SUBNET" -o "\$WAN_IFACE" -m comment --comment "\$IPT_COMMENT" -j SNAT --to-source "\$WAN_IP" 2>/dev/null || true
fi
ipt -t mangle -C FORWARD -s "\$SUBNET" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "\$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || ipt -t mangle -I FORWARD -s "\$SUBNET" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "\$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu
ipt -t mangle -C FORWARD -d "\$SUBNET" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "\$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || ipt -t mangle -I FORWARD -d "\$SUBNET" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "\$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu

if command -v ip6tables >/dev/null 2>&1 && ip -6 route show default 2>/dev/null | grep -q .; then
    ip6t() { ip6tables -w "\$XT_WAIT" "\$@"; }
    ip6t -C FORWARD -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || ip6t -I FORWARD 1 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
    ip6t -C FORWARD -i "\$CSQTT_IFACE" -m comment --comment "\$IPT_COMMENT" -j ACCEPT 2>/dev/null || ip6t -I FORWARD -i "\$CSQTT_IFACE" -m comment --comment "\$IPT_COMMENT" -j ACCEPT
    ip6t -C FORWARD -o "\$CSQTT_IFACE" -m comment --comment "\$IPT_COMMENT" -j ACCEPT 2>/dev/null || ip6t -I FORWARD -o "\$CSQTT_IFACE" -m comment --comment "\$IPT_COMMENT" -j ACCEPT
    ip6t -t nat -C POSTROUTING -s "\$SUBNET6" ! -o "\$CSQTT_IFACE" -m comment --comment "\$IPT_COMMENT" -j MASQUERADE 2>/dev/null || ip6t -t nat -A POSTROUTING -s "\$SUBNET6" ! -o "\$CSQTT_IFACE" -m comment --comment "\$IPT_COMMENT" -j MASQUERADE || true
    ip6t -t mangle -C FORWARD -s "\$SUBNET6" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "\$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || ip6t -t mangle -I FORWARD -s "\$SUBNET6" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "\$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true
    ip6t -t mangle -C FORWARD -d "\$SUBNET6" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "\$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || ip6t -t mangle -I FORWARD -d "\$SUBNET6" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "\$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true
fi
umask 077
mkdir -p "\$CSQTT_RUNTIME_DIR"
: > "\$NETWORK_READY_FILE"
NETEOF
    chmod 0755 "$target"
}

write_tun_recovery_helper() {
    local target="$1"
    cat > "$target" << RECOVEREOF
#!/bin/sh
set -eu
CSQTT_IFACE="$CSQTT_IFACE"
PEER_PORT="$PEER_PORT"
CSQTT_CONFIG_DIR="$CSQTT_CONFIG_DIR"

peer_port_is_busy() {
    ss -H -lun "sport = :\$PEER_PORT" 2>/dev/null | grep -q .
}

peer_port_pids() {
    ss -H -lunp "sport = :\$PEER_PORT" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -un || true
}

csqtt_process_is_owned() {
    pid="\$1"
    [ -r "/proc/\$pid/cmdline" ] || return 1
    executable="\$(readlink "/proc/\$pid/exe" 2>/dev/null || true)"
    case "\$executable" in
        /usr/local/bin/csqtt|'/usr/local/bin/csqtt (deleted)'|/usr/local/lib/csqtt/*|'/usr/local/lib/csqtt/'*' (deleted)') return 0 ;;
    esac
    command_line="\$({ tr '\\0' ' ' < "/proc/\$pid/cmdline"; } 2>/dev/null || true)"
    case " \$command_line " in
        *" --config-dir \$CSQTT_CONFIG_DIR "*|*" /usr/local/bin/csqtt "*|*" /usr/local/lib/csqtt/"*) return 0 ;;
        *) return 1 ;;
    esac
}

release_owned_peer_port() {
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        peer_port_is_busy || return 0
        pids="\$(peer_port_pids)"
        if [ -z "\$pids" ]; then
            echo "[CSQTT] UDP/\$PEER_PORT is occupied by a runtime without visible PID" >&2
            ss -H -lunp "sport = :\$PEER_PORT" >&2 || true
            return 24
        fi
        for pid in \$pids; do
            if [ "\$attempt" -le 2 ]; then
                echo "[CSQTT] UDP/\$PEER_PORT runtime PID \$pid; terminating" >&2
                kill -TERM "\$pid" 2>/dev/null || true
            else
                echo "[CSQTT] UDP/\$PEER_PORT runtime PID \$pid; killing" >&2
                kill -KILL "\$pid" 2>/dev/null || true
            fi
        done
        sleep 0.2
    done
    echo "[CSQTT] UDP/\$PEER_PORT did not release" >&2
    ss -H -lunp "sport = :\$PEER_PORT" >&2 || true
    return 24
}

release_owned_peer_port || exit \$?

if ! ip link show "\$CSQTT_IFACE" >/dev/null 2>&1; then
    exit 0
fi

echo "[CSQTT] stale TUN interface \$CSQTT_IFACE detected; recovering runtime" >&2
for attempt in 1 2 3 4; do
    timeout 2 ip link del "\$CSQTT_IFACE" >/dev/null 2>&1 || true
    if ! ip link show "\$CSQTT_IFACE" >/dev/null 2>&1; then
        echo "[CSQTT] stale TUN interface \$CSQTT_IFACE removed" >&2
        exit 0
    fi
    sleep 0.2
done

ip -d link show "\$CSQTT_IFACE" >&2 || true
echo "[CSQTT] unable to release TUN interface \$CSQTT_IFACE" >&2
exit 23
RECOVEREOF
    chmod 0755 "$target"
}

install_network_helper() {
    ensure_csqtt_directory /usr/local/lib/csqtt 755
    write_network_helper /usr/local/lib/csqtt/network-up.sh
    write_tun_recovery_helper /usr/local/lib/csqtt/tun-recover.sh
}

verify_configured_network() {
    local iface="${1:-$(detect_wan_interface)}"
    [ -n "$iface" ] || die "WAN-интерфейс не определён при проверке сети"
    [ "$(cat /proc/sys/net/ipv4/ip_forward 2>/dev/null || echo 0)" = "1" ] || die "IPv4 forwarding не включён"
    ip link show "$iface" >/dev/null 2>&1 || die "WAN-интерфейс $iface исчез"
}

setup_csqtt_binary() {
    prog 0.60 "Бинарник..."
    echo "Установка csqtt..."

    install -m 0755 "$UPLOAD_BINARY" /usr/local/bin/csqtt || \
        die "Не удалось установить новый бинарник csqtt"
    echo "✓ csqtt установлен"

    rm -f /usr/local/bin/hev-socks5-tunnel
    echo "✓ SOCKS5-форвардер встроен в csqtt (TPROXY, поднимается при первом использовании)"

    if command -v systemctl >/dev/null 2>&1; then
        systemctl stop csqtt-cascade.service --no-block >/dev/null 2>&1 || true
        timeout 1 systemctl kill --kill-who=all --signal=SIGKILL csqtt-cascade.service >/dev/null 2>&1 || true
    fi
    if [ -e /etc/systemd/system/csqtt-cascade.service ] || [ -L /etc/systemd/system/csqtt-cascade.service ]; then
        SYSTEMD_NEEDS_RELOAD=1
    fi
    rm -f /etc/systemd/system/csqtt-cascade.service
    rm -f /etc/systemd/system/multi-user.target.wants/csqtt-cascade.service
    rm -f /usr/local/bin/csqtt-cascade /usr/local/bin/hev-socks5-server
    rm -f /usr/local/share/csqtt/cascade.sh

    mkdir -p "$CSQTT_CONFIG_DIR"
}

setup_csqtt_environment() {
    ensure_persistent_directory "$CSQTT_CONFIG_DIR" 700
    install -m 0600 "$UPLOAD_ENV_FILE" "$CSQTT_ENV_FILE" || \
        die "Не удалось активировать загруженную WEB-конфигурацию"
    if ! grep -Eq '^[[:space:]]*(export[[:space:]]+)?CSQTT_PUBLIC_IP=.+$' "$CSQTT_ENV_FILE"; then
        local detected_ip="${CSQTT_PUBLIC_IP:-}"
        [ -n "$detected_ip" ] || detected_ip="$(detect_public_ipv4 2>/dev/null || true)"
        if [ -n "$detected_ip" ]; then
            printf 'CSQTT_PUBLIC_IP=%s\n' "$detected_ip" >> "$CSQTT_ENV_FILE"
            log_info "Определен публичный IP сервера: $detected_ip"
        fi
    fi
    install -m 0600 "$UPLOAD_OVERRIDES_FILE" "$CSQTT_DEPLOY_OVERRIDES_FILE" || \
        die "Не удалось активировать загруженную deploy-конфигурацию"
    echo "✓ Конфигурация запуска установлена безопасным EnvironmentFile"
}

LE_CERTBOT_COMMAND=""

is_public_ipv4() {
    local ip="$1" first second third fourth value
    case "$ip" in
        *[!0-9.]*|*..*|.*|*.) return 1 ;;
    esac
    IFS=. read -r first second third fourth <<< "$ip"
    for value in "$first" "$second" "$third" "$fourth"; do
        case "$value" in
            ''|*[!0-9]*) return 1 ;;
        esac
        [ "$value" -le 255 ] || return 1
    done
    [ "$first" -gt 0 ] || return 1
    [ "$first" -lt 224 ] || return 1
    [ "$first" -ne 10 ] || return 1
    [ "$first" -ne 127 ] || return 1
    [ "$first" -ne 0 ] || return 1
    [ "$first" -ne 169 ] || [ "$second" -ne 254 ] || return 1
    [ "$first" -ne 192 ] || [ "$second" -ne 168 ] || return 1
    [ "$first" -ne 172 ] || [ "$second" -lt 16 ] || [ "$second" -gt 31 ] || return 1
    [ "$first" -ne 100 ] || [ "$second" -lt 64 ] || [ "$second" -gt 127 ] || return 1
    [ "$first" -ne 198 ] || [ "$second" -ne 18 ] || return 1
    [ "$first" -ne 198 ] || [ "$second" -ne 19 ] || return 1
    return 0
}

# Зеркало is_public_ipv4: то, что публичная функция отклоняет как «не публичное»,
# здесь классифицируется как частное, чтобы текст отказа был точным. 198.18/19 —
# фактически /15, как и в is_public_ipv4.
is_private_ipv4() {
    local ip="$1" first second third fourth value
    case "$ip" in
        *[!0-9.]*|*..*|.*|*.) return 1 ;;
    esac
    IFS=. read -r first second third fourth <<< "$ip"
    for value in "$first" "$second" "$third" "$fourth"; do
        case "$value" in
            ''|*[!0-9]*) return 1 ;;
        esac
        [ "$value" -le 255 ] || return 1
    done
    [ "$first" -eq 0 ] && return 0
    [ "$first" -eq 10 ] && return 0
    [ "$first" -eq 127 ] && return 0
    [ "$first" -eq 100 ] && [ "$second" -ge 64 ] && [ "$second" -le 127 ] && return 0
    [ "$first" -eq 169 ] && [ "$second" -eq 254 ] && return 0
    [ "$first" -eq 172 ] && [ "$second" -ge 16 ] && [ "$second" -le 31 ] && return 0
    [ "$first" -eq 192 ] && [ "$second" -eq 168 ] && return 0
    [ "$first" -eq 198 ] && { [ "$second" -eq 18 ] || [ "$second" -eq 19 ]; } && return 0
    [ "$first" -ge 224 ] && return 0
    return 1
}

# IDN/punycode не транскодируется осознанно: не-ASCII имя отвергается, человек
# вводит punycode сам. Последняя метка обязана содержать букву — это отсекает
# «1.2.3.4.5», не прошедшее is_public_ipv4.
is_dns_hostname() {
    local name="$1" label last
    [ -n "$name" ] || return 1
    [ "${#name}" -le 253 ] || return 1
    case "$name" in
        *[!A-Za-z0-9.-]*) return 1 ;;
        .*|*.|*..*) return 1 ;;
    esac
    last="${name##*.}"
    case "$last" in
        *[A-Za-z]*) ;;
        *) return 1 ;;
    esac
    local IFS=.
    for label in $name; do
        [ -n "$label" ] || return 1
        [ "${#label}" -le 63 ] || return 1
        case "$label" in
            -*|*-) return 1 ;;
        esac
    done
    return 0
}

# Значение из $UPLOAD_ENV_FILE в systemd-режиме обёрнуто в кавычки и экранировано
# (DeployOperations.kt, systemdEnvironmentValue), в docker-режиме — голое.
panel_host_from_uploaded_env() {
    local value
    [ -f "$UPLOAD_ENV_FILE" ] || return 1
    value="$(grep -E '^[[:space:]]*(export[[:space:]]+)?CSQTT_PUBLIC_IP=' "$UPLOAD_ENV_FILE" 2>/dev/null | head -n 1 | cut -d= -f2- || true)"
    [ -n "$value" ] || return 1
    printf '%s\n' "$value"
}

# Определяет режим панели один раз в начале установки. Приоритет — файл,
# присланный телефоном; переменная окружения нужна для ручного запуска.
resolve_panel_mode() {
    local raw="" host=""
    raw="$(panel_host_from_uploaded_env 2>/dev/null || true)"
    if [ -z "$raw" ]; then
        raw="${CSQTT_PUBLIC_IP:-}"
    fi
    host="$(printf '%s' "$raw" | tr -d '\\' | tr -d '"' | tr -d "'" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"

    if [ -z "$host" ]; then
        die "Поле «IP сервера или домен» не заполнено: укажите публичный IPv4-адрес или доменное имя" "$EXIT_INVALID_ARGUMENT"
    fi

    if is_public_ipv4 "$host"; then
        PANEL_MODE="ip"
        PANEL_HOST="$host"
        WEB_PORT="${CSQTT_WEB_PORT:-46002}"
        LE_RENEW_BEFORE_SECONDS="259200"
        log_info "Режим панели: IP ${PANEL_HOST}, порт ${WEB_PORT}"
        return 0
    fi

    if is_dns_hostname "$host"; then
        PANEL_MODE="domain"
        PANEL_HOST="$host"
        WEB_PORT="443"
        LE_RENEW_BEFORE_SECONDS="2592000"
        log_info "Режим панели: домен ${PANEL_HOST}, порт ${WEB_PORT}"
        return 0
    fi

    if is_private_ipv4 "$host"; then
        die "Панель и туннель требуют публичный IPv4 или домен: ${host} недостижим с TURN-релея и из интернета. Чтобы вернуть предыдущий режим, повторите установку, указав IP-адрес сервера" "$EXIT_INVALID_ARGUMENT"
    fi

    die "Ожидается публичный IPv4-адрес или доменное имя, получено: ${host}. Чтобы вернуть предыдущий режим, повторите установку, указав IP-адрес сервера" "$EXIT_INVALID_ARGUMENT"
}

# Баннеры установщика печатают режим и хост панели: в доменном режиме порт 443
# и домен, в IP-режиме порт и адрес.
panel_web_mode_label() {
    case "${PANEL_MODE:-ip}" in
        domain) printf 'домен %s\n' "${PANEL_HOST:-}" ;;
        *) printf 'IP %s\n' "${PANEL_HOST:-}" ;;
    esac
}

panel_web_port_label() {
    printf '%s (%s)\n' "$WEB_PORT" "$(panel_web_mode_label)"
}

print_installer_header() {
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║       CSQTT VPN Server — Installer v${SCRIPT_VERSION}                    ║"
    if [ -n "${PANEL_MODE:-}" ]; then
        echo "║       PEER: ${PEER_PORT}  |  SSH: ${SSH_PORT}  | WEB: ${WEB_PORT}       ║"
    else
        echo "║       PEER: ${PEER_PORT}  |  SSH: ${SSH_PORT}                          ║"
    fi
    echo "╚══════════════════════════════════════════════════════════════╝"
}

detect_public_ipv4() {
    local route_ip
    route_ip="$(ip -4 route get 77.88.8.8 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}' | head -n 1)"
    if [ -n "$route_ip" ] && is_public_ipv4 "$route_ip"; then
        printf '%s\n' "$route_ip"
        return 0
    fi
    local endpoint value
    for endpoint in https://api.ipify.org https://ipv4.icanhazip.com; do
        value="$(curl -4 -fsS --connect-timeout 3 --max-time 7 "$endpoint" 2>/dev/null || true)"
        value="$(printf '%s' "$value" | tr -d '[:space:]')"
        if is_public_ipv4 "$value"; then
            printf '%s\n' "$value"
            return 0
        fi
    done
    return 1
}

# В доменном режиме нужен --domains, в IP-режиме — --ip-address: системный
# certbot без нужного флага не должен приниматься за готового клиента, иначе
# установка уйдёт в лишний venv или в ложный отказ.
panel_certbot_flag() {
    case "${PANEL_MODE:-ip}" in
        domain) printf '%s\n' "--domains" ;;
        *) printf '%s\n' "--ip-address" ;;
    esac
}

certbot_supports() {
    local certbot="$1" flag
    flag="$(panel_certbot_flag)"
    "$certbot" certonly --help all 2>&1 | grep -Fq -- "$flag"
}

letsencrypt_http_port_listeners() {
    ss -ltnH "sport = :${LE_HTTP_PORT}" 2>/dev/null || true
}

# TCP/80 нужен HTTP-01 и должен быть свободен. В доменном режиме занятость —
# фатальная ошибка: подставить самоподписанный сертификат вместо доменного
# нельзя (решение владельца 4).
assert_letsencrypt_http_port_is_available() {
    local listeners
    listeners="$(letsencrypt_http_port_listeners)"
    [ -z "$listeners" ] && return 0
    log_warn "TCP/${LE_HTTP_PORT} занят другим сервисом:"
    printf '%s\n' "$listeners" | tee -a "$LOG_FILE" >&2
    die "Порт ${LE_HTTP_PORT} занят другим сервисом; освободите его: он нужен Let’s Encrypt для проверки домена. Чтобы вернуть предыдущий режим, повторите установку, указав IP-адрес сервера"
}

ensure_letsencrypt_client() {
    local system_certbot=""
    local -a python_packages=()
    if command -v certbot >/dev/null 2>&1; then
        system_certbot="$(command -v certbot)"
        if certbot_supports "$system_certbot"; then
            LE_CERTBOT_COMMAND="$system_certbot"
            return 0
        fi
    fi
    if [ -x "$CSQTT_LE_CERTBOT" ] && certbot_supports "$CSQTT_LE_CERTBOT"; then
        LE_CERTBOT_COMMAND="$CSQTT_LE_CERTBOT"
        return 0
    fi

    log_step "Подготовка Let’s Encrypt клиента..."
    if ! command -v python3 >/dev/null 2>&1 || ! python3 -c 'import ensurepip' >/dev/null 2>&1; then
        case "$PKG_MGR" in
            apt) python_packages=(python3 python3-venv) ;;
            dnf|yum) python_packages=(python3 python3-pip) ;;
            pacman) python_packages=(python) ;;
        esac
        if ! pkg_install_with_refresh "${python_packages[@]}"; then
            log_warn "Python/venv недоступны; WEB-панель временно останется на self-signed TLS"
            return 1
        fi
    fi
    if ! command -v openssl >/dev/null 2>&1; then
        if ! pkg_install_with_refresh openssl; then
            log_warn "OpenSSL недоступен; проверка и обновление Let’s Encrypt сертификата отложены"
            return 1
        fi
    fi
    if [ ! -x "$CSQTT_LE_CERTBOT" ] || ! "$CSQTT_LE_CERTBOT" --version >/dev/null 2>&1; then
        rm -rf "$CSQTT_LE_VENV"
        if ! python3 -m venv "$CSQTT_LE_VENV" >>"$LOG_FILE" 2>&1; then
            log_warn "Не удалось создать изолированный Let’s Encrypt client; WEB-панель временно останется на self-signed TLS"
            return 1
        fi
    fi
    if ! timeout 180 "$CSQTT_LE_VENV/bin/pip" install --disable-pip-version-check --no-cache-dir --upgrade 'certbot>=5.4,<6' >>"$LOG_FILE" 2>&1; then
        log_warn "Не удалось установить актуальный Certbot с поддержкой IP-сертификатов; WEB-панель временно останется на self-signed TLS"
        return 1
    fi
    if ! certbot_supports "$CSQTT_LE_CERTBOT"; then
        log_warn "Установленный Certbot не поддерживает сертификаты Let’s Encrypt для выбранного режима панели"
        return 1
    fi
    LE_CERTBOT_COMMAND="$CSQTT_LE_CERTBOT"
    return 0
}

# Имя сертификата зависит от режима: IP-сертификат исторически csqtt-ip-<ip>,
# доменный — csqtt-<домен>. Версия с режимом нужна проверкам состояния: они
# сверяют имя с режимом, записанным в state, а не с текущим.
letsencrypt_cert_name_for_mode() {
    local mode="$1" identifier="$2"
    case "$mode" in
        domain) printf 'csqtt-%s\n' "$identifier" ;;
        *) printf 'csqtt-ip-%s\n' "${identifier//./-}" ;;
    esac
}

letsencrypt_cert_name() {
    letsencrypt_cert_name_for_mode "${PANEL_MODE:-ip}" "$1"
}

# SAN сверяется с режимом: домен в SAN — DNS:<домен>, IP — IP Address:<ip>.
panel_san_matches() {
    local mode="$1" identifier="$2" subject_alt_names="$3"
    case "$mode" in
        domain)
            printf '%s\n' "$subject_alt_names" | grep -Fq "DNS:${identifier}"
            ;;
        *)
            printf '%s\n' "$subject_alt_names" | grep -Fq "IP Address:${identifier}" || \
                printf '%s\n' "$subject_alt_names" | grep -Fq "IP:${identifier}"
            ;;
    esac
}

state_value() {
    local key="$1"
    [ -f "$CSQTT_LE_STATE_FILE" ] || return 1
    sed -n "s/^${key}=//p" "$CSQTT_LE_STATE_FILE" | head -n 1
}

# Легаси-состояние letsencrypt-ip.env (формат IP=/CERT_NAME=) читается как
# IP-режим. Без этого после переименования state установка приняла бы свой же
# IP-сертификат за пользовательский и молча оставила бы панель без продления.
legacy_letsencrypt_state_value() {
    local key="$1" file="${CSQTT_CONFIG_DIR}/letsencrypt-ip.env"
    [ -f "$file" ] || return 1
    sed -n "s/^${key}=//p" "$file" | head -n 1
}

# Переносит легаси-состояние в новый формат. Только IP-режим: в доменном
# установка обязана либо выпустить доменный сертификат, либо упасть, не
# записывая чужой режим.
migrate_legacy_letsencrypt_state() {
    local legacy_ip cert_name
    [ -f "$CSQTT_LE_STATE_FILE" ] && return 1
    legacy_ip="$(legacy_letsencrypt_state_value IP 2>/dev/null || true)"
    is_public_ipv4 "$legacy_ip" || return 1
    cert_name="$(legacy_letsencrypt_state_value CERT_NAME 2>/dev/null || true)"
    [ -n "$cert_name" ] || cert_name="$(letsencrypt_cert_name_for_mode ip "$legacy_ip")"
    printf 'MODE=ip\nIDENTIFIER=%s\nCERT_NAME=%s\n' "$legacy_ip" "$cert_name" > "$CSQTT_LE_STATE_FILE"
    chmod 0600 "$CSQTT_LE_STATE_FILE"
    log_info "Состояние Let’s Encrypt перенесено в ${CSQTT_LE_STATE_FILE}"
}

certificate_is_csqtt_rcgen() {
    local issuer subject
    [ -s "$CSQTT_CONFIG_DIR/web_cert.pem" ] || return 1
    command -v openssl >/dev/null 2>&1 || return 1
    issuer="$(openssl x509 -noout -issuer -nameopt RFC2253 -in "$CSQTT_CONFIG_DIR/web_cert.pem" 2>/dev/null || true)"
    subject="$(openssl x509 -noout -subject -nameopt RFC2253 -in "$CSQTT_CONFIG_DIR/web_cert.pem" 2>/dev/null || true)"
    issuer="${issuer#issuer=}"
    subject="${subject#subject=}"
    [ "$subject" = "CN=rcgen self signed cert" ] && [ "$issuer" = "$subject" ]
}

certificate_is_csqtt_letsencrypt() {
    local saved_mode saved_identifier cert_name issuer subject_alt_names
    [ -s "$CSQTT_CONFIG_DIR/web_cert.pem" ] || return 1
    [ -s "$CSQTT_CONFIG_DIR/web_key.pem" ] || return 1
    command -v openssl >/dev/null 2>&1 || return 1
    saved_mode="$(state_value MODE 2>/dev/null || true)"
    saved_identifier="$(state_value IDENTIFIER 2>/dev/null || true)"
    cert_name="$(state_value CERT_NAME 2>/dev/null || true)"
    if [ -z "$saved_mode" ]; then
        # Легаси-файл описывает только IP-режим.
        saved_identifier="$(legacy_letsencrypt_state_value IP 2>/dev/null || true)"
        if is_public_ipv4 "$saved_identifier"; then
            saved_mode="ip"
            [ -n "$cert_name" ] || cert_name="$(legacy_letsencrypt_state_value CERT_NAME 2>/dev/null || true)"
        fi
    fi
    case "$saved_mode" in
        domain) is_dns_hostname "$saved_identifier" || return 1 ;;
        ip) is_public_ipv4 "$saved_identifier" || return 1 ;;
        *) return 1 ;;
    esac
    [ "$cert_name" = "$(letsencrypt_cert_name_for_mode "$saved_mode" "$saved_identifier")" ] || return 1
    issuer="$(openssl x509 -noout -issuer -nameopt RFC2253 -in "$CSQTT_CONFIG_DIR/web_cert.pem" 2>/dev/null || true)"
    issuer="${issuer#issuer=}"
    case "$issuer" in
        *"O=Let's Encrypt"*|*ISRG*) ;;
        *) return 1 ;;
    esac
    subject_alt_names="$(openssl x509 -noout -ext subjectAltName -in "$CSQTT_CONFIG_DIR/web_cert.pem" 2>/dev/null || true)"
    panel_san_matches "$saved_mode" "$saved_identifier" "$subject_alt_names"
}

web_certificate_is_csqtt_managed() {
    certificate_is_csqtt_rcgen || certificate_is_csqtt_letsencrypt
}

web_certificate_is_user_managed() {
    [ -s "$CSQTT_CONFIG_DIR/web_cert.pem" ] || return 1
    ! web_certificate_is_csqtt_managed
}

letsencrypt_certificate_is_current() {
    local identifier="$1" saved_mode saved_identifier
    certificate_is_csqtt_letsencrypt || return 1
    saved_mode="$(state_value MODE 2>/dev/null || true)"
    [ "$saved_mode" = "${PANEL_MODE:-ip}" ] || return 1
    saved_identifier="$(state_value IDENTIFIER 2>/dev/null || true)"
    [ "$saved_identifier" = "$identifier" ] || return 1
    [ -s "$CSQTT_CONFIG_DIR/web_cert.pem" ] || return 1
    openssl x509 -noout -checkend "$LE_RENEW_BEFORE_SECONDS" -in "$CSQTT_CONFIG_DIR/web_cert.pem" >/dev/null 2>&1
}

disable_letsencrypt_renewal_timer() {
    rm -f -- /etc/cron.d/csqtt-letsencrypt
    command -v systemctl >/dev/null 2>&1 || return 0
    systemctl disable --now "$CSQTT_LE_TIMER" >/dev/null 2>&1 || true
}

# Остановка без выключения: таймер обязан замолчать на время переключения, но
# включать его будет install_letsencrypt_renewal_timer в конце установки.
stop_letsencrypt_renewal_timer() {
    command -v systemctl >/dev/null 2>&1 || return 0
    systemctl stop "$CSQTT_LE_TIMER" "$CSQTT_LE_SERVICE" >/dev/null 2>&1 || true
}

install_letsencrypt_certificate() {
    local identifier="$1" cert_name="$2" source_dir cert_tmp key_tmp state_tmp
    source_dir="${CSQTT_LE_ROOT}/live/${cert_name}"
    [ -s "$source_dir/fullchain.pem" ] && [ -s "$source_dir/privkey.pem" ] || return 1
    cert_tmp="$(mktemp "${CSQTT_CONFIG_DIR}/.web_cert.XXXXXX")" || return 1
    key_tmp="$(mktemp "${CSQTT_CONFIG_DIR}/.web_key.XXXXXX")" || {
        rm -f -- "$cert_tmp"
        return 1
    }
    state_tmp="$(mktemp "${CSQTT_CONFIG_DIR}/.letsencrypt.XXXXXX")" || {
        rm -f -- "$cert_tmp" "$key_tmp"
        return 1
    }
    if ! install -m 0644 "$source_dir/fullchain.pem" "$cert_tmp" || \
       ! install -m 0600 "$source_dir/privkey.pem" "$key_tmp"; then
        rm -f -- "$cert_tmp" "$key_tmp" "$state_tmp"
        return 1
    fi
    printf 'MODE=%s\nIDENTIFIER=%s\nCERT_NAME=%s\n' "${PANEL_MODE:-ip}" "$identifier" "$cert_name" > "$state_tmp"
    chmod 0600 "$state_tmp"
    # State пишется последним: если активация файлов не удалась, состояние не
    # должно объявлять режим, которому диск не соответствует.
    mv -f -- "$cert_tmp" "$CSQTT_CONFIG_DIR/web_cert.pem"
    mv -f -- "$key_tmp" "$CSQTT_CONFIG_DIR/web_key.pem"
    mv -f -- "$state_tmp" "$CSQTT_LE_STATE_FILE"
    secure_persistent_state
}

# SAN сертификата, лежащего в web_cert.pem. Нужен adopt-у, которому state уже
# недоступен: в доменном режиме извлекается DNS:<домен>, в IP-режиме — адрес.
letsencrypt_certificate_identifier() {
    local subject_alt_names
    [ -s "$CSQTT_CONFIG_DIR/web_cert.pem" ] || return 1
    command -v openssl >/dev/null 2>&1 || return 1
    subject_alt_names="$(openssl x509 -noout -ext subjectAltName -in "$CSQTT_CONFIG_DIR/web_cert.pem" 2>/dev/null || true)"
    case "${PANEL_MODE:-ip}" in
        domain) printf '%s\n' "$subject_alt_names" | sed -n -E 's/.*DNS:([A-Za-z0-9.-]+).*/\1/p' | head -n 1 ;;
        *) printf '%s\n' "$subject_alt_names" | sed -n -E 's/.*(IP Address:|IP:)([0-9.]+).*/\2/p' | head -n 1 ;;
    esac
}

adopt_letsencrypt_certificate() {
    local identifier cert_name source_dir issuer
    [ -s "$CSQTT_CONFIG_DIR/web_cert.pem" ] || return 1
    [ -s "$CSQTT_CONFIG_DIR/web_key.pem" ] || return 1
    command -v openssl >/dev/null 2>&1 || return 1
    issuer="$(openssl x509 -noout -issuer -nameopt RFC2253 -in "$CSQTT_CONFIG_DIR/web_cert.pem" 2>/dev/null || true)"
    issuer="${issuer#issuer=}"
    case "$issuer" in
        *"O=Let's Encrypt"*|*ISRG*) ;;
        *) return 1 ;;
    esac
    identifier="$(letsencrypt_certificate_identifier 2>/dev/null || true)"
    # SAN сертификата обязан соответствовать текущему режиму, иначе adopt
    # записал бы чужой режим посреди установки.
    case "${PANEL_MODE:-ip}" in
        domain)
            [ -n "$identifier" ] || return 1
            [ "$identifier" = "$PANEL_HOST" ] || return 1
            ;;
        *)
            is_public_ipv4 "$identifier" || return 1
            ;;
    esac
    cert_name="$(letsencrypt_cert_name "$identifier")"
    source_dir="${CSQTT_LE_ROOT}/live/${cert_name}"
    [ -s "$source_dir/fullchain.pem" ] || return 1
    [ -s "$source_dir/privkey.pem" ] || return 1
    install_letsencrypt_certificate "$identifier" "$cert_name"
}

issue_letsencrypt_certificate() {
    local certbot="$1" identifier="$2" cert_name="$3" public_ip="$2"
    if [ "${PANEL_MODE:-ip}" = "domain" ]; then
        assert_letsencrypt_http_port_is_available
        # Идентификатор доменного режима — это PANEL_HOST; certbot получает его
        # напрямую, чтобы имя в заявке и в панели не могли разойтись.
        if ! timeout 90 "$certbot" certonly --standalone --http-01-port "$LE_HTTP_PORT" \
            --non-interactive --agree-tos --register-unsafely-without-email \
            --domains "$PANEL_HOST" \
            --cert-name "$cert_name" --keep-until-expiring >>"$LOG_FILE" 2>&1; then
            die "Let’s Encrypt не подтвердил домен ${identifier}; смотрите ${LOG_FILE}. Проверьте A-запись и что порт ${LE_HTTP_PORT} доступен из интернета. Чтобы вернуть предыдущий режим, повторите установку, указав IP-адрес сервера"
        fi
        if ! install_letsencrypt_certificate "$identifier" "$cert_name"; then
            die "Let’s Encrypt выдал сертификат для ${identifier}, но CSQTT не смог безопасно активировать его: проверьте свободное место в ${CSQTT_CONFIG_DIR}. Чтобы вернуть предыдущий режим, повторите установку, указав IP-адрес сервера"
        fi
        log_info "Let’s Encrypt сертификат активирован для домена ${identifier}"
        return 0
    fi
    if [ -n "$(letsencrypt_http_port_listeners)" ]; then
        log_warn "TCP/${LE_HTTP_PORT} уже занят другим сервисом; Let’s Encrypt IP-сертификат отложен, сохранён self-signed fallback"
        return 1
    fi
    if ! timeout 90 "$certbot" certonly --standalone --http-01-port "$LE_HTTP_PORT" \
        --non-interactive --agree-tos --register-unsafely-without-email \
        --preferred-profile shortlived --ip-address "$public_ip" \
        --cert-name "$cert_name" --keep-until-expiring >>"$LOG_FILE" 2>&1; then
        if ! timeout 90 "$certbot" certonly --standalone --http-01-port "$LE_HTTP_PORT" \
            --non-interactive --agree-tos --register-unsafely-without-email \
            --ip-address "$public_ip" \
            --cert-name "$cert_name" --keep-until-expiring >>"$LOG_FILE" 2>&1; then
            log_warn "Let’s Encrypt не подтвердил IP ${public_ip}; сохранён self-signed fallback, следующая проверка выполнится автоматически"
            return 1
        fi
    fi
    if ! install_letsencrypt_certificate "$identifier" "$cert_name"; then
        log_warn "Let’s Encrypt выдал сертификат, но CSQTT не смог безопасно активировать его"
        return 1
    fi
    log_info "Let’s Encrypt IP-сертификат активирован для ${identifier}"
}

generate_rcgen_fallback_certificate() {
    local cert_tmp key_tmp current_ip="${1:-}" san_ext
    command -v openssl >/dev/null 2>&1 || return 1
    cert_tmp="$(mktemp "${CSQTT_CONFIG_DIR}/.web_cert.XXXXXX")" || return 1
    key_tmp="$(mktemp "${CSQTT_CONFIG_DIR}/.web_key.XXXXXX")" || { rm -f -- "$cert_tmp"; return 1; }
    san_ext="subjectAltName = DNS:localhost,IP:127.0.0.1"
    if [ -n "$current_ip" ] && is_public_ipv4 "$current_ip"; then
        san_ext="${san_ext},IP:${current_ip}"
    fi
    if ! openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
        -subj "/CN=rcgen self signed cert" \
        -addext "$san_ext" \
        -keyout "$key_tmp" -out "$cert_tmp" >/dev/null 2>&1; then
        if ! openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
            -subj "/CN=rcgen self signed cert" \
            -keyout "$key_tmp" -out "$cert_tmp" >/dev/null 2>&1; then
            rm -f -- "$cert_tmp" "$key_tmp"
            return 1
        fi
    fi
    mv -f -- "$cert_tmp" "$CSQTT_CONFIG_DIR/web_cert.pem"
    mv -f -- "$key_tmp" "$CSQTT_CONFIG_DIR/web_key.pem"
    chmod 644 "$CSQTT_CONFIG_DIR/web_cert.pem"
    chmod 600 "$CSQTT_CONFIG_DIR/web_key.pem"
    rm -f -- "$CSQTT_LE_STATE_FILE"
}

write_letsencrypt_renewal_helper() {
    install -d -m 0755 /usr/local/lib
    cat > "$CSQTT_LE_RENEW_HELPER" << 'LEHELPER'
#!/bin/bash
set -Eeuo pipefail

CERTBOT="${CSQTT_LE_CERTBOT:-}"
if [ -z "$CERTBOT" ] || ! "$CERTBOT" --version >/dev/null 2>&1; then
    if [ -x /opt/csqtt-certbot/bin/certbot ] && /opt/csqtt-certbot/bin/certbot --version >/dev/null 2>&1; then
        CERTBOT="/opt/csqtt-certbot/bin/certbot"
    elif command -v certbot >/dev/null 2>&1 && certbot --version >/dev/null 2>&1; then
        CERTBOT="$(command -v certbot)"
    fi
fi
CONFIG_DIR="${CSQTT_CONFIG_DIR:-/etc/csqtt}"
LE_ROOT="${CSQTT_LE_ROOT:-/etc/letsencrypt}"
LE_IDENTIFIER="${CSQTT_LE_IDENTIFIER:-}"
STATE_FILE="${CONFIG_DIR}/letsencrypt.env"
CERT_PATH="${CONFIG_DIR}/web_cert.pem"
KEY_PATH="${CONFIG_DIR}/web_key.pem"
RENEW_BEFORE_SECONDS="${CSQTT_LE_RENEW_BEFORE_SECONDS:-259200}"
HTTP_PORT="${CSQTT_LE_HTTP_PORT:-80}"
SERVICE_MANAGER="${CSQTT_DEPLOY_MODE:-systemd}"

log() {
    logger -t csqtt-letsencrypt -- "$*" 2>/dev/null || true
    printf '%s\n' "$*"
}

is_public_ipv4() {
    local ip="$1" first second third fourth value
    case "$ip" in
        *[!0-9.]*|*..*|.*|*.) return 1 ;;
    esac
    IFS=. read -r first second third fourth <<< "$ip"
    for value in "$first" "$second" "$third" "$fourth"; do
        case "$value" in
            ''|*[!0-9]*) return 1 ;;
        esac
        [ "$value" -le 255 ] || return 1
    done
    [ "$first" -gt 0 ] && [ "$first" -lt 224 ] && [ "$first" -ne 10 ] && [ "$first" -ne 127 ] || return 1
    [ "$first" -ne 169 ] || [ "$second" -ne 254 ] || return 1
    [ "$first" -ne 192 ] || [ "$second" -ne 168 ] || return 1
    [ "$first" -ne 172 ] || [ "$second" -lt 16 ] || [ "$second" -gt 31 ] || return 1
    [ "$first" -ne 100 ] || [ "$second" -lt 64 ] || [ "$second" -gt 127 ] || return 1
    [ "$first" -ne 198 ] || [ "$second" -ne 18 ] || return 1
    [ "$first" -ne 198 ] || [ "$second" -ne 19 ] || return 1
}

public_ipv4() {
    local route_ip
    route_ip="$(ip -4 route get 77.88.8.8 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}' | head -n 1)"
    if [ -n "$route_ip" ] && is_public_ipv4 "$route_ip"; then
        printf '%s\n' "$route_ip"
        return 0
    fi
    local endpoint value
    for endpoint in https://api.ipify.org https://ipv4.icanhazip.com; do
        value="$(curl -4 -fsS --connect-timeout 3 --max-time 7 "$endpoint" 2>/dev/null || true)"
        value="$(printf '%s' "$value" | tr -d '[:space:]')"
        if is_public_ipv4 "$value"; then
            printf '%s\n' "$value"
            return 0
        fi
    done
    value="$(state_value IDENTIFIER 2>/dev/null || true)"
    [ -n "$value" ] || value="$(state_value IP 2>/dev/null || true)"
    if is_public_ipv4 "$value"; then
        printf '%s\n' "$value"
        return 0
    fi
    return 1
}

state_value() {
    local key="$1" value=""
    if [ -f "$STATE_FILE" ]; then
        value="$(sed -n "s/^${key}=//p" "$STATE_FILE" | head -n 1)"
    fi
    if [ -z "$value" ] && [ -f "${CONFIG_DIR}/letsencrypt-ip.env" ]; then
        value="$(sed -n "s/^${key}=//p" "${CONFIG_DIR}/letsencrypt-ip.env" | head -n 1)"
    fi
    [ -n "$value" ] || return 1
    printf '%s\n' "$value"
}

is_dns_name() {
    local name="$1"
    [ -n "$name" ] || return 1
    [ "${#name}" -le 253 ] || return 1
    case "$name" in
        *[!A-Za-z0-9.-]*|.*|*.|*..*) return 1 ;;
    esac
    case "${name##*.}" in
        *[A-Za-z]*) return 0 ;;
    esac
    return 1
}

# Режим читается из state; если state потерян, он выводится из идентификатора,
# который передаёт юнит: адрес — IP-режим, имя — доменный. Пустой идентификатор
# (старый юнит) даёт прежнее поведение IP-режима.
panel_mode() {
    local mode
    mode="$(state_value MODE 2>/dev/null || true)"
    if [ -n "$mode" ]; then
        printf '%s\n' "$mode"
        return 0
    fi
    if [ -n "$LE_IDENTIFIER" ] && ! is_public_ipv4 "$LE_IDENTIFIER"; then
        printf 'domain\n'
        return 0
    fi
    printf 'ip\n'
}

panel_identifier() {
    local identifier
    identifier="$(state_value IDENTIFIER 2>/dev/null || true)"
    if [ -n "$identifier" ]; then
        printf '%s\n' "$identifier"
        return 0
    fi
    printf '%s\n' "$LE_IDENTIFIER"
}

current_identifier() {
    if [ "$(panel_mode)" = "domain" ]; then
        panel_identifier
    else
        public_ipv4
    fi
}

cert_name_for() {
    local mode="$1" identifier="$2"
    if [ "$mode" = "domain" ]; then
        printf 'csqtt-%s\n' "$identifier"
    else
        printf 'csqtt-ip-%s\n' "${identifier//./-}"
    fi
}

certificate_is_csqtt_rcgen() {
    local issuer subject
    [ -s "$CERT_PATH" ] || return 1
    command -v openssl >/dev/null 2>&1 || return 1
    issuer="$(openssl x509 -noout -issuer -nameopt RFC2253 -in "$CERT_PATH" 2>/dev/null || true)"
    subject="$(openssl x509 -noout -subject -nameopt RFC2253 -in "$CERT_PATH" 2>/dev/null || true)"
    issuer="${issuer#issuer=}"
    subject="${subject#subject=}"
    [ "$subject" = "CN=rcgen self signed cert" ] && [ "$issuer" = "$subject" ]
}

certificate_is_csqtt_letsencrypt() {
    local mode identifier cert_name issuer subject_alt_names
    [ -s "$CERT_PATH" ] || return 1
    [ -s "$KEY_PATH" ] || return 1
    command -v openssl >/dev/null 2>&1 || return 1
    mode="$(panel_mode)"
    identifier="$(state_value IDENTIFIER 2>/dev/null || true)"
    cert_name="$(state_value CERT_NAME 2>/dev/null || true)"
    if [ -z "$identifier" ] || [ -z "$cert_name" ]; then
        subject_alt_names="$(openssl x509 -noout -ext subjectAltName -in "$CERT_PATH" 2>/dev/null || true)"
        if [ "$mode" = "domain" ]; then
            identifier="$(printf '%s\n' "$subject_alt_names" | sed -n -E 's/.*DNS:([A-Za-z0-9.-]+).*/\1/p' | head -n 1)"
        else
            identifier="$(printf '%s\n' "$subject_alt_names" | sed -n -E 's/.*(IP Address:|IP:)([0-9.]+).*/\2/p' | head -n 1)"
        fi
        cert_name="$(cert_name_for "$mode" "$identifier")"
    fi
    if [ "$mode" = "domain" ]; then
        is_dns_name "$identifier" || return 1
    else
        is_public_ipv4 "$identifier" || return 1
    fi
    [ "$cert_name" = "$(cert_name_for "$mode" "$identifier")" ] || return 1
    issuer="$(openssl x509 -noout -issuer -nameopt RFC2253 -in "$CERT_PATH" 2>/dev/null || true)"
    issuer="${issuer#issuer=}"
    case "$issuer" in
        *"O=Let's Encrypt"*|*ISRG*) ;;
        *) return 1 ;;
    esac
    subject_alt_names="$(openssl x509 -noout -ext subjectAltName -in "$CERT_PATH" 2>/dev/null || true)"
    if [ "$mode" = "domain" ]; then
        printf '%s\n' "$subject_alt_names" | grep -Fq "DNS:${identifier}"
    else
        printf '%s\n' "$subject_alt_names" | grep -Fq "IP Address:${identifier}" || printf '%s\n' "$subject_alt_names" | grep -Fq "IP:${identifier}"
    fi
}

certificate_is_csqtt_managed() {
    certificate_is_csqtt_rcgen || certificate_is_csqtt_letsencrypt
}

# Пустой CSQTT_LE_IDENTIFIER — не расхождение: старый юнит переменную не
# передаёт вовсе, и трактовка пустого значения как mismatch вызвала бы боевой
# перевыпуск на пустом месте (а под set -u — падение).
needs_renewal() {
    local current="$1" saved_identifier
    if certificate_is_csqtt_rcgen; then
        return 0
    fi
    saved_identifier="$(state_value IDENTIFIER 2>/dev/null || true)"
    if [ -z "$saved_identifier" ]; then
        saved_identifier="$(state_value IP 2>/dev/null || true)"
    fi
    [ "$saved_identifier" = "$current" ] || return 0
    if [ -z "$LE_IDENTIFIER" ]; then
        log "Let’s Encrypt: юнит не передал CSQTT_LE_IDENTIFIER; сверка режима пропущена"
    elif [ "$saved_identifier" != "$LE_IDENTIFIER" ]; then
        log "Let’s Encrypt: идентификатор в состоянии (${saved_identifier}) расходится с ожидаемым (${LE_IDENTIFIER}); требуется перевыпуск"
        return 0
    fi
    [ -s "$CERT_PATH" ] || return 0
    openssl x509 -noout -checkend "$RENEW_BEFORE_SECONDS" -in "$CERT_PATH" >/dev/null 2>&1 || return 0
    return 1
}

certificate_is_expired() {
    [ -s "$CERT_PATH" ] || return 0
    command -v openssl >/dev/null 2>&1 || return 1
    ! openssl x509 -noout -checkend 0 -in "$CERT_PATH" >/dev/null 2>&1
}

generate_rcgen_fallback() {
    local current_ip="${1:-}" cert_tmp key_tmp san_ext
    command -v openssl >/dev/null 2>&1 || return 1
    cert_tmp="$(mktemp "${CONFIG_DIR}/.web_cert.XXXXXX")" || return 1
    key_tmp="$(mktemp "${CONFIG_DIR}/.web_key.XXXXXX")" || { rm -f -- "$cert_tmp"; return 1; }
    san_ext="subjectAltName = DNS:localhost,IP:127.0.0.1"
    if [ -n "$current_ip" ] && is_public_ipv4 "$current_ip"; then
        san_ext="${san_ext},IP:${current_ip}"
    fi
    if ! openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
        -subj "/CN=rcgen self signed cert" \
        -addext "$san_ext" \
        -keyout "$key_tmp" -out "$cert_tmp" >/dev/null 2>&1; then
        if ! openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
            -subj "/CN=rcgen self signed cert" \
            -keyout "$key_tmp" -out "$cert_tmp" >/dev/null 2>&1; then
            rm -f -- "$cert_tmp" "$key_tmp"
            return 1
        fi
    fi
    mv -f -- "$cert_tmp" "$CERT_PATH"
    mv -f -- "$key_tmp" "$KEY_PATH"
    chmod 644 "$CERT_PATH"
    chmod 600 "$KEY_PATH"
    rm -f -- "$STATE_FILE"
}

fallback_if_expired() {
    local current_ip="${1:-}"
    if certificate_is_expired; then
        log "Let’s Encrypt: текущий сертификат истёк; активируется fallback rcgen сертификат"
        if generate_rcgen_fallback "$current_ip"; then
            reload_csqtt_tls
            log "Let’s Encrypt: fallback rcgen сертификат успешно активирован"
        fi
    fi
}

install_certificate() {
    local mode="$1" identifier="$2" cert_name="$3" source_dir cert_tmp key_tmp state_tmp
    source_dir="${LE_ROOT}/live/${cert_name}"
    [ -s "$source_dir/fullchain.pem" ] && [ -s "$source_dir/privkey.pem" ] || return 1
    cert_tmp="$(mktemp "${CONFIG_DIR}/.web_cert.XXXXXX")" || return 1
    key_tmp="$(mktemp "${CONFIG_DIR}/.web_key.XXXXXX")" || { rm -f -- "$cert_tmp"; return 1; }
    state_tmp="$(mktemp "${CONFIG_DIR}/.letsencrypt.XXXXXX")" || { rm -f -- "$cert_tmp" "$key_tmp"; return 1; }
    if ! install -m 0644 "$source_dir/fullchain.pem" "$cert_tmp" || ! install -m 0600 "$source_dir/privkey.pem" "$key_tmp"; then
        rm -f -- "$cert_tmp" "$key_tmp" "$state_tmp"
        return 1
    fi
    printf 'MODE=%s\nIDENTIFIER=%s\nCERT_NAME=%s\n' "$mode" "$identifier" "$cert_name" > "$state_tmp"
    chmod 0600 "$state_tmp"
    # State последним: при неудачной активации файлов состояние не должно
    # объявлять режим, которому диск не соответствует.
    mv -f -- "$cert_tmp" "$CERT_PATH"
    mv -f -- "$key_tmp" "$KEY_PATH"
    mv -f -- "$state_tmp" "$STATE_FILE"
    chmod 644 "$CERT_PATH"
    chmod 600 "$KEY_PATH" "$STATE_FILE"
}

reload_csqtt_tls() {
    if [ "$SERVICE_MANAGER" = "docker" ]; then
        docker kill --signal USR1 csqtt >/dev/null 2>&1 || return 0
    else
        systemctl kill --kill-who=main --signal USR1 csqtt >/dev/null 2>&1 || return 0
    fi
}

main() {
    local mode identifier cert_name
    mode="$(panel_mode)"
    # В доменном режиме без state нечего продлевать: самоподписанный откат
    # запрещён, панель должна честно дождаться переустановки. Проверка идёт до
    # разбора сертификата, иначе свой же старый сертификат был бы принят за
    # пользовательский и причина молчания осталась бы невнятной.
    if [ "$mode" = "domain" ] && [ ! -f "$STATE_FILE" ]; then
        log "Let’s Encrypt: режим домена, но ${STATE_FILE} отсутствует; сертификат не обновляется. Повторите установку, чтобы восстановить состояние"
        return 0
    fi
    if [ -s "$CERT_PATH" ] && ! certificate_is_csqtt_managed; then
        log "Let’s Encrypt: обнаружен пользовательский TLS-сертификат; CSQTT не изменяет его и отключает собственное продление"
        return 0
    fi
    identifier="$(current_identifier 2>/dev/null || true)"
    if [ -z "$CERTBOT" ] || [ ! -x "$CERTBOT" ]; then
        log "Let’s Encrypt: Certbot не найден, обновление отложено"
        [ "$mode" = "domain" ] || fallback_if_expired "$identifier"
        return 0
    fi
    if [ -z "$identifier" ]; then
        log "Let’s Encrypt: публичный адрес не определён, обновление отложено"
        [ "$mode" = "domain" ] || fallback_if_expired ""
        return 0
    fi
    if ! needs_renewal "$identifier"; then
        return 0
    fi
    if ss -ltnH "sport = :${HTTP_PORT}" 2>/dev/null | grep -q .; then
        log "Let’s Encrypt: TCP/${HTTP_PORT} занят, обновление отложено"
        [ "$mode" = "domain" ] || fallback_if_expired "$identifier"
        return 0
    fi
    cert_name="$(cert_name_for "$mode" "$identifier")"
    if [ "$mode" = "domain" ]; then
        if ! timeout 90 "$CERTBOT" certonly --standalone --http-01-port "$HTTP_PORT" --non-interactive --agree-tos --register-unsafely-without-email --domains "$identifier" --cert-name "$cert_name" --force-renewal; then
            log "Let’s Encrypt: перевыпуск сертификата для домена ${identifier} не удался, текущий сертификат сохранён; исправьте причину и повторите установку"
            return 0
        fi
    else
        if ! timeout 90 "$CERTBOT" certonly --standalone --http-01-port "$HTTP_PORT" --non-interactive --agree-tos --register-unsafely-without-email --preferred-profile shortlived --ip-address "$identifier" --cert-name "$cert_name" --force-renewal; then
            if ! timeout 90 "$CERTBOT" certonly --standalone --http-01-port "$HTTP_PORT" --non-interactive --agree-tos --register-unsafely-without-email --ip-address "$identifier" --cert-name "$cert_name" --force-renewal; then
                log "Let’s Encrypt: перевыпуск IP-сертификата не удался, текущий сертификат сохранён"
                fallback_if_expired "$identifier"
                return 0
            fi
        fi
    fi
    if ! install_certificate "$mode" "$identifier" "$cert_name"; then
        log "Let’s Encrypt: не удалось безопасно активировать новый сертификат"
        [ "$mode" = "domain" ] || fallback_if_expired "$identifier"
        return 0
    fi
    reload_csqtt_tls
    log "Let’s Encrypt: сертификат обновлён и TLS-конфигурация CSQTT перезагружена"
}

main "$@"
LEHELPER
    chmod 0755 "$CSQTT_LE_RENEW_HELPER"
}

install_letsencrypt_renewal_timer() {
    rm -f -- /etc/cron.d/csqtt-letsencrypt
    command -v systemctl >/dev/null 2>&1 || {
        log_warn "systemd не найден: автоматическое продление Let’s Encrypt требует systemd"
        return 0
    }
    cat > "/etc/systemd/system/${CSQTT_LE_SERVICE}" << LEUNIT
[Unit]
Description=Renew CSQTT Let’s Encrypt certificate
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
Environment=CSQTT_DEPLOY_MODE=${DEPLOY_MODE}
Environment=CSQTT_LE_CERTBOT=${LE_CERTBOT_COMMAND}
Environment=CSQTT_LE_RENEW_BEFORE_SECONDS=${LE_RENEW_BEFORE_SECONDS}
Environment=CSQTT_LE_ROOT=${CSQTT_LE_ROOT}
Environment=CSQTT_LE_IDENTIFIER=${PANEL_HOST:-}
ExecStart=${CSQTT_LE_RENEW_HELPER}
LEUNIT
    cat > "/etc/systemd/system/${CSQTT_LE_TIMER}" << LETIMER
[Unit]
Description=Check CSQTT Let’s Encrypt certificate lifetime

[Timer]
OnBootSec=5min
OnUnitInactiveSec=24h
RandomizedDelaySec=10min
Persistent=true

[Install]
WantedBy=timers.target
LETIMER
    systemctl daemon-reload || {
        log_warn "Не удалось обновить systemd unit для Let’s Encrypt"
        return 0
    }
    systemctl enable --now "$CSQTT_LE_TIMER" >/dev/null 2>&1 || \
        log_warn "Не удалось включить автоматическое продление Let’s Encrypt"
}

# Доменный режим: сертификат Let’s Encrypt для PANEL_HOST на порту 443.
# Любая неудача — die. Самоподписанного отката здесь нет: тихая подмена
# сертификата в доменном режиме запрещена решением владельца 4.
setup_domain_web_panel_tls() {
    local identifier="${PANEL_HOST:-}" cert_name
    if ! ensure_letsencrypt_client; then
        die "Certbot с поддержкой --domains недоступен: без него нельзя выпустить сертификат для ${identifier}. Установите certbot и повторите установку. Чтобы вернуть предыдущий режим, повторите установку, указав IP-адрес сервера"
    fi
    write_letsencrypt_renewal_helper
    assert_letsencrypt_http_port_is_available
    if letsencrypt_certificate_is_current "$identifier"; then
        log_info "Let’s Encrypt сертификат для домена ${identifier} действителен; перевыпуск не требуется"
    else
        cert_name="$(letsencrypt_cert_name "$identifier")"
        issue_letsencrypt_certificate "$LE_CERTBOT_COMMAND" "$identifier" "$cert_name"
    fi
    # Легаси-файл удаляется только здесь, после успешной активации доменного
    # сертификата. В сохраняемом списке его трогать нельзя: csqtt_cleanup снёс бы
    # его раньше, чем появится letsencrypt.env. Удалять раньше тоже нельзя: при
    # отказе выпуска IP-сертификат перестал бы узнаваться собственным, и откат
    # в IP-режим оставил бы панель без продления. Таймер на это время остановлен
    # в csqtt_cleanup, так что старый хелпер легаси-файл не прочитает.
    rm -f -- "$CSQTT_CONFIG_DIR/letsencrypt-ip.env"
    install_letsencrypt_renewal_timer
}

setup_web_panel_tls() {
    local public_ip cert_name
    if ! certificate_is_csqtt_letsencrypt && adopt_letsencrypt_certificate; then
        log_info "Let’s Encrypt-сертификат CSQTT принят под автоматическое продление"
    fi
    if web_certificate_is_user_managed; then
        rm -f -- "$CSQTT_LE_STATE_FILE"
        disable_letsencrypt_renewal_timer
        log_info "Используется заранее установленный TLS-сертификат пользователя; CSQTT сохраняет его и не заменяет Let’s Encrypt"
        return 0
    fi
    if [ "${PANEL_MODE:-ip}" = "domain" ]; then
        setup_domain_web_panel_tls
        return 0
    fi
    migrate_legacy_letsencrypt_state || true
    public_ip="${PANEL_HOST:-}"
    if ! is_public_ipv4 "$public_ip"; then
        public_ip="$(detect_public_ipv4 2>/dev/null || true)"
    fi
    if [ -z "$public_ip" ]; then
        public_ip="$(state_value IDENTIFIER 2>/dev/null || true)"
    fi
    if ! ensure_letsencrypt_client; then
        if [ ! -s "$CSQTT_CONFIG_DIR/web_cert.pem" ] || ! openssl x509 -noout -checkend 0 -in "$CSQTT_CONFIG_DIR/web_cert.pem" >/dev/null 2>&1; then
            generate_rcgen_fallback_certificate "$public_ip" || true
        fi
        return 0
    fi
    write_letsencrypt_renewal_helper
    rm -f -- "$CSQTT_CONFIG_DIR/letsencrypt-ip.env"
    if [ -z "$public_ip" ]; then
        log_warn "Публичный IPv4 не определён; WEB-панель временно останется на self-signed TLS"
        if [ ! -s "$CSQTT_CONFIG_DIR/web_cert.pem" ] || ! openssl x509 -noout -checkend 0 -in "$CSQTT_CONFIG_DIR/web_cert.pem" >/dev/null 2>&1; then
            generate_rcgen_fallback_certificate "" || true
        fi
        install_letsencrypt_renewal_timer
        return 0
    fi
    if letsencrypt_certificate_is_current "$public_ip"; then
        log_info "Let’s Encrypt IP-сертификат действителен; перевыпуск не требуется"
    else
        cert_name="$(letsencrypt_cert_name "$public_ip")"
        if ! issue_letsencrypt_certificate "$LE_CERTBOT_COMMAND" "$public_ip" "$cert_name"; then
            if [ ! -s "$CSQTT_CONFIG_DIR/web_cert.pem" ] || ! openssl x509 -noout -checkend 0 -in "$CSQTT_CONFIG_DIR/web_cert.pem" >/dev/null 2>&1; then
                generate_rcgen_fallback_certificate "$public_ip" || true
            fi
        fi
    fi
    install_letsencrypt_renewal_timer
}

# Две дешёвые проверки доменного режима. Выполняются ДО csqtt_cleanup: если порт
# 80 занят или certbot не умеет --domains, установка падает, не трогая
# работающую панель.
run_domain_mode_preflight() {
    [ "${PANEL_MODE:-}" = "domain" ] || return 0
    assert_letsencrypt_http_port_is_available
    if ! ensure_letsencrypt_client; then
        die "Certbot с поддержкой --domains недоступен: без него нельзя выпустить сертификат для ${PANEL_HOST}. Установите certbot и повторите установку. Чтобы вернуть предыдущий режим, повторите установку, указав IP-адрес сервера"
    fi
}

verify_web_panel() {
    local attempt scheme code
    local probe_file="/tmp/csqtt-web-probe.$$"
    local secret_path="${CSQTT_WEB_SECRET_PATH:-}"
    if [ -z "$secret_path" ] && [ -f "$CSQTT_ENV_FILE" ]; then
        secret_path="$(grep -E '^[[:space:]]*(export[[:space:]]+)?CSQTT_WEB_SECRET_PATH=' "$CSQTT_ENV_FILE" 2>/dev/null | head -n1 | cut -d= -f2- | tr -d '\"' | tr -d "'\"'" | tr -d '[:space:]' || true)"
    fi
    local probe_path=""
    if [ -n "$secret_path" ]; then
        probe_path="${secret_path#/}"
    fi

    for attempt in $(seq 1 24); do
        for scheme in https http; do
            code="$(curl -k -sS -o "$probe_file" -w "%{http_code}" \
                --connect-timeout 1 --max-time 2 \
                "${scheme}://127.0.0.1:${WEB_PORT}/${probe_path}" 2>>"$LOG_FILE" || true)"
            case "$code" in
                200|301|302|401)
                    rm -f -- "$probe_file"
                    log_info "WEB-панель ответила локальной health-проверке (${scheme}, HTTP ${code})"
                    return 0
                    ;;
            esac
        done
        sleep 0.25
    done
    rm -f -- "$probe_file"
    die "WEB-панель не ответила на локальную health-проверку порта ${WEB_PORT}"
}

ensure_docker() {
    log_step "Проверка Docker Engine..."

    if ! command -v docker >/dev/null 2>&1; then
        pkg_update
        if [ "$PKG_MGR" = "pacman" ]; then
            pkg_install docker || die "Не удалось установить Docker Engine"
        else
            pkg_install ca-certificates curl || die "Не удалось установить curl и ca-certificates для Docker"
            local installer
            installer="$(mktemp)"
            curl -fsSL https://get.docker.com -o "$installer" || die "Не удалось загрузить официальный установщик Docker"
            sh "$installer" >>"$LOG_FILE" 2>&1 || {
                rm -f "$installer"
                die "Не удалось установить Docker Engine"
            }
            rm -f "$installer"
        fi
    fi

    if command -v systemctl >/dev/null 2>&1; then
        systemctl enable --now docker >>"$LOG_FILE" 2>&1 || true
    elif command -v service >/dev/null 2>&1; then
        service docker start >>"$LOG_FILE" 2>&1 || true
    fi

    docker --version | tee -a "$LOG_FILE"
    docker info >>"$LOG_FILE" 2>&1 || die "Docker установлен, но демон недоступен; проверьте docker info"
    log_info "Docker Engine готов"
}

ensure_tun_device() {
    if [ -c /dev/net/tun ]; then
        chmod 666 /dev/net/tun 2>/dev/null || true
        return 0
    fi
    if command -v modprobe >/dev/null 2>&1; then
        modprobe tun >>"$LOG_FILE" 2>&1 || true
    fi
    mkdir -p /dev/net
    [ -c /dev/net/tun ] || mknod /dev/net/tun c 10 200 >>"$LOG_FILE" 2>&1 || true
    [ -c /dev/net/tun ] || die "Устройство /dev/net/tun недоступно на этом VPS"
    chmod 666 /dev/net/tun 2>/dev/null || true
}

check_kernel_compatibility() {
    [ "$(uname -s)" = "Linux" ] || die "CSQTT Server поддерживает только Linux"
    local release base major minor
    release="$(uname -r)"
    base="${release%%-*}"
    major="${base%%.*}"
    base="${base#*.}"
    minor="${base%%.*}"
    case "$major:$minor" in
        *[!0-9:]*|:*) log_warn "Не удалось разобрать версию ядра $release; решающей будет рабочая проверка TUN" ;;
        *)
            if [ "$major" -lt 5 ] || { [ "$major" -eq 5 ] && [ "$minor" -lt 5 ]; }; then
                log_warn "Ядро $release старше проверенного диапазона 5.5–7.x; установка продолжится только при успешных рабочих пробах"
            else
                log_info "Ядро Linux $release входит в поддерживаемый диапазон"
            fi
            ;;
    esac
}

load_kernel_network_modules() {
    command -v modprobe >/dev/null 2>&1 || return 0
    local module
    for module in tun nf_tables ip_tables iptable_filter iptable_nat iptable_mangle nf_nat nf_conntrack xt_comment xt_MASQUERADE xt_TCPMSS; do
        modprobe "$module" >>"$LOG_FILE" 2>&1 || true
    done
}

probe_tun_support() {
    ensure_tun_device
    local probe_iface="cqtprobe$(( $$ % 100000 ))"
    ip link del "$probe_iface" >>"$LOG_FILE" 2>&1 || true
    if ! ip tuntap add dev "$probe_iface" mode tun >>"$LOG_FILE" 2>&1; then
        load_kernel_network_modules
        ip link del "$probe_iface" >>"$LOG_FILE" 2>&1 || true
        if ! ip tuntap add dev "$probe_iface" mode tun >>"$LOG_FILE" 2>&1; then
            die "TUN существует, но ядро или политика VPS запрещает TUNSETIFF/CAP_NET_ADMIN"
        fi
    fi
    if ! ip link del "$probe_iface" >>"$LOG_FILE" 2>&1; then
        die "Рабочий TUN создан, но тестовый интерфейс $probe_iface не удалось удалить"
    fi
    log_info "TUN проверен реальным созданием и удалением интерфейса"
}

run_platform_preflight() {
    prog 0.35 "Совместимость ядра..."
    log_step "Проверка совместимости ядра и VPS..."
    check_kernel_compatibility
    probe_tun_support
}

install_docker_boot_prerequisite() {
    command -v systemctl >/dev/null 2>&1 || \
        die "Docker-режим требует systemd для подготовки TUN до автозапуска контейнера"

    ensure_csqtt_directory /usr/local/lib/csqtt 755
    cat > "$CSQTT_DOCKER_PREREQ_HELPER" << 'PREREQ'
#!/bin/sh
set -eu

if [ ! -c /dev/net/tun ]; then
    command -v modprobe >/dev/null 2>&1 && modprobe tun || true
    mkdir -p /dev/net
    [ -c /dev/net/tun ] || mknod /dev/net/tun c 10 200
fi

[ -c /dev/net/tun ] || exit 20
chmod 666 /dev/net/tun 2>/dev/null || true
mkdir -p /run/csqtt
chmod 0755 /run/csqtt 2>/dev/null || true
rm -f /run/csqtt/docker-network.ready
touch /run/xtables.lock
chmod 600 /run/xtables.lock 2>/dev/null || true
PREREQ
    chmod 0755 "$CSQTT_DOCKER_PREREQ_HELPER"

    local unit_tmp
    unit_tmp="$(mktemp)"
    cat > "$unit_tmp" << PREREQUNIT
[Unit]
Description=Prepare CSQTT Docker TUN and xtables lock
After=local-fs.target
Before=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=${CSQTT_DOCKER_PREREQ_HELPER}

[Install]
RequiredBy=docker.service
PREREQUNIT
    install -m 0644 "$unit_tmp" "/etc/systemd/system/${CSQTT_DOCKER_PREREQ_SERVICE}"
    rm -f "$unit_tmp"
    systemctl daemon-reload || die "Не удалось обновить systemd для Docker prerequisites"
    systemctl enable "$CSQTT_DOCKER_PREREQ_SERVICE" >/dev/null 2>&1 || \
        die "Не удалось включить Docker prerequisites"
    "$CSQTT_DOCKER_PREREQ_HELPER" || \
        die "Не удалось обновить Docker runtime prerequisites"
    systemctl start "$CSQTT_DOCKER_PREREQ_SERVICE" || \
        die "Не удалось подготовить TUN/xtables lock для Docker"
}

write_csqtt_dockerfile() {
    local target="$1"
    cat > "$target" << 'DOCKERFILE'
FROM alpine:3.21
ARG CSQTT_BUILD_DNS_PRIMARY
ARG CSQTT_BUILD_DNS_SECONDARY
RUN set -eu; if [ -n "${CSQTT_BUILD_DNS_PRIMARY:-}" ]; then printf 'nameserver %s\n' "$CSQTT_BUILD_DNS_PRIMARY" > /etc/resolv.conf; fi; if [ -n "${CSQTT_BUILD_DNS_SECONDARY:-}" ]; then printf 'nameserver %s\n' "$CSQTT_BUILD_DNS_SECONDARY" >> /etc/resolv.conf; fi; timeout -k 5s 120s apk add --no-cache --no-progress ca-certificates iproute2 iptables; rm -rf /var/cache/apk/*
COPY csqtt /usr/local/bin/csqtt
COPY network-up.sh /usr/local/lib/csqtt/network-up.sh
COPY tun-recover.sh /usr/local/lib/csqtt/tun-recover.sh
RUN chmod 0755 /usr/local/bin/csqtt /usr/local/lib/csqtt/network-up.sh /usr/local/lib/csqtt/tun-recover.sh
ENTRYPOINT ["/bin/sh", "-ec", "/usr/local/lib/csqtt/network-up.sh; /usr/local/lib/csqtt/tun-recover.sh; exec /usr/local/bin/csqtt \"$@\"", "--"]
DOCKERFILE
}

docker_build_candidate_image() {
    local image="$1" context_dir="$2" primary_log fallback_log
    primary_log="$(mktemp)" || return 1
    if timeout -k 15s "${DOCKER_BUILD_TIMEOUT_SECONDS}s" docker build --network host -t "$image" "$context_dir" >"$primary_log" 2>&1; then
        cat "$primary_log" >>"$LOG_FILE"
        rm -f -- "$primary_log"
        return 0
    fi
    cat "$primary_log" >>"$LOG_FILE"
    if ! grep -Eqi 'Temporary failure resolving|Could not resolve|Name or service not known|Connection timed out|Could not connect|Network is unreachable|Failed to fetch|apk|returned a non-zero code: 124' "$primary_log"; then
        rm -f -- "$primary_log"
        return 1
    fi
    rm -f -- "$primary_log"
    log_warn "Docker build не получил DNS через сеть VPS; повтор с резервными DNS Яндекса"
    fallback_log="$(mktemp)" || return 1
    if timeout -k 15s "${DOCKER_BUILD_TIMEOUT_SECONDS}s" docker build --network default \
        --build-arg CSQTT_BUILD_DNS_PRIMARY=77.88.8.8 \
        --build-arg CSQTT_BUILD_DNS_SECONDARY=77.88.8.1 \
        -t "$image" "$context_dir" >"$fallback_log" 2>&1; then
        cat "$fallback_log" >>"$LOG_FILE"
        rm -f -- "$fallback_log"
        log_info "Docker-образ собран через резервные DNS Яндекса"
        return 0
    fi
    cat "$fallback_log" >>"$LOG_FILE"
    rm -f -- "$fallback_log"
    return 1
}

prepare_docker_candidate() {
    local source_binary="$1" context_dir
    [ -x "$source_binary" ] || \
        die "Docker-образ нельзя собрать без установленного бинарника csqtt" "$EXIT_PREFLIGHT_FAILED"
    ensure_tun_device
    DOCKER_CONTEXT_DIR="$(mktemp -d /tmp/csqtt-docker.XXXXXX)" || \
        die "Не удалось создать Docker staging context" "$EXIT_PREFLIGHT_FAILED"
    context_dir="$DOCKER_CONTEXT_DIR"
    install -m 0755 "$source_binary" "$context_dir/csqtt" || \
        die "Не удалось подготовить Docker-бинарник" "$EXIT_PREFLIGHT_FAILED"
    write_network_helper "$context_dir/network-up.sh" || \
        die "Не удалось подготовить Docker network helper" "$EXIT_PREFLIGHT_FAILED"
    write_tun_recovery_helper "$context_dir/tun-recover.sh" || \
        die "Не удалось подготовить Docker TUN recovery helper" "$EXIT_PREFLIGHT_FAILED"
    write_csqtt_dockerfile "$context_dir/Dockerfile"

    CSQTT_DOCKER_CANDIDATE_IMAGE="${CSQTT_DOCKER_IMAGE}-candidate-$$"
    if ! docker_build_candidate_image "$CSQTT_DOCKER_CANDIDATE_IMAGE" "$context_dir"; then
        tail -n 80 "$LOG_FILE" | sed 's/^/   >> /' >&2 || true
        die "Сборка Docker-образа CSQTT завершилась ошибкой" "$EXIT_PREFLIGHT_FAILED"
    fi
    probe_docker_runtime "$CSQTT_DOCKER_CANDIDATE_IMAGE"
    remove_managed_work_dir "$DOCKER_CONTEXT_DIR" || true
    DOCKER_CONTEXT_DIR=""
    log_info "Docker-образ прошёл проверку: $CSQTT_DOCKER_CANDIDATE_IMAGE"
}

setup_csqtt_docker() {
    prog 0.75 "Docker-образ..."
    [ -n "$CSQTT_DOCKER_CANDIDATE_IMAGE" ] || \
        die "Не найден проверенный Docker-кандидат" "$EXIT_PREFLIGHT_FAILED"
    docker tag "$CSQTT_DOCKER_CANDIDATE_IMAGE" "$CSQTT_DOCKER_IMAGE" || \
        die "Не удалось активировать проверенный Docker-образ"
    install_docker_boot_prerequisite
    log_info "Проверенный Docker-образ активирован: $CSQTT_DOCKER_IMAGE"
}

probe_docker_runtime() {
    local image="${1:-$CSQTT_DOCKER_IMAGE}"
    local probe_iface="cqtd$(( $$ % 100000 ))" probe_chain="CSQTT_D$(( $$ % 1000000 ))" output
    if ! output="$(docker run --rm \
        --network host \
        --cap-drop ALL \
        --cap-add NET_ADMIN \
        --cap-add NET_RAW \
        --device /dev/net/tun:/dev/net/tun \
        --security-opt seccomp=unconfined \
        --env "PROBE_IFACE=$probe_iface" \
        --env "PROBE_CHAIN=$probe_chain" \
        --entrypoint /bin/sh \
        "$image" -ec '
ip tuntap add dev "$PROBE_IFACE" mode tun
ip link del "$PROBE_IFACE"
for table in filter nat mangle; do
    iptables -w 2 -t "$table" -N "$PROBE_CHAIN"
    iptables -w 2 -t "$table" -X "$PROBE_CHAIN"
done
' 2>&1)"; then
        printf '%s\n' "$output" >>"$LOG_FILE"
        die "Docker не прошёл рабочую проверку TUN или netfilter"
    fi
    printf '%s\n' "$output" >>"$LOG_FILE"
    log_info "Docker runtime проверен: TUN/netfilter"
}

run_csqtt_docker_container() {
    docker run -d \
        --name "$CSQTT_DOCKER_CONTAINER" \
        --label com.csqtt.managed=true \
        --label com.csqtt.component=server \
        --restart unless-stopped \
        --network host \
        --cap-drop ALL \
        --cap-add NET_ADMIN \
        --cap-add NET_RAW \
        --cap-add NET_BIND_SERVICE \
        --device /dev/net/tun:/dev/net/tun \
        --security-opt seccomp=unconfined \
        --stop-timeout 5 \
        --ulimit nofile=65535:65535 \
        --env-file "$CSQTT_ENV_FILE" \
        --env CSQTT_SERVICE_MANAGER=docker \
        --volume "$CSQTT_CONFIG_DIR:$CSQTT_CONFIG_DIR" \
        --mount "type=bind,src=${CSQTT_RUNTIME_DIR},dst=${CSQTT_RUNTIME_DIR}" \
        --mount "type=bind,src=/run/xtables.lock,dst=/run/xtables.lock" \
        "$CSQTT_DOCKER_IMAGE" \
        --listen "0.0.0.0:${PEER_PORT}" \
        --web-port "$WEB_PORT" \
        --config-dir "$CSQTT_CONFIG_DIR" >>"$LOG_FILE" 2>&1 || \
        die "Не удалось создать Docker-контейнер CSQTT"
}

print_docker_deploy_summary() {
    local main_pid="$1"
    echo ""
    echo "✓ Деплой успешно завершён"
    echo "CSQTT_DEPLOY_OK"
    echo "   Режим:      Docker"
    echo "   Бинарник:   /usr/local/bin/csqtt"
    echo "   Конфиг:     ${CSQTT_CONFIG_DIR}"
    echo "   Контейнер:  ${CSQTT_DOCKER_CONTAINER}"
    echo "   Образ:      ${CSQTT_DOCKER_IMAGE}"
    echo "   PEER:       порт ${PEER_PORT}"
    echo "   SSH:        порт ${SSH_PORT}"
    echo "   WEB:        $(panel_web_port_label)"
    echo "   PID:        ${main_pid}"
    echo "   Логи:       docker logs -f ${CSQTT_DOCKER_CONTAINER}"
    echo "   Статус:     docker ps --filter name=${CSQTT_DOCKER_CONTAINER}"
    echo ""
}

start_csqtt_docker() {
    prog 0.90 "Запуск Docker..."
    ensure_tun_device
    remove_all_csqtt_docker_containers
    assert_peer_port_is_available
    remove_csqtt_tun_interface || die "Не удалось освободить TUN-интерфейс $CSQTT_IFACE перед запуском Docker"

    run_csqtt_docker_container

    local state running main_pid restarts candidate_pid=0 candidate_restarts=0 found=0
    local attempts=0
    while [ "$attempts" -lt 48 ]; do
        attempts=$((attempts + 1))
        state="$(docker inspect --format '{{.State.Running}}|{{.State.Pid}}|{{.RestartCount}}' "$CSQTT_DOCKER_CONTAINER" 2>/dev/null || echo 'false|0|-1')"
        IFS='|' read -r running main_pid restarts <<< "$state"
        case "$main_pid" in ''|*[!0-9]*) main_pid=0 ;; esac
        case "$restarts" in ''|*[!0-9]*) restarts=-1 ;; esac
        if [ "$running" = "true" ] && [ "$main_pid" -gt 1 ] && ip link show "$CSQTT_IFACE" >/dev/null 2>&1; then
            candidate_pid="$main_pid"
            candidate_restarts="$restarts"
            found=1
            break
        fi
        sleep 0.25
    done

    if [ "$found" -ne 1 ]; then
        docker logs --tail 50 "$CSQTT_DOCKER_CONTAINER" 2>&1 | sed 's/^/   >> /' || true
        die "Docker CSQTT не запустился или TUN $CSQTT_IFACE не создан"
    fi

    sleep "$START_STABILITY_SECONDS"
    state="$(docker inspect --format '{{.State.Running}}|{{.State.Pid}}|{{.RestartCount}}' "$CSQTT_DOCKER_CONTAINER" 2>/dev/null || echo 'false|0|-1')"
    IFS='|' read -r running main_pid restarts <<< "$state"
    if [ "$running" != "true" ] || [ "$main_pid" != "$candidate_pid" ] || [ "$restarts" != "$candidate_restarts" ] || \
       ! ip link show "$CSQTT_IFACE" >/dev/null 2>&1; then
        docker logs --tail 50 "$CSQTT_DOCKER_CONTAINER" 2>&1 | sed 's/^/   >> /' || true
        die "Docker CSQTT вошёл в crash-loop во время стабильной проверки"
    fi

    verify_configured_network
    verify_web_panel
    finish_deployment
    prog 1.0 "Готово!"
    print_docker_deploy_summary "$candidate_pid"
}

setup_csqtt_service() {
    prog 0.75 "Сервис..."
    echo "🔧 Создание systemd-сервиса CSQTT..."

    local unit_tmp
    unit_tmp="$(mktemp)"
    cat > "$unit_tmp" << CSQTTSVC
[Unit]
Description=CSQTT VPN Server
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=60
StartLimitBurst=3

[Service]
Type=simple
EnvironmentFile=-${CSQTT_ENV_FILE}
Environment=CSQTT_SERVICE_MANAGER=systemd
ExecStartPre=/usr/local/lib/csqtt/network-up.sh
ExecStartPre=/usr/local/lib/csqtt/tun-recover.sh
ExecStart=/usr/local/bin/csqtt --listen 0.0.0.0:${PEER_PORT} --web-port ${WEB_PORT} --config-dir ${CSQTT_CONFIG_DIR}
Restart=on-failure
RestartSec=3
KillMode=control-group
TimeoutStartSec=30
TimeoutStopSec=5
Nice=-20

[Install]
WantedBy=multi-user.target
CSQTTSVC

    if [ ! -f /etc/systemd/system/csqtt.service ] || ! cmp -s "$unit_tmp" /etc/systemd/system/csqtt.service; then
        install -m 0644 "$unit_tmp" /etc/systemd/system/csqtt.service
        SYSTEMD_NEEDS_RELOAD=1
    fi
    rm -f "$unit_tmp"

    if [ "$SYSTEMD_NEEDS_RELOAD" -eq 1 ]; then
        systemctl daemon-reload || die "systemctl daemon-reload завершился ошибкой"
        SYSTEMD_NEEDS_RELOAD=0
    fi

    mkdir -p /etc/systemd/system/multi-user.target.wants
    ln -sfn /etc/systemd/system/csqtt.service /etc/systemd/system/multi-user.target.wants/csqtt.service
    echo "✓ Сервис csqtt.service создан и включён"
}

diagnose_systemd_failure() {
    local props result="unknown" exec_code="unknown" exec_status="unknown" restarts="unknown" active="unknown" sub="unknown" pid="0"
    props="$(systemctl show csqtt -p Result -p ExecMainCode -p ExecMainStatus -p NRestarts -p ActiveState -p SubState -p MainPID 2>/dev/null || true)"
    while IFS='=' read -r key value; do
        case "$key" in
            Result) result="$value" ;; ExecMainCode) exec_code="$value" ;; ExecMainStatus) exec_status="$value" ;;
            NRestarts) restarts="$value" ;; ActiveState) active="$value" ;; SubState) sub="$value" ;; MainPID) pid="$value" ;;
        esac
    done <<< "$props"
    log_error "systemd: active=$active/$sub result=$result code=$exec_code status=$exec_status restarts=$restarts pid=$pid"
    journalctl -u csqtt -b -n 50 --no-pager 2>/dev/null | sed 's/^/   >> /' | tee -a "$LOG_FILE" || true
}

print_systemd_deploy_summary() {
    local main_pid="$1"
    echo ""
    echo "══════════════════════════════════════════════════════════════"
    echo "✓ Деплой успешно завершён"
    echo "CSQTT_DEPLOY_OK"
    echo "   Режим: systemd"
    echo "   Бинарник: /usr/local/bin/csqtt"
    echo "   Unit: /etc/systemd/system/csqtt.service"
    echo "   Конфиг: ${CSQTT_CONFIG_DIR}"
    echo "   NAT:  MASQUERADE (восстанавливается после reboot)"
    echo "   PEER: порт ${PEER_PORT}"
    echo "   SSH:  порт ${SSH_PORT}"
    echo "   WEB:  $(panel_web_port_label)"
    echo "   PID:  ${main_pid}"
    echo "   Логи:   journalctl -u csqtt -f"
    echo "   Статус: systemctl status csqtt"
    echo "══════════════════════════════════════════════════════════════"
    echo ""
}

start_csqtt() {
    if [ "$DEPLOY_MODE" = "docker" ]; then
        start_csqtt_docker
        return
    fi
    prog 0.90 "Запуск..."
    echo "🚀 Запуск CSQTT VPN Server..."

    [ -x /usr/local/bin/csqtt ] || die "Исполняемый файл /usr/local/bin/csqtt не установлен"

    if ! systemctl start csqtt; then
        systemctl reset-failed csqtt >/dev/null 2>&1 || true
        if ! systemctl start csqtt; then
            diagnose_systemd_failure
            die "systemctl start csqtt завершился ошибкой"
        fi
    fi

    local props key value active sub main_pid restarts
    local candidate_pid=0 candidate_restarts=0 attempts=0 found=0
    while [ "$attempts" -lt 48 ]; do
        attempts=$((attempts + 1))
        active=""; sub=""; main_pid=0; restarts=0
        props="$(systemctl show csqtt -p ActiveState -p SubState -p MainPID -p NRestarts 2>/dev/null || true)"
        while IFS='=' read -r key value; do
            case "$key" in
                ActiveState) active="$value" ;; SubState) sub="$value" ;; MainPID) main_pid="$value" ;; NRestarts) restarts="$value" ;;
            esac
        done <<< "$props"
        case "$main_pid" in ''|*[!0-9]*) main_pid=0 ;; esac
        case "$restarts" in ''|*[!0-9]*) restarts=0 ;; esac

        if [ "$active" = "active" ] && [ "$sub" = "running" ] && [ "$main_pid" -gt 1 ] && \
           kill -0 "$main_pid" 2>/dev/null && ip link show "$CSQTT_IFACE" >/dev/null 2>&1; then
            candidate_pid="$main_pid"
            candidate_restarts="$restarts"
            found=1
            break
        fi
        sleep 0.25
    done

    if [ "$found" -ne 1 ]; then
        diagnose_systemd_failure
        die "CSQTT не запустился: сервис не running или TUN $CSQTT_IFACE не создан"
    fi

    sleep "$START_STABILITY_SECONDS"
    active=""; sub=""; main_pid=0; restarts=0
    props="$(systemctl show csqtt -p ActiveState -p SubState -p MainPID -p NRestarts 2>/dev/null || true)"
    while IFS='=' read -r key value; do
        case "$key" in
            ActiveState) active="$value" ;; SubState) sub="$value" ;; MainPID) main_pid="$value" ;; NRestarts) restarts="$value" ;;
        esac
    done <<< "$props"
    case "$main_pid" in ''|*[!0-9]*) main_pid=0 ;; esac
    case "$restarts" in ''|*[!0-9]*) restarts=0 ;; esac

    if [ "$active" != "active" ] || [ "$sub" != "running" ] || [ "$main_pid" != "$candidate_pid" ] || \
       [ "$restarts" != "$candidate_restarts" ] || ! kill -0 "$candidate_pid" 2>/dev/null || \
       ! ip link show "$CSQTT_IFACE" >/dev/null 2>&1; then
        diagnose_systemd_failure
        die "CSQTT вошёл в crash-loop или потерял TUN во время стабильной проверки"
    fi

    verify_configured_network
    verify_web_panel
    [ "$restarts" -eq 0 ] || log_warn "Во время старта csqtt перезапустился $restarts раз(а), затем стабилизировался"
    log_info "CSQTT сервер подтверждён: PID $candidate_pid, интерфейс $CSQTT_IFACE активен"
    finish_deployment

    prog 1.0 "Готово!"
    print_systemd_deploy_summary "$candidate_pid"
}

do_uninstall() {
    log_step "Удаление CSQTT..."

    remove_all_csqtt_docker_containers
    if command -v docker >/dev/null 2>&1; then
        timeout 5 docker image rm "$CSQTT_DOCKER_IMAGE" >/dev/null 2>&1 || true
    fi

    if command -v systemctl >/dev/null 2>&1; then
        systemctl disable --now "$CSQTT_LE_TIMER" >/dev/null 2>&1 || true
        systemctl stop "$CSQTT_LE_SERVICE" --no-block >/dev/null 2>&1 || true
        stop_csqtt_docker_prereq
        stop_all_running_csqtt_systemd_units
    fi
    force_stop_csqtt_processes || log_warn "Не все процессы CSQTT завершились до удаления runtime"
    remove_all_csqtt_systemd_units
    rm -f "/etc/systemd/system/${CSQTT_DOCKER_PREREQ_SERVICE}"
    rm -f "/etc/systemd/system/docker.service.requires/${CSQTT_DOCKER_PREREQ_SERVICE}"
    rm -f "/etc/systemd/system/docker.service.wants/${CSQTT_DOCKER_PREREQ_SERVICE}"
    rm -f "/etc/systemd/system/${CSQTT_LE_SERVICE}" "/etc/systemd/system/${CSQTT_LE_TIMER}" /etc/cron.d/csqtt-letsencrypt
    command -v systemctl >/dev/null 2>&1 && systemctl daemon-reload >/dev/null 2>&1 || true

    cleanup_legacy_proxy_interfaces
    remove_csqtt_tun_interface || log_warn "Не удалось удалить TUN-интерфейс $CSQTT_IFACE во время uninstall"
    cleanup_csqtt_netfilter_rules || true

    rm -f /usr/local/bin/csqtt /usr/local/bin/csqtt-cascade "$CSQTT_LE_RENEW_HELPER"
    rm -f /usr/local/bin/hev-socks5-tunnel /usr/local/bin/hev-socks5-server
    rm -rf /run/csqtt /usr/local/lib/csqtt /var/lib/csqtt /opt/csqtt-certbot
    secure_persistent_state
    rm -f "$CSQTT_SYSCTL_FILE" "$CSQTT_UDP_SYSCTL_FILE"
    sysctl --system >/dev/null 2>&1 || true

    log_info "CSQTT удалён. SQLite state и конфигурация сохранены в ${CSQTT_CONFIG_DIR}"
}

do_status() {
    echo "Статус CSQTT:"
    echo ""
    if command -v docker >/dev/null 2>&1 && docker inspect "$CSQTT_DOCKER_CONTAINER" >/dev/null 2>&1; then
        if [ "$(docker inspect --format '{{.State.Running}}' "$CSQTT_DOCKER_CONTAINER" 2>/dev/null)" = "true" ]; then
            log_info "Docker-контейнер: АКТИВЕН"
        else
            log_warn "Docker-контейнер: НЕ АКТИВЕН"
        fi
    fi
    if command -v systemctl >/dev/null 2>&1 && systemctl is-active csqtt &>/dev/null; then
        log_info "Сервис: АКТИВЕН"
    else
        log_warn "Сервис: НЕ АКТИВЕН"
    fi
    if [ -f /usr/local/bin/csqtt ]; then
        log_info "Бинарник: установлен"
    else
        log_warn "Бинарник: НЕ найден"
    fi
    if ip link show "$CSQTT_IFACE" &>/dev/null; then
        log_info "CSQTT интерфейс ($CSQTT_IFACE): активен"
    else
        log_warn "CSQTT интерфейс ($CSQTT_IFACE): не активен"
    fi
}

handle_unexpected_error() {
    local line="$1" status="$2"
    trap - ERR
    log_error "Неперехваченная ошибка deploy.sh (строка $line, exit=$status)"
    printf 'CSQTT_DEPLOY_ERROR|%s|Неперехваченная ошибка deploy.sh (строка %s, exit=%s)\n' "$DEPLOY_PHASE" "$line" "$status"
    cleanup_deploy_uploads
    case "$DEPLOY_PHASE" in
        validation) exit "$EXIT_INVALID_ARGUMENT" ;;
        preflight) exit "$EXIT_PREFLIGHT_FAILED" ;;
        *) exit "$status" ;;
    esac
}

trap 'handle_unexpected_error "$LINENO" "$?"' ERR

main() {
    local action="${1:-install}"
    check_root
    mkdir -p "$(dirname "$LOG_FILE")"
    echo "=== CSQTT Installer v${SCRIPT_VERSION} — $(date) ===" >> "$LOG_FILE"

    case "$action" in
        status|--status|-s)       print_installer_header; do_status ;;
        uninstall|--uninstall|-u)
            print_installer_header
            DEPLOY_PHASE="validation"
            validate_port "CSQTT_PEER_PORT" "$PEER_PORT"
            do_uninstall
            ;;
        install|--install|-i)
            DEPLOY_PHASE="validation"
            case "$DEPLOY_MODE" in
                systemd|docker) ;;
                *) die "CSQTT_DEPLOY_MODE должен быть systemd или docker" ;;
            esac
            validate_port "CSQTT_PEER_PORT" "$PEER_PORT"
            validate_port "CSQTT_SSH_PORT" "$SSH_PORT"
            resolve_panel_mode
            # Баннер печатается после разбора режима: до него WEB_PORT ещё
            # равен значению телефона и в доменном режиме показывал бы 46002.
            print_installer_header
            validate_port "CSQTT_WEB_PORT" "$WEB_PORT"
            validate_positive_seconds "CSQTT_DOCKER_BUILD_TIMEOUT_SECONDS" "$DOCKER_BUILD_TIMEOUT_SECONDS"
            validate_distinct_network_ports

            local total_started=$SECONDS
            detect_os
            run_timed "зависимости" install_prerequisites
            require_runtime_tools
            if [ "$DEPLOY_MODE" = "docker" ]; then
                prog 0.12 "Docker..."
                run_timed "Docker" ensure_docker
            fi
            run_timed "sysctl" setup_sysctl
            run_timed "CPU mitigations" tune_cpu_mitigations

            run_timed "проверка upload" prepare_uploaded_release
            if [ "$DEPLOY_MODE" = "docker" ]; then
                run_timed "Docker preflight" prepare_docker_candidate "$UPLOAD_BINARY"
            fi
            run_domain_mode_preflight
            DEPLOY_PHASE="cutover"
            run_timed "переключение runtime" csqtt_cleanup
            run_timed "preflight" run_platform_preflight
            detect_firewall
            run_timed "NAT/firewall" setup_nat_and_firewall
            install_network_helper
            setup_csqtt_environment
            run_timed "Let’s Encrypt" setup_web_panel_tls
            DEPLOY_PHASE="activation"
            run_timed "бинарник" setup_csqtt_binary
            run_timed "принудительная остановка" force_stop_csqtt_processes
            # Порт панели проверяется после остановки старого runtime и до попытки
            # его занять. В docker-режиме контейнер стартует в setup_csqtt_docker,
            # и без этой проверки занятый 443 дал бы невнятный crash-loop вместо
            # прямого сообщения о занятом порте.
            assert_web_port_is_available
            if [ "$DEPLOY_MODE" = "docker" ]; then
                run_timed "Docker activation" setup_csqtt_docker
            else
                run_timed "systemd unit" setup_csqtt_service
            fi
            run_timed "запуск" start_csqtt
            log_info "Общее время deploy.sh: $((SECONDS - total_started))с"
            ;;
        *) die "Неизвестная команда установщика: $action" ;;
    esac
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    main "$@"
fi
