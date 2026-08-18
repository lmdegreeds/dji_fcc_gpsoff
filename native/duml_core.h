// duml_core.h — the loopback DUML transport this app runs on.
//
// Implements only the reusable, non-secret half of the mechanism:
//   * DUML V1 framing (0x55, CRC-8/CRC-16) — layout & CRCs from FreeFCC (AGPL).
//   * flight-controller parameter hashing (03:F8/03:F9) — algo from Skylab (AGPL).
//   * a loopback DUML client transport with port auto-detect,
//   * sequence-number request/response correlation with a waiter table + dedup,
//     shared by the main and aux channels.
//
// It deliberately does NOT implement DJI's DUML encryption handshake
// (General EncryptConfig): it is only needed for the encrypted FlyController
// path. The un-encrypted region/SDR path this app uses (proven by
// FreeFCC/Skylab) needs none of it.
#ifndef DUML_CORE_H
#define DUML_CORE_H

#include <cstdint>
#include <vector>
#include <string>

namespace duml {

using Bytes = std::vector<uint8_t>;

// ---- CRC (tables built at init from the documented polynomials) ------------
uint8_t  crc8(const uint8_t* data, size_t len);   // poly 0x8C reflected, init 0x77, over first 3 bytes
uint16_t crc16(const uint8_t* data, size_t len);  // poly 0x8408 reflected, init 0x3692

// ---- Frame build / parse ---------------------------------------------------
struct Frame {
    uint8_t  sender   = 130;   // MOBILE_APP, index 4
    uint8_t  receiver = 0;     // dst TTII
    uint8_t  cmdType  = 0x00;  // ACK/encryption flags (0x20 ack-before-exec, 0x00 no-ack; enc bit stays 0)
    uint8_t  cmdSet   = 0;
    uint8_t  cmdId    = 0;
    Bytes    payload;
    uint16_t seq      = 0;     // filled by transport on send
};

// Serialize a Frame to wire bytes (with both CRCs). seq is taken from f.seq.
Bytes build_frame(const Frame& f);

// Parse one complete 0x55 frame from a buffer. Returns bytes consumed (0 if
// incomplete / need more). Sets `produced` to true and fills `out` ONLY when a
// complete, CRC-valid frame was decoded; `produced` stays false when the
// consumed bytes were leading garbage or a resync past a false 0x55. Callers
// must gate dispatch on `produced`, never on payload/cmd contents (an empty
// GENERAL/ACK frame is still a valid frame).
size_t parse_frame(const uint8_t* buf, size_t len, Frame& out, bool& produced);

// ---- Parameter hash (FlyController by-hash addressing) ---------------------
// h = 0; for b in (name + "_0"): h = ((h<<8) | b) % 0xFFFFFFFB ; little-endian 4 bytes.
Bytes param_hash(const std::string& name);

// ---- Transport -------------------------------------------------------------
// A single loopback DUML session. Coexists with DJI Fly as an additional
// client of the on-device proxy (the model FreeFCC/Skylab prove works). The
// hooks marked SINGLE-CLIENT-UPGRADE are where port handover/hijack would go
// if a proxy turns out to be single-client.
class Transport {
public:
    // Candidate loopback ports for the persistent channel, scanned in order.
    // 40007 is excluded on purpose (DJI Fly's video mirror) — see the .cpp.
    static const std::vector<int> kScanPorts;   // {40009, 40008, 8901..8904}

    Transport();
    ~Transport();

    // Connect to the first working proxy port on 127.0.0.1. Starts the RX
    // thread. Returns the chosen port, or -1.
    int start();
    void stop();

    // ---- aux / hijack read ---------------------------------------------------
    // Open a SECOND, concurrent read socket to [port] — the port DJI Fly holds
    // (40007 = its FPV/telemetry mirror on RC2). This is the "hijack": a passive
    // reader of the stream that also flows to Fly. Frames it parses go through
    // the SAME dedup as the main channel, so a frame seen on both is counted as
    // a cross-channel duplicate and delivered once. The connect is retried
    // because the proxy may momentarily refuse a second client.
    //
    // Intrusive by nature: a live connection on 40007 can perturb Fly's video.
    // Use only to observe; stop it when done.
    int  start_aux(int port);         // returns the port, or -1 if it never connected
    void stop_aux();
    bool aux_running() const;

    // Fire-and-forget a frame (region/SDR writes are ACK-before-exec but the
    // profile runner does not need the reply).
    bool send(Frame& f);

    // Send and wait up to timeout_ms for a response with the same seq.
    // Returns the response payload, or empty on timeout. This is the
    // seq-waiter path (see .cpp): dedup + echo-filter + match-by-seq.
    Bytes request(Frame& f, int timeout_ms);

    // Set the native->host RX sink (every parsed frame that survives the echo
    // filter and dedup is delivered here — the "onNativeRcLinkDumlFrame"
    // equivalent). `wire`/`wlen` are the exact on-the-wire bytes of the frame
    // (0x55 .. CRC16) so the host can record them verbatim to pcap; `route` is
    // 0 for the main channel, 1 for the hijacked aux channel.
    using RxSink = void(*)(const Frame&, const uint8_t* wire, size_t wlen, int route, void* user);
    void set_rx_sink(RxSink sink, void* user);

    // Diagnostics: is 127.0.0.1:port accepting connections right now?
    static bool probe_port(int port);

    // Diagnostics: transport counters as "key=val key=val ..." (RX/TX/dedup/etc).
    std::string stats() const;

    // One-shot: connect to 127.0.0.1:port, send an already-built wire frame
    // (Kotlin may have wrapped it), optionally read a reply within read_ms,
    // then close. This is the path EVERY feature frame takes — profiles on
    // 40009, LED/GPS/param writes on 40008 (unwrapped) — so we are never a
    // long-lived competitor to DJI Fly on a port. Mirrors Skylab's
    // `DumlTransport().sendFrame(port=...)` per-op model.
    //
    // want_set/want_id: when >= 0, walk the read window and return the payload of
    // the first frame whose cmdSet/cmdId match (so a country/param reply is picked
    // out of interleaved telemetry, not the first frame that happens to arrive).
    // Both -1 (default) keeps the legacy "first parsed frame" behaviour. Returns
    // the matching payload, or empty.
    static Bytes send_once(int port, const Bytes& wire, int read_ms,
                           int want_set = -1, int want_id = -1);

    // One-shot WRITE with an honest result: connect + send the whole frame, then
    // close. Returns true only if both the connect and the full write succeeded —
    // used to tell "the apply really went out" from "the link dropped mid-send".
    // Unlike send_once, this does NOT depend on a reply (injected reads do not
    // route back on RC2), so a false here means a genuine send failure.
    static bool send_frame(int port, const Bytes& wire);

    // DUSS / 4G side channel: connect AF_UNIX abstract "/duss/mb/0x205" and
    // write one frame (no readback — the module does not answer).
    static bool duss_send(const Bytes& wire);

private:
    void stop_main();          // tear down just the main channel (leaves aux alone)
    struct Impl;
    Impl* p_;
};

// ---- DUSS firmware-bus diagnostics (REPORT/FIRMWARE-BUS-DUSS.md) ------------
// These are standalone and stateless — no persistent socket, no thread. They are
// driven only by the /duss/* diag endpoints, for on-hardware verification of the
// firmware-bus write path before it is trusted anywhere in the app.

// Sweep connect() across {SOCK_DGRAM,SOCK_STREAM} x {abstract,pathname} to `peer`
// (default "/duss/mb/0x205"), reporting OK / errno for each combo — so the real
// namespace + socket type of the router mailbox are discovered, not assumed.
std::string duss_probe(const std::string& peer);

// One full DUSS transaction, faithful to the report's §3 C skeleton:
//   socket(AF_UNIX,type) -> [bind abstract source] -> SO_RCVTIMEO ->
//   connect(peer) -> send(wire) -> recv loop matching wantSet/wantId (-1 = any).
// With noConnect, uses sendto(peer)+recvfrom(ANY) instead — so a reply from a
// mailbox other than the peer is caught and its source named (report §4.1).
// Returns a "k=v ... reply=HEX" trace; every failing syscall's errno is included,
// so an SELinux denial reads differently from a missing socket or a dead peer.
std::string duss_xact(const Bytes& wire,
                      bool dgram, bool peerAbstract, bool bindSource, bool noConnect,
                      const std::string& peer, const std::string& source,
                      int readMs, int wantSet, int wantId);

}  // namespace duml
#endif
