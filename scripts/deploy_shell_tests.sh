#!/usr/bin/env bash
#
# Поведенческие тесты app/src/main/assets/deploy.sh.
#
#   НИКОГДА НЕ ЗАПУСКАТЬ НА БОЕВОЙ МАШИНЕ.
#
# Раннер исполняет отдельные функции установщика. Некоторые из них на живой
# системе разрушительны: `all_csqtt_process_pids` обходит /proc и убивает любой
# процесс csqtt (на рабочей машине может жить настоящий rust-клиент),
# `remove_csqtt_tun_interface` делает настоящий `ip link del csqtt1` (это
# TUN-интерфейс самого проекта), `remove_all_csqtt_docker_containers` — настоящий
# `docker rm -f`, а пути /usr/local/bin/csqtt, /usr/local/lib/csqtt и
# /etc/systemd/system зашиты.
#
# Поэтому:
#   1. раннер отказывается работать под root (обойти можно только явным
#      CSQTT_ALLOW_ROOT_TESTS=1, и это осознанное решение того, кто это делает);
#   2. `csqtt_cleanup` из раннера не вызывается НИКОГДА;
#   3. certbot, iptables, systemctl, ss, install, docker, ip, openssl и curl
#      подменяются заглушками в $MOCK_BIN, которые первыми стоят в PATH.
#
# Запуск: ./scripts/deploy_shell_tests.sh
# Один кейс: ./scripts/deploy_shell_tests.sh test_domain_selects_domain_mode

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd -P)"
DEPLOY_SCRIPT="${REPO_ROOT}/app/src/main/assets/deploy.sh"

ORIGINAL_PATH="$PATH"
PASSED=0
FAILED=0
FAILED_NAMES=()

if [ ! -f "$DEPLOY_SCRIPT" ]; then
    printf 'Не найден deploy.sh: %s\n' "$DEPLOY_SCRIPT" >&2
    exit 1
fi

# Предохранитель: под root заглушки обходятся не везде, а rm -f /usr/local/bin/csqtt
# и ip link del сработают по-настоящему. CSQTT_EUID_OVERRIDE существует только
# для того, чтобы сам предохранитель был проверяемым тестом.
require_non_root() {
    local euid="${CSQTT_EUID_OVERRIDE:-${EUID}}"
    [ "$euid" -eq 0 ] || return 0
    [ "${CSQTT_ALLOW_ROOT_TESTS:-}" = "1" ] && return 0
    printf 'deploy_shell_tests.sh: запуск под root запрещён: тесты вызывают разрушительные\n' >&2
    printf 'функции установщика. Если это осознанное решение, задайте CSQTT_ALLOW_ROOT_TESTS=1.\n' >&2
    return 1
}

require_non_root || exit 1

# ---------------------------------------------------------------------------
# Песочница и заглушки
# ---------------------------------------------------------------------------

SANDBOX=""
MOCK_BIN=""
CALL_LOG=""
FAKE_LE=""
FAKE_CFG=""
STUB_STATE_DIR=""
T_OWNER_PID=""
T_TEST_NAME=""

STUB_NAMES=(certbot iptables systemctl ss install docker ip openssl curl)

write_stub() {
    local name="$1"
    local file="$MOCK_BIN/$name"
    local real_openssl real_install
    real_openssl="$(PATH="$ORIGINAL_PATH" command -v openssl || true)"
    real_install="$(PATH="$ORIGINAL_PATH" command -v install || true)"
    {
        printf '%s\n' '#!/usr/bin/env bash' 'set -u'
        printf 'stub_name=%q\n' "$name"
        printf 'real_openssl=%q\n' "$real_openssl"
        printf 'real_install=%q\n' "$real_install"
        cat <<'STUB_COMMON'
state_dir="${STUB_STATE_DIR:?}"
stdout_file="$state_dir/$stub_name.stdout"
exit_file="$state_dir/$stub_name.exit"
fail_file="$state_dir/$stub_name.fail_times"

log_call() {
    printf '%s %s\n' "$stub_name" "$*" >> "${CALL_LOG:?}"
}

# Бюджет неуспехов: t_stub_fail_times <команда> <n>.
consume_fail() {
    local remaining
    [ -f "$fail_file" ] || return 1
    remaining="$(cat "$fail_file" 2>/dev/null || true)"
    case "$remaining" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ "$remaining" -gt 0 ] || return 1
    printf '%s\n' "$((remaining - 1))" > "$fail_file"
    return 0
}

print_stdout() {
    [ -f "$stdout_file" ] && cat "$stdout_file"
    return 0
}

forced_exit_code() {
    local code=""
    [ -f "$exit_file" ] && code="$(cat "$exit_file" 2>/dev/null || true)"
    case "$code" in
        ''|*[!0-9]*) printf '%s' 0 ;;
        *) printf '%s' "$code" ;;
    esac
}

finish() {
    exit "$(forced_exit_code)"
}
STUB_COMMON

        case "$name" in
            certbot)
                cat <<'STUB_CERTBOT'
# `certbot certonly --help all` используется как проба поддержки нужного режиму
# флага. Такой вызов НЕ пишется в CALL_LOG: иначе ensure_letsencrypt_client решит,
# что клиент не поддерживает флаг, уйдёт в python3 -m venv /opt/csqtt-certbot
# и упадёт с отказом записи.
if printf '%s' " $* " | grep -q -- ' --help '; then
    if [ "$(cat "$state_dir/certbot.ip_support" 2>/dev/null || printf '%s' 1)" = "1" ]; then
        printf '%s\n' '--ip-address' '--domains'
    fi
    exit 0
fi
log_call "$@"
if printf '%s' " $* " | grep -q -- ' certonly '; then
    if consume_fail; then
        exit 1
    fi
    [ "$(forced_exit_code)" -eq 0 ] || finish
    cert_name=""
    domain=""
    ip_address=""
    previous=""
    for argument in "$@"; do
        case "$previous" in
            --cert-name) cert_name="$argument" ;;
            --domains|-d) domain="$argument" ;;
            --ip-address) ip_address="$argument" ;;
        esac
        case "$argument" in
            --cert-name=*) cert_name="${argument#--cert-name=}" ;;
            --domains=*) domain="${argument#--domains=}" ;;
            --ip-address=*) ip_address="${argument#--ip-address=}" ;;
        esac
        previous="$argument"
    done
    if [ -z "$cert_name" ]; then
        exit 0
    fi
    # «Выпуск» обязан быть настоящим сертификатом: установщик затем читает
    # файлы, разбирает issuer и SAN. Поэтому сертификат генерируется реальным
    # openssl, но вызов пишется в CALL_LOG под именем certbot-fixture, чтобы
    # проверки «в доменном режиме нет openssl req -x509» не ловили фикстуру.
    common_name="${domain:-$ip_address}"
    if [ -n "$domain" ]; then
        san="DNS:${domain}"
    else
        san="IP:${ip_address}"
    fi
    printf 'certbot-fixture %s\n' "req -x509 -newkey rsa:2048 -nodes -days 90 -subj /O=Let's Encrypt/CN=${common_name} -addext subjectAltName=${san}" >> "$CALL_LOG"
    mkdir -p "$FAKE_LE/live/$cert_name"
    "$real_openssl" req -x509 -newkey rsa:2048 -nodes -days 90 \
        -subj "/O=Let's Encrypt/CN=${common_name}" \
        -addext "subjectAltName = ${san}" \
        -keyout "$FAKE_LE/live/$cert_name/privkey.pem" \
        -out "$FAKE_LE/live/$cert_name/fullchain.pem" >/dev/null 2>&1 || exit 1
    exit 0
fi
print_stdout
finish
STUB_CERTBOT
                ;;
            openssl)
                cat <<'STUB_OPENSSL'
# Наблюдение и настоящее поведение одновременно: SAN, issuer и checkend
# разбирают реальный сертификат.
log_call "$@"
exec "$real_openssl" "$@"
STUB_OPENSSL
                ;;
            install)
                cat <<'STUB_INSTALL'
# Бюджет неуспехов t_stub_fail_times относится только к установке файлов:
# install -d в write_letsencrypt_renewal_helper идёт первым и не должен
# съедать отказ активации сертификата (Review Focus #4).
case " $* " in
    *" -d "*)
        log_call "$@"
        exec "$real_install" "$@"
        ;;
esac
log_call "$@"
if consume_fail; then
    exit 1
fi
[ "$(forced_exit_code)" -eq 0 ] || finish
exec "$real_install" "$@"
STUB_INSTALL
                ;;
            systemctl)
                cat <<'STUB_SYSTEMCTL'
log_call "$@"
# Момент включения таймера фиксируется вместе с фактом существования нового
# хелпера: так проверяется, что легаси-файл удаляется не раньше, чем появится
# letsencrypt.env и хелпер.
case " $* " in
    *" enable --now "*)
        if [ -f "${CSQTT_LE_RENEW_HELPER:-}" ]; then
            printf '%s\n' 'systemctl-marker renew-helper=present' >> "${CALL_LOG:?}"
        else
            printf '%s\n' 'systemctl-marker renew-helper=missing' >> "${CALL_LOG:?}"
        fi
        ;;
esac
if consume_fail; then
    exit 1
fi
print_stdout
finish
STUB_SYSTEMCTL
                ;;
            iptables)
                cat <<'STUB_IPTABLES'
# Для iptables бюджет неуспехов из t_stub_fail_times относится ТОЛЬКО к -D:
# ограниченный цикл удаления проверяется и на успехе, и на постоянном неуспехе,
# а -S/-C/-I при этом должны работать штатно.
verb="$1"
if [ "$verb" = "-w" ]; then
    verb="$3"
fi
case "$verb" in
    -S)
        log_call "$@"
        print_stdout
        exit 0
        ;;
    -D)
        log_call "$@"
        if consume_fail; then
            exit 1
        fi
        finish
        ;;
esac
log_call "$@"
if consume_fail; then
    exit 1
fi
print_stdout
finish
STUB_IPTABLES
                ;;
            *)
                cat <<'STUB_PLAIN'
log_call "$@"
if consume_fail; then
    exit 1
fi
print_stdout
finish
STUB_PLAIN
                ;;
        esac
    } > "$file"
    chmod 0755 "$file"
}

write_stubs() {
    local name
    for name in "${STUB_NAMES[@]}"; do
        write_stub "$name"
    done
}

t_begin() {
    T_TEST_NAME="$1"
    SANDBOX="$(mktemp -d "${TMPDIR:-/tmp}/csqtt-deploy-tests.XXXXXX")"
    MOCK_BIN="$SANDBOX/bin"
    STUB_STATE_DIR="$SANDBOX/state"
    CALL_LOG="$SANDBOX/calls.log"
    FAKE_LE="$SANDBOX/le"
    FAKE_CFG="$SANDBOX/cfg"
    mkdir -p "$MOCK_BIN" "$STUB_STATE_DIR" "$FAKE_LE" "$FAKE_CFG"
    : > "$CALL_LOG"
    write_stubs
    T_OWNER_PID="${BASHPID:-$$}"
    # shellcheck disable=SC2064
    trap 't_end' EXIT

    export MOCK_BIN STUB_STATE_DIR CALL_LOG FAKE_LE FAKE_CFG
    export PATH="$MOCK_BIN:$ORIGINAL_PATH"
    export CSQTT_XT_WAIT=1
    export CSQTT_CONFIG_DIR="$FAKE_CFG"
    export CSQTT_INSTALL_LOG_FILE="$SANDBOX/install.log"
    export CSQTT_LE_ROOT="$FAKE_LE"
    export CSQTT_LE_STATE_FILE="$FAKE_CFG/letsencrypt.env"
    export CSQTT_LE_RENEW_HELPER="$FAKE_CFG/csqtt-letsencrypt-renew.sh"
    export CSQTT_LE_CERTBOT="$MOCK_BIN/certbot"
    export CSQTT_LE_VENV="$SANDBOX/certbot-venv"
    export CSQTT_UPLOAD_ENV_FILE="$SANDBOX/upload-web.env"
    export CSQTT_PUBLIC_IP=""
    unset PANEL_MODE PANEL_HOST CSQTT_LE_IDENTIFIER T_RENEW_BEFORE T_RENEW_HTTP_PORT
}

t_end() {
    # Вложенные подshell (t_run) наследуют EXIT-trap, поэтому песочницу убирает
    # только тот процесс, который её создал.
    [ "${BASHPID:-$$}" = "$T_OWNER_PID" ] || return 0
    [ -n "$SANDBOX" ] && rm -rf -- "$SANDBOX"
    SANDBOX=""
    export PATH="$ORIGINAL_PATH"
    return 0
}

# t_run '<код>' — подshell, в котором уже выполнен `source deploy.sh`.
# Все проверки состояния обязаны идти через него: константы скрипта readonly,
# повторный source в том же процессе падает на «readonly variable».
# deploy.sh в начале выполняет PANEL_MODE="" / PANEL_HOST="", поэтому режим,
# заданный кейсом через окружение, восстанавливается сразу после source.
t_run() {
    local code="$1" inherited_mode="${PANEL_MODE:-}" inherited_host="${PANEL_HOST:-}"
    (
        # shellcheck disable=SC1090
        . "$DEPLOY_SCRIPT"
        trap - ERR EXIT
        set +e
        PANEL_MODE="$inherited_mode"
        PANEL_HOST="$inherited_host"
        eval "$code"
    )
}

t_run_fails() {
    local code="$1" message="$2"
    if t_run "$code" >/dev/null 2>&1; then
        t_fail "$message: ожидался ненулевой код возврата"
    fi
}

t_log_calls() {
    printf '  --- журнал заглушек (%s) ---\n' "$CALL_LOG"
    if [ -s "$CALL_LOG" ]; then
        sed 's/^/  | /' "$CALL_LOG"
    else
        printf '  | (пусто)\n'
    fi
}

t_fail() {
    printf '  ✗ %s\n' "$*" >&2
    t_log_calls >&2
    exit 1
}

t_assert_contains() {
    local file="$1" needle="$2" message="$3"
    grep -Fq -- "$needle" "$file" || t_fail "$message (нет «$needle» в $file)"
}

t_assert_not_contains() {
    local file="$1" needle="$2" message="$3"
    if grep -Fq -- "$needle" "$file"; then
        t_fail "$message (найдено «$needle» в $file)"
    fi
}

t_assert_eq() {
    local actual="$1" expected="$2" message="$3"
    [ "$actual" = "$expected" ] || t_fail "$message (ожидалось «$expected», получено «$actual»)"
}

t_assert_failed() {
    local command="$1" message="$2"
    if eval "$command" >/dev/null 2>&1; then
        t_fail "$message: ожидался ненулевой код возврата"
    fi
}

t_count_calls() {
    local name="$1" pattern="${2:-}"
    awk -v name="$name" -v pattern="$pattern" '
        index($0, name " ") == 1 {
            if (pattern == "" || index($0, pattern) > 0) total++
        }
        END { print total + 0 }
    ' "$CALL_LOG"
}

t_stub_exit() {
    printf '%s\n' "$2" > "$STUB_STATE_DIR/$1.exit"
}

t_stub_fail_times() {
    printf '%s\n' "$2" > "$STUB_STATE_DIR/$1.fail_times"
}

t_stub_stdout() {
    printf '%s\n' "$2" > "$STUB_STATE_DIR/$1.stdout"
}

# Заглушка certbot печатает --ip-address на --help. Этот переключатель нужен,
# чтобы проверить обратный случай: клиент без поддержки IP-сертификатов.
t_stub_certbot_ip_support() {
    printf '%s\n' "$1" > "$STUB_STATE_DIR/certbot.ip_support"
}

# t_upload_env '<строка>' — дописывает строку в файл, который читает
# resolve_panel_mode ($UPLOAD_ENV_FILE).
t_upload_env() {
    printf '%s\n' "$1" >> "$SANDBOX/upload-web.env"
}

# t_seed_le_certificate <mode> <identifier> [days|expired] — кладёт в песочницу
# настоящий сертификат с issuer «O=Let's Encrypt» и state-файл, не вызывая
# certbot. Нужен там, где сертификат уже должен лежать на диске.
t_seed_le_certificate() {
    local mode="$1" identifier="$2" validity="${3:-90}" cert_name san source_dir
    case "$mode" in
        domain) cert_name="csqtt-${identifier}"; san="DNS:${identifier}" ;;
        *) cert_name="csqtt-ip-${identifier//./-}"; san="IP:${identifier}" ;;
    esac
    source_dir="$FAKE_LE/live/$cert_name"
    mkdir -p "$source_dir"
    if [ "$validity" = "expired" ]; then
        PATH="$ORIGINAL_PATH" openssl req -x509 -newkey rsa:2048 -nodes \
            -not_before 20200101000000Z -not_after 20200201000000Z \
            -subj "/O=Let's Encrypt/CN=${identifier}" \
            -addext "subjectAltName = ${san}" \
            -keyout "$source_dir/privkey.pem" -out "$source_dir/fullchain.pem" >/dev/null 2>&1
    else
        PATH="$ORIGINAL_PATH" openssl req -x509 -newkey rsa:2048 -nodes -days "$validity" \
            -subj "/O=Let's Encrypt/CN=${identifier}" \
            -addext "subjectAltName = ${san}" \
            -keyout "$source_dir/privkey.pem" -out "$source_dir/fullchain.pem" >/dev/null 2>&1
    fi
    cp "$source_dir/fullchain.pem" "$FAKE_CFG/web_cert.pem"
    cp "$source_dir/privkey.pem" "$FAKE_CFG/web_key.pem"
    printf 'MODE=%s\nIDENTIFIER=%s\nCERT_NAME=%s\n' "$mode" "$identifier" "$cert_name" \
        > "$FAKE_CFG/letsencrypt.env"
}

# t_write_renewal_helper — генерирует хелпер продления в песочнице. install -d
# /usr/local/lib под непривилегированным пользователем ругается в stderr; это
# ожидаемо и к содержимому хелпера отношения не имеет.
t_write_renewal_helper() {
    t_run 'write_letsencrypt_renewal_helper' 2>/dev/null
}

# t_run_renewal_helper — запускает сгенерированный хелпер. Подменяются только
# переменные окружения: CSQTT_CONFIG_DIR/CSQTT_LE_ROOT/CSQTT_LE_CERTBOT уже
# экспортированы t_begin, идентификатор и окно продления задаёт сам кейс.
t_run_renewal_helper() {
    CSQTT_LE_RENEW_BEFORE_SECONDS="${T_RENEW_BEFORE:-259200}" \
    CSQTT_LE_HTTP_PORT="${T_RENEW_HTTP_PORT:-80}" \
    CSQTT_DEPLOY_MODE=systemd \
        bash "$FAKE_CFG/csqtt-letsencrypt-renew.sh"
}

# ---------------------------------------------------------------------------
# Кейсы: Task 1 — тестируемость и раннер
# ---------------------------------------------------------------------------

test_smoke_source_does_not_run_main() {
    t_begin "smoke: source does not run main"
    t_run 'true'
    t_assert_eq "$?" "0" "source deploy.sh не должен запускать установку"
    t_run 't_assert_eq "$CSQTT_CONFIG_DIR" "$FAKE_CFG" "CSQTT_CONFIG_DIR доступна и переопределена"'
}

test_smoke_stub_captures_argv() {
    t_begin "smoke: stub captures argv"
    PATH="$MOCK_BIN:$PATH" systemctl daemon-reload >/dev/null 2>&1
    t_assert_contains "$CALL_LOG" "systemctl daemon-reload" "заглушка обязана записать свои аргументы"
}

test_runner_refuses_root() {
    t_begin "runner refuses to run as root"
    t_assert_eq "$(sed -n 's/.*CSQTT_ALLOW_ROOT_TESTS.*/found/p' "$0" | head -n 1)" "found" \
        "раннер обязан знать про CSQTT_ALLOW_ROOT_TESTS"
    t_assert_failed 'CSQTT_EUID_OVERRIDE=0 require_non_root' \
        "предохранитель обязан отказать при EUID 0"
    CSQTT_EUID_OVERRIDE=0 CSQTT_ALLOW_ROOT_TESTS=1 require_non_root || \
        t_fail "CSQTT_ALLOW_ROOT_TESTS=1 обязан снимать предохранитель"
    require_non_root || t_fail "без override предохранитель не должен мешать обычному пользователю"
}

# ---------------------------------------------------------------------------
# Кейсы: Task 2 — каталог данных нельзя сносить
# ---------------------------------------------------------------------------

test_symlinked_config_dir_keeps_link_and_data() {
    t_begin "symlinked config dir keeps both link and data"
    mkdir -p "$FAKE_CFG/target"
    printf 'данные' > "$FAKE_CFG/target/csqtt.db"
    ln -s "$FAKE_CFG/target" "$FAKE_CFG/cfg"
    t_run 'ensure_persistent_directory "$CSQTT_CONFIG_DIR/cfg" 700'
    t_assert_eq "$(readlink "$FAKE_CFG/cfg")" "$FAKE_CFG/target" \
        "симлинк каталога данных обязан пережить установку"
    t_assert_eq "$(cat "$FAKE_CFG/target/csqtt.db")" "данные" \
        "данные в цели симлинка обязаны остаться"
    t_assert_eq "$(stat -c %a "$FAKE_CFG/target")" "700" \
        "права выставляются на цель симлинка"
}

test_dangling_config_symlink_fails_without_deleting() {
    t_begin "dangling symlink fails without deleting"
    ln -s "$FAKE_CFG/missing-target" "$FAKE_CFG/cfg"
    t_run_fails 'ensure_persistent_directory "$CSQTT_CONFIG_DIR/cfg" 700' \
        "висячая ссылка обязана дать отказ"
    t_assert_eq "$(readlink "$FAKE_CFG/cfg")" "$FAKE_CFG/missing-target" \
        "висячая ссылка не должна удаляться"
    [ -e "$FAKE_CFG/missing-target" ] && t_fail "цель висячей ссылки не должна создаваться"
    return 0
}

test_non_directory_config_path_fails_without_deleting() {
    t_begin "non-directory config path fails without deleting"
    printf 'важное' > "$FAKE_CFG/cfg"
    t_run_fails 'ensure_persistent_directory "$CSQTT_CONFIG_DIR/cfg" 700' \
        "обычный файл на месте каталога данных обязан дать отказ"
    t_assert_eq "$(cat "$FAKE_CFG/cfg")" "важное" "файл обязан остаться нетронутым"
}

test_missing_config_dir_is_created() {
    t_begin "missing config dir is created"
    t_run 'ensure_persistent_directory "$CSQTT_CONFIG_DIR/cfg" 700'
    t_assert_eq "$(stat -c %a "$FAKE_CFG/cfg")" "700" "каталог данных создаётся с режимом 700"
}

test_helper_dir_still_gets_recreated() {
    t_begin "helper dir still gets recreated"
    ln -s /usr/local/lib/csqtt "$FAKE_CFG/helper"
    t_run 'ensure_csqtt_directory "$CSQTT_CONFIG_DIR/helper" 755'
    [ -L "$FAKE_CFG/helper" ] && t_fail "вспомогательный каталог должен пересоздаваться, а не оставаться симлинком"
    t_assert_eq "$(stat -c %a "$FAKE_CFG/helper")" "755" "вспомогательный каталог создаётся с режимом 755"
}

# ---------------------------------------------------------------------------
# Кейсы: Task 3 — определение режима панели
# ---------------------------------------------------------------------------

test_public_ip_selects_ip_mode() {
    t_begin "public ip selects ip mode"
    export CSQTT_PUBLIC_IP=203.0.113.7
    t_run 'resolve_panel_mode
        t_assert_eq "$PANEL_MODE" "ip" "режим панели"
        t_assert_eq "$PANEL_HOST" "203.0.113.7" "хост панели"
        t_assert_eq "$WEB_PORT" "46002" "порт панели в IP-режиме"
        t_assert_eq "$LE_RENEW_BEFORE_SECONDS" "259200" "окно продления в IP-режиме"'
}

test_domain_selects_domain_mode_on_443() {
    t_begin "domain selects domain mode on 443"
    export CSQTT_PUBLIC_IP=panel.example.com
    t_run 'resolve_panel_mode
        t_assert_eq "$PANEL_MODE" "domain" "режим панели"
        t_assert_eq "$PANEL_HOST" "panel.example.com" "хост панели"
        t_assert_eq "$WEB_PORT" "443" "порт панели в доменном режиме"
        t_assert_eq "$LE_RENEW_BEFORE_SECONDS" "2592000" "окно продления в доменном режиме"'
}

test_domain_overrides_client_web_port() {
    t_begin "domain overrides a web port supplied by the client"
    export CSQTT_PUBLIC_IP=panel.example.com
    export CSQTT_WEB_PORT=46002
    t_run 'resolve_panel_mode
        t_assert_eq "$WEB_PORT" "443" "доменный режим обязан переопределить порт клиента"'
}

test_garbage_web_port_with_domain_still_resolves_to_443() {
    t_begin "garbage web port with a domain still resolves to 443"
    export CSQTT_PUBLIC_IP=panel.example.com
    export CSQTT_WEB_PORT=abc
    t_run 'resolve_panel_mode
        t_assert_eq "$WEB_PORT" "443" "мусорный порт клиента не должен мешать доменному режиму"'
}

test_host_is_lowercased() {
    t_begin "host is lowercased"
    export CSQTT_PUBLIC_IP=Panel.Example.COM
    t_run 'resolve_panel_mode
        t_assert_eq "$PANEL_HOST" "panel.example.com" "хост обязан нормализоваться в нижний регистр"'
}

test_blank_host_fails_before_parsing() {
    t_begin "blank host fails before any parsing"
    export CSQTT_PUBLIC_IP="   "
    t_run_fails 'resolve_panel_mode' "пустое поле обязано привести к отказу"
    t_assert_eq "$(wc -l < "$CALL_LOG" | tr -d ' ')" "0" "до разбора не должно быть внешних вызовов"
}

test_private_ipv4_is_rejected() {
    t_begin "private ipv4 is rejected"
    local ip
    for ip in 192.168.1.5 10.0.0.1 172.16.0.1 127.0.0.1 100.64.0.1; do
        export CSQTT_PUBLIC_IP="$ip"
        t_run_fails 'resolve_panel_mode' "частный адрес $ip обязан быть отклонён"
    done
}

test_non_address_and_non_domain_is_rejected() {
    t_begin "non address and non domain is rejected"
    export CSQTT_PUBLIC_IP="не домен"
    t_run_fails 'resolve_panel_mode' "произвольная строка обязана быть отклонена"
}

test_quoted_value_from_uploaded_env_is_accepted() {
    t_begin "quoted value from the uploaded env file is accepted"
    export CSQTT_PUBLIC_IP=203.0.113.7
    t_upload_env 'CSQTT_PUBLIC_IP="panel.example.com"'
    t_run 'resolve_panel_mode
        t_assert_eq "$PANEL_HOST" "panel.example.com" "кавычки из systemd-формы обязаны сниматься"
        t_assert_eq "$PANEL_MODE" "domain" "файл имеет приоритет над переменной окружения"'
}

test_escaped_value_from_uploaded_env_is_accepted() {
    t_begin "escaped value from the uploaded env file is accepted"
    t_upload_env 'CSQTT_PUBLIC_IP="panel\.example.com"'
    t_run 'resolve_panel_mode
        t_assert_eq "$PANEL_HOST" "panel.example.com" "обратные слэши обязаны сниматься"'
}

test_hostname_validator_vectors() {
    t_begin "hostname validator vectors"
    local name
    for name in panel.example.com localhost a.b.c.d.e; do
        t_run "is_dns_hostname '$name'" || t_fail "«$name» обязан считаться доменным именем"
    done
    for name in 203.0.113.7 1.2.3.4.5 -bad.example.com bad-.example.com bad..example.com .example.com example.com. ""; do
        t_run_fails "is_dns_hostname '$name'" "«$name» не должен считаться доменным именем"
    done
}

# ---------------------------------------------------------------------------
# Кейсы: Task 4 — firewall: закрытие старого порта
# ---------------------------------------------------------------------------

test_domain_mode_removes_46002_rule() {
    t_begin "domain mode removes the 46002 rule"
    t_stub_stdout iptables '-A INPUT -p tcp -m tcp --dport 46002 -m comment --comment CSQTT_MANAGED -j ACCEPT'
    t_run 'PANEL_MODE=domain; fw_close_unused_panel_port'
    t_assert_contains "$CALL_LOG" "-D INPUT" "старое правило закрывается через iptables -D"
    t_assert_contains "$CALL_LOG" "--dport 46002" "закрывается именно порт 46002"
}

test_ip_mode_removes_443_rule() {
    t_begin "ip mode removes the 443 rule"
    t_stub_stdout iptables '-A INPUT -p tcp -m tcp --dport 443 -m comment --comment CSQTT_MANAGED -j ACCEPT'
    t_run 'PANEL_MODE=ip; fw_close_unused_panel_port'
    t_assert_contains "$CALL_LOG" "--dport 443" "обратный переход обязан закрыть 443"
    t_assert_not_contains "$CALL_LOG" "--dport 46002" "в IP-режиме правило 46002 не трогается"
}

test_current_port_rule_is_still_added() {
    t_begin "current port rule is still added"
    t_run 'fw_add_input_tcp 46002'
    t_assert_contains "$CALL_LOG" "-p tcp --dport 46002" "правило текущего порта открывается штатно"
}

test_removal_stops_on_success() {
    t_begin "removal stops on success"
    t_stub_stdout iptables '-A INPUT -p tcp -m tcp --dport 46002 -m comment --comment CSQTT_MANAGED -j ACCEPT'
    t_stub_fail_times iptables 2
    t_run 'PANEL_MODE=domain; fw_close_unused_panel_port'
    t_assert_eq "$(t_count_calls iptables "-D INPUT")" "3" "два неуспеха и успех — ровно три попытки удаления"
}

test_removal_terminates_without_the_rule() {
    t_begin "removal on a host that never had the rule terminates"
    t_stub_stdout iptables '-A INPUT -p tcp -m tcp --dport 46002 -m comment --comment CSQTT_MANAGED -j ACCEPT'
    t_stub_fail_times iptables 100
    t_run 'PANEL_MODE=domain; fw_close_unused_panel_port'
    local attempts
    attempts="$(t_count_calls iptables "-D INPUT")"
    [ "$attempts" -ge 1 ] || t_fail "удаление обязано хотя бы раз попробовать снять правило"
    [ "$attempts" -le 8 ] || t_fail "цикл удаления обязан быть ограничен 8 попытками, было $attempts"
    return 0
}

test_network_helper_closes_the_unused_port() {
    t_begin "network helper closes the port the panel no longer uses"
    t_run 'PANEL_MODE=domain; write_network_helper "$FAKE_CFG/network-up.sh"'
    t_assert_contains "$FAKE_CFG/network-up.sh" 'STALE_WEB_PORT="46002"' \
        "хелпер обязан знать, какой порт закрывается"
    t_assert_contains "$FAKE_CFG/network-up.sh" 'ipt -D INPUT -p tcp --dport "$STALE_WEB_PORT"' \
        "вторая копия правил обязана снимать старое правило"
    sh -n "$FAKE_CFG/network-up.sh" || t_fail "сгенерированный хелпер обязан остаться валидным POSIX sh"
}

test_network_helper_keeps_the_reverse_switch() {
    t_begin "network helper keeps the reverse switch"
    t_run 'PANEL_MODE=ip; write_network_helper "$FAKE_CFG/network-up.sh"'
    t_assert_contains "$FAKE_CFG/network-up.sh" 'STALE_WEB_PORT="443"' \
        "в IP-режиме хелпер закрывает 443"
}

# ---------------------------------------------------------------------------
# Кейсы: Task 5 — выпуск сертификата для домена
# ---------------------------------------------------------------------------

test_domain_issuance_asks_certbot_for_a_dns_name_only() {
    t_begin "domain issuance asks certbot for a dns name only"
    export PANEL_MODE=domain PANEL_HOST=panel.example.com
    t_run 'setup_web_panel_tls'
    t_assert_contains "$CALL_LOG" "certbot certonly --standalone --http-01-port 80" \
        "доменный выпуск идёт через HTTP-01"
    t_assert_contains "$CALL_LOG" "--domains panel.example.com" "certbot получает домен"
    t_assert_not_contains "$CALL_LOG" "--ip-address" "в доменном режиме IP-флаг не передаётся"
    t_assert_not_contains "$CALL_LOG" "shortlived" "в доменном режиме профиль не переопределяется"
}

test_ip_issuance_is_unchanged() {
    t_begin "ip issuance is unchanged"
    export PANEL_MODE=ip PANEL_HOST=203.0.113.7
    t_run 'setup_web_panel_tls'
    t_assert_contains "$CALL_LOG" "--ip-address 203.0.113.7" "IP-режим передаёт адрес"
    t_assert_contains "$CALL_LOG" "--preferred-profile shortlived" "IP-режим сохраняет профиль"
    t_assert_not_contains "$CALL_LOG" "--domains" "IP-режим не передаёт домен"
}

test_domain_mode_never_falls_back_to_self_signed() {
    t_begin "domain mode never falls back to a self signed certificate"
    t_stub_fail_times certbot 2
    export PANEL_MODE=domain PANEL_HOST=panel.example.com
    t_run_fails 'setup_web_panel_tls' "неудача выпуска в доменном режиме обязана быть фатальной"
    t_assert_not_contains "$CALL_LOG" "openssl req -x509" "самоподпись в доменном режиме запрещена"
}

test_ip_mode_still_falls_back_to_self_signed() {
    t_begin "ip mode still falls back to a self signed certificate"
    t_stub_fail_times certbot 2
    export PANEL_MODE=ip PANEL_HOST=203.0.113.7
    t_run 'setup_web_panel_tls'
    t_assert_contains "$CALL_LOG" "openssl req -x509" "в IP-режиме откат на самоподпись сохранён"
}

test_activation_failure_is_fatal_in_domain_mode() {
    t_begin "activation failure is fatal in domain mode"
    t_stub_fail_times install 1
    export PANEL_MODE=domain PANEL_HOST=panel.example.com
    t_run_fails 'setup_web_panel_tls' "неудача активации сертификата обязана быть фатальной"
    t_assert_not_contains "$CALL_LOG" "openssl req -x509" "откат на самоподпись запрещён"
    [ -e "$FAKE_CFG/letsencrypt.env" ] && t_fail "при неудачной активации state не должен записываться"
    return 0
}

test_missing_certbot_client_is_fatal_in_domain_mode() {
    t_begin "missing certbot client is fatal in domain mode"
    t_stub_certbot_ip_support 0
    export PANEL_MODE=domain PANEL_HOST=panel.example.com
    t_run_fails 'setup_web_panel_tls' "без certbot с --domains доменная установка обязана падать"
    t_assert_not_contains "$CALL_LOG" "openssl req -x509" "откат на самоподпись запрещён"
}

test_occupied_port_80_fails_in_domain_mode() {
    t_begin "occupied port 80 fails in domain mode"
    t_stub_stdout ss 'LISTEN 0 4096 0.0.0.0:80 0.0.0.0:*'
    export PANEL_MODE=domain PANEL_HOST=panel.example.com
    local output
    if output="$(t_run 'setup_web_panel_tls' 2>&1)"; then
        t_fail "занятый TCP/80 в доменном режиме обязан остановить установку"
    fi
    printf '%s' "$output" | grep -Fq "80" || t_fail "в отказе обязан упоминаться порт 80"
    return 0
}

test_identifier_comes_from_panel_host() {
    t_begin "identifier comes from PANEL_HOST"
    export PANEL_MODE=domain PANEL_HOST=panel.example.com
    t_run 'setup_web_panel_tls'
    t_assert_contains "$CALL_LOG" "--domains panel.example.com" "идентификатор берётся из PANEL_HOST"
    t_assert_not_contains "$CALL_LOG" "ip -4 route get" "detect_public_ipv4 в доменном режиме не вызывается"
}

test_certificate_name_and_state_follow_the_mode() {
    t_begin "certificate name and state file follow the mode"
    export PANEL_MODE=domain PANEL_HOST=panel.example.com
    t_run 'setup_web_panel_tls'
    t_assert_contains "$FAKE_CFG/letsencrypt.env" "MODE=domain" "state фиксирует режим"
    t_assert_contains "$FAKE_CFG/letsencrypt.env" "IDENTIFIER=panel.example.com" "state фиксирует идентификатор"
    t_assert_contains "$FAKE_CFG/letsencrypt.env" "CERT_NAME=csqtt-panel.example.com" "имя сертификата следует режиму"
    t_assert_contains "$CALL_LOG" "--cert-name csqtt-panel.example.com" "certbot получает то же имя"
}

test_ip_certificate_name_keeps_the_dash_form() {
    t_begin "ip certificate name keeps the dash form"
    export PANEL_MODE=ip PANEL_HOST=203.0.113.7
    t_run 'setup_web_panel_tls'
    t_assert_contains "$FAKE_CFG/letsencrypt.env" "CERT_NAME=csqtt-ip-203-0-113-7" "IP-имя сохраняет дефисы"
}

test_domain_certificate_is_recognised_as_current() {
    t_begin "domain certificate is recognised as current"
    export PANEL_MODE=domain PANEL_HOST=panel.example.com
    t_run 'setup_web_panel_tls'
    t_run 'setup_web_panel_tls'
    t_assert_eq "$(t_count_calls certbot "certonly")" "1" \
        "повторная установка не выпускает второй сертификат"
}

test_user_supplied_certificate_is_left_alone_in_both_modes() {
    t_begin "user supplied certificate is left alone in both modes"
    PATH="$ORIGINAL_PATH" openssl req -x509 -newkey rsa:2048 -nodes -days 90 \
        -subj "/CN=user.example.org" -addext "subjectAltName = DNS:user.example.org" \
        -keyout "$FAKE_CFG/web_key.pem" -out "$FAKE_CFG/web_cert.pem" >/dev/null 2>&1
    export PANEL_MODE=domain PANEL_HOST=panel.example.com
    t_run 'setup_web_panel_tls'
    export PANEL_MODE=ip PANEL_HOST=203.0.113.7
    t_run 'setup_web_panel_tls'
    t_assert_not_contains "$CALL_LOG" "certbot certonly" "пользовательский сертификат не перевыпускается"
    t_assert_contains "$CALL_LOG" "systemctl disable --now csqtt-letsencrypt.timer" \
        "продление выключается для пользовательского сертификата"
}

test_adopt_recovers_a_domain_certificate_after_state_loss() {
    t_begin "adopt recovers a domain certificate after the state file is lost"
    export PANEL_MODE=domain PANEL_HOST=panel.example.com
    t_run 'setup_web_panel_tls'
    rm -f "$FAKE_CFG/letsencrypt.env"
    t_run 'setup_web_panel_tls'
    t_assert_eq "$(t_count_calls certbot "certonly")" "1" "adopt не выпускает второй сертификат"
    t_assert_contains "$FAKE_CFG/letsencrypt.env" "MODE=domain" "adopt восстановил состояние"
}

test_legacy_state_survives_until_the_new_helper_is_written() {
    t_begin "legacy state file survives until the new helper is written"
    printf 'IP=203.0.113.7\nCERT_NAME=csqtt-ip-203-0-113-7\n' > "$FAKE_CFG/letsencrypt-ip.env"
    export PANEL_MODE=ip PANEL_HOST=203.0.113.7
    t_run 'clear_runtime_config_preserving_database'
    [ -f "$FAKE_CFG/letsencrypt-ip.env" ] || t_fail "легаси-файл обязан пережить очистку runtime"
    t_run 'setup_web_panel_tls'
    [ -e "$FAKE_CFG/letsencrypt-ip.env" ] && t_fail "легаси-файл обязан быть удалён к концу установки"
    t_assert_contains "$CALL_LOG" "systemctl-marker renew-helper=present" \
        "новый хелпер обязан существовать в момент включения таймера"
}

test_cutover_stops_the_renewal_timer() {
    t_begin "cutover stops the renewal timer before the helper is overwritten"
    t_run 'stop_letsencrypt_renewal_timer'
    t_assert_contains "$CALL_LOG" "systemctl stop csqtt-letsencrypt.timer csqtt-letsencrypt.service" \
        "таймер обязан быть остановлен"
    t_assert_not_contains "$CALL_LOG" "enable --now" "остановка не включает таймер обратно"
}

test_domain_issuance_failure_keeps_legacy_state_for_rollback() {
    t_begin "domain issuance failure keeps the legacy state for rollback"
    t_seed_le_certificate ip 203.0.113.7
    printf 'IP=203.0.113.7\nCERT_NAME=csqtt-ip-203-0-113-7\n' > "$FAKE_CFG/letsencrypt-ip.env"
    rm -f "$FAKE_CFG/letsencrypt.env"
    t_stub_fail_times certbot 2
    export PANEL_MODE=domain PANEL_HOST=panel.example.com
    t_run_fails 'setup_web_panel_tls' "отказ выпуска доменного сертификата обязана быть фатальным"
    [ -f "$FAKE_CFG/letsencrypt-ip.env" ] || t_fail "легаси-состояние обязано пережить отказ: без него откат в IP-режим перестанет узнавать свой IP-сертификат и оставит панель без продления"
    [ -e "$FAKE_CFG/letsencrypt.env" ] && t_fail "при отказе выпуска state не должен записываться"
    return 0
}

test_legacy_ip_state_is_migrated_in_ip_mode() {
    t_begin "legacy ip state is migrated without reissuing the certificate"
    t_seed_le_certificate ip 203.0.113.7
    printf 'IP=203.0.113.7\nCERT_NAME=csqtt-ip-203-0-113-7\n' > "$FAKE_CFG/letsencrypt-ip.env"
    rm -f "$FAKE_CFG/letsencrypt.env"
    t_run 'PANEL_MODE=ip; PANEL_HOST=203.0.113.7; setup_web_panel_tls'
    t_assert_contains "$FAKE_CFG/letsencrypt.env" "MODE=ip" "легаси-состояние переносится в новый формат"
    t_assert_eq "$(t_count_calls certbot "certonly")" "0" "действующий сертификат не перевыпускается"
    t_assert_not_contains "$CALL_LOG" "systemctl disable --now" \
        "свой IP-сертификат не должен считаться пользовательским"
}

test_legacy_ip_certificate_is_not_user_managed_in_domain_mode() {
    t_begin "legacy ip certificate is not treated as a user certificate in domain mode"
    t_seed_le_certificate ip 203.0.113.7
    printf 'IP=203.0.113.7\nCERT_NAME=csqtt-ip-203-0-113-7\n' > "$FAKE_CFG/letsencrypt-ip.env"
    rm -f "$FAKE_CFG/letsencrypt.env"
    t_run 'PANEL_MODE=domain; PANEL_HOST=panel.example.com; setup_web_panel_tls'
    t_assert_contains "$CALL_LOG" "--domains panel.example.com" \
        "доменная установка обязана выпустить доменный сертификат"
    t_assert_not_contains "$CALL_LOG" "systemctl disable --now" \
        "свой IP-сертификат не должен считаться пользовательским"
    t_assert_contains "$FAKE_CFG/letsencrypt.env" "MODE=domain" "в конце установки state описывает домен"
}

test_domain_install_does_not_adopt_an_ip_certificate() {    t_begin "domain install with an old IP certificate does not write MODE=ip state"
    t_seed_le_certificate ip 203.0.113.7
    rm -f "$FAKE_CFG/letsencrypt.env"
    export PANEL_MODE=domain PANEL_HOST=panel.example.com
    t_run_fails 'adopt_letsencrypt_certificate' "adopt обязан отказаться от сертификата чужого режима"
    [ -e "$FAKE_CFG/letsencrypt.env" ] && t_fail "state не должен записываться при несоответствии режиму"
    return 0
}

test_domain_preflight_dies_before_the_runtime_is_stopped() {
    t_begin "domain preflight dies before the runtime is stopped"
    t_stub_stdout ss 'LISTEN 0 4096 0.0.0.0:80 0.0.0.0:*'
    export PANEL_MODE=domain PANEL_HOST=panel.example.com
    t_run_fails 'run_domain_mode_preflight' "занятый TCP/80 обязан остановить установку"
    t_assert_not_contains "$CALL_LOG" "systemctl stop csqtt.service" \
        "проверка обязана идти до cutover"
}

# ---------------------------------------------------------------------------
# Кейсы: Task 5 — хелпер продления
# ---------------------------------------------------------------------------

test_renewal_helper_renews_the_domain_without_the_short_lived_profile() {
    t_begin "renewal helper renews the domain without the short lived profile"
    t_write_renewal_helper
    t_seed_le_certificate domain panel.example.com
    export CSQTT_LE_IDENTIFIER=panel.example.com
    T_RENEW_BEFORE=100000000 t_run_renewal_helper
    t_assert_contains "$CALL_LOG" "certbot certonly" "хелпер обязан продлевать сертификат"
    t_assert_contains "$CALL_LOG" "--domains panel.example.com" "домен передаётся certbot-у"
    t_assert_not_contains "$CALL_LOG" "shortlived" "доменное продление без профиля shortlived"
    t_assert_not_contains "$CALL_LOG" "--ip-address" "доменное продление без IP-флага"
}

test_renewal_helper_never_self_signs_in_domain_mode() {
    t_begin "renewal helper never self signs in domain mode"
    t_write_renewal_helper
    t_seed_le_certificate domain panel.example.com
    export CSQTT_LE_IDENTIFIER=panel.example.com
    t_stub_fail_times certbot 2
    local output
    if ! output="$(T_RENEW_BEFORE=100000000 t_run_renewal_helper 2>&1)"; then
        t_fail "хелпер обязан завершиться успешно, сохранив текущий сертификат"
    fi
    printf '%s' "$output" | grep -Fq "не удался" || t_fail "в журнал обязано попасть предупреждение"
    t_assert_not_contains "$CALL_LOG" "openssl req -x509" "самоподпись в доменном режиме запрещена"
    return 0
}

test_renewal_helper_self_signs_in_ip_mode() {
    t_begin "renewal helper self signs in ip mode"
    t_write_renewal_helper
    t_seed_le_certificate ip 203.0.113.7 expired
    t_stub_fail_times certbot 2
    T_RENEW_BEFORE=100000000 t_run_renewal_helper || t_fail "хелпер обязан завершиться успешно"
    t_assert_contains "$CALL_LOG" "openssl req -x509" "в IP-режиме откат на самоподпись сохранён"
}

test_renewal_helper_exits_when_state_is_missing_in_domain_mode() {
    t_begin "renewal helper exits when the state file is missing in domain mode"
    t_write_renewal_helper
    t_seed_le_certificate domain panel.example.com
    rm -f "$FAKE_CFG/letsencrypt.env"
    export CSQTT_LE_IDENTIFIER=panel.example.com
    local output
    if ! output="$(t_run_renewal_helper 2>&1)"; then
        t_fail "хелпер обязан выйти успешно"
    fi
    printf '%s' "$output" | grep -Fq "отсутствует" || t_fail "ранний выход обязан быть записан в журнал"
    t_assert_not_contains "$CALL_LOG" "certbot certonly" "certbot не должен вызываться"
    t_assert_not_contains "$CALL_LOG" "openssl req -x509" "самоподпись запрещена"
    return 0
}

test_renewal_helper_renews_on_identifier_mismatch() {
    t_begin "renewal helper renews on identifier mismatch"
    t_write_renewal_helper
    t_seed_le_certificate domain panel.example.com
    export CSQTT_LE_IDENTIFIER=old.example.com
    t_run_renewal_helper || t_fail "хелпер обязан завершиться успешно"
    t_assert_contains "$CALL_LOG" "--force-renewal" "расхождение идентификатора требует перевыпуска"
    t_assert_contains "$CALL_LOG" "--domains panel.example.com" "перевыпуск идёт для домена"
}

test_renewal_helper_exits_quietly_without_identifier() {
    t_begin "renewal helper exits quietly when the unit passes no identifier"
    t_write_renewal_helper
    t_seed_le_certificate domain panel.example.com
    unset CSQTT_LE_IDENTIFIER
    local output
    if ! output="$(t_run_renewal_helper 2>&1)"; then
        t_fail "хелпер обязан выйти успешно"
    fi
    printf '%s' "$output" | grep -Fq "CSQTT_LE_IDENTIFIER" || t_fail "пропуск сверки обязан быть записан в журнал"
    t_assert_not_contains "$CALL_LOG" "certbot certonly" "пустой идентификатор не должен вызывать перевыпуск"
    t_assert_not_contains "$CALL_LOG" "openssl req -x509" "самоподпись запрещена"
    return 0
}

# ---------------------------------------------------------------------------
# Кейсы: Task 6 — порты запуска, docker-capability и проверка занятости
# ---------------------------------------------------------------------------

test_docker_run_grants_net_bind_service() {
    t_begin "docker run grants net bind service"
    t_run 'run_csqtt_docker_container'
    t_assert_contains "$CALL_LOG" "--cap-add NET_BIND_SERVICE" \
        "контейнер под --cap-drop ALL не забиндит 443 без этой capability"
}

test_occupied_web_port_fails_before_panel_starts() {
    t_begin "occupied web port fails before the panel starts"
    t_stub_stdout ss 'LISTEN 0 4096 0.0.0.0:443 0.0.0.0:*'
    local output
    if output="$(t_run 'PANEL_MODE=domain; PANEL_HOST=panel.example.com; WEB_PORT=443; assert_web_port_is_available' 2>&1)"; then
        t_fail "занятый порт панели обязан остановить установку"
    fi
    printf '%s' "$output" | grep -Fq "443" || t_fail "в отказе обязан упоминаться порт"
    return 0
}

test_free_web_port_passes() {
    t_begin "free web port passes"
    t_run 'PANEL_MODE=domain; PANEL_HOST=panel.example.com; WEB_PORT=443; assert_web_port_is_available'
    t_assert_eq "$?" "0" "свободный порт не должен мешать запуску"
}

test_banners_report_the_effective_port() {
    t_begin "banners report the effective port"
    local output
    output="$(t_run 'PANEL_MODE=domain; PANEL_HOST=panel.example.com; WEB_PORT=443; panel_web_port_label; print_installer_header; print_systemd_deploy_summary 4321; print_docker_deploy_summary 4321')"
    [ "$(printf '%s\n' "$output" | grep -Fc '443 (домен panel.example.com)')" -ge 3 ] || \
        t_fail "доменный режим обязан печатать 443 и домен во всех баннерах"
    output="$(t_run 'PANEL_MODE=ip; PANEL_HOST=203.0.113.7; WEB_PORT=46002; panel_web_port_label; print_installer_header; print_systemd_deploy_summary 4321; print_docker_deploy_summary 4321')"
    [ "$(printf '%s\n' "$output" | grep -Fc '46002 (IP 203.0.113.7)')" -ge 3 ] || \
        t_fail "IP-режим обязан печатать 46002 и адрес во всех баннерах"
    t_assert_contains "$DEPLOY_SCRIPT" '${WEB_PORT}/tcp(WEB: $(panel_web_mode_label))' \
        "сводка портов NAT/firewall печатает режим"
}

TESTS=(
    test_smoke_source_does_not_run_main
    test_smoke_stub_captures_argv
    test_runner_refuses_root
    test_symlinked_config_dir_keeps_link_and_data
    test_dangling_config_symlink_fails_without_deleting
    test_non_directory_config_path_fails_without_deleting
    test_missing_config_dir_is_created
    test_helper_dir_still_gets_recreated
    test_public_ip_selects_ip_mode
    test_domain_selects_domain_mode_on_443
    test_domain_overrides_client_web_port
    test_garbage_web_port_with_domain_still_resolves_to_443
    test_host_is_lowercased
    test_blank_host_fails_before_parsing
    test_private_ipv4_is_rejected
    test_non_address_and_non_domain_is_rejected
    test_quoted_value_from_uploaded_env_is_accepted
    test_escaped_value_from_uploaded_env_is_accepted
    test_hostname_validator_vectors
    test_domain_mode_removes_46002_rule
    test_ip_mode_removes_443_rule
    test_current_port_rule_is_still_added
    test_removal_stops_on_success
    test_removal_terminates_without_the_rule
    test_network_helper_closes_the_unused_port
    test_network_helper_keeps_the_reverse_switch
    test_domain_issuance_asks_certbot_for_a_dns_name_only
    test_ip_issuance_is_unchanged
    test_domain_mode_never_falls_back_to_self_signed
    test_ip_mode_still_falls_back_to_self_signed
    test_activation_failure_is_fatal_in_domain_mode
    test_missing_certbot_client_is_fatal_in_domain_mode
    test_occupied_port_80_fails_in_domain_mode
    test_identifier_comes_from_panel_host
    test_certificate_name_and_state_follow_the_mode
    test_ip_certificate_name_keeps_the_dash_form
    test_domain_certificate_is_recognised_as_current
    test_user_supplied_certificate_is_left_alone_in_both_modes
    test_adopt_recovers_a_domain_certificate_after_state_loss
    test_legacy_state_survives_until_the_new_helper_is_written
    test_domain_issuance_failure_keeps_legacy_state_for_rollback
    test_cutover_stops_the_renewal_timer
    test_legacy_ip_state_is_migrated_in_ip_mode
    test_legacy_ip_certificate_is_not_user_managed_in_domain_mode
    test_domain_install_does_not_adopt_an_ip_certificate
    test_domain_preflight_dies_before_the_runtime_is_stopped
    test_renewal_helper_renews_the_domain_without_the_short_lived_profile
    test_renewal_helper_never_self_signs_in_domain_mode
    test_renewal_helper_self_signs_in_ip_mode
    test_renewal_helper_exits_when_state_is_missing_in_domain_mode
    test_renewal_helper_renews_on_identifier_mismatch
    test_renewal_helper_exits_quietly_without_identifier
    test_docker_run_grants_net_bind_service
    test_occupied_web_port_fails_before_panel_starts
    test_free_web_port_passes
    test_banners_report_the_effective_port
)

# ---------------------------------------------------------------------------
# Запуск
# ---------------------------------------------------------------------------

main() {
    local selected=("$@") test_name
    if [ "${#selected[@]}" -eq 0 ]; then
        selected=("${TESTS[@]}")
    fi
    printf 'CSQTT deploy.sh behavioural tests\n'
    printf 'deploy.sh: %s\n' "$DEPLOY_SCRIPT"
    for test_name in "${selected[@]}"; do
        printf '\n▶ %s\n' "$test_name"
        if ( set +e; "$test_name" ); then
            printf '  ✓ ok\n'
            PASSED=$((PASSED + 1))
        else
            printf '  ✗ FAILED\n' >&2
            FAILED=$((FAILED + 1))
            FAILED_NAMES+=("$test_name")
        fi
    done
    printf '\nитого: %s зелёных, %s красных\n' "$PASSED" "$FAILED"
    if [ "$FAILED" -ne 0 ]; then
        printf 'красные: %s\n' "${FAILED_NAMES[*]}" >&2
        return 1
    fi
    return 0
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    main "$@"
fi
