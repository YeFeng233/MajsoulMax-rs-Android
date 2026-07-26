# 雀魂 Max for Android

An all-in-one Android front end for [MajsoulMax-rs](https://github.com/Xerxes-2/MajsoulMax-rs).
Upstream is a desktop MITM proxy that unlocks characters, skins, decorations and
titles in Mahjong Soul; on Android it has so far meant Termux plus NekoBox plus a
manually installed certificate plus hand-edited JSON. This app collapses all of
that into one install:

1. **One-tap certificate install** — jumps straight to the system CA installer,
   with per-ROM fallbacks and a live read-back of whether the certificate is
   actually trusted.
2. **Bundled Meta (mihomo) kernel** — the app owns a `VpnService`, ships the
   kernel, generates the routing rules, and keeps its own traffic out of the
   tunnel so the proxy cannot loop back on itself. No NekoBox, no Termux.
3. **Interactive configuration** — typed forms for `settings.json` and
   `settings.mod.json`, a validating raw-JSON editor, and separate controls for
   the tunnel itself.

Upstream is consumed **unmodified**, as a git submodule, through its public Rust
API. Pulling a newer upstream commit is a submodule bump.

> This project is free and open source. If you paid for it, you were scammed.
> For study and personal use only. Using it may get your account banned; neither
> the upstream author nor this packaging takes any responsibility. Licensed
> GPL-3.0, same as upstream.

## How it fits together

```
┌──────────────┐        ┌──────────┐      ┌──────────────┐      ┌───────────┐
│ game / other │  tun   │ hev-     │socks5│ Meta kernel  │ http │ MITM core │
│ apps         ├───────►│ socks5-  ├─────►│ (mihomo)     ├─────►│ (Rust)    ├──► internet
└──────────────┘        │ tunnel   │      │  rules       │      └───────────┘
                        └──────────┘      └──────┬───────┘
                                                 │ DIRECT (everything else)
                                                 ▼
```

* **MITM core** — upstream's crate, cross-compiled to a JNI shared library
  (`rust/majsoul-jni`). Kotlin drives it through `start`/`stop`/`state`.
* **Meta kernel** — the official mihomo Android binary, shipped inside `jniLibs`
  (the only place an app may execute from) and driven as a child process over its
  documented YAML config.
* **tun bridge** — hev-socks5-tunnel, which owns the user-space TCP/IP stack
  between the tun descriptor and the kernel's SOCKS5 port.
* **Routing** — `DOMAIN-KEYWORD` rules for `majsoul` / `maj-soul` /
  `mahjongsoul` go to the MITM proxy, everything else goes DIRECT. Because the
  tun hands us IP packets, the kernel's TLS-SNI sniffer recovers the hostname so
  those domain rules can match at all.
* **Loopback protection** — the VPN excludes this app's own package, so the
  kernel's outbound connections and the proxy's upstream connections can never
  re-enter the tunnel. This replaces upstream's `PROCESS-NAME` rule for desktop.

The tunnel runs in a separate `:core` process, which is killed on stop. That is
deliberate: the Rust core intentionally leaks a `&'static Settings` (plus a large
protobuf descriptor pool) per run, and letting the process exit reclaims it while
also guaranteeing the next start reads fresh config from disk.

`docs/ARCHITECTURE.md` goes into more detail, including the failure modes each
design decision is avoiding.

## Building

The APK is not committed. Two ways to get one:

### GitHub Actions (no local toolchain)

Push the repo and the workflow in `.github/workflows/build.yml` does everything:
installs the NDK, protoc and Rust Android targets, downloads the Meta kernel,
builds hev-socks5-tunnel and the Rust core, then assembles and uploads a signed
APK as a build artifact. `workflow_dispatch` lets you pin a specific mihomo
release or build a debug variant.

The release build is signed with a throwaway key generated in CI so it installs
without extra steps. Swap in your own keystore before distributing anything.

### Locally

Prerequisites: JDK 17, Android SDK with NDK 27, CMake 3.22, Rust 1.85+ with
`cargo-ndk`, `protoc`, and `jq`.

```bash
git clone --recursive https://github.com/<you>/MajsoulMax-Android.git
cd MajsoulMax-Android
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.2.12479018

./scripts/fetch-mihomo.sh        # -> app/src/main/jniLibs/<abi>/libmihomo.so
./scripts/build-tun2socks.sh     # -> app/src/main/jniLibs/<abi>/libhev-socks5-tunnel.so
./scripts/build-rust.sh          # -> app/build/generated/jniLibs/<abi>/libmajsoulmax.so

./gradlew assembleDebug
```

`./gradlew assembleDebug` on its own also invokes `build-rust.sh` through
`preBuild`; set `-Pmajsoulmax.buildRust=false` to skip that when the library is
already built. The build warns rather than fails when a native payload is
missing, so you can iterate on the UI without a full toolchain — the VPN just
refuses to start and says why.

ABIs shipped: `arm64-v8a` and `armeabi-v7a` (no x86_64, so it will not install on an x86_64 emulator).

## Using it

1. Open the app and accept the disclaimer.
2. **Cert** tab → *Install certificate*. The pre-flight checklist on Home turns
   green once the system actually trusts it.
3. **Config** tab → set what you want unlocked. `Mod` covers characters, skins,
   titles and decorations; `Proxy` covers ports, routed domains and DNS.
4. Home → flip the switch, grant the VPN prompt, launch Mahjong Soul.

If the game client ignores user-installed CAs — Android only honours them for
apps that opt in — use **Open Mahjong Soul** on the Home screen instead. That
runs the web client in a WebView with a proxy override pointed at the MITM core,
needs no VPN consent at all, and works because a WebView does honour the user
trust store.

Per-app routing lives in the **Apps** tab; by default everything except this app
goes through the tunnel.

## Things worth knowing

* **Only line 1.** Same limitation as upstream on Android.
* **Unlocks are local.** Other players still see your real character.
* **`liqi.desc` is compiled in.** Upstream embeds the protobuf descriptor at
  build time, so auto-update refreshes `liqi.json` and `lqc.lqbin` but a protocol
  change that alters the descriptor needs a rebuild of the APK.
* **The tun bridge is the one external native contract.** Everything else talks
  to its dependency over a stable interface (Rust public API, mihomo's YAML
  config). `app/src/main/cpp/tun2socks_jni.c` declares two hev-socks5-tunnel
  symbols directly; if upstream ever renames them, that file and
  `scripts/build-tun2socks.sh` are the only places to touch, and a build without
  the payload degrades to a clear runtime error rather than a crash.

## Layout

```
app/                        Android app (Kotlin, Compose, Material 3)
  src/main/kotlin/.../core     natives, assets, certificate, kernel supervisor
  src/main/kotlin/.../data     config repository, tunnel settings, status IPC
  src/main/kotlin/.../service  VpnService, notification, controller
  src/main/kotlin/.../ui       Compose screens
  src/main/cpp                 JNI shim for hev-socks5-tunnel
rust/majsoul-jni/           JNI bridge over the upstream crate
external/MajsoulMax-rs      upstream, unmodified (submodule)
external/hev-socks5-tunnel  tun bridge (submodule)
scripts/                    native build/fetch scripts
```

## Credits

* [Xerxes-2/MajsoulMax-rs](https://github.com/Xerxes-2/MajsoulMax-rs) — the proxy
  and mod logic this app packages.
* [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo) — the Meta kernel.
* [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — the
  tun bridge.
