# Gooby

## CSQTTR built-in TURN preset (experimental)

CSQTTR uses `ConfigBundle::builtin_turn()` when its opt-in Gooby switch is on
and no external ZIP is supplied. This preset handles both numeric IPv4/IPv6
destinations and hostnames. The original `builtin_base()` and ZIP domain-rule
behaviour below is preserved; original custom rules are not silently replaced.

For plain TURN/TCP the preset recognizes the Allocate request type, aligned
message length and STUN magic cookie. It writes the 20-byte header in four parts
of 1, 5, 2 and 12 bytes with NODELAY and three 5 ms waits. It preserves every
protocol byte, including transaction ID, attributes, MESSAGE-INTEGRITY and
FINGERPRINT. The body is forwarded unchanged. At most the first two consecutive
Allocate requests are split (initial request and the retry after a 401 challenge).
After that, or upon a different message, both directions are ordinary stream
copies. Server responses run concurrently with the request processing. No TTL
change, fake data, new relay protocol, payload pacing or background polling is
added for plain TCP. An incomplete header/body is bounded by a 10 second setup
timeout; normal idle copying does not add a periodic timer.

TLS still uses the existing ClientHello/SNI base strategy and the caller's
certificate verification. UDP never enters this runtime. The switch remains off
by default and adds no new profile format or transport mode.

`Runtime::stats()` reports applied TURN requests, TLS ClientHellos and recognized
passthrough connections. The optional observer fires once per kind per runtime,
so 70 workers do not flood the Android log. CSQTTR logs the first applied strategy
and totals on normal shutdown. These counters indicate completed writes, not
proof of server acceptance, a successful tunnel or DPI bypass.

TCP can coalesce writes and DPI can reassemble streams. This is an experiment in
setup segmentation, **not TLS encryption or a demonstrated TSPU bypass**. An IP
rate limit or classification of later packets may remain unaffected. A full
authenticated allocation and an A/B speed test in the affected network are still
required before claiming a fix. No server change is required for this strategy.

Placement matters: the upstream `test/scripts/youtube.lua` transforms the HTTPS
ClientHello of a site when that stream is sent directly through SOCKS5. CSQTTR's
embedded proxy instead receives the connection to the TURN relay. Site packets
are already encrypted by `dispatcher.rs` / `obfs.rs` before TURN transport, so
the site's SNI is not visible at this hook. Transforming inner site TLS would be
a different integration and is not implemented by this preset.

Protocol references: [STUN message structure](https://www.rfc-editor.org/rfc/rfc8489.html#section-5)
and [TURN allocations](https://www.rfc-editor.org/rfc/rfc8656.html#section-7).

Local tests cover fragmented/coalesced input, malformed and short headers,
truncated bodies, the two-request limit, byte preservation and concurrent
challenge/data transfer through the actual CSQTTR TCP connector. The manual
`rust-client/examples/gooby_turn_probe.rs` accepts explicitly supplied known TURN
endpoints and compares direct vs split unauthenticated 401 challenges. It does
not obtain credentials, allocate a relay, send user traffic or measure speed.

Gooby is a Rust SOCKS5 engine whose behavior is selected by a self-contained ZIP configuration. The project is licensed under Apache-2.0.

## Configuration format

Gooby accepts exactly one configuration source:

```text
gooby <config.zip>
gooby --b64_conf <BASE64_OF_CONFIG_ZIP>
```

The ZIP layout is:

```text
rules.db
scripts/
  optional-script.lua
```

`rules.db` is SQLite. Gooby locates a table containing these three columns (the sample uses table `rules`):

| column | type | meaning |
|---|---|---|
| `domains` | TEXT | JSON array such as `["youtube.com","*.youtube.com","*.google"]` |
| `strategy` | TEXT | `base`, `directly`, or `custom` |
| `script` | TEXT/NULL | `.lua` filename; required only for `custom` |

Domain matching is case-insensitive. `youtube.com` is exact. `*.youtube.com` matches subdomains such as `www.youtube.com`, but not the bare `youtube.com`. If several rules match, exact beats wildcard, then the longest suffix wins; ties use database order. If no rule matches, traffic is `directly`.

## Strategies

### `directly`

After the SOCKS5 CONNECT succeeds, Gooby transparently pipes TCP in both directions without modifying application data.

### `base`

Gooby reads the first TLS record, parses ClientHello/SNI, and reconstructs the handshake so the SNI hostname is spread across small TLS records (2 bytes of hostname per record). By default it also applies a best-effort low-TTL disorder step to the first two generated records before restoring the socket TTL. If the first data is not a parseable TLS ClientHello with SNI, it passes through unchanged.

This intentionally mirrors the core behavior of the supplied NoDPI reference at the behavior level, but the Rust source here is an independent implementation rather than a line-by-line port.

### `custom`

Gooby loads `scripts/<script>` from the same archive using `mlua` with the vendored LuaJIT backend. The script must define:

```lua
function process(packet, ctx)
    -- return nil / binary string / plan table
end
```

`packet` is a client→server application-data chunk. On the first call, if the connection starts with TLS, Gooby reads exactly the first TLS record before invoking Lua; later calls receive ordinary stream chunks (currently up to 16 KiB). One Lua VM is kept per TCP connection, so scripts can keep connection-local state in globals/upvalues. `ctx` contains:

```text
ctx.host
ctx.port
ctx.direction == "client_to_server"
ctx.first              -- true only for the first processed unit
ctx.sequence           -- 0, 1, 2, ...
```

Accepted returns:

```lua
return nil                 -- unchanged
return packet              -- one binary chunk
return {chunk1, chunk2}    -- chunk sequence
return {
  chunks = {chunk1, chunk2},
  low_ttl_first = true,    -- optional; honored for the initial unit
  delay_ms = 10,           -- optional, max 5000
}
return { drop = true }     -- send nothing for the initial flight
```

Gooby exposes a helper to Lua:

```lua
local chunks = gooby.fragment_tls_sni(packet, 2)
```

It returns an array of binary TLS records or `nil` when the input is not a supported ClientHello/SNI record. Scripts may ignore the helper and produce arbitrary binary output themselves.

For safety against accidental runaway configs, one Lua plan is limited to 4096 chunks and 1 MiB total output.

## Build

Use a current stable Rust toolchain plus a working native C build toolchain. LuaJIT and SQLite are built vendored by dependencies:

```bash
cargo build --release
```

Run:

```bash
./target/release/gooby test/gooby-youtube.zip --listen 127.0.0.1:1080
```

The server implements SOCKS5 CONNECT with the no-auth method. UDP ASSOCIATE and BIND are not implemented. QUIC/HTTP3 is therefore not proxied; clients normally fall back to TCP/TLS, but a client that bypasses SOCKS for QUIC may need QUIC disabled.

Use a SOCKS client that sends hostnames to the proxy (often called `socks5h` / remote DNS); connections addressed only by IP cannot be matched against domain rules and therefore default to `directly`.

Useful options:

```text
--listen 127.0.0.1:1080
--no-disorder
--initial-record-limit 65535
-v, --verbose
```

## Example

`test/gooby-youtube.zip` is ready to run. Its YouTube-related domains use `strategy=custom` and `youtube.lua`, which calls the built-in SNI fragmentation helper and requests the same low-TTL disorder stage as the base strategy.

## Notes

Gooby is a TCP SOCKS5 proxy, so the word “packet” in the Lua API means an application-data chunk read from a TCP stream, not a raw IP packet captured from the network interface. The first TLS unit is record-aligned; later custom-script units follow TCP read boundaries. `base` changes only the initial ClientHello and then becomes a transparent tunnel, while `custom` may keep transforming later client→server chunks.

The low-TTL disorder technique is inherently network/OS dependent. Use `--no-disorder` if a route or platform does not tolerate it; TLS record fragmentation still remains active.


Treat configuration archives as trusted input: `custom` rules execute Lua code inside the Gooby process. `Lua::new()` uses mlua's safe standalone library set, but a script can still intentionally consume CPU or memory.
