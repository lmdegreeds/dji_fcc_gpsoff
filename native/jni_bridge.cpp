// jni_bridge.cpp — the JNI surface: a native transport plus a Kotlin RX sink
// callback. Methods are bound with RegisterNatives from JNI_OnLoad, so the
// library exports only JNI_OnLoad.
#include <jni.h>
#include <memory>
#include <mutex>
#include "duml_core.h"

using namespace duml;

namespace {
JavaVM* g_vm = nullptr;
jclass  g_sink_cls = nullptr;      // com/dji/fccgpsoff/DumlNative
jmethodID g_sink_mid = nullptr;    // static void onNativeFrame(int,int,int,int,byte[],int,byte[])
std::unique_ptr<Transport> g_tp;
std::once_flag g_tp_once;

// Lazily create the transport so aux/capture works even if the main channel was
// never started (nativeStartAux before nativeStart). call_once guards against two
// JNI callers (e.g. nativeStart / nativeStartAux on different threads) racing the
// construction.
Transport* tp() { std::call_once(g_tp_once, [] { g_tp.reset(new Transport()); }); return g_tp.get(); }

// Per-thread JNIEnv that attaches once and detaches at thread exit (its
// thread_local destructor runs when the native RX thread finishes). Replaces the
// old attach/detach-per-frame, which paid a JVM round trip on every RX frame.
struct JniAttach {
    JNIEnv* env = nullptr;
    bool attached = false;
    JNIEnv* get() {
        if (env) return env;
        jint r = g_vm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (r == JNI_EDETACHED) {
            if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) { env = nullptr; return nullptr; }
            attached = true;
        } else if (r != JNI_OK) {
            env = nullptr;
        }
        return env;
    }
    ~JniAttach() { if (attached && g_vm) g_vm->DetachCurrentThread(); }
};
thread_local JniAttach t_jni;

void rx_sink(const Frame& f, const uint8_t* wire, size_t wlen, int route, void*) {
    if (!g_sink_cls || !g_sink_mid) return;
    JNIEnv* e = t_jni.get();
    if (!e) return;                                   // attach failed — nothing we can do
    jbyteArray pl = e->NewByteArray((jsize)f.payload.size());
    jbyteArray wr = e->NewByteArray((jsize)wlen);
    if (pl && wr) {
        e->SetByteArrayRegion(pl, 0, (jsize)f.payload.size(), (const jbyte*)f.payload.data());
        e->SetByteArrayRegion(wr, 0, (jsize)wlen, (const jbyte*)wire);
        e->CallStaticVoidMethod(g_sink_cls, g_sink_mid,
                                (jint)f.sender, (jint)f.receiver,
                                (jint)f.cmdSet, (jint)f.cmdId, pl, (jint)route, wr);
    }
    if (pl) e->DeleteLocalRef(pl);
    if (wr) e->DeleteLocalRef(wr);
    // The Kotlin sink is user-settable and may throw; a pending exception left
    // on this thread corrupts the next JNI call on the RX thread (UB/abort). A
    // failed NewByteArray (OOM) also leaves one pending — clear either way.
    if (e->ExceptionCheck()) { e->ExceptionDescribe(); e->ExceptionClear(); }
}

// Build a Java byte[] from native Bytes, propagating OOM as null (a pending
// exception the caller returns straight to the JVM) instead of dereferencing it.
jbyteArray to_jbytes(JNIEnv* e, const Bytes& b) {
    jbyteArray out = e->NewByteArray((jsize)b.size());
    if (!out) return nullptr;
    e->SetByteArrayRegion(out, 0, (jsize)b.size(), (const jbyte*)b.data());
    return out;
}

// Copy a (possibly null) Java byte[] into native Bytes.
Bytes bytes_from_java(JNIEnv* e, jbyteArray a) {
    if (!a) return {};
    jsize n = e->GetArrayLength(a);
    Bytes b(n);
    if (n > 0) e->GetByteArrayRegion(a, 0, n, (jbyte*)b.data());
    return b;
}

Frame frame_from_java(JNIEnv* e, jint recv, jint cmdType, jint cmdSet, jint cmdId, jbyteArray payload) {
    Frame f; f.sender = 130; f.receiver = (uint8_t)recv;
    f.cmdType = (uint8_t)cmdType; f.cmdSet = (uint8_t)cmdSet; f.cmdId = (uint8_t)cmdId;
    f.payload = bytes_from_java(e, payload);
    return f;
}

jint jni_start(JNIEnv*, jclass) {
    tp()->set_rx_sink(rx_sink, nullptr);
    return (jint)g_tp->start();
}
void jni_stop(JNIEnv*, jclass) { if (g_tp) g_tp->stop(); }

// aux / hijack read — a second reader on DJI Fly's port (default 40007).
jint jni_start_aux(JNIEnv*, jclass, jint port) {
    tp()->set_rx_sink(rx_sink, nullptr);            // ensure the sink is wired even if main never started
    return (jint)g_tp->start_aux((int)port);
}
void jni_stop_aux(JNIEnv*, jclass) { if (g_tp) g_tp->stop_aux(); }
jboolean jni_aux_running(JNIEnv*, jclass) { return (g_tp && g_tp->aux_running()) ? JNI_TRUE : JNI_FALSE; }

jboolean jni_send(JNIEnv* e, jclass, jint recv, jint ct, jint cs, jint ci, jbyteArray pl) {
    if (!g_tp) return JNI_FALSE;
    Frame f = frame_from_java(e, recv, ct, cs, ci, pl);
    return g_tp->send(f) ? JNI_TRUE : JNI_FALSE;
}

jbyteArray jni_request(JNIEnv* e, jclass, jint recv, jint ct, jint cs, jint ci, jbyteArray pl, jint timeout) {
    if (!g_tp) return nullptr;
    Frame f = frame_from_java(e, recv, ct, cs, ci, pl);
    Bytes r = g_tp->request(f, timeout);
    if (r.empty()) return nullptr;
    return to_jbytes(e, r);
}

jbyteArray jni_param_hash(JNIEnv* e, jclass, jstring name) {
    if (!name) return nullptr;
    const char* c = e->GetStringUTFChars(name, nullptr);
    if (!c) return nullptr;                           // OOM — pending exception
    Bytes h = param_hash(c);
    e->ReleaseStringUTFChars(name, c);
    return to_jbytes(e, h);
}

// Write a whole profile over one socket. `frames` is byte[][]; returns the count
// of frames fully written, or -1 when the connect failed.
jint jni_send_many(JNIEnv* e, jclass, jint port, jobjectArray frames, jint gap_ms) {
    if (!frames) return -1;
    jsize n = e->GetArrayLength(frames);
    std::vector<Bytes> out;
    out.reserve((size_t)n);
    for (jsize i = 0; i < n; ++i) {
        jobject o = e->GetObjectArrayElement(frames, i);
        if (!o) continue;
        out.push_back(bytes_from_java(e, (jbyteArray)o));
        e->DeleteLocalRef(o);                     // n can be ~45: do not fill the local-ref table
    }
    return (jint)Transport::send_many((int)port, out, (int)gap_ms);
}

jboolean jni_duss(JNIEnv* e, jclass, jbyteArray wire) {
    return Transport::duss_send(bytes_from_java(e, wire)) ? JNI_TRUE : JNI_FALSE;
}

// Copy a (possibly null) Java String into std::string (empty on null/OOM).
std::string str_from_java(JNIEnv* e, jstring s) {
    if (!s) return {};
    const char* c = e->GetStringUTFChars(s, nullptr);
    if (!c) return {};
    std::string r(c);
    e->ReleaseStringUTFChars(s, c);
    return r;
}

// DUSS firmware-bus diagnostics — see duss_bus.cpp / REPORT/FIRMWARE-BUS-DUSS.md.
jstring jni_duss_probe(JNIEnv* e, jclass, jstring peer) {
    return e->NewStringUTF(duss_probe(str_from_java(e, peer)).c_str());
}

// flags: bit0 = SOCK_DGRAM, bit1 = peer in abstract ns, bit2 = bind abstract
// source, bit3 = no-connect (sendto + recvfrom ANY).
jstring jni_duss_xact(JNIEnv* e, jclass, jint flags, jstring peer, jstring source,
                      jbyteArray wire, jint readMs, jint wantSet, jint wantId) {
    std::string t = duss_xact(bytes_from_java(e, wire),
                              (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0,
                              str_from_java(e, peer), str_from_java(e, source),
                              (int)readMs, (int)wantSet, (int)wantId);
    return e->NewStringUTF(t.c_str());
}

jbyteArray jni_build(JNIEnv* e, jclass, jint sender, jint recv, jint ct, jint cs, jint ci, jbyteArray pl) {
    Frame f; f.sender = (uint8_t)sender; f.receiver = (uint8_t)recv;
    f.cmdType = (uint8_t)ct; f.cmdSet = (uint8_t)cs; f.cmdId = (uint8_t)ci;
    f.payload = bytes_from_java(e, pl);
    return to_jbytes(e, build_frame(f));              // empty byte[] if payload oversize
}

jbyteArray jni_send_once(JNIEnv* e, jclass, jint port, jbyteArray wire, jint readMs) {
    Bytes r = Transport::send_once(port, bytes_from_java(e, wire), readMs);
    if (r.empty()) return nullptr;
    return to_jbytes(e, r);
}

jbyteArray jni_send_once_match(JNIEnv* e, jclass, jint port, jbyteArray wire, jint readMs, jint wantSet, jint wantId) {
    Bytes r = Transport::send_once(port, bytes_from_java(e, wire), readMs, wantSet, wantId);
    if (r.empty()) return nullptr;
    return to_jbytes(e, r);
}

jboolean jni_send_frame(JNIEnv* e, jclass, jint port, jbyteArray wire) {
    return Transport::send_frame(port, bytes_from_java(e, wire)) ? JNI_TRUE : JNI_FALSE;
}

jboolean jni_probe(JNIEnv*, jclass, jint port) {
    return Transport::probe_port(port) ? JNI_TRUE : JNI_FALSE;
}

jstring jni_stats(JNIEnv* e, jclass) {
    std::string s = g_tp ? g_tp->stats() : std::string("not started");
    return e->NewStringUTF(s.c_str());
}

const JNINativeMethod kMethods[] = {
    {"nativeStart",     "()I",                   (void*)jni_start},
    {"nativeStop",      "()V",                   (void*)jni_stop},
    {"nativeStartAux",  "(I)I",                  (void*)jni_start_aux},
    {"nativeStopAux",   "()V",                   (void*)jni_stop_aux},
    {"nativeAuxRunning","()Z",                   (void*)jni_aux_running},
    {"nativeSend",      "(IIII[B)Z",             (void*)jni_send},
    {"nativeRequest",   "(IIII[BI)[B",           (void*)jni_request},
    {"nativeParamHash", "(Ljava/lang/String;)[B",(void*)jni_param_hash},
    {"nativeDussSend",  "([B)Z",                 (void*)jni_duss},
    {"nativeDussProbe", "(Ljava/lang/String;)Ljava/lang/String;", (void*)jni_duss_probe},
    {"nativeDussXact",  "(ILjava/lang/String;Ljava/lang/String;[BIII)Ljava/lang/String;", (void*)jni_duss_xact},
    {"nativeBuildFrame","(IIIII[B)[B",           (void*)jni_build},
    {"nativeSendOnce",  "(I[BI)[B",              (void*)jni_send_once},
    {"nativeSendOnceMatch","(I[BIII)[B",         (void*)jni_send_once_match},
    {"nativeSendFrame", "(I[B)Z",                (void*)jni_send_frame},
    {"nativeSendMany",  "(I[[BI)I",              (void*)jni_send_many},
    {"nativeProbePort", "(I)Z",                   (void*)jni_probe},
    {"nativeStats",     "()Ljava/lang/String;",   (void*)jni_stats},
};
}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    JNIEnv* e = nullptr;
    if (vm->GetEnv((void**)&e, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass c = e->FindClass("com/dji/fccgpsoff/DumlNative");
    if (!c) return JNI_ERR;
    g_sink_cls = (jclass)e->NewGlobalRef(c);
    g_sink_mid = e->GetStaticMethodID(c, "onNativeFrame", "(IIII[BI[B)V");
    if (e->RegisterNatives(c, kMethods, sizeof(kMethods)/sizeof(kMethods[0])) != 0) return JNI_ERR;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void*) {
    if (g_tp) { g_tp->stop(); g_tp.reset(); }          // join RX threads, close sockets
    JNIEnv* e = nullptr;
    if (g_sink_cls && vm->GetEnv((void**)&e, JNI_VERSION_1_6) == JNI_OK) {
        e->DeleteGlobalRef(g_sink_cls);
    }
    g_sink_cls = nullptr; g_sink_mid = nullptr;
}
