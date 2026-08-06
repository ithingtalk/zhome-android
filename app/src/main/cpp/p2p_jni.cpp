#include <jni.h>
#include <android/log.h>
#include <libp2p_export.h>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

/* Same tag as Kotlin RemoteLinkCoordinator for one Logcat filter */
#define TAG "RemoteLink"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JavaVM* g_zhome_jvm = nullptr;
jclass g_zhome_NativeBridge_class = nullptr;
jmethodID g_zhome_NativeBridge_onLibp2pIotSend = nullptr;
jmethodID g_zhome_NativeBridge_onLibp2pAppMessage = nullptr;

static std::mutex g_libp2p_mutex;
static std::atomic<bool> g_libp2p_inited{false};
static std::string g_home_dir;
static std::string g_cache_dir;

static void attach_env(JNIEnv** env, bool* attached) {
    *attached = false;
    *env = nullptr;
    if (!g_zhome_jvm) return;
    if (g_zhome_jvm->GetEnv(reinterpret_cast<void**>(env), JNI_VERSION_1_6) != JNI_OK) {
        g_zhome_jvm->AttachCurrentThread(env, nullptr);
        *attached = true;
    }
}

static void cb_iot_send(const char* pMsg) {
    if (!pMsg) return;
    const size_t n = std::strlen(pMsg);
    LOGI("libp2p iot_send (%zu bytes)", n);
    JNIEnv* env = nullptr;
    bool attached = false;
    attach_env(&env, &attached);
    if (!env) return;

    if (g_zhome_NativeBridge_class && g_zhome_NativeBridge_onLibp2pIotSend) {
        jstring j = env->NewStringUTF(pMsg);
        if (j) {
            env->CallStaticVoidMethod(g_zhome_NativeBridge_class, g_zhome_NativeBridge_onLibp2pIotSend, j);
            env->DeleteLocalRef(j);
        }
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (attached) g_zhome_jvm->DetachCurrentThread();
}

static void cb_p2p_recv(const char* pMsg) {
    if (!pMsg) return;
    LOGI("libp2p -> app message (%zu bytes)", std::strlen(pMsg));
    JNIEnv* env = nullptr;
    bool attached = false;
    attach_env(&env, &attached);
    if (!env) return;

    if (g_zhome_NativeBridge_class && g_zhome_NativeBridge_onLibp2pAppMessage) {
        jstring j = env->NewStringUTF(pMsg);
        if (j) {
            env->CallStaticVoidMethod(g_zhome_NativeBridge_class, g_zhome_NativeBridge_onLibp2pAppMessage, j);
            env->DeleteLocalRef(j);
        }
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (attached) g_zhome_jvm->DetachCurrentThread();
}

extern "C" JNIEXPORT void JNICALL
Java_com_ithingtalk_zhome_jni_NativeBridge_libp2pInit(
    JNIEnv* env, jobject /*thiz*/, jint jIceGatherMode) {

    std::lock_guard<std::mutex> lock(g_libp2p_mutex);
    if (g_libp2p_inited.load()) {
        LOGI("libp2pInit: already initialized");
        return;
    }
    LOGI("libp2p_init (ice=%d)", (int)jIceGatherMode);
    libp2p_init(cb_iot_send, cb_p2p_recv, static_cast<int>(jIceGatherMode));
    LOGI(
        "libp2p_init returned; MY_PRINT from libip2p.a uses the same Logcat tag \"%s\" "
        "(filter this tag or Verbose). Rebuild libip2p.a after any p2p.h MY_PRINT change.",
        "RemoteLink");
    g_libp2p_inited = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ithingtalk_zhome_jni_NativeBridge_libp2pUpdateIceGatherMode(
    JNIEnv* /*env*/, jobject /*thiz*/, jint jMode) {
    if (!g_libp2p_inited.load()) {
        LOGW("libp2pUpdateIceGatherMode: libp2p not initialized (still updating global preference)");
    }
    libp2p_update_ice_gather_mode(static_cast<int>(jMode));
}

extern "C" JNIEXPORT void JNICALL
Java_com_ithingtalk_zhome_jni_NativeBridge_libp2pExit(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_libp2p_mutex);
    if (!g_libp2p_inited.load()) return;
    LOGI("libp2p_exit");
    libp2p_exit();
    g_libp2p_inited = false;
}

/* Returns JNI_TRUE when the message was a P2P-internal packet (already consumed).
   Returns JNI_FALSE for NAS app-layer responses that must be routed to Kotlin. */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ithingtalk_zhome_jni_NativeBridge_libp2pRecvFromIot(
    JNIEnv* env, jobject /*thiz*/, jstring jPayload) {
    if (!g_libp2p_inited.load()) {
        LOGW("libp2pRecvFromIot: libp2p not initialized");
        return JNI_FALSE;
    }
    const char* s = env->GetStringUTFChars(jPayload, nullptr);
    if (!s) return JNI_FALSE;
    const bool handled = libp2p_recv_p2p_cmd_from_iot(s);
    LOGI("libp2p_recv_p2p_cmd_from_iot handled=%d len=%zu", handled ? 1 : 0, std::strlen(s));
    env->ReleaseStringUTFChars(jPayload, s);
    return handled ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ithingtalk_zhome_jni_NativeBridge_libp2pDownloadFile(
    JNIEnv* env, jobject /*thiz*/,
    jstring jRemotePath, jstring jLocalPath) {
    if (!g_libp2p_inited.load()) {
        LOGW("libp2pDownloadFile: libp2p not initialized");
        return;
    }
    const char* remote = env->GetStringUTFChars(jRemotePath, nullptr);
    const char* local = env->GetStringUTFChars(jLocalPath, nullptr);
    if (remote && local) {
        LOGI("libp2p_download_file remote=%s local=%s", remote, local);
        libp2p_download_file(remote, local);
    }
    if (remote) env->ReleaseStringUTFChars(jRemotePath, remote);
    if (local) env->ReleaseStringUTFChars(jLocalPath, local);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ithingtalk_zhome_jni_NativeBridge_libp2pUploadFile(
    JNIEnv* env, jobject /*thiz*/,
    jstring jLocalPath, jstring jRemotePath) {
    if (!g_libp2p_inited.load()) {
        LOGW("libp2pUploadFile: libp2p not initialized");
        return;
    }
    const char* local = env->GetStringUTFChars(jLocalPath, nullptr);
    const char* remote = env->GetStringUTFChars(jRemotePath, nullptr);
    if (local && remote) {
        LOGI("libp2p_upload_file local=%s remote=%s", local, remote);
        libp2p_upload_file(local, remote);
    }
    if (local) env->ReleaseStringUTFChars(jLocalPath, local);
    if (remote) env->ReleaseStringUTFChars(jRemotePath, remote);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ithingtalk_zhome_jni_NativeBridge_libp2pIntoDevice(
    JNIEnv* env, jobject /*thiz*/, jstring jHome, jstring jCache, jstring jAppIotId, jstring jNasId) {
    if (!g_libp2p_inited.load()) {
        LOGW("libp2pIntoDevice: libp2p not initialized");
        return;
    }
    const char* home = env->GetStringUTFChars(jHome, nullptr);
    const char* cache = env->GetStringUTFChars(jCache, nullptr);
    const char* appId = env->GetStringUTFChars(jAppIotId, nullptr);
    const char* nas = env->GetStringUTFChars(jNasId, nullptr);
    if (home && cache && appId && nas) {
        libp2p_into_device(home, cache, appId, nas);
        LOGI("libp2p_into_device appIotId=%s nasId=%s", appId, nas);
    }
    if (home) env->ReleaseStringUTFChars(jHome, home);
    if (cache) env->ReleaseStringUTFChars(jCache, cache);
    if (appId) env->ReleaseStringUTFChars(jAppIotId, appId);
    if (nas) env->ReleaseStringUTFChars(jNasId, nas);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ithingtalk_zhome_jni_NativeBridge_libp2pLeaveDevice(JNIEnv*, jobject) {
    if (!g_libp2p_inited.load()) {
        return;
    }
    libp2p_leave_device();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ithingtalk_zhome_jni_NativeBridge_libp2pHttpProxyHost(JNIEnv* env, jclass /*clazz*/) {
    return env->NewStringUTF(P2P_HTTP_IP);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ithingtalk_zhome_jni_NativeBridge_libp2pHttpProxyPort(JNIEnv* /*env*/, jclass /*clazz*/) {
    return static_cast<jint>(std::atoi(P2P_HTTP_PORT));
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_zhome_jvm = vm;
    JNIEnv* env = nullptr;
    const jint ge = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (ge == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != 0 || !env) {
            return JNI_ERR;
        }
    } else if (ge != JNI_OK || !env) {
        return JNI_ERR;
    }

    jclass local = env->FindClass("com/ithingtalk/zhome/jni/NativeBridge");
    if (!local) {
        LOGE("JNI_OnLoad: FindClass NativeBridge failed");
        return JNI_ERR;
    }
    g_zhome_NativeBridge_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!g_zhome_NativeBridge_class) {
        return JNI_ERR;
    }

    g_zhome_NativeBridge_onLibp2pIotSend =
        env->GetStaticMethodID(g_zhome_NativeBridge_class, "onLibp2pIotSend", "(Ljava/lang/String;)V");
    g_zhome_NativeBridge_onLibp2pAppMessage =
        env->GetStaticMethodID(g_zhome_NativeBridge_class, "onLibp2pAppMessage", "(Ljava/lang/String;)V");

    if (!g_zhome_NativeBridge_onLibp2pIotSend || !g_zhome_NativeBridge_onLibp2pAppMessage) {
        LOGE("JNI_OnLoad: missing NativeBridge static method(s)");
        env->DeleteGlobalRef(g_zhome_NativeBridge_class);
        g_zhome_NativeBridge_class = nullptr;
        return JNI_ERR;
    }

    LOGI("zhome_native loaded (libip2p only)");
    return JNI_VERSION_1_6;
}
