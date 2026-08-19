// duml_core.cpp — see duml_core.h. POSIX/Android. Scaffold quality: coherent
// and self-consistent, but exercise on real hardware before trusting timings.
#include "duml_core.h"

#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <map>
#include <mutex>
#include <thread>

#include <arpa/inet.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>
#include <cerrno>
#include <chrono>
#include <utility>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DumlCore", __VA_ARGS__)

namespace duml {

// ---------- CRC ----------
static uint8_t  CRC8_T[256];
static uint16_t CRC16_T[256];
static void init_crc() {
    for (int i = 0; i < 256; ++i) {
        uint8_t c = (uint8_t)i;
        for (int k = 0; k < 8; ++k) c = (c & 1) ? (uint8_t)((c >> 1) ^ 0x8C) : (uint8_t)(c >> 1);
        CRC8_T[i] = c;
    }
    for (int i = 0; i < 256; ++i) {
        uint16_t c = (uint16_t)i;
        for (int k = 0; k < 8; ++k) c = (c & 1) ? (uint16_t)((c >> 1) ^ 0x8408) : (uint16_t)(c >> 1);
        CRC16_T[i] = c;
    }
}
// Populate the tables on first use, from ANY path. Previously init_crc() ran
// only in Transport's ctor, so frames built via nativeBuildFrame / send_once
// before a Transport existed (e.g. a diag /serial or /send before /connect)
// went out with zero CRC8/CRC16 and the flight controller dropped them.
static void ensure_crc() { static std::once_flag f; std::call_once(f, init_crc); }

uint8_t crc8(const uint8_t* d, size_t n) {
    ensure_crc();
    uint8_t c = 0x77;
    for (size_t i = 0; i < n; ++i) c = CRC8_T[(c ^ d[i]) & 0xFF];
    return c;
}
uint16_t crc16(const uint8_t* d, size_t n) {
    ensure_crc();
    uint16_t c = 0x3692;
    for (size_t i = 0; i < n; ++i) c = CRC16_T[(c ^ d[i]) & 0xFF] ^ (c >> 8);
    return c;
}

// ---------- framing ----------
// Max payload the 10-bit length field can encode: total <= 1023, header+CRC = 13.
static constexpr size_t kMaxPayload = 1023 - 13;

Bytes build_frame(const Frame& f) {
    // The length lives in a 10-bit field (out[1] + 2 bits of out[2]); a payload
    // past kMaxPayload would silently truncate the encoded length into a corrupt
    // frame the FC drops. Refuse instead — parse_frame rejects total>1023 too, so
    // build/parse now agree. No shipped profile/param write comes close to this.
    if (f.payload.size() > kMaxPayload) return {};
    size_t total = 11 + f.payload.size() + 2;   // 11 header + payload + CRC16
    Bytes out(total, 0);
    out[0] = 0x55;
    out[1] = (uint8_t)(total & 0xFF);
    out[2] = (uint8_t)(((total >> 8) & 0x03) | 0x04);   // version 1 in high bits
    out[3] = crc8(out.data(), 3);
    out[4] = f.sender;
    out[5] = f.receiver;
    out[6] = (uint8_t)(f.seq & 0xFF);
    out[7] = (uint8_t)((f.seq >> 8) & 0xFF);
    out[8] = f.cmdType;
    out[9] = f.cmdSet;
    out[10] = f.cmdId;
    std::memcpy(out.data() + 11, f.payload.data(), f.payload.size());
    uint16_t c = crc16(out.data(), total - 2);
    out[total - 2] = (uint8_t)(c & 0xFF);
    out[total - 1] = (uint8_t)((c >> 8) & 0xFF);
    return out;
}

size_t parse_frame(const uint8_t* buf, size_t len, Frame& out, bool& produced) {
    produced = false;
    // find magic
    size_t i = 0;
    while (i < len && buf[i] != 0x55) ++i;
    if (i + 4 > len) return i;                      // need header+crc8 (drop leading garbage)
    const uint8_t* p = buf + i;
    size_t total = (size_t)p[1] | ((size_t)(p[2] & 0x03) << 8);
    if (total < 13 || total > 1023) return i + 1;   // resync past this 0x55
    if (i + total > len) return i;                  // incomplete, keep buffering
    if (crc8(p, 3) != p[3]) return i + 1;
    uint16_t want = (uint16_t)p[total - 2] | ((uint16_t)p[total - 1] << 8);
    if (crc16(p, total - 2) != want) return i + 1;
    out.sender = p[4]; out.receiver = p[5];
    out.seq = (uint16_t)p[6] | ((uint16_t)p[7] << 8);
    out.cmdType = p[8]; out.cmdSet = p[9]; out.cmdId = p[10];
    out.payload.assign(p + 11, p + total - 2);
    produced = true;                                // CRC-valid frame decoded
    return i + total;
}

// ---------- param hash ----------
Bytes param_hash(const std::string& name) {
    std::string s = name + "_0";
    uint64_t h = 0;
    for (unsigned char b : s) h = ((h << 8) | b) % 0xFFFFFFFBULL;
    return { (uint8_t)h, (uint8_t)(h >> 8), (uint8_t)(h >> 16), (uint8_t)(h >> 24) };
}

// ---------- transport ----------
// 40007 is deliberately NOT here: it is DJI Fly's FPV video mirror on RC2 and a
// persistent connection there disturbs Fly's link. Capture it on demand instead
// (diag server /cap), never as the main channel.
const std::vector<int> Transport::kScanPorts = {40009, 40008, 8901, 8902, 8903, 8904};

// How long a frame stays in the dedup window. Long enough to catch a retransmit or
// the same frame arriving on both routes, short enough that steady telemetry is
// delivered again rather than suppressed for the life of the process.
static constexpr uint64_t kDedupWindowMs = 2000;

// How many times send_many may re-open the socket inside one batch before giving
// up. Generous: one profile is 21 frames, and under contention the broker can
// close after almost every one, so this has to allow a per-frame reconnect.
static constexpr int kSendManyMaxReconnects = 64;

static uint64_t now_ms() {
    return (uint64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

// Main-channel reconnect pacing.
//
// A clean close (recv==0) is this broker's ROUTINE behavior — measured: every single
// drop over a 92 s window reported mainDrop=0, at ~19 drops/minute. So it is not an
// error condition and does not deserve an error's backoff: waiting 200 ms after each
// one threw away ~6% of the stream for nothing. Reconnect almost immediately, and
// keep a real pause for the cases that actually indicate trouble — an errno from
// recv, or a port that will not accept at all.
static constexpr useconds_t kMainCleanCloseBackoffUs = 30 * 1000;    // peer hung up: routine here
static constexpr useconds_t kMainReconnectBackoffUs  = 200 * 1000;   // recv() error
static constexpr useconds_t kMainRetryBackoffUs      = 2000 * 1000;  // when nothing accepts at all

// Our own sender id (MOBILE_APP, see Frame::sender default). Frames we transmit
// carry this; genuine device replies/telemetry never do. A loopback bus that
// reflects our TX back is recognized by this — NOT by seq, which responses
// legitimately reuse from the request.
static constexpr uint8_t kMobileApp = 130;

// Send the whole buffer, retrying short writes; a partial write on a stream
// socket would desync the framed peer. MSG_NOSIGNAL avoids SIGPIPE killing us
// when the proxy closed the connection.
static bool send_all(int fd, const uint8_t* data, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = ::send(fd, data + off, len - off, MSG_NOSIGNAL);
        if (n > 0) { off += (size_t)n; continue; }
        if (n < 0 && errno == EINTR) continue;
        return false;
    }
    return true;
}

// Content key for the dedup window: covers seq + addressing + cmd + payload, so
// an identical retransmit is dropped while recurring telemetry that reuses a
// constant seq (often 0) but carries fresh data still gets through.
static uint64_t frame_key(const Frame& f) {
    uint64_t h = 1469598103934665603ULL;            // FNV-1a 64
    auto mix = [&](uint8_t b) { h ^= b; h *= 1099511628211ULL; };
    mix(f.sender); mix(f.receiver);
    mix((uint8_t)(f.seq & 0xFF)); mix((uint8_t)(f.seq >> 8));
    mix(f.cmdType); mix(f.cmdSet); mix(f.cmdId);
    for (uint8_t b : f.payload) mix(b);
    return h;
}

// Result of matching a frame against the dedup window: first sighting (NEW),
// an identical retransmit on the SAME route (dupSame), or the same content
// already seen on the OTHER route (dupCross — the frame reached us on both the
// main and the hijacked aux channel, proof the two readers run in parallel).
enum SeenKind { SEEN_NEW, SEEN_SAME, SEEN_CROSS };

struct Transport::Impl {
    std::atomic<int> fd{-1};
    std::atomic<bool> run{false};
    std::thread rx;
    std::atomic<uint16_t> seq{1};

    // aux / hijack channel (a second reader of DJI Fly's port)
    std::atomic<int> aux_fd{-1};
    std::atomic<bool> aux_run{false};
    std::thread aux_rx;
    std::atomic<int> aux_port{-1};
    std::atomic<uint32_t> epoch{0};
    std::atomic<uint32_t> aux_reconnects{0};   // times the hijack socket was re-acquired after eviction
    std::atomic<uint32_t> main_reconnects{0};  // …same, for the main channel (the broker hangs up on silent clients)
    std::atomic<int> last_drop_cause{-1};      // 0 = peer closed, >0 = errno, -1 = never dropped
    std::atomic<uint64_t> last_window_ms{0};   // how long the last main connection lasted

    RxSink sink = nullptr; void* sink_user = nullptr;

    // diagnostics counters
    std::atomic<uint64_t> c_rx{0}, c_tx{0}, c_matched{0}, c_dup{0}, c_echo{0}, c_timeout{0};
    std::atomic<uint64_t> c_aux_rx{0}, c_dup_cross{0};

    // seq-waiter table
    std::mutex m;
    std::condition_variable cv;
    std::map<uint16_t, Bytes> ready;                   // seq -> response payload
    std::map<uint16_t, int> waiters;                   // seq -> outstanding request() count
    struct Seen { uint64_t key; int route; uint64_t ms; };
    std::deque<Seen> recent;                           // dedup window, oldest first

    /**
     * Match a frame's content key against the recent window. A brand-new key ->
     * NEW (and it is remembered). A key already present -> SAME if it first
     * arrived on this same route, else CROSS. Keyed by content (frame_key), not
     * by seq, so recurring telemetry that reuses a constant seq but carries
     * fresh data is NOT mistaken for a duplicate — and dupCross means the two
     * channels genuinely saw the same frame, not merely the same seq number.
     *
     * The window is bounded in TIME as well as in size, and that matters more
     * than it looks. A matching entry is not refreshed or moved, so it used to
     * leave only when 256 fresh keys pushed it out — on a quiet channel that
     * never happens, and a device that repeats one frame verbatim (constant seq
     * AND constant payload, which is exactly what controller housekeeping looks
     * like) would be delivered once and then silently dropped forever. `recent`
     * also survives a reconnect, so the suppression outlived the socket that
     * caused it. Now an entry simply expires: a genuine retransmit still gets
     * filtered inside [kDedupWindowMs], and steady telemetry keeps flowing.
     */
    SeenKind classify(const Frame& f, int route, uint64_t now_ms) {
        // Insertion order is time order, so expired entries are always at the front.
        while (!recent.empty() && now_ms - recent.front().ms > kDedupWindowMs) recent.pop_front();
        uint64_t k = frame_key(f);
        for (auto& x : recent) if (x.key == k)
            return x.route == route ? SEEN_SAME : SEEN_CROSS;
        recent.push_back({k, route, now_ms});
        if (recent.size() > 256) recent.pop_front();
        return SEEN_NEW;
    }

    // Echo-filter + dedup one parsed frame, then deliver to any seq waiter and
    // to the host sink. Shared by the main (route 0) and aux/hijack (route 1)
    // readers so both feed one reconciled stream. `wire`/`wlen` are the exact
    // on-the-wire bytes for verbatim pcap capture.
    void dispatch(const Frame& f, const uint8_t* wire, size_t wlen, int route);

    // recv() loop over one socket: buffer, parse whole 0x55 frames, dispatch.
    // Returns when the socket drops (recv<=0) or srun is cleared — it does NOT
    // clear srun itself, so a supervisor can reconnect and call it again.
    //
    // Reports WHY it came back in `cause`: 0 = the peer closed cleanly (recv==0),
    // >0 = errno from a failed recv, -1 = we were told to stop. The distinction is
    // the whole diagnosis for 40009 — a broker that hangs up on a silent client
    // looks like cause 0, a reset looks like ECONNRESET.
    void reader_loop(std::atomic<int>& sfd, std::atomic<bool>& srun, int route, int* cause = nullptr);

    // Main-channel supervisor: same self-healing shape as aux_supervise below.
    void main_supervise(int port);

    // Aux/hijack supervisor: a live connection on DJI Fly's port (40007) gets
    // evicted after a burst — the proxy hands the port back to Fly. So we simply
    // re-acquire it: reconnect the aux socket whenever it drops and keep reading,
    // until stop_aux() clears aux_run. This is what keeps capture alive instead
    // of dying after the first few frames.
    int  aux_connect(int port);              // handover retry -> fd, or -1 (bails if aux_run cleared)
    void aux_supervise(int port);            // the aux thread body: (re)connect + read forever
};

void Transport::Impl::dispatch(const Frame& f, const uint8_t* wire, size_t wlen, int route) {
    RxSink s = nullptr; void* su = nullptr;
    Bytes wcopy;
    {
        std::lock_guard<std::mutex> lk(m);
        if (route == 0) c_rx++; else c_aux_rx++;
        // Our own TX reflected by a loopback bus (sender==MOBILE_APP). Recognized
        // by sender, NOT seq — responses/telemetry legitimately reuse the request
        // seq, so a seq-based echo filter would drop the reply a waiter is on.
        if (f.sender == kMobileApp) { c_echo++; return; }
        switch (classify(f, route, now_ms())) {
            case SEEN_SAME:  c_dup++;       return;    // dupSame  -> identical retransmit, drop
            case SEEN_CROSS: c_dup_cross++; return;    // dupCross -> already delivered on the other route
            case SEEN_NEW:   break;
        }
        c_matched++;
        // Only stash a payload (and wake the waiters) when a request() is actually
        // waiting on this seq. Telemetry — which reuses a constant seq, often 0,
        // that nobody waits on — would otherwise accumulate in `ready` forever and
        // fire a cv.notify_all() storm on every RX frame.
        if (waiters.count(f.seq)) { ready[f.seq] = f.payload; cv.notify_all(); }
        // Capture the sink under the lock so a concurrent set_rx_sink() can't leave
        // us calling a torn pointer after we drop it.
        s = sink; su = sink_user;
        if (s) wcopy.assign(wire, wire + wlen);
    }
    // Call the host sink OUTSIDE the lock (it hops into the JVM).
    if (s) s(f, wcopy.data(), wcopy.size(), route, su);
}

void Transport::Impl::reader_loop(std::atomic<int>& sfd, std::atomic<bool>& srun, int route, int* cause) {
    Bytes buf;
    uint8_t tmp[4096];
    if (cause) *cause = -1;
    while (srun) {
        int cur = sfd.load();
        if (cur < 0) break;
        ssize_t n = ::recv(cur, tmp, sizeof(tmp), 0);
        if (n <= 0) {
            if (cause) *cause = (n == 0) ? 0 : errno;
            break;
        }
        buf.insert(buf.end(), tmp, tmp + n);
        size_t off = 0;
        while (off < buf.size()) {
            Frame f;
            bool produced = false;
            size_t used = parse_frame(buf.data() + off, buf.size() - off, f, produced);
            if (used == 0) break;                      // incomplete, need more bytes
            size_t before = off; off += used;
            if (!produced) continue;                   // consumed leading garbage / resync byte
            // Exact wire bytes = the CRC-valid frame starting at its 0x55 in [before, off).
            size_t fs = before;
            while (fs < off && buf[fs] != 0x55) ++fs;
            dispatch(f, buf.data() + fs, off - fs, route);
        }
        if (off) buf.erase(buf.begin(), buf.begin() + off);
    }
}

Transport::Transport() : p_(new Impl) { static std::once_flag f; std::call_once(f, init_crc); }
Transport::~Transport() { stop(); delete p_; }

void Transport::set_rx_sink(RxSink sink, void* user) {
    // Under `m`: dispatch() reads sink/sink_user under the same lock, so a caller
    // rewiring the sink can never race a torn read on the RX thread.
    std::lock_guard<std::mutex> lk(p_->m);
    p_->sink = sink; p_->sink_user = user;
}

static int connect_loopback(int port) {
    int fd = ::socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    sockaddr_in a{}; a.sin_family = AF_INET; a.sin_port = htons((uint16_t)port);
    inet_pton(AF_INET, "127.0.0.1", &a.sin_addr);
    if (::connect(fd, (sockaddr*)&a, sizeof(a)) != 0) { ::close(fd); return -1; }
    return fd;
}

/**
 * The main channel's supervisor — the fix for a socket that will not stay open.
 *
 * Measured on RC 2 (rc331), repeatedly and with nothing of ours writing on it: a
 * connection to 127.0.0.1:40009 delivers 12–17 frames over ~1.0–1.4 s and is then
 * closed by the broker. The next one-shot socket our app opens is ~4 s later, so we
 * are not evicting ourselves; the broker simply does not keep a silent client. The
 * old code ran the reader ONCE and let the thread die, leaving the channel down
 * until a 30 s health check in Kotlin noticed — so the app saw ~20 frames in ten
 * minutes and every passive consumer (LinkState, SerialSniffer, RadioLinkMonitor)
 * was effectively blind.
 *
 * The aux/hijack reader has had exactly this shape since the 40007 capture work —
 * "a live connection gets evicted after a burst, so re-acquire it" — and it is why
 * that channel logged 908 frames in the same session. The main channel now gets the
 * same treatment: reconnect on drop, back off briefly, keep the epoch/counters
 * honest. Reconnect churn is in the same order as one FCC apply, which opens ~45
 * short-lived sockets on this very port in 1.6 s.
 */
void Transport::Impl::main_supervise(int port) {
    while (run) {
        auto t0 = std::chrono::steady_clock::now();
        uint64_t before = c_rx.load();
        int cause = -1;
        reader_loop(fd, run, /*route=*/0, &cause);
        auto ms = (uint64_t)std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t0).count();
        int old = fd.exchange(-1);
        if (old >= 0) { ::shutdown(old, SHUT_RDWR); ::close(old); }
        if (!run) break;                              // clean stop, not a drop
        last_drop_cause = cause;
        last_window_ms = ms;
        // cause: 0 = peer closed (the broker's routine behavior here), >0 = errno,
        // -1 = the fd was taken from under us. Never hand -1 to strerror().
        LOGI("main RX dropped after %llu ms / %llu frames (cause=%d: %s) — re-acquiring port=%d",
             (unsigned long long)ms, (unsigned long long)(c_rx.load() - before), cause,
             cause == 0 ? "peer closed" : (cause > 0 ? strerror(cause) : "socket taken"), port);
        usleep(cause == 0 ? kMainCleanCloseBackoffUs : kMainReconnectBackoffUs);
        if (!run) break;
        int nfd = connect_loopback(port);
        if (nfd < 0) {                                // broker gone (RC/aircraft down)
            usleep(kMainRetryBackoffUs);
            continue;
        }
        // stop_main() may have run while we were connecting: it has already taken the
        // (empty) fd and is waiting in join(), so publishing this one would leak it.
        if (!run) { ::shutdown(nfd, SHUT_RDWR); ::close(nfd); break; }
        { std::lock_guard<std::mutex> lk(m); ready.clear(); }   // no stale seq across a reconnect
        fd = nfd; epoch++; main_reconnects++;
    }
    run = false;
}

int Transport::start() {
    // Always tear the main channel down first (idempotent): joins any previous rx
    // thread — including one that already exited on a dropped socket — before we
    // reassign fd, which both prevents reassigning a live thread (was a crash) and
    // closes the old descriptor. Does NOT touch the independent aux/hijack reader.
    stop_main();
    int fd = -1, chosen = -1;
    for (int port : kScanPorts) { fd = connect_loopback(port); if (fd >= 0) { chosen = port; break; } }
    if (fd < 0) return -1;                            // no proxy reachable
    p_->fd = fd; p_->run = true; p_->epoch++;
    // New epoch: drop any response payloads left from the previous connection so a
    // stale seq can't satisfy a fresh request after a reconnect.
    { std::lock_guard<std::mutex> lk(p_->m); p_->ready.clear(); }
    LOGI("main RX connected port=%d fd=%d epoch=%u", chosen, fd, p_->epoch.load());
    Impl* p = p_;
    int supervised = chosen;
    p_->rx = std::thread([p, supervised] { p->main_supervise(supervised); });
    return chosen;
}

// Tear down just the main RX channel. stop() layers stop_aux() on top; start()
// reuses this so re-connecting never disturbs the aux reader.
void Transport::stop_main() {
    p_->run = false;
    int fd = p_->fd.exchange(-1);
    if (fd >= 0) { ::shutdown(fd, SHUT_RDWR); ::close(fd); }   // unblock recv()
    if (p_->rx.joinable()) p_->rx.join();
}

void Transport::stop() {
    stop_aux();
    stop_main();
}

// ---- aux / hijack read -----------------------------------------------------
// One handover acquisition: retry the connect a bounded number of times (the
// proxy may momentarily refuse a second client while it hands the port over).
// Bails immediately if aux_run was cleared by stop_aux() mid-retry.
int Transport::Impl::aux_connect(int port) {
    const int kAttempts = 20;
    for (int i = 0; i < kAttempts; ++i) {
        if (!aux_run) return -1;                      // stop requested during retry
        int fd = connect_loopback(port);
        if (fd >= 0) { LOGI("aux RX handover acquired after %d attempt(s) port=%d", i + 1, port); return fd; }
        LOGI("aux RX handover attempt %d/%d failed port=%d", i + 1, kAttempts, port);
        usleep(100 * 1000);                           // 100 ms backoff between attempts
    }
    return -1;
}

// The aux thread: read the hijack socket, and each time the proxy evicts us
// (recv drops) re-acquire it and keep going, until stop_aux() clears aux_run.
// A live 40007 hijack is inherently flappy — this is what turns "a few frames
// then dead" into a continuous, self-healing capture.
void Transport::Impl::aux_supervise(int port) {
    while (aux_run) {
        reader_loop(aux_fd, aux_run, /*route=*/1);    // read until the socket drops or we're told to stop
        int old = aux_fd.exchange(-1);
        if (old >= 0) { ::shutdown(old, SHUT_RDWR); ::close(old); }
        if (!aux_run) break;                          // clean stop
        LOGI("aux RX evicted — re-acquiring hijack port=%d epoch=%u", port, epoch.load());
        usleep(250 * 1000);                           // brief backoff so we don't storm the proxy
        int fd = aux_connect(port);
        if (fd < 0) { if (!aux_run) break; usleep(250 * 1000); continue; }  // keep trying while capturing
        aux_fd = fd; epoch++; aux_reconnects++;
        LOGI("aux RX re-connected port=%d fd=%d hijack=1 epoch=%u (reconnect #%u)",
             port, fd, epoch.load(), aux_reconnects.load());
    }
    aux_run = false;
}

int Transport::start_aux(int port) {
    if (p_->aux_run) stop_aux();
    p_->aux_port = port;
    p_->aux_run = true;                                // desired-on; aux_connect + reader_loop honor it
    int fd = p_->aux_connect(port);
    if (fd < 0) {
        p_->aux_run = false;
        LOGI("aux RX handover failed port=%d", port);
        return -1;
    }
    p_->aux_fd = fd; p_->epoch++;
    LOGI("aux RX connected port=%d fd=%d hijack=1 epoch=%u", port, fd, p_->epoch.load());
    Impl* p = p_;
    p_->aux_rx = std::thread([p, port] { p->aux_supervise(port); });
    return port;
}

void Transport::stop_aux() {
    if (!p_->aux_run && p_->aux_fd.load() < 0) return;
    p_->aux_run = false;                              // stops reader_loop, aux_connect retries, and the supervisor
    int fd = p_->aux_fd.exchange(-1);
    if (fd >= 0) { ::shutdown(fd, SHUT_RDWR); ::close(fd); }   // unblock a recv() in progress
    if (p_->aux_rx.joinable()) p_->aux_rx.join();
    LOGI("aux RX disconnect port=%d hijack=1", p_->aux_port.load());
    p_->aux_port = -1;
}

// Reports intent: true while the hijack is active and self-healing, even across
// brief reconnect gaps. Instantaneous socket state is `auxConnected` in stats().
bool Transport::aux_running() const { return p_->aux_run.load(); }

bool Transport::send(Frame& f) {
    int fd = p_->fd.load();
    if (fd < 0) return false;
    f.seq = p_->seq.fetch_add(1);
    Bytes w = build_frame(f);
    if (!send_all(fd, w.data(), w.size())) return false;
    p_->c_tx++;
    return true;
}

bool Transport::probe_port(int port) {
    int fd = connect_loopback(port);
    if (fd < 0) return false;
    ::close(fd);
    return true;
}

std::string Transport::stats() const {
    char b[480];
    snprintf(b, sizeof(b),
        "rx=%llu auxRx=%llu tx=%llu matched=%llu dupSame=%llu dupCross=%llu echo=%llu "
        "timeout=%llu connected=%d auxConnected=%d auxPort=%d auxReconnects=%u epoch=%u "
        "mainReconnects=%u mainDrop=%d mainWindowMs=%llu",
        (unsigned long long)p_->c_rx.load(), (unsigned long long)p_->c_aux_rx.load(),
        (unsigned long long)p_->c_tx.load(), (unsigned long long)p_->c_matched.load(),
        (unsigned long long)p_->c_dup.load(), (unsigned long long)p_->c_dup_cross.load(),
        (unsigned long long)p_->c_echo.load(), (unsigned long long)p_->c_timeout.load(),
        p_->fd.load() >= 0 ? 1 : 0, p_->aux_fd.load() >= 0 ? 1 : 0,
        p_->aux_port.load(), p_->aux_reconnects.load(), p_->epoch.load(),
        p_->main_reconnects.load(), p_->last_drop_cause.load(),
        (unsigned long long)p_->last_window_ms.load());
    return std::string(b);
}

Bytes Transport::request(Frame& f, int timeout_ms) {
    int fd = p_->fd.load();
    if (fd < 0) return {};
    // Allocate the seq and register the waiter atomically under `m`, BEFORE the
    // frame goes out, so dispatch() (which only stashes seqs with a live waiter)
    // catches even a reply that races straight back. Frame build + write happen
    // outside the lock — never hold `m` across a blocking send.
    uint16_t s;
    {
        std::lock_guard<std::mutex> lk(p_->m);
        s = p_->seq.fetch_add(1);
        f.seq = s;
        p_->waiters[s]++;
    }
    // Drop this request's waiter; erase the slot when the last waiter on `s` is
    // gone. Caller must hold `m`.
    auto release = [&] { if (--p_->waiters[s] <= 0) { p_->waiters.erase(s); p_->ready.erase(s); } };
    Bytes w = build_frame(f);
    if (w.empty() || !send_all(fd, w.data(), w.size())) {
        std::lock_guard<std::mutex> lk(p_->m); release();
        return {};
    }
    p_->c_tx++;
    std::unique_lock<std::mutex> lk(p_->m);
    Bytes r;
    if (p_->cv.wait_for(lk, std::chrono::milliseconds(timeout_ms),
                        [&] { return p_->ready.count(s) > 0; }))
        r = p_->ready[s];
    else
        p_->c_timeout++;
    release();
    return r;                                         // empty on timeout
}

Bytes Transport::send_once(int port, const Bytes& wire, int read_ms, int want_set, int want_id) {
    int fd = connect_loopback(port);
    if (fd < 0) return {};
    if (!send_all(fd, wire.data(), wire.size())) { ::shutdown(fd, SHUT_RDWR); ::close(fd); return {}; }
    Bytes result;
    if (read_ms > 0) {
        timeval tv{ read_ms / 1000, (read_ms % 1000) * 1000 };
        ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        Bytes buf; uint8_t tmp[2048];
        bool done = false;
        // Read until a matching inner DUML frame parses or the window elapses.
        // With want_set/want_id < 0 this returns the first parsed frame (legacy);
        // otherwise it walks past non-matching frames (telemetry) to the reply.
        for (int i = 0; i < 16 && !done; ++i) {
            ssize_t n = ::recv(fd, tmp, sizeof(tmp), 0);
            if (n <= 0) break;
            buf.insert(buf.end(), tmp, tmp + n);
            size_t off = 0;
            while (off < buf.size()) {
                Frame f;
                bool produced = false;
                size_t used = parse_frame(buf.data() + off, buf.size() - off, f, produced);
                if (used == 0) break;                       // incomplete — need more bytes
                off += used;
                if (produced &&
                    (want_set < 0 || f.cmdSet == want_set) &&
                    (want_id  < 0 || f.cmdId  == want_id)) {
                    result = f.payload; done = true; break;
                }
            }
            if (off) buf.erase(buf.begin(), buf.begin() + off);
        }
    }
    ::shutdown(fd, SHUT_RDWR); ::close(fd);
    return result;
}

bool Transport::send_frame(int port, const Bytes& wire) {
    int fd = connect_loopback(port);
    if (fd < 0) return false;
    bool ok = send_all(fd, wire.data(), wire.size());
    ::shutdown(fd, SHUT_RDWR); ::close(fd);
    return ok;
}

int Transport::send_many(int port, const std::vector<Bytes>& frames, int gap_ms) {
    int fd = connect_loopback(port);
    if (fd < 0) return -1;
    int written = 0;
    int reconnects = 0;
    for (size_t i = 0; i < frames.size(); ) {
        if (send_all(fd, frames[i].data(), frames[i].size())) {
            ++written; ++i;
            if (gap_ms > 0 && i < frames.size()) usleep((useconds_t)gap_ms * 1000);
            continue;
        }
        // The broker closed this socket mid-profile. Measured on RC 2: with another
        // reader holding 40007 (a capture, or the UI's parameter poll) a 40009 socket
        // survives only one or two writes, and a plain give-up delivered 1 of 21
        // frames while still reporting the write path as healthy. Reconnect and carry
        // on from the frame that failed — the point of one socket is FEWER connects,
        // not fewer frames, so under contention it must degrade to the old behavior
        // rather than silently drop the profile.
        ::shutdown(fd, SHUT_RDWR); ::close(fd);
        if (++reconnects > kSendManyMaxReconnects) return written;
        fd = connect_loopback(port);
        if (fd < 0) return written;
    }
    ::shutdown(fd, SHUT_RDWR); ::close(fd);
    if (reconnects > 0)
        LOGI("send_many: %d frames on port %d needed %d reconnect(s)", written, port, reconnects);
    return written;
}

bool Transport::duss_send(const Bytes& wire) {
    int fd = ::socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return false;
    sockaddr_un a{}; a.sun_family = AF_UNIX;
    // abstract namespace: leading NUL, then "/duss/mb/0x205"
    const char* name = "/duss/mb/0x205";
    a.sun_path[0] = '\0';
    std::memcpy(a.sun_path + 1, name, std::strlen(name));
    socklen_t len = (socklen_t)(offsetof(sockaddr_un, sun_path) + 1 + std::strlen(name));
    if (::connect(fd, (sockaddr*)&a, len) != 0) { ::close(fd); return false; }
    bool ok = send_all(fd, wire.data(), wire.size());
    ::close(fd);
    return ok;
}

}  // namespace duml
