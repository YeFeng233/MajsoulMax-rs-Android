# Architecture notes

This document records the decisions that are not obvious from the code, and the
failure mode each one is avoiding. Read it before changing anything in `core/` or
`service/`.

## Why upstream is a submodule, not a fork

`majsoul_max_rs` exposes everything the app needs as public API: `Settings`,
`ModSettings`, `Modder`, `build_and_start_proxy`, and — critically —
`build_and_start_proxy` takes a `graceful_shutdown: impl Future`, which is
exactly the hook a mobile lifecycle needs. So `rust/majsoul-jni` is a separate
crate that depends on upstream by path and reimplements what `main.rs` does,
rather than patching upstream to add JNI entry points.

Consequences:

* Bumping upstream is `git -C external/MajsoulMax-rs checkout <rev>`. No rebase,
  no merge conflicts in vendored code.
* `liqi_config/` and `src/ca/hudsucker.cer` are copied out of the submodule into
  app assets at build time (`stageUpstreamAssets` in `app/build.gradle.kts`), so
  the protocol data and the CA are never duplicated in this repo.
* Upstream's `build.rs` writes generated code into its own `src/proto`, so the
  submodule working tree is dirtied by a build. That is upstream's behaviour, not
  a bug here.

One deviation from `main.rs` is intentional: upstream asks the user to restart
after a successful protocol update. On Android the JNI layer reloads `Settings`
from disk and continues, because "please restart the program" is not a thing a
mobile app can say.

## Why the tunnel lives in its own process

`hudsucker`'s handler requires `&'static Settings`, so the JNI layer does
`Box::leak`. Each run leaks the settings plus a `prost_reflect::DescriptorPool`
and the parsed `liqi.json` — megabytes, not kilobytes. Three options existed:

1. Cache the leaked `Settings` in a `OnceLock` and reuse it. Rejected: config
   edits would then need an app restart to take effect, which defeats the point
   of having an editor.
2. Leak on every start and accept unbounded growth. Rejected for a long-lived
   app the user toggles repeatedly.
3. Run the tunnel in `:core` and kill that process on stop.

Option 3 also buys process isolation from native crashes and a guaranteed
fresh-config read on every start, so that is what `AndroidManifest.xml` declares
and what `stopSelfAndProcess` implements.

The cost is that UI and tunnel are in different processes, which rules out shared
in-memory state. Hence:

* **Status** — `status.json` plus a package-scoped broadcast
  (`data/TunnelStatus.kt`). The file is the source of truth so the UI shows the
  right state even if it was not running when the state changed; the broadcast is
  only a "re-read it" nudge. A bound service or a broadcast-only design would
  both get a cold start wrong.
* **Logs** — one append-only file that the Rust core, the kernel's output pump and
  the Kotlin service all write to, tailed by the UI (`core/LogStore.kt`). No IPC,
  survives a `:core` restart, and shareable straight into a bug report.
* **Tunnel settings** — `tunnel.json`, not DataStore, because DataStore is not
  multi-process safe. The UI is the only writer; `:core` only reads, once, at
  startup.

## Why DOMAIN-KEYWORD rules need the sniffer

The tun delivers IP packets. hev-socks5-tunnel forwards them to the kernel's
SOCKS5 port with an IP destination, so by the time the kernel applies rules the
hostname is gone and `DOMAIN-KEYWORD,majsoul` can never match.

Two ways out: fake-IP DNS, or connection sniffing. The generated config uses
`sniffer.override-destination`, which recovers the hostname from the TLS
handshake and rewrites the destination before rules run. Fake-IP would also work
but requires DNS traffic to actually reach the kernel's resolver, which in turn
needs DNS hijacking that a SOCKS5 inbound does not do. Sniffing needs nothing
extra, so `dns.enable` is left `false` and the tun simply advertises a real
upstream resolver.

## Why loopback protection is a VPN exclusion

Upstream's Clash example uses `PROCESS-NAME-REGEX,majsoul_max_rs.*?,DIRECT` to
keep the proxy's own traffic from being routed back through itself. Android has no
equivalent that applies to an in-process library, and `find-process-mode` is
unreliable there anyway.

Instead `MajsoulVpnService.excludeSelf` calls `addDisallowedApplication` on our
own package. That removes *all* of this app's traffic from the tun in one stroke:
the kernel dialling `127.0.0.1:23410`, the MITM core dialling Mahjong Soul, and
the protocol-update downloads. It is stronger than a process rule and cannot be
misconfigured by the user.

The side effect is that a WebView we host is also outside the tunnel — which is
why `ui/web/GameActivity.kt` sets a WebView proxy override instead of relying on
routing. That turned out to be a feature: the built-in browser then works with no
VPN consent at all, and the service supports a `proxyOnly` mode for exactly that.

## Why the config editor patches instead of serialising

`settings.mod.json` contains `viewsPresets`: ten arrays of protobuf `ViewSlot`
messages. Modelling that in Kotlin would be a lot of work for a field almost
nobody edits by hand, and — worse — a Kotlin data class round-trip would silently
drop any field this app does not know about, including fields a future upstream
release adds. Rust would then refuse to deserialise the file at all, since its
structs require every field.

So `data/ConfigRepository.kt` works on `JsonObject` and saves a **patch**: read
the file as it is on disk right now, overlay only the keys the user touched, write
the merged result. Unknown fields are preserved byte-for-byte, and the Rust side
rewriting `liqiVersion` after an update cannot be reverted by a save from a stale
screen.

## Why the Meta kernel is a child process

The alternative was linking mihomo's Go internals as a `c-shared` library, as some
Android clients do. Rejected: that couples the app to Go-internal APIs that change
between releases, and it means building Go in CI.

A child process talks to us over mihomo's documented CLI (`-d`, `-f`) and YAML
config, which is stable across releases. Upgrading the kernel is then a file
swap — `scripts/fetch-mihomo.sh` with a different tag — and never a code change.

Two constraints follow from Android:

* The binary must live in `jniLibs` and be named `lib*.so`. That directory is the
  only exec-permitted location for an app, and the naming is what makes the
  packager treat it as a native library.
* `packaging { jniLibs { useLegacyPackaging = true } }` is mandatory. Without it
  the library is never extracted to disk and `canExecute()` is false —
  `MihomoKernel.start` checks for exactly this and says so, because the failure is
  otherwise a baffling `EACCES`.

`MihomoKernel.start` also polls the mixed port rather than assuming a successful
`exec` means a working kernel; a bad config makes mihomo exit non-zero within
milliseconds and the user gets the exit code and the log instead of a tunnel that
silently drops everything.

## The certificate problem

Android 7 stopped trusting user-installed CAs for apps that do not opt in, and
Android 11 broke the in-app installer intent on most ROMs. There is no single API
that works everywhere, so `core/CertManager.kt` returns an ordered list of
candidates and the screen tries them in turn:

1. `KeyChain.createInstallIntent()` with the DER bytes — the direct route, still
   works on older releases.
2. `ACTION_VIEW` on a `FileProvider` URI with `application/x-x509-ca-cert`, which
   most ROMs route to `com.android.certinstaller`.
3. `ACTION_SECURITY_SETTINGS`, then `ACTION_SETTINGS`.

Plus an export to Downloads for the fully manual path, and verbatim steps in the
UI. Every candidate is filtered through `resolveActivity` first.

Trust state is *read back* from `AndroidCAStore` rather than inferred from "the
install intent returned OK", because on several ROMs it returns OK without
installing anything. That store exposes system and user CAs together, so one scan
answers the question for both.

The residual limitation is honest and stated in the UI: if the game client does
not opt into user CAs, no amount of installing helps, and the built-in browser is
the answer.

## Native payloads and graceful degradation

Three `.so` files are not in git and are produced by the scripts:

| file | produced by | lands in | missing means |
|---|---|---|---|
| `libmajsoulmax.so` | `scripts/build-rust.sh` | `app/build/generated/jniLibs` | the MITM core cannot start |
| `libmihomo.so` | `scripts/fetch-mihomo.sh` | `app/src/main/jniLibs` | the kernel cannot start |
| `libhev-socks5-tunnel.so` | `scripts/build-tun2socks.sh` | `app/src/main/jniLibs` | the tun cannot be bridged |

Both directories are registered as `jniLibs` source dirs. The split is deliberate:
the Rust output is produced *by* a Gradle task, so it lives in `build/` and is
wired in as a task-backed source dir (which is what gives AGP's merge tasks a real
dependency on it); the other two are produced out-of-band by scripts Gradle does
not run, so they sit in `src/main/jniLibs` — which is also where the CMake shim
looks for the tunnel library to link against.

The build *warns* about each rather than failing, and the CMake shim compiles a
stub returning `TUN2SOCKS_UNAVAILABLE` when the tunnel payload is absent. This
matters for two reasons: the UI is buildable without a Rust or NDK toolchain, and
a broken build surfaces as a named pre-flight check on the Home screen instead of
an `UnsatisfiedLinkError` at launch.

`Tun2SocksNative` and `MitmNative` both guard `System.loadLibrary` behind a lazy
`available` flag for the same reason.

## Startup order, and why it is that order

`MajsoulVpnService.startTunnel` runs:

1. unpack assets — the core reads them as files, so nothing works before this
2. start the MITM core, then **poll until it reports RUNNING** — it may spend a
   minute refreshing `liqi.json`/`lqc.lqbin` over the network first
3. start the kernel, waiting for its port to accept
4. establish the tun and hand the descriptor to the bridge

Each step is a `step()` call that publishes its description, so a hang is visible
in the notification and on the Home screen rather than being a spinner. Teardown
runs in reverse, and every step is independently failure-tolerant — the tun
descriptor must outlive the native tunnel, so it is closed only after
`Tun2SocksNative.stop()` returns.

Foreground status is claimed *before* startup begins, because Android allows only
a few seconds for that and step 2 alone can take much longer.
