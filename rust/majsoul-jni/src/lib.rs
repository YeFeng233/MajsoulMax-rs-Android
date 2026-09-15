//! JNI bridge for running the MajsoulMax-rs MITM proxy inside an Android app.
//!
//! Upstream is consumed unmodified through its public API
//! (`Settings`, `ModSettings`, `Modder`, `build_and_start_proxy`), so pulling a
//! newer submodule commit needs no changes here. Everything Android-specific —
//! log routing, lifecycle, state reporting — lives in this file.
//!
//! Lifecycle contract with Kotlin (`moe.majsoulmax.app.core.MitmNative`):
//!
//! * `nativeStart` returns immediately; startup continues on a worker thread
//!   because the optional `liqi`/`lqc.lqbin` refresh does network I/O.
//! * `nativeState` is polled to observe progress, and `nativeLastError`
//!   carries the failure text when it lands on [`STATE_ERROR`].
//! * `nativeStop` requests a graceful shutdown and joins the worker.

#![allow(non_snake_case)]

use std::{
    ffi::CString,
    fs::{File, OpenOptions},
    io::{self, Write},
    os::raw::{c_char, c_int},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicI32, Ordering},
        Arc, Mutex, Once, OnceLock,
    },
    thread::JoinHandle,
};

use anyhow::{anyhow, bail, Context, Result};
use jni::{
    objects::{JClass, JString},
    sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE},
    JNIEnv,
};
use majsoul_max_rs::{build_and_start_proxy, ModSettings, Modder, RwLock, Settings};
use tokio::sync::oneshot;
use tracing::{error, info, warn};
use tracing_subscriber::fmt::MakeWriter;

// Mirrored by MitmNative.State in Kotlin. Keep the numbers in sync.
const STATE_STOPPED: i32 = 0;
const STATE_STARTING: i32 = 1;
const STATE_RUNNING: i32 = 2;
const STATE_STOPPING: i32 = 3;
const STATE_ERROR: i32 = 4;

static STATE: AtomicI32 = AtomicI32::new(STATE_STOPPED);
static LAST_ERROR: Mutex<Option<String>> = Mutex::new(None);
static WORKER: Mutex<Option<Worker>> = Mutex::new(None);

struct Worker {
    shutdown: Option<oneshot::Sender<()>>,
    handle: Option<JoinHandle<()>>,
}

fn set_state(state: i32) {
    STATE.store(state, Ordering::SeqCst);
}

fn fail(err: &anyhow::Error) {
    let text = format!("{err:#}");
    error!("{text}");
    *LAST_ERROR.lock().unwrap_or_else(|e| e.into_inner()) = Some(text);
    set_state(STATE_ERROR);
}

// ---------------------------------------------------------------------------
// Logging: tee tracing output to logcat and to a file the UI process tails.
// ---------------------------------------------------------------------------

const ANDROID_LOG_INFO: c_int = 4;
const LOG_TAG: &str = "MajsoulMax";

#[link(name = "log")]
extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

fn logcat(line: &str) {
    let Ok(tag) = CString::new(LOG_TAG) else {
        return;
    };
    // Interior NULs cannot reach logcat, so drop them rather than the line.
    let Ok(msg) = CString::new(line.replace('\0', "")) else {
        return;
    };
    // SAFETY: both pointers are valid, NUL-terminated, and only read for the
    // duration of the call.
    unsafe {
        __android_log_write(ANDROID_LOG_INFO, tag.as_ptr(), msg.as_ptr());
    }
}

#[derive(Clone)]
struct LogSink {
    file: Arc<Mutex<Option<File>>>,
}

struct LogSinkWriter {
    file: Arc<Mutex<Option<File>>>,
}

impl Write for LogSinkWriter {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        for line in String::from_utf8_lossy(buf).lines() {
            if !line.is_empty() {
                logcat(line);
            }
        }
        if let Ok(mut guard) = self.file.lock() {
            if let Some(file) = guard.as_mut() {
                // A failed log write must never take the proxy down with it.
                let _ = file.write_all(buf);
            }
        }
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        if let Ok(mut guard) = self.file.lock() {
            if let Some(file) = guard.as_mut() {
                let _ = file.flush();
            }
        }
        Ok(())
    }
}

impl<'a> MakeWriter<'a> for LogSink {
    type Writer = LogSinkWriter;

    fn make_writer(&'a self) -> Self::Writer {
        LogSinkWriter {
            file: Arc::clone(&self.file),
        }
    }
}

static LOG_FILE: OnceLock<Arc<Mutex<Option<File>>>> = OnceLock::new();
static LOG_INIT: Once = Once::new();

/// Installs the global subscriber once per process. Later calls only re-point
/// the file handle, which is all a restart within the same process needs.
fn init_logging(log_file: &Path) {
    let slot = LOG_FILE.get_or_init(|| Arc::new(Mutex::new(None)));

    if let Some(parent) = log_file.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    match OpenOptions::new().create(true).append(true).open(log_file) {
        Ok(file) => {
            if let Ok(mut guard) = slot.lock() {
                *guard = Some(file);
            }
        }
        Err(e) => logcat(&format!("failed to open log file {log_file:?}: {e}")),
    }

    LOG_INIT.call_once(|| {
        let filter = tracing_subscriber::EnvFilter::builder()
            .with_default_directive(tracing_subscriber::filter::LevelFilter::WARN.into())
            .from_env()
            .unwrap_or_default()
            .add_directive("majsoul_max_rs=info".parse().expect("static directive"))
            .add_directive("majsoulmax=info".parse().expect("static directive"));

        let sink = LogSink {
            file: Arc::clone(slot),
        };

        tracing_subscriber::fmt()
            .with_env_filter(filter)
            .with_writer(sink)
            .with_target(true)
            .with_level(true)
            .without_time() // the UI stamps lines as it tails them
            .compact()
            .init();
    });
}

// ---------------------------------------------------------------------------
// Core startup
// ---------------------------------------------------------------------------

/// Loads settings, refreshes protocol data when asked to, and builds the modder.
///
/// Unlike upstream's `main`, a successful update does not ask the user to
/// restart: the settings are simply reloaded from disk and startup continues.
async fn prepare(dir: &Path) -> Result<(&'static Settings, Option<Modder>)> {
    let mut settings = Settings::new(dir).context("加载 settings.json 失败")?;

    if settings.auto_update() {
        info!("检查 liqi 更新…");
        match settings.update().await {
            Ok(true) => {
                info!("liqi 已更新，重新载入配置");
                settings = Settings::new(dir).context("更新后重新加载 settings.json 失败")?;
            }
            Ok(false) => {}
            Err(e) => warn!("更新 liqi 失败，继续使用本地版本: {e:#}"),
        }
    }

    // hudsucker's handler wants a 'static Settings. The tunnel runs in its own
    // process (`:core`) which is killed on stop, so this leak is bounded by the
    // process lifetime rather than growing across restarts.
    let settings: &'static Settings = Box::leak(Box::new(settings));

    let modder = if settings.mod_on() {
        let mut mod_settings = ModSettings::new(settings).context("加载 settings.mod.json 失败")?;

        if mod_settings.auto_update() {
            info!("检查 lqc.lqbin 更新…");
            match mod_settings.get_lqc().await {
                Ok(true) => {
                    info!("lqc.lqbin 已更新，重新载入 Mod 配置");
                    mod_settings =
                        ModSettings::new(settings).context("更新后重新加载 Mod 配置失败")?;
                }
                Ok(false) => {}
                Err(e) => warn!("更新 lqc.lqbin 失败，继续使用本地版本: {e:#}"),
            }
        }

        info!("Mod 已启用");
        Some(
            Modder::new(RwLock::new(mod_settings))
                .await
                .context("初始化 Modder 失败")?,
        )
    } else {
        info!("Mod 已关闭");
        None
    };

    if settings.helper_on() {
        info!("助手转发已启用: {}", settings.api_url);
    }

    Ok((settings, modder))
}

/// Fails fast on the single most common startup problem — something else already
/// holding the MITM port — so the UI can say so instead of showing a dead proxy.
fn check_port_free(addr: &str) -> Result<()> {
    let listener = std::net::TcpListener::bind(addr)
        .with_context(|| format!("无法绑定 {addr}，端口可能已被占用"))?;
    drop(listener);
    Ok(())
}

/// Polls the proxy address until it answers, then publishes [`STATE_RUNNING`].
///
/// Necessary because hudsucker binds inside `start()`, not while building: without
/// this, callers that react to RUNNING by connecting would race the bind and get
/// ECONNREFUSED.
async fn await_listening(addr: &str) {
    loop {
        if tokio::net::TcpStream::connect(addr).await.is_ok() {
            info!("MITM 代理监听于 {addr}");
            set_state(STATE_RUNNING);
            return;
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
}

async fn run(dir: PathBuf, mut shutdown: oneshot::Receiver<()>) -> Result<()> {
    // `prepare` can spend tens of seconds refreshing protocol files over the
    // network. Racing it against the stop signal keeps `nativeStop` responsive
    // instead of blocking its caller until the download finishes.
    let (settings, modder) = tokio::select! {
        prepared = prepare(&dir) => prepared?,
        _ = &mut shutdown => {
            info!("启动过程中收到停止信号");
            return Ok(());
        }
    };

    check_port_free(&settings.proxy_addr)?;
    let addr = settings.proxy_addr.clone();

    let graceful = async move {
        let _ = shutdown.await;
        info!("收到停止信号，正在优雅关闭…");
    };

    let serve = build_and_start_proxy(settings, modder, graceful);
    tokio::pin!(serve);

    tokio::select! {
        result = &mut serve => return result,
        () = await_listening(&addr) => {}
    }

    serve.await
}

fn start(config_dir: PathBuf, log_file: PathBuf) -> Result<()> {
    let mut guard = WORKER.lock().map_err(|_| anyhow!("状态锁已损坏"))?;
    if guard.is_some() {
        bail!("MITM 核心已在运行");
    }

    init_logging(&log_file);

    if !config_dir.is_dir() {
        bail!("配置目录不存在: {}", config_dir.display());
    }

    *LAST_ERROR.lock().unwrap_or_else(|e| e.into_inner()) = None;
    set_state(STATE_STARTING);

    let (tx, rx) = oneshot::channel();
    let handle = std::thread::Builder::new()
        .name("majsoul-mitm".to_owned())
        .stack_size(4 * 1024 * 1024)
        .spawn(move || {
            let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                let runtime = tokio::runtime::Builder::new_multi_thread()
                    .worker_threads(2)
                    .enable_all()
                    .thread_name("majsoul-rt")
                    .build()
                    .context("创建 tokio 运行时失败")?;
                runtime.block_on(run(config_dir, rx))
            }));

            match outcome {
                Ok(Ok(())) => {
                    info!("MITM 核心已停止");
                    if STATE.load(Ordering::SeqCst) != STATE_ERROR {
                        set_state(STATE_STOPPED);
                    }
                }
                Ok(Err(e)) => fail(&e),
                Err(_) => fail(&anyhow!("MITM 核心线程 panic")),
            }
        })
        .context("启动 MITM 线程失败")?;

    *guard = Some(Worker {
        shutdown: Some(tx),
        handle: Some(handle),
    });
    Ok(())
}

fn stop() {
    let worker = {
        let Ok(mut guard) = WORKER.lock() else {
            return;
        };
        guard.take()
    };

    let Some(mut worker) = worker else {
        set_state(STATE_STOPPED);
        return;
    };

    if STATE.load(Ordering::SeqCst) != STATE_ERROR {
        set_state(STATE_STOPPING);
    }

    if let Some(tx) = worker.shutdown.take() {
        let _ = tx.send(());
    }
    if let Some(handle) = worker.handle.take() {
        // hudsucker's graceful shutdown drops the listener promptly; if the join
        // ever hangs the caller kills the whole `:core` process anyway.
        let _ = handle.join();
    }
    if STATE.load(Ordering::SeqCst) != STATE_ERROR {
        set_state(STATE_STOPPED);
    }
}

// ---------------------------------------------------------------------------
// JNI surface
// ---------------------------------------------------------------------------

fn jstring_to_path(env: &mut JNIEnv, value: &JString) -> Result<PathBuf> {
    let text = env
        .get_string(value)
        .context("读取 Java 字符串失败")?
        .to_string_lossy()
        .into_owned();
    Ok(PathBuf::from(text))
}

#[no_mangle]
pub extern "C" fn Java_moe_majsoulmax_app_core_MitmNative_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    config_dir: JString,
    log_file: JString,
) -> jint {
    let result = (|| -> Result<()> {
        let dir = jstring_to_path(&mut env, &config_dir)?;
        let log = jstring_to_path(&mut env, &log_file)?;
        start(dir, log)
    })();

    match result {
        Ok(()) => 0,
        Err(e) => {
            fail(&e);
            -1
        }
    }
}

#[no_mangle]
pub extern "C" fn Java_moe_majsoulmax_app_core_MitmNative_nativeStop(_env: JNIEnv, _class: JClass) {
    stop();
}

#[no_mangle]
pub extern "C" fn Java_moe_majsoulmax_app_core_MitmNative_nativeState(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    STATE.load(Ordering::SeqCst)
}

#[no_mangle]
pub extern "C" fn Java_moe_majsoulmax_app_core_MitmNative_nativeIsRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if STATE.load(Ordering::SeqCst) == STATE_RUNNING {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "C" fn Java_moe_majsoulmax_app_core_MitmNative_nativeLastError(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let message = LAST_ERROR
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
        .unwrap_or_default();
    match env.new_string(message) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "C" fn Java_moe_majsoulmax_app_core_MitmNative_nativeVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string(env!("CARGO_PKG_VERSION")) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
