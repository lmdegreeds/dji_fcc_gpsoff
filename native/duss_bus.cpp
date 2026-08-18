// duss_bus.cpp — the "firmware bus" (DUSS) transport, a faithful re-implementation
// of REPORT/FIRMWARE-BUS-DUSS.md for on-hardware verification.
//
// DUSS = DJI Unix-domain Socket System: the internal message bus in the firmware
// of DJI's Android controllers. Components speak DUML frames over AF_UNIX sockets
// under /duss/mb/, where the socket name is the hex mailbox address. The router
// lives at /duss/mb/0x205; our source mailbox is /duss/mb/0x1e00.
//
// The report flags many specifics as [inference] — the peer's namespace
// (abstract vs pathname), the socket type (DGRAM vs STREAM), the exact source
// bytes, and whether SELinux even lets an ordinary app touch /duss/mb/*. So this
// file is DIAGNOSTIC-FIRST: it tries the documented sequence, sweeps the plausible
// variants, and reports every syscall's errno verbatim — on real hardware the
// trace tells us the truth instead of us guessing. Nothing here is on the app's
// hot path; it is driven only by the /duss/* diag endpoints.
#include "duml_core.h"

#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>

#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "NLD-DUSS-1E00", __VA_ARGS__)

namespace duml {

namespace {

// "<errno> (<strerror>)" — so a caller can tell EACCES/EPERM (SELinux denial,
// report §10) from ENOENT (no such mailbox socket) from ECONNREFUSED (socket
// present but not accepting) at a glance.
std::string errno_str(int e) {
    return std::to_string(e) + " (" + std::string(std::strerror(e)) + ")";
}

const char* HEXD = "0123456789abcdef";
std::string to_hex(const uint8_t* d, size_t n) {
    std::string s; s.reserve(n * 2);
    for (size_t i = 0; i < n; ++i) { s.push_back(HEXD[d[i] >> 4]); s.push_back(HEXD[d[i] & 0xF]); }
    return s;
}
std::string hex2(uint8_t b) { std::string s; s.push_back(HEXD[b >> 4]); s.push_back(HEXD[b & 0xF]); return s; }

// Render the source of a received datagram: "@name" for an abstract socket,
// "name" for a pathname, "unnamed" for an unbound sender (autobind). Lets the
// reply trace name the mailbox that actually answered.
std::string render_un(const sockaddr_un& a, socklen_t len) {
    if (len <= (socklen_t)offsetof(sockaddr_un, sun_path)) return "unnamed";
    size_t n = (size_t)len - offsetof(sockaddr_un, sun_path);
    if (a.sun_path[0] == '\0') {                       // abstract: name is bytes [1..n)
        size_t m = n > 0 ? n - 1 : 0;
        while (m > 0 && a.sun_path[m] == '\0') --m;     // trim trailing NUL padding
        return "@" + std::string(a.sun_path + 1, m);
    }
    return std::string(a.sun_path, strnlen(a.sun_path, n));
}

// Fill a sockaddr_un for `name`. abstract=true => Linux abstract namespace
// (leading NUL, no filesystem entry, len stops at the last name byte). abstract
// =false => a pathname socket (a real file the firmware created), NUL-terminated.
// Returns the address length to pass to bind()/connect().
socklen_t fill_un(sockaddr_un& a, const std::string& name, bool abstract) {
    std::memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    size_t cap = sizeof(a.sun_path);
    if (abstract) {
        size_t n = name.size();
        if (n > cap - 1) n = cap - 1;
        a.sun_path[0] = '\0';
        std::memcpy(a.sun_path + 1, name.data(), n);
        return (socklen_t)(offsetof(sockaddr_un, sun_path) + 1 + n);   // NO trailing NUL for abstract
    }
    size_t n = name.size();
    if (n > cap - 1) n = cap - 1;
    std::memcpy(a.sun_path, name.data(), n);
    a.sun_path[n] = '\0';
    return (socklen_t)(offsetof(sockaddr_un, sun_path) + n + 1);       // include the NUL for pathname
}

// One connect()-only attempt; leaves nothing open. For DGRAM, connect() only
// records the default peer, so it validates the address exists and is reachable
// without sending anything.
std::string probe_one(bool dgram, bool abstract, const std::string& peer) {
    int fd = ::socket(AF_UNIX, dgram ? SOCK_DGRAM : SOCK_STREAM, 0);
    if (fd < 0) return "socket errno=" + errno_str(errno);
    sockaddr_un a; socklen_t len = fill_un(a, peer, abstract);
    std::string r = (::connect(fd, (sockaddr*)&a, len) == 0) ? "OK" : ("FAIL errno=" + errno_str(errno));
    ::close(fd);
    return r;
}

}  // namespace

std::string duss_probe(const std::string& peer) {
    std::string out = "peer=" + peer + "\n";
    out += " DGRAM  abstract : " + probe_one(true,  true,  peer) + "\n";
    out += " DGRAM  pathname : " + probe_one(true,  false, peer) + "\n";
    out += " STREAM abstract : " + probe_one(false, true,  peer) + "\n";
    out += " STREAM pathname : " + probe_one(false, false, peer);
    LOGI("duss_probe %s", peer.c_str());
    return out;
}

std::string duss_xact(const Bytes& wire,
                      bool dgram, bool peerAbstract, bool bindSource, bool noConnect,
                      const std::string& peer, const std::string& source,
                      int readMs, int wantSet, int wantId) {
    std::string t;
    auto add = [&](const std::string& s) { t += s; t.push_back(' '); };

    add(std::string("type=") + (dgram ? "DGRAM" : "STREAM"));
    int fd = ::socket(AF_UNIX, dgram ? SOCK_DGRAM : SOCK_STREAM, 0);
    if (fd < 0) { add("socket=FAIL"); add("errno=" + errno_str(errno)); return t; }

    // 1) bind the local source. A DGRAM socket MUST have a bound address for the
    //    router to reply to (report §2/§3); without it the reply has nowhere to go.
    if (bindSource) {
        sockaddr_un src; socklen_t sl = fill_un(src, source, /*abstract=*/true);
        if (::bind(fd, (sockaddr*)&src, sl) < 0) {
            add("bind=FAIL"); add("bindErrno=" + errno_str(errno)); add("src=@" + source);
            ::close(fd); return t;                    // can't receive a reply — stop here
        }
        add("bind=OK"); add("src=@" + source);
    } else {
        add("bind=skip");
    }

    // 2) receive/send timeouts so recv() can't wedge the worker (report §3/§6).
    if (readMs > 0) {
        timeval tv{ readMs / 1000, (readMs % 1000) * 1000 };
        ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        ::setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    }

    // 3) address the router mailbox. Two modes:
    //    connected — connect() then send()/recv(): faithful to report §3, but a
    //      connected DGRAM socket accepts replies ONLY from the connected peer, so
    //      a reply that comes back from a different mailbox is silently dropped.
    //    unconnected (noConnect) — sendto(peer) then recvfrom(ANY): catches the
    //      reply whatever mailbox it comes from, and names that source (report §4.1
    //      calls out the sendto variant). Use this when the connected recv is silent.
    sockaddr_un peerA; socklen_t pl = fill_un(peerA, peer, peerAbstract);
    const std::string peerLbl = std::string("peer=") + (peerAbstract ? "@" : "") + peer;

    ssize_t sn;
    if (noConnect) {
        add("mode=sendto");
        sn = ::sendto(fd, wire.data(), wire.size(), MSG_NOSIGNAL, (sockaddr*)&peerA, pl);
    } else {
        if (::connect(fd, (sockaddr*)&peerA, pl) < 0) {
            add("connect=FAIL"); add("connectErrno=" + errno_str(errno)); add(peerLbl);
            ::close(fd); return t;
        }
        add("connect=OK");
        sn = ::send(fd, wire.data(), wire.size(), MSG_NOSIGNAL);
    }
    add(peerLbl);

    // 4) the send result.
    if (sn < 0) { add("send=FAIL"); add("sendErrno=" + errno_str(errno)); ::close(fd); return t; }
    add("send=OK"); add("sent=" + std::to_string((long)sn) + "/" + std::to_string(wire.size()));
    if ((size_t)sn != wire.size()) add("short-write!");

    // 5) read the reply window and match the first DUML frame with wantSet/wantId
    //    (-1 = any), walking past interleaved telemetry (report §6). In unconnected
    //    mode record the datagram's source so we learn which mailbox answered.
    if (readMs > 0) {
        Bytes buf; uint8_t tmp[2048]; bool got = false; std::string lastFrom;
        for (int i = 0; i < 64 && !got; ++i) {
            ssize_t n;
            if (noConnect) {
                sockaddr_un from{}; socklen_t fl = sizeof(from);
                n = ::recvfrom(fd, tmp, sizeof(tmp), 0, (sockaddr*)&from, &fl);
                if (n > 0) lastFrom = render_un(from, fl);
            } else {
                n = ::recv(fd, tmp, sizeof(tmp), 0);
            }
            if (n <= 0) { if (i == 0) add(n == 0 ? "recv=closed" : "recv=timeout"); break; }
            if (!lastFrom.empty()) add("rxFrom=" + lastFrom);
            // Dump the raw datagram (capped) so a reply that ISN'T a plain DUML
            // frame — an ack, a DUSS-level control message, an enveloped frame — is
            // still visible instead of vanishing into a bare "reply=-".
            add("rxRaw=" + to_hex(tmp, (size_t)n > 128 ? 128 : (size_t)n) + (n > 128 ? "..." : ""));
            buf.insert(buf.end(), tmp, tmp + n);
            size_t off = 0;
            while (off < buf.size()) {
                Frame f; bool produced = false;
                size_t used = parse_frame(buf.data() + off, buf.size() - off, f, produced);
                if (used == 0) break;                 // incomplete — need more bytes
                off += used;
                if (produced && (wantSet < 0 || f.cmdSet == wantSet) && (wantId < 0 || f.cmdId == wantId)) {
                    add("rxFrame=OK");
                    add("rxSrcDst=" + hex2(f.sender) + hex2(f.receiver));
                    add("rxCmd=" + hex2(f.cmdSet) + "/" + hex2(f.cmdId));
                    add("reply=" + (f.payload.empty() ? std::string("(empty)")
                                                      : to_hex(f.payload.data(), f.payload.size())));
                    got = true; break;
                }
                // A datagram arrived but did not match the wanted cmd — say so
                // rather than reporting a bare timeout, so "wrong reply" is visible.
                if (produced) add("rxOther=" + hex2(f.cmdSet) + "/" + hex2(f.cmdId));
            }
            if (off) buf.erase(buf.begin(), buf.begin() + off);
        }
        if (!got) add("reply=-");
    } else {
        add("recv=skip"); add("reply=-");
    }

    ::close(fd);
    LOGI("duss_xact %s", t.c_str());
    return t;
}

}  // namespace duml
