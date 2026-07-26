/*
 * JNI shim around hev-socks5-tunnel.
 *
 * The VpnService hands us the tun file descriptor and a YAML config that points
 * at the Meta kernel's SOCKS5 port; hev-socks5-tunnel owns the user-space TCP/IP
 * stack that bridges the two. Its blocking main loop runs on a dedicated pthread
 * so the Android main thread is never held.
 */

#include <jni.h>
#include <errno.h>
#include <pthread.h>
#include <time.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "MajsoulMax/tun2socks"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Return codes shared with Tun2SocksNative.kt */
#define TUN2SOCKS_OK 0
#define TUN2SOCKS_ALREADY_RUNNING (-1)
#define TUN2SOCKS_BAD_ARGUMENT (-2)
#define TUN2SOCKS_THREAD_FAILED (-3)
#define TUN2SOCKS_UNAVAILABLE (-4)

#ifdef HAVE_HEV_TUNNEL
/*
 * Stable C ABI exported by hev-socks5-tunnel; declared here so the build does
 * not depend on the submodule's headers being checked out.
 */
extern int hev_socks5_tunnel_main_from_str(const unsigned char *config_str,
                                          unsigned int config_len,
                                          int tun_fd);
extern void hev_socks5_tunnel_quit(void);
#endif

/*
 * The worker is detached and its exit is signalled through g_cond rather than
 * joined. Joining would be wrong in two ways: if the tunnel exits on its own
 * nobody is waiting, so the thread would leak; and two concurrent stops would
 * join the same thread twice, which is undefined.
 */
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_cond = PTHREAD_COND_INITIALIZER;
static int g_running = 0;
/*
 * libhev-socks5-tunnel.so ships a JNI_OnLoad that RegisterNatives against a Java
 * class from upstream's own sample app. That class is absent here, so FindClass
 * returns null and ART aborts the process. System.loadLibrary resolves JNI_OnLoad
 * by dlsym on our handle, and bionic searches the library before its DT_NEEDED
 * dependencies, so a no-op here wins and upstream's never runs. We do not need it:
 * our entry points resolve by JNI name mangling, and we call
 * hev_socks5_tunnel_main_from_str directly rather than through its Java bridge.
 */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void) vm;
    (void) reserved;
    return JNI_VERSION_1_6;
}

/* How long nativeStop waits for the tunnel loop to acknowledge quit(). */
#define STOP_TIMEOUT_SECONDS 5

struct tunnel_args {
    unsigned char *config;
    unsigned int config_len;
    int tun_fd;
};

static void *tunnel_main(void *opaque)
{
    struct tunnel_args *args = (struct tunnel_args *) opaque;

#ifdef HAVE_HEV_TUNNEL
    LOGI("tunnel starting on fd %d", args->tun_fd);
    int rc = hev_socks5_tunnel_main_from_str(args->config, args->config_len, args->tun_fd);
    if (rc != 0) {
        LOGE("tunnel exited with %d", rc);
    } else {
        LOGI("tunnel exited cleanly");
    }
#else
    LOGE("built without hev-socks5-tunnel; tunnel cannot run");
#endif

    free(args->config);
    free(args);

    pthread_mutex_lock(&g_lock);
    g_running = 0;
    pthread_cond_broadcast(&g_cond);
    pthread_mutex_unlock(&g_lock);
    return NULL;
}

JNIEXPORT jint JNICALL
Java_moe_majsoulmax_app_core_Tun2SocksNative_nativeStart(JNIEnv *env,
                                                        jclass clazz,
                                                        jstring config,
                                                        jint tun_fd)
{
    (void) clazz;

#ifndef HAVE_HEV_TUNNEL
    (void) env; (void) config; (void) tun_fd;
    return TUN2SOCKS_UNAVAILABLE;
#else
    if (config == NULL || tun_fd < 0) {
        return TUN2SOCKS_BAD_ARGUMENT;
    }

    pthread_mutex_lock(&g_lock);
    if (g_running) {
        pthread_mutex_unlock(&g_lock);
        return TUN2SOCKS_ALREADY_RUNNING;
    }

    const char *utf = (*env)->GetStringUTFChars(env, config, NULL);
    if (utf == NULL) {
        pthread_mutex_unlock(&g_lock);
        return TUN2SOCKS_BAD_ARGUMENT;
    }

    size_t len = strlen(utf);
    struct tunnel_args *args = (struct tunnel_args *) calloc(1, sizeof(*args));
    unsigned char *copy = (unsigned char *) malloc(len + 1);
    if (args == NULL || copy == NULL) {
        free(args);
        free(copy);
        (*env)->ReleaseStringUTFChars(env, config, utf);
        pthread_mutex_unlock(&g_lock);
        return TUN2SOCKS_THREAD_FAILED;
    }

    memcpy(copy, utf, len);
    copy[len] = '\0';
    (*env)->ReleaseStringUTFChars(env, config, utf);

    args->config = copy;
    args->config_len = (unsigned int) len;
    args->tun_fd = tun_fd;

    g_running = 1;
    pthread_t thread;
    if (pthread_create(&thread, NULL, tunnel_main, args) != 0) {
        g_running = 0;
        free(copy);
        free(args);
        pthread_mutex_unlock(&g_lock);
        LOGE("pthread_create failed");
        return TUN2SOCKS_THREAD_FAILED;
    }
    pthread_detach(thread);
    pthread_mutex_unlock(&g_lock);

    return TUN2SOCKS_OK;
#endif
}

JNIEXPORT void JNICALL
Java_moe_majsoulmax_app_core_Tun2SocksNative_nativeStop(JNIEnv *env, jclass clazz)
{
    (void) env;
    (void) clazz;

#ifdef HAVE_HEV_TUNNEL
    pthread_mutex_lock(&g_lock);
    if (!g_running) {
        pthread_mutex_unlock(&g_lock);
        return;
    }
    pthread_mutex_unlock(&g_lock);

    hev_socks5_tunnel_quit();

    /*
     * quit() issued before hev's event loop is up can be dropped, so wait with a
     * deadline and retry rather than blocking forever.
     */
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += STOP_TIMEOUT_SECONDS;

    pthread_mutex_lock(&g_lock);
    while (g_running) {
        struct timespec step;
        clock_gettime(CLOCK_REALTIME, &step);
        if (step.tv_sec >= deadline.tv_sec) {
            LOGE("tunnel did not stop within %ds", STOP_TIMEOUT_SECONDS);
            break;
        }
        step.tv_sec += 1;
        if (pthread_cond_timedwait(&g_cond, &g_lock, &step) == ETIMEDOUT) {
            pthread_mutex_unlock(&g_lock);
            hev_socks5_tunnel_quit();
            pthread_mutex_lock(&g_lock);
        }
    }
    pthread_mutex_unlock(&g_lock);
#endif
}

JNIEXPORT jboolean JNICALL
Java_moe_majsoulmax_app_core_Tun2SocksNative_nativeIsRunning(JNIEnv *env, jclass clazz)
{
    (void) env;
    (void) clazz;

    pthread_mutex_lock(&g_lock);
    int running = g_running;
    pthread_mutex_unlock(&g_lock);
    return running ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_moe_majsoulmax_app_core_Tun2SocksNative_nativeIsAvailable(JNIEnv *env, jclass clazz)
{
    (void) env;
    (void) clazz;
#ifdef HAVE_HEV_TUNNEL
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}
