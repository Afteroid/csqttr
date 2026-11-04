package com.csqtt.client

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object TrafficDiagnosticParser {
    const val PREFIX = "__CSQTT_TRAFFIC__|"
    fun parse(line: String): LogEntry? {
        if (!line.startsWith(PREFIX) || line.length > 8192) return null
        return runCatching {
            val j = JSONObject(line.removePrefix(PREFIX))
            val seq = j.getLong("sequence")
            val time = j.getLong("time_ms")
            val ms = j.getLong("elapsed_ms")
            val active = j.getInt("active")
            require(seq > 0 && time > 0 && ms > 0 && active >= 0)
            val c = j.getJSONObject("counters")
            fun n(key: String): Long = c.getLong(key).also { require(it >= 0) }
            fun rate(bytes: Long): String {
                require(bytes >= 0)
                return String.format(Locale.ROOT, "%.2f", bytes.toDouble() * 8 / ms / 1000)
            }
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.ROOT).format(Date(time))
            val text = "[ПЕРЕДАЧА] $stamp · окно ${ms} мс · потоков $active\n" +
                "Приложения ↑${rate(j.getLong("app_up_bytes"))} ↓${rate(j.getLong("app_down_bytes"))} Мбит/с; в туннель ${n("dispatch_bytes")} Б\n" +
                "UDP → сокет: ${n("udp_socket_bytes")} Б / ${n("udp_socket_packets")} пак.; relay → клиент: ${n("relay_bytes")} Б / ${n("relay_packets")} пак.\n" +
                "Очередь TURN: полна ${n("turn_queue_full")}, ожидание ${n("turn_queue_wait_us")} мкс; сокет WouldBlock ${n("udp_would_block")}, ожидание ${n("udp_wait_us")} мкс\n" +
                "Макс. ожидание за подключение: очередь ${n("turn_queue_wait_max_us")} / сокет ${n("udp_wait_max_us")} мкс\n" +
                "Локальные сбросы: нет потока ${n("no_worker_drops")}; wrap ${n("wrap_drops")}; фрагменты/буферы ${n("fragment_pool_drops")}; передача в TURN ${n("turn_enqueue_errors")}; UDP send ${n("udp_send_drops")}; закрытие UDP/очереди ${n("udp_closing_drops")}/${n("turn_closing_drops")}\n" +
                "Приём: очередь relay ${n("relay_queue_drops")}; буферы ${n("relay_pool_drops")}; auth ${n("auth_drops")}; replay ${n("replay_drops")}; не RTP/IP ${n("non_rtp_drops")}/${n("non_ip_drops")}\n" +
                "В приложения: вытеснения ${n("downlink_queue_drops")}; TUN сбросы ${n("tun_write_drops")}, WouldBlock ${n("tun_would_block")}, временные ошибки ${n("tun_transient_errors")}\n" +
                "Счётчики за окно; ожидание суммарно по потокам. UDP включает ChannelData, relay — полезную нагрузку с шифрованием и служебными сообщениями. Принято сокетом ≠ доставлено серверу; потери сети неизвестны."
            LogEntry("traffic_diag_${time.toString().padStart(16, '0')}_${seq.toString().padStart(20, '0')}", text, priority = 66, level = LogLevel.DBG)
        }.getOrNull()
    }
}
