package com.dji.fccgpsoff

import android.content.Context
import android.content.Intent
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A tiny HTTP diagnostic server hosted INSIDE the app, so it survives DJI Fly
 * being in the foreground (DJI Fly kills the system telnet, but not our own
 * process). Lets a laptop on the same Wi-Fi pull logs and drive tests over the
 * network — the NetworkLogServer idea from Skylab.
 *
 * Endpoints (all plain HTTP on 0.0.0.0:8899):
 *   GET  /log                 full diagnostic log
 *   GET  /logjson             log as structured rows for the filterable table
 *   GET  /stats               native transport counters
 *   GET  /ports               loopback DUML port scan
 *   GET  /connect             start the persistent main channel
 *   GET  /fcc                 apply FCC (one frame 07:30 — fcc.json)
 *   GET  /keepon?mode=        start the keepalive (mode=home_point|periodic)
 *   GET  /keepoff             stop the keepalive
 *   GET  /homepoint           passive 03:44 home-point state (the event trigger)
 *   GET  /link                link liveness — is device telemetry arriving (connected)
 *   GET  /country             one-shot 07:19 country read
 *   GET  /ledon /ledoff       LED writes (40008)
 *   GET  /gpson /gpsoff       GPS writes (40008)
 *   GET  /deviceinfo          VersionInquiry round-trip (usually "no reply")
 *   GET  /serial              aircraft serial (00:51; passive listen fallback)
 *   GET  /readparams          read known params by name (usually "no answer" —
 *                             injected reads don't route back on RC2)
 *   POST /params/load?name=N  upload a .dhp/.dhv2params catalog (file = request body)
 *   GET  /paramsets.json      the parameter sets bundled in the APK (common + per-model)
 *   GET  /paramset/load?id=N  load one bundled set into the catalog (by set id)
 *   GET  /paramgroups.json    semantic groups present in the loaded catalog, with counts
 *   GET  /params.json[?q=&limit=&edit=1&group=G]  the loaded catalog for the web editor;
 *                             every match by default, limit=N caps it, edit=1
 *                             drops the read-only (min==max) entries, group=G keeps
 *                             only one semantic group (see /paramgroups.json)
 *   GET  /params/info[.json]?name=N  03:F7 — the AIRCRAFT's own type/width/min/max/default
 *                             for one parameter (a status-3 answer means "no such parameter
 *                             on this board", which is different from no answer at all)
 *   GET  /params/reset?name=N[&fallback=1]  03:FA — reset to the AIRCRAFT's default;
 *                             fallback=1 falls back to the loaded catalog's default
 *   GET  /params/read?name=N  read one parameter's current value by name
 *   GET  /params/read.json?name=N  the same read, as JSON for the editor row
 *   GET  /params/write?name=N&value=V  encode (decimal or 0x..) + write to 40008, read back
 *   GET  /clear               clear the log
 *   GET  /send?port=P&hex=H&read=MS   inject one raw wire frame, return reply hex
 *   GET  /cap?port=P&ms=MS            passively capture a port's stream, return hex
 *   GET  /probe?port=P&hex=H&ms=MS    send on one socket, return the WHOLE reply stream
 *
 * DUSS firmware bus (REPORT/FIRMWARE-BUS-DUSS.md) — the controller's internal
 * /duss/mb/ DUML mailbox, verified on hardware before it's trusted. Independent
 * of DJI Fly's TCP ports, so not read-gated:
 *   GET  /duss/scan                   /proc/net/unix DUSS sockets the kernel sees (§9)
 *   GET  /duss/probe[?peer=]          sweep connect() {DGRAM,STREAM}×{abstract,path}
 *   GET  /duss/version[?dgram=&pabs=&bind=&nc=&ms=]  safe VersionInquiry round-trip (§11)
 *   GET  /duss/req[?hex=H | set=&id=&recv=&type=&payload=][&dgram=0&pabs=0&bind=0&nc=0&peer=&src=&ms=&wantset=&wantid=]
 *                                     one full DUSS transaction; returns a k=v trace + reply
 *                                     (defaults from RC hardware: DGRAM, abstract peer, source
 *                                      bound, sendto+recvfrom ANY — pass nc=0 for connect+recv)
 *   GET  /duss/send?hex=H             legacy fire-and-forget on abstract 0x205
 *
 * Live hijack-read capture (aux reader on DJI Fly's port, streamed to the browser
 * which assembles a .pcap itself — nothing is written on device):
 *   GET  /capstart[?port=40007]       enable capture + open the aux/hijack reader
 *   GET  /capstop                     disable capture + close the aux reader
 *   GET  /capstatus                   JSON: capturing/auxRunning/buffered/dropped/lastId
 *   GET  /capframes?since=ID          JSON: new frames past cursor ID ({id,t,r,h})
 *
 * DJI Fly flight records (Android/data/dji.go.v5/files/FlightRecord):
 *   GET  /records[?dir=PATH]          text listing + which access route works
 *   GET  /records.json[?dir=PATH]     the same listing as JSON (used by the UI)
 *   GET  /record?name=N[&dir=PATH]    download one record file
 *   GET  /records.zip[?dir=PATH]      download every visible record as one zip
 *   GET  /records/grant               open the folder picker on the RC screen
 *   GET  /records/forget              drop the stored folder permission
 *   GET  /records/download            copy records to Download/FlightRecord
 *
 * Screen recordings — the RC2's own Movies folders (internal + SD card),
 * streamed with HTTP Range so the browser can play/scrub them inline:
 *   GET  /movies                      text listing of every visible recording
 *   GET  /movies.json                 the same listing as JSON (used by the UI)
 *   GET  /movie?id=ID[&dl=1]          stream one recording (Range-capable); dl=1 forces download
 *   GET  /movies/grant                request media-read permission on the RC screen
 *
 * No authentication: a debug tool for a trusted LAN. Bound to 0.0.0.0.
 */
object DiagServer {

    const val PORT = 8899

    // A worker cap so a burst of slow/held clients (each route runs to completion
    // under runBlocking) can't exhaust the IO dispatcher and wedge the server.
    private const val MAX_WORKERS = 16
    // Ceiling on the request head we buffer, so a client that never sends the
    // blank-line terminator can't make us read forever.
    private const val MAX_REQUEST_BYTES = 16 * 1024
    // Ceiling on an uploaded parameter file (.dhp/.dhv2params JSON).
    private const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024
    // Per-socket read timeout: a client that connects and stalls is dropped.
    // ParamRead.confirmWrite's worst case has to stay inside this.
    private const val SOCKET_TIMEOUT_MS = 15_000
    // Hard cap on live connections — reject beyond this instead of queueing
    // unbounded coroutines against a slow/hostile LAN.
    private const val MAX_CONNECTIONS = 32
    // Ceiling on the capture/probe window a client can request, so /cap and /probe
    // can't pin a worker for an arbitrary duration.
    private const val MAX_WINDOW_MS = 10_000

    private val activeConns = AtomicInteger(0)
    private val lifecycleLock = Any()

    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    // SupervisorJob: one handler failing (a client disconnecting mid-write) must
    // not cancel the scope and kill the accept loop with it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val workers = Dispatchers.IO.limitedParallelism(MAX_WORKERS)
    private lateinit var appCtx: Context

    val isRunning: Boolean get() = running

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        ForegroundGate.ownPackage = appCtx.packageName
        runCatching { ControllerProbe.read() }   // fill the RC slot from system properties
        if (running) return
        running = true
        val app = appCtx
        scope.launch {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress("0.0.0.0", PORT))
                // Race guard: if stop() ran while we were binding, close and bail —
                // otherwise this ServerSocket leaks and the port stays bound.
                synchronized(lifecycleLock) {
                    if (!running) { runCatching { s.close() }; return@launch }
                    server = s
                }
                DiagLog.info("diag server listening on :$PORT")
                val features = Features(app)
                while (running) {
                    val sock = s.accept()
                    if (activeConns.get() >= MAX_CONNECTIONS) {
                        runCatching { reject503(sock) }
                        continue
                    }
                    activeConns.incrementAndGet()
                    scope.launch(workers) {
                        try { handle(sock, features) } finally { activeConns.decrementAndGet() }
                    }
                }
            } catch (e: Exception) {
                if (running) DiagLog.err("diag server: ${e.message}")
                running = false
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            running = false
            runCatching { server?.close() }
            server = null
        }
        DiagLog.info("diag server stopped")
    }

    /** Minimal 503 for a connection refused over the concurrency cap. */
    private fun reject503(sock: Socket) {
        sock.use {
            val body = "busy".toByteArray()
            it.getOutputStream().apply {
                write(("HTTP/1.1 503 Service Unavailable\r\nContent-Type: text/plain\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                write(body); flush()
            }
        }
    }

    /** A response body plus the few headers that file downloads need. */
    private class Resp(
        val bytes: ByteArray,
        val ctype: String = "text/plain; charset=utf-8",
        val extra: List<String> = emptyList(),
        val status: String = "200 OK"
    )

    /**
     * Read the request line + headers, stopping EXACTLY at the CRLFCRLF terminator
     * so the request body (for a POST upload) is left untouched in the stream. Reads
     * a byte at a time — cheap because [handle] wraps the socket in a
     * BufferedInputStream. Capped at [MAX_REQUEST_BYTES] so a client that never
     * sends the blank line can't make us read forever.
     */
    private fun readHead(ins: InputStream): String {
        val acc = java.io.ByteArrayOutputStream(1024)
        var m = 0                                  // \r\n\r\n match state
        while (acc.size() < MAX_REQUEST_BYTES) {
            val b = ins.read()
            if (b < 0) break
            acc.write(b)
            m = when {
                b == 13 && (m == 0 || m == 2) -> m + 1   // CR at pos 0 or 2
                b == 10 && (m == 1 || m == 3) -> m + 1   // LF at pos 1 or 3
                else -> 0
            }
            if (m == 4) break
        }
        return String(acc.toByteArray(), Charsets.ISO_8859_1)
    }

    /** Content-Length from a header block, or 0 if absent/unparseable. */
    private fun contentLength(head: String): Int =
        Regex("(?i)Content-Length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Read up to [len] body bytes (capped at [MAX_UPLOAD_BYTES]) from the stream. */
    private fun readBody(ins: InputStream, len: Int): ByteArray {
        if (len <= 0) return ByteArray(0)
        val cap = minOf(len, MAX_UPLOAD_BYTES)
        val out = java.io.ByteArrayOutputStream(minOf(cap, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var remaining = cap
        while (remaining > 0) {
            val r = ins.read(buf, 0, minOf(buf.size, remaining))
            if (r < 0) break
            out.write(buf, 0, r)
            remaining -= r
        }
        return out.toByteArray()
    }

    /** Write a minimal text/plain 200 (used by the POST upload handler). */
    private fun writePlain(sock: Socket, text: String) {
        val body = text.toByteArray()
        sock.getOutputStream().apply {
            write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Access-Control-Allow-Origin: *\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
            write(body); flush()
        }
    }

    private fun handle(sock: Socket, f: Features) {
        try { sock.use {
            sock.soTimeout = SOCKET_TIMEOUT_MS          // drop a client that connects then stalls
            val ins = java.io.BufferedInputStream(sock.getInputStream())
            val reqText = readHead(ins)
            val line = reqText.substringBefore("\r\n")
            val parts = line.split(" ")
            val method = parts.getOrElse(0) { "GET" }
            val path = parts.getOrElse(1) { "/" }
            val p0 = path.substringBefore('?')
            // Videos are streamed (Range-capable), not buffered into a Resp.
            if (p0 == "/movie") {
                runCatching { serveMovie(sock, path, reqText) }
                    .onFailure { DiagLog.err("movie stream: ${it.message}") }
                return@use
            }
            // Parameter-catalog upload: the file arrives as the POST body.
            if (method == "POST" && p0 == "/params/load") {
                val body = readBody(ins, contentLength(reqText))
                val name = query(path, "name") ?: "upload"
                val msg = runCatching { ParamCatalog.load(String(body, Charsets.UTF_8), name) }
                    .fold({ "loaded $it params from $name" }, { "parse error: ${it.message}" })
                writePlain(sock, msg)
                return@use
            }
            val resp = try {
                when (p0) {
                    "/movies.json" -> Resp(Movies.json(appCtx).toByteArray(), "application/json; charset=utf-8")
                    "/params.json" -> Resp(paramsJson(query(path, "q"), query(path, "limit")?.toIntOrNull(),
                        query(path, "edit") == "1", query(path, "group")).toByteArray(), "application/json; charset=utf-8")
                    "/paramsets.json" -> Resp(paramSetsJson().toByteArray(), "application/json; charset=utf-8")
                    "/paramgroups.json" -> Resp(paramGroupsJson(query(path, "edit") == "1").toByteArray(), "application/json; charset=utf-8")
                    "/table/status" -> Resp(ParamDump.statusJson().toByteArray(), "application/json; charset=utf-8")
                    "/table.json" -> Resp(ParamDump.asDhpJson().toByteArray(), "application/json; charset=utf-8")
                    "/params/info.json" -> Resp(runBlocking {
                        paramInfoJson(query(path, "name") ?: "")
                    }.toByteArray(), "application/json; charset=utf-8")
                    "/params/read.json" -> Resp(runBlocking {
                        readParamJson(query(path, "name") ?: "")
                    }.toByteArray(), "application/json; charset=utf-8")
                    "/", "/ui" -> Resp(UI_HTML.toByteArray(), "text/html; charset=utf-8")
                    "/capstatus" -> Resp(DumlCapture.statusJson().toByteArray(), "application/json; charset=utf-8")
                    "/serialwatch" -> Resp(SerialSniffer.statusJson().toByteArray(), "application/json; charset=utf-8")
                    "/foreground" -> Resp(ForegroundGate.statusJson().toByteArray(), "application/json; charset=utf-8")
                    "/identity" -> Resp(AircraftIdentity.statusJson().toByteArray(), "application/json; charset=utf-8")
                    "/screen" -> screen()
                    "/capframes" -> Resp(
                        DumlCapture.sinceJson(query(path, "since")?.toLongOrNull() ?: 0L).toByteArray(),
                        "application/json; charset=utf-8")
                    "/records.json" -> Resp(FlightRecords.json(appCtx, query(path, "dir")).toByteArray(),
                        "application/json; charset=utf-8")
                    "/record" -> record(path)
                    "/records.zip" -> recordsZip(path)
                    else -> Resp(runBlocking { route(path, f) }.toByteArray())
                }
            } catch (e: Exception) {
                Resp("error: ${e.message}".toByteArray(), status = "500 Internal Server Error")
            }
            val head = StringBuilder("HTTP/1.1 ${resp.status}\r\nContent-Type: ${resp.ctype}\r\n")
                .append("Access-Control-Allow-Origin: *\r\nContent-Length: ${resp.bytes.size}\r\nConnection: close\r\n")
            for (h in resp.extra) head.append(h).append("\r\n")
            head.append("\r\n")
            val out = sock.getOutputStream()
            out.write(head.toString().toByteArray())
            out.write(resp.bytes)
            out.flush()
        } } catch (e: Exception) {
            // A client that hangs up mid-response throws here; log and move on so
            // the exception never escapes into the (supervised) worker coroutine.
            DiagLog.err("diag handle: ${e.message}")
        }
    }

    /**
     * On-demand screen PNG via the accessibility service. Triggers an async
     * capture and waits briefly for a fresh frame; the browser just displays the
     * bytes. Falls back to the last good frame on the ~1/s rate limit.
     */
    private fun screen(): Resp {
        val svc = DjiFlyAccessibilityService.instance
            ?: return Resp("accessibility service off — tap Enable a11y, turn it on, then retry".toByteArray(),
                status = "503 Service Unavailable")
        if (android.os.Build.VERSION.SDK_INT < 30)
            return Resp("screenshots need Android 11+".toByteArray(), status = "501 Not Implemented")
        val before = ScreenshotStore.atMs
        svc.requestScreenshot()
        val end = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < end && ScreenshotStore.atMs == before && ScreenshotStore.error == null) {
            try { Thread.sleep(50) } catch (_: InterruptedException) { break }
        }
        val png = ScreenshotStore.png
        if (ScreenshotStore.atMs != before && png != null)
            return Resp(png, "image/png", listOf("Cache-Control: no-store"))
        if (png != null)   // fresh capture failed (rate limit) — serve the last good frame
            return Resp(png, "image/png", listOf("Cache-Control: no-store", "X-Screenshot: stale"))
        return Resp(("screenshot failed: " + (ScreenshotStore.error ?: "timeout")).toByteArray(),
            status = "500 Internal Server Error")
    }

    /** One flight record, resolved by name against the current listing (no path traversal). */
    private fun record(path: String): Resp {
        val name = query(path, "name") ?: return Resp("missing name".toByteArray(), status = "400 Bad Request")
        val rec = FlightRecords.find(appCtx, name, query(path, "dir"))
            ?: return Resp("not found: $name".toByteArray(), status = "404 Not Found")
        val data = FlightRecords.read(appCtx, rec)
            ?: return Resp("unreadable: $name".toByteArray(), status = "500 Internal Server Error")
        DiagLog.info("record served: ${rec.name} (${data.size} B)")
        return Resp(data, "application/octet-stream", attachment(rec.name.substringAfterLast('/')))
    }

    private fun recordsZip(path: String): Resp {
        val recs = FlightRecords.list(appCtx, query(path, "dir"))
        if (recs.isEmpty()) return Resp("no records visible — see /records".toByteArray(), status = "404 Not Found")
        val data = FlightRecords.zip(appCtx, recs)
        DiagLog.info("records zip served: ${recs.size} file(s), ${data.size} B")
        return Resp(data, "application/zip", attachment("FlightRecord.zip"))
    }

    /**
     * Stream one screen recording straight to the socket with HTTP Range support
     * so an HTML5 <video> can start immediately and scrub. Nothing is buffered in
     * memory — bytes are copied from a seekable source, so a multi-GB clip is fine.
     */
    private fun serveMovie(sock: Socket, path: String, reqText: String) {
        val out = sock.getOutputStream()
        val id = query(path, "id")
        val src = id?.let { Movies.source(appCtx, it) }
        if (src == null) {
            val body = "not found".toByteArray()
            out.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                    "Access-Control-Allow-Origin: *\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(body); out.flush(); return
        }
        val total = src.length
        var start = 0L
        var end = total - 1
        var partial = false
        Regex("(?i)Range:\\s*bytes=(\\d*)-(\\d*)").find(reqText)?.let { m ->
            // toLongOrNull: an over-long digit run ("bytes=9999…-") must not throw
            // NumberFormatException and leave the client hung with no response.
            val s = m.groupValues[1].toLongOrNull()
            val e = m.groupValues[2].toLongOrNull()
            when {
                s != null -> { start = s; if (e != null) end = e; partial = true }
                e != null -> { start = maxOf(0L, total - e); partial = true }   // suffix range
            }
            end = minOf(end, total - 1)
        }
        if (total > 0 && (start > end || start >= total)) {
            out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\n" +
                    "Access-Control-Allow-Origin: *\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
            out.flush(); return
        }
        val len = if (total == 0L) 0L else end - start + 1
        val disp = if (query(path, "dl") != null) "attachment" else "inline"
        val head = StringBuilder("HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\n")
            .append("Content-Type: ${headerSafe(src.mime)}\r\n")
            .append("Content-Length: $len\r\n")
            .append("Accept-Ranges: bytes\r\n")
        if (partial) head.append("Content-Range: bytes $start-$end/$total\r\n")
        head.append("Content-Disposition: $disp; filename=\"${headerSafe(src.filename)}\"\r\n")
            .append("Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
        if (len <= 0L) { out.flush(); return }
        src.open(start).use { ins ->
            var remaining = len
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val r = ins.read(buf, 0, toRead)
                if (r < 0) break
                out.write(buf, 0, r)
                remaining -= r
            }
        }
        out.flush()
        DiagLog.info("movie streamed: ${src.filename} ${start}-${end}/${total}")
    }

    private fun attachment(filename: String) =
        listOf("Content-Disposition: attachment; filename=\"" + headerSafe(filename) + "\"")

    /** Strip CR/LF (header injection) and quotes from a value placed in an HTTP header. */
    private fun headerSafe(s: String) = s.replace('\r', ' ').replace('\n', ' ').replace('"', '_')

    /** App settings + live service state, for the web dashboard's Main page. */
    private fun appStateJson(): String =
        "{\"lito\":${AppState.litoMode}," +
            "\"autoKeepalive\":${AppState.autoKeepalive},\"keepaliveMode\":\"${AppState.keepaliveMode.wire}\"," +
            "\"autoOverlay\":${AppState.autoOverlay},\"autoDiag\":${AppState.autoDiag}," +
            "\"keepaliveRunning\":${FccKeepaliveService.running}," +
            "\"keepaliveActiveMode\":\"${FccKeepaliveService.activeMode?.wire ?: ""}\"," +
            "\"overlayRunning\":${OverlayService.running},\"diagRunning\":$isRunning," +
            "\"a11y\":${ForegroundGate.accessibilityConnected}}"

    /**
     * The loaded catalog (filtered by [q], and by [editOnly] to drop the entries
     * whose limits leave no room — see [ParamCatalog.Def.editable]) as JSON for
     * the web param editor. [limit] caps the rows
     * returned; absent or 0 means every match, which is what the page asks for.
     * `matched` is always the uncapped count, so a capped view can say so instead
     * of leaving the cap looking like the whole catalog.
     */
    private fun paramsJson(q: String?, limit: Int?, editOnly: Boolean, group: String?): String {
        // Name/editable filter first, then the optional semantic-group filter — the
        // same two-stage narrowing the in-app editor does. `matched` is the count
        // AFTER both filters but BEFORE the row cap, so "showing N of M" is honest.
        val all = ParamCatalog.matches(q ?: "", editOnly)
            .let { if (group.isNullOrEmpty()) it else it.filter { d -> ParamGroups.groupIdOf(d.name) == group } }
        val matched = all.size
        val res = if ((limit ?: 0) <= 0) all else all.take(limit!!)
        val items = res.joinToString(",", "[", "]") {
            "{\"name\":${Json.quote(it.name)},\"value\":${Json.quote(it.value)}," +
                "\"def\":${Json.quote(it.def)},\"min\":${Json.quote(it.min)},\"max\":${Json.quote(it.max)}," +
                "\"type\":${it.typeId},\"tname\":${Json.quote(it.typeName)},\"ro\":${!it.editable}}"
        }
        return "{\"src\":${Json.quote(ParamCatalog.sourceName)},\"total\":${ParamCatalog.params.size}," +
            "\"locked\":${ParamCatalog.params.count { !it.editable }}," +
            "\"matched\":$matched,\"shown\":${res.size},\"params\":$items}"
    }

    /** The parameter sets bundled in the APK (common set first), as JSON for the
     *  web editor's "load from set" dropdown. English labels — the web UI is
     *  English regardless of the app's language. */
    private fun paramSetsJson(): String =
        BundledParamSets.list(appCtx).joinToString(",", "[", "]") {
            "{\"id\":${Json.quote(it.id)},\"label\":${Json.quote(it.labelEn)}," +
                "\"count\":${it.count},\"unique\":${it.unique},\"common\":${it.isCommon}}"
        }

    /** The semantic groups actually present in the loaded catalog, each with its
     *  count, as JSON for the web editor's group filter. When [editOnly] is set the
     *  counts are computed over the editable subset only — so the number beside a
     *  group in the dropdown matches the rows `/params.json?group=…&edit=1` yields,
     *  instead of the larger whole-catalog tally. Empty array when nothing loaded. */
    private fun paramGroupsJson(editOnly: Boolean): String =
        ParamGroups.present(ParamCatalog.matches("", editOnly)).joinToString(",", "[", "]") { (g, n) ->
            "{\"id\":${Json.quote(g.id)},\"label\":${Json.quote(g.labelEn)}," +
                "\"desc\":${Json.quote(g.descEn)},\"n\":$n}"
        }

    /** Read one parameter by name and format its value (or "no answer"). */
    private suspend fun readParam(name: String): String {
        val v = ParamRead.read(name) ?: return "$name: no answer (reads often don't route on RC2)"
        val t = ParamCatalog.find(name)?.typeName ?: ""
        return "$name = ${ParamCatalog.decode(v, t)}  (${DumlWire.toHex(v)}, ${v.size} B)"
    }

    /**
     * The same read as [readParam] but machine-readable, so the editor can fill in
     * one row's live value instead of the page having to scrape the text line.
     * `value` is absent when the FC didn't answer — the caller must keep showing
     * the catalog value as unverified rather than inventing one.
     */
    private suspend fun readParamJson(name: String): String {
        val v = ParamRead.read(name)
            ?: return "{\"name\":${Json.quote(name)},\"error\":\"no answer (reads often don't route on RC2)\"}"
        val t = ParamCatalog.find(name)?.typeName ?: ""
        return "{\"name\":${Json.quote(name)},\"value\":${Json.quote(ParamCatalog.decode(v, t))}," +
            "\"hex\":${Json.quote(DumlWire.toHex(v))},\"bytes\":${v.size}}"
    }

    /**
     * Restore one parameter to the **aircraft's own** default with `03:FA`.
     *
     * A separate DUML reset opcode does exist — `03:FA ResetParamsByHash`, verified on a
     * Lito X1 v400 to reset exactly one parameter with six neighbours untouched. It takes
     * the default from the firmware, so unlike the old path it does not depend on an
     * export being loaded, being for this aircraft, or being for this firmware.
     *
     * The catalog's own default stays available behind `&fallback=1`, for the case where
     * the board won't answer `03:F7` and the user knows the loaded export is the right one.
     */
    private suspend fun resetParam(name: String, allowFallback: Boolean): String {
        val (result, message) = ParamMeta.reset(name)
        if (result != ParamMeta.ResetResult.UNKNOWN_DEFAULT || !allowFallback) return message
        val def = ParamCatalog.find(name)
            ?: return "$message · no catalog entry either, so there is no default to fall back to"
        if (def.def.isEmpty()) return "$message · ${ParamCatalog.sourceName} carries no default for it"
        return "$message\nfalling back to the catalog's default (${ParamCatalog.sourceName}): " +
            writeParam(name, def.def)
    }

    /** `03:F7` metadata for one parameter, read off the aircraft.
     *
     *  The `.json` sibling is dispatched from [handle]'s own `when`, which never reaches
     *  [route]'s gate check — so the gate is asserted here too. [ParamMeta.info] also
     *  refuses on its own, but it can only return "no answer", and reporting a policy
     *  block as a routing failure would be a lie. */
    private suspend fun paramInfo(name: String): String =
        if (!ForegroundGate.readsAllowed()) "blocked: " + (ForegroundGate.blockReason() ?: "DJI Fly is active")
        else when (val i = ParamMeta.info(name, force = true)) {
            null -> "$name: no answer (03:F7 unanswered — no route back, which is NOT the same as absent)"
            is ConfigTable.Info.Absent ->
                "$name: NO SUCH PARAMETER on this aircraft (03:F7 status ${i.status})"
            is ConfigTable.Info.Ok ->
                "${i.name} = ${i.typeName} / ${i.size} B / ${i.min} … ${i.max} / default ${i.def} " +
                    "(attribute ${i.attribute})"
        }

    private suspend fun paramInfoJson(name: String): String =
        if (!ForegroundGate.readsAllowed())
            "{\"name\":${Json.quote(name)},\"error\":\"blocked while DJI Fly is active\"}"
        else when (val i = ParamMeta.info(name, force = true)) {
            null -> "{\"name\":${Json.quote(name)},\"error\":\"no answer\"}"
            is ConfigTable.Info.Absent ->
                "{\"name\":${Json.quote(name)},\"absent\":true,\"status\":${i.status}}"
            is ConfigTable.Info.Ok ->
                "{\"name\":${Json.quote(i.name)},\"type\":${Json.quote(i.typeName)},\"typeId\":${i.typeId}," +
                    "\"size\":${i.size},\"attribute\":${i.attribute},\"min\":${Json.quote(i.min)}," +
                    "\"max\":${Json.quote(i.max)},\"def\":${Json.quote(i.def)}}"
        }

    /**
     * Encode + write a parameter by name from the web editor, then read it back and
     * report an honest result — the same safe path as the in-app editor
     * ([ParamCatalog.encodeChecked] + [ParameterAddress.write]): range/width checked,
     * never a guessed 1-byte write, and CONFIRMED only when the read-back matches.
     */
    private suspend fun writeParam(name: String, value: String): String {
        val def = ParamCatalog.find(name) ?: ParamCatalog.Def(name, "", -1, "", "", "")
        // Read BEFORE writing. This is the verification step: it pins the exact byte
        // width from the aircraft (so the width is never guessed from the catalog),
        // and it turns the report into an honest "was X → Y" instead of a blind poke.
        // A null here is "no answer", not "absent" — reads often don't route on RC2.
        val cur = ParamRead.read(name)
        val was = cur?.let { "${ParamCatalog.decode(it, def.typeName)} (${DumlWire.toHex(it)})" }
            ?: "unread — no answer to the pre-write read"
        return when (val enc = ParamCatalog.encodeChecked(def, value, cur?.size)) {
            is ParamCatalog.Encoded.Invalid -> "rejected: ${enc.reason} · was $was"
            is ParamCatalog.Encoded.Ok -> {
                val alreadySet = cur != null && cur.contentEquals(enc.bytes)
                val ok = ParameterAddress(name).write(enc.bytes, writes = 2, gapMs = 120)
                val back = ParamRead.confirmWrite(name, enc.bytes)
                val result = when {
                    !ok -> "LINK DOWN — frame did not leave the socket"
                    back == null -> "SENT — no read-back (normal on RC2)"
                    back.value.contentEquals(enc.bytes) ->
                        (if (alreadySet) "CONFIRMED (already held this value)" else "CONFIRMED") +
                            " after ${back.afterMs} ms"
                    else -> "SENT — read-back ${ParamCatalog.decode(back.value, def.typeName)} " +
                        "(${DumlWire.toHex(back.value)}) still differs ${back.afterMs} ms later"
                }
                "$name: was $was → ${ParamCatalog.decode(enc.bytes, def.typeName)} " +
                    "(${DumlWire.toHex(enc.bytes)}, ${enc.widthNote}) · $result"
            }
        }
    }

    /** Is the media-read runtime permission (needed to list screen recordings) held? */
    private fun moviesPermitted(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 23) return true
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33)
            android.Manifest.permission.READ_MEDIA_VIDEO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return appCtx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun query(path: String, key: String): String? =
        path.substringAfter('?', "").split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    // Read-family endpoints that open a socket to DJI Fly's ports. Blocked while
    // DJI Fly is the active window (would risk a video/link blip); allowed once
    // our app / any non-Fly app is foreground and Fly is merely backgrounded.
    private val READ_GATED = setOf(
        "/serial", "/deviceinfo", "/readparams", "/readw", "/params/read", "/params/info", "/params/reset", "/table", "/table/dump",
        "/country", "/cap", "/probe", "/capstart", "/model"
    )

    private suspend fun route(path: String, f: Features): String {
        val p = path.substringBefore('?')
        val isRead = p in READ_GATED || (p == "/send" && (query(path, "read")?.toIntOrNull() ?: 0) > 0)
        if (isRead && !ForegroundGate.readsAllowed()) {
            DiagLog.warn("blocked read $p — ${ForegroundGate.foregroundPackage} is foreground")
            return "blocked: " + (ForegroundGate.blockReason() ?: "reads disabled while DJI Fly is active")
        }
        return try {
            when (p) {
                "/foreground" -> ForegroundGate.statusJson()
                "/identity" -> AircraftIdentity.statusJson()
                "/model" -> {
                    val rc = ControllerProbe.read()
                    AircraftModelProbe.capture(f) + " | rc(prop)=" + (rc?.let { "${it.name} [${it.code}]" } ?: "—")
                }
                "/rc" -> {
                    val m = ControllerProbe.read()
                    "resolved: " + (m?.let { "${it.name} [${it.code}]" } ?: "none") + "\nprops: " + ControllerProbe.rawProps()
                }
                "/log" -> DiagLog.asText()
                "/logjson" -> DiagLog.asJson()
                // Which build is actually running. Ask this BEFORE interpreting a log
                // pulled from a live controller — see CLAUDE.md.
                "/version" -> AppVersion.of(appCtx)
                "/stats" -> DumlBus.stats()
                "/ports" -> DumlBus.probePorts()
                // The main channel is on-demand: /connect raises it by hand and holds it
                // until /disconnect. Before the channel was demoted, the keepalive held
                // it for the whole session and /connect needed no counterpart — now an
                // unpaired /connect would pin a broker slot with nothing reading it.
                "/connect" -> "port=" + f.connect()
                "/disconnect" -> { f.disconnect(); "main channel released (up only while capture or /connect holds it)" }
                "/fcc" -> if (f.applyFcc()) "FCC sent — reboot to apply" else "port busy"
                // Minimal-sequence search: play a SUBSET of fcc_full.json and score the
                // two visible effects by eye. See Experiment.kt for why the verdict
                // is persisted and why a single negative run proves nothing.
                "/frames" -> Experiment.frames(appCtx)
                "/exp" -> runCatching {
                    val keep = Experiment.select(appCtx, query(path, "keep"), query(path, "drop"))
                    Experiment.start(
                        appCtx,
                        label = query(path, "label") ?: "exp",
                        keep = keep,
                        count = query(path, "count")?.toIntOrNull() ?: 5,
                        gapSec = query(path, "gap")?.toIntOrNull() ?: 15,
                        rounds = query(path, "rounds")?.toIntOrNull() ?: 2,
                        // Apply FCC no longer sends the regulatory write (it never stuck), so the
                        // harness default matches the product: off unless explicitly asked for.
                        withReg = (query(path, "reg")?.toIntOrNull() ?: 0) != 0,
                    )
                }.getOrElse { "bad subset: ${it.message}" }
                "/exp/status" -> Experiment.status()
                "/exp/cancel" -> Experiment.cancel()
                "/exp/verdict" -> Experiment.verdict(
                    appCtx,
                    power = (query(path, "power")?.toIntOrNull() ?: 0) != 0,
                    r58 = (query(path, "r58")?.toIntOrNull() ?: 0) != 0,
                    note = query(path, "note"),
                )
                "/exp/log" -> Experiment.log(appCtx)
                // Experiment harness: fire EXACTLY ONE apply, `sec` seconds after the
                // next aircraft session appears on DJI Fly's screen, and nothing else.
                //
                // The normal keepalive sends five to seven bursts per link-up, so when
                // FCC finally shows up in DJI Fly there is no way to tell which burst
                // did it. Turn the keepalive off (/keepoff), arm this, power-cycle the
                // drone, and the answer is unambiguous: one apply at a known offset.
                "/applyat" -> {
                    if (query(path, "cancel") == "1") { ApplyAt.cancel(); "armed apply cancelled" }
                    else ApplyAt.arm(appCtx, scope, query(path, "sec")?.toLongOrNull() ?: 10L,
                        query(path, "then")?.toLongOrNull() ?: 15L,
                        query(path, "count")?.toIntOrNull() ?: 1)
                }
                "/applyat/status" -> ApplyAt.status()
                "/ce" -> "disabled: Restore CE was removed from this build (FCC-only)"
                "/keepon" -> {
                    val mode = KeepaliveMode.of(query(path, "mode") ?: AppState.keepaliveMode.wire)
                    AppState.setKeepaliveMode(appCtx, mode)
                    AppState.setAutoKeepalive(appCtx, true)
                    FccKeepaliveService.start(appCtx, mode)
                    "keepalive started — ${mode.label}"
                }
                // Clears the auto-start flag too, otherwise this is a pause, not an off:
                // MainActivity.applyAutoStart() re-launches the keepalive whenever its
                // screen is opened, so a /keepoff issued for a measurement was silently
                // undone the next time the user switched to the app — and the "manual
                // only" runs it was meant to isolate were quietly full of automatic
                // applies. Symmetric with /keepon, which arms it again.
                "/keepoff" -> {
                    AppState.setAutoKeepalive(appCtx, false)
                    FccKeepaliveService.stop(appCtx)
                    "keepalive stopped (auto-start disabled too — /keepon re-arms it)"
                }
                "/overlayon" -> { OverlayService.start(appCtx); "overlay requested (needs 'display over other apps')" }
                "/overlayoff" -> { OverlayService.stop(appCtx); "overlay stopped" }
                "/profile" -> {
                    query(path, "lito")?.let {
                        val v = it == "1" || it.equals("true", true)
                        AppState.setLito(appCtx, v)
                        // Same rule as the in-app switch: an explicit choice is an override
                        // and must outrank whatever the next startup probe concludes.
                        AircraftSession.serial.ifEmpty { StartupProbe.serial }
                            .takeIf { s -> s.isNotEmpty() }
                            ?.let { s -> DeviceStore.setManualVariant(appCtx, s, v) }
                    }
                    "profile: " + (if (AppState.litoMode) "Lito X1 names" else "other DJI names") +
                        if (query(path, "lito") != null) " (manual — the startup probe will not overwrite it)" else ""
                }
                "/profile/detect" -> {
                    // Drop the cached VALUE too, not just the manual flag — otherwise
                    // `known ?: detectVariant()` returns the cache and no probe happens.
                    val s = AircraftSession.serial.ifEmpty { StartupProbe.serial }
                    if (s.isNotEmpty()) DeviceStore.clearVariant(appCtx, s)
                    StartupProbe.run(appCtx)
                    "re-probed: " + (if (AppState.litoMode) "Lito X1 names" else "other DJI names") +
                        " (variant=${StartupProbe.variant}" +
                        (if (StartupProbe.variant == null) ", undecided — profile left as it was" else "") + ")"
                }
                "/setauto" -> {
                    query(path, "ka")?.let { AppState.setAutoKeepalive(appCtx, it == "1") }
                    query(path, "ov")?.let { AppState.setAutoOverlay(appCtx, it == "1") }
                    query(path, "diag")?.let { AppState.setAutoDiag(appCtx, it == "1") }
                    "auto-start: keepalive=${AppState.autoKeepalive} overlay=${AppState.autoOverlay} diag=${AppState.autoDiag}"
                }
                "/appstate" -> appStateJson()
                "/state" -> { FlightState.refresh(); FlightState.statusJson() }
                "/a11y" -> {
                    appCtx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "Accessibility settings opened on the RC — enable \"DJI_FCC_GPSOFF — model & foreground\" to turn on model detection + read gating"
                }
                "/identity/forget" -> {
                    val s = AircraftSession.serial.ifEmpty { SerialSniffer.serial.ifEmpty { StartupProbe.serial } }
                    AircraftIdentity.drone.clear()
                    if (s.isNotEmpty()) DeviceStore.forgetModel(appCtx, s)
                    "cleared drone identity" + if (s.isNotEmpty()) " + cached model for $s — re-detecting" else " (no serial known yet)"
                }
                "/homepoint" -> HomePointMonitor.statusJson()
                "/radiolink" -> RadioLinkMonitor.statusJson()
                "/radiolink/reset" -> { RadioLinkMonitor.reset(); "radio-link tally cleared" }
                "/link" -> LinkState.statusJson()
                "/dronelink" -> DroneLink.statusJson()   // honest: live aircraft OSD (needs aux reader up — keepalive/capture)
                // Aircraft link as DJI Fly's own screen reports it — the only link
                // signal available while Fly is foreground. Puts zero traffic on the bus.
                "/flylink" -> FlyLink.statusJson()
                // The raw labels the accessibility tree gave us plus the verdict —
                // how to confirm on hardware that Fly exposes its mode label at all.
                "/flyui" -> FlyLink.snapshotJson()
                "/country" -> FccCountry.read() ?: "no reply"
                // ---- DUSS firmware bus (REPORT/FIRMWARE-BUS-DUSS.md) ----------
                // Diagnostics for the internal /duss/mb/ mailbox write path, so it
                // can be proven on real hardware before being trusted. This is the
                // controller's own bus, independent of DJI Fly's TCP ports — hence
                // not READ_GATED (no 40007 video to blip).
                "/duss/scan" -> DussBus.scan()
                "/duss/probe" -> DussBus.probe(query(path, "peer") ?: DussBus.PEER)
                "/duss/version" -> DussBus.versionInquiry(
                    dgram = query(path, "dgram") != "0",
                    peerAbstract = query(path, "pabs") != "0",   // hardware: mailbox is abstract
                    bindSource = query(path, "bind") != "0",
                    noConnect = query(path, "nc") != "0",         // hardware: connected recv is silent
                    readMs = query(path, "ms")?.toIntOrNull()?.coerceIn(0, MAX_WINDOW_MS) ?: 500)
                "/duss/req" -> dussReq(path)
                "/duss/send" -> {
                    val hex = query(path, "hex") ?: return "missing hex"
                    val ok = DumlNative.nativeDussSend(DumlWire.hex(hex))
                    DiagLog.tx(0x205, "duss/send", DumlWire.hex(hex))
                    "duss/send (legacy abstract 0x205, fire-and-forget): " + if (ok) "sent" else "failed (connect/write)"
                }
                "/ledon" -> "led=" + f.setLed(true)
                "/ledoff" -> "led=" + f.setLed(false)
                "/gpson" -> "gps=" + f.setGps(true)
                "/gpsoff" -> "gps=" + f.setGps(false)
                "/deviceinfo" -> f.deviceInfo()?.let { DumlWire.toHex(it) } ?: "no reply"
                "/serial" -> {
                    // ?live=1 requires a genuine 00:51 reply (drone answering now), not the
                    // sticky 51:14 broadcast the controller keeps emitting after power-off.
                    val live = query(path, "live") == "1"
                    AircraftSerial.read(liveOnly = live).ifEmpty {
                        if (live) "no live reply (00:51 unanswered — aircraft off, or reads don't route)"
                        else "no serial (00:51 unanswered — board may not support it; passive listen is the fallback)"
                    }
                }
                "/readparams" -> Diagnostics.readAllKnown()
                "/params/read" -> readParam(query(path, "name") ?: return "missing name")
                "/params/write" -> writeParam(query(path, "name") ?: return "missing name",
                                              query(path, "value") ?: return "missing value")
                "/params/reset" -> resetParam(query(path, "name") ?: return "missing name",
                                              query(path, "fallback") == "1")
                "/params/info" -> paramInfo(query(path, "name") ?: return "missing name")
                "/table" -> ParamTable.checkFingerprint(
                    appCtx, AircraftSession.serial.ifEmpty { StartupProbe.serial })
                "/table/dump" -> ParamDump.start(appCtx, query(path, "table")?.toIntOrNull() ?: 0)
                "/table/stop" -> { ParamDump.stop(); "dump stopped" }
                "/table/files" -> ParamDump.savedReport(appCtx)
                "/table/load" -> ParamDump.loadSaved(appCtx, query(path, "name") ?: return "missing name")
                "/table/save" -> ParamDump.save(
                    appCtx, AircraftSession.serial.ifEmpty { StartupProbe.serial },
                    query(path, "partial") == "1")
                "/paramset/load" -> {
                    val id = query(path, "id") ?: return "missing id"
                    val e = BundledParamSets.byId(appCtx, id) ?: return "unknown set: $id"
                    runCatching { BundledParamSets.load(appCtx, e, e.labelEn) }
                        .fold({ "loaded $it params from ${e.labelEn}" }, { "set load error: ${it.message}" })
                }
                "/readw" -> {
                    val name = query(path, "name") ?: return "missing name"
                    val raw = ParamRead.readRaw(name)
                    if (raw == null) "$name: no answer (40007 window)"
                    else "$name RAW=${DumlWire.toHex(raw)} (${raw.size} B) value=${ParamRead.parseValue(raw)?.let { DumlWire.toHex(it) } ?: "-"} hash ${DumlWire.toHex(DumlNative.nativeParamHash(name))}"
                }
                "/capstart" -> {
                    val port = query(path, "port")?.toIntOrNull() ?: DumlCapture.DEFAULT_AUX_PORT
                    "capture on ($port): " + DumlCapture.start(port)
                }
                "/capstop" -> DumlCapture.stop()
                "/clear" -> { DiagLog.clear(); "cleared" }
                "/records" -> FlightRecords.report(appCtx, query(path, "dir"))
                "/records/grant" -> {
                    appCtx.startActivity(Intent(appCtx, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra(MainActivity.EXTRA_ACTION, MainActivity.ACTION_GRANT_RECORDS)
                    })
                    "folder picker opened on the RC screen — pick FlightRecord and confirm"
                }
                "/records/forget" -> { FlightRecords.forget(appCtx); "flight-record access forgotten" }
                "/records/download" -> FlightRecords.copyToDownloads(appCtx, query(path, "dir"))
                "/movies" -> Movies.report(appCtx)
                "/movies/grant" -> {
                    appCtx.startActivity(Intent(appCtx, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra(MainActivity.EXTRA_ACTION, MainActivity.ACTION_GRANT_MOVIES)
                    })
                    "media-access prompt opened on the RC screen — allow, then reload the list"
                }
                // Permission probes so the web UI can auto-open the RC grant prompt
                // the moment a panel is opened without access, instead of waiting for
                // a manual click. "granted" | "denied" (plain text).
                "/movies/perm" -> if (moviesPermitted()) "granted" else "denied"
                "/records/perm" -> if (AppState.recordsTree != null) "granted" else "denied"
                "/screen/perm" -> if (DjiFlyAccessibilityService.instance != null) "granted" else "denied"
                "/send" -> {
                    val port = query(path, "port")?.toIntOrNull() ?: DumlWire.PORT_FCC
                    val hex = query(path, "hex") ?: return "missing hex"
                    val read = query(path, "read")?.toIntOrNull() ?: 0
                    val reply = DumlBus.sendOnce(port, DumlWire.hex(hex), read, "diag/send")
                    "sent to $port; reply=" + (reply?.let { DumlWire.toHex(it) } ?: "none")
                }
                "/cap" -> {
                    val port = query(path, "port")?.toIntOrNull() ?: DumlWire.PORT_LED
                    val ms = (query(path, "ms")?.toIntOrNull() ?: 2000).coerceIn(0, MAX_WINDOW_MS)
                    capture(port, ms)
                }
                "/probe" -> {
                    // send a request on ONE socket, return the FULL reply stream
                    // (so a param-read response can be found by seq, not just the
                    // first telemetry frame that /send returns).
                    val port = query(path, "port")?.toIntOrNull() ?: DumlWire.PORT_LED
                    val hex = query(path, "hex") ?: return "missing hex"
                    val ms = (query(path, "ms")?.toIntOrNull() ?: 1000).coerceIn(0, MAX_WINDOW_MS)
                    probe(port, DumlWire.hex(hex), ms)
                }
                "/help" -> "endpoints: /version /log /logjson /stats /applyat?sec=&then=&count=&cancel=1 /applyat/status /ports /connect /disconnect /fcc " +
                        "/frames /exp?keep=|drop=&label=&count=&gap=&rounds=&reg= /exp/status /exp/cancel /exp/verdict?power=&r58=&note= /exp/log " +
                        "/foreground /identity /identity/forget /model /rc /screen /a11y " +
                        "/keepon?mode=home_point|periodic /keepoff /overlayon /overlayoff " +
                        "/profile?lito=1|0 /setauto?ka=&ov=&diag= /appstate /state /homepoint /radiolink /radiolink/reset /link /dronelink /country " +
                        "/ledon /ledoff /gpson /gpsoff /deviceinfo /serial[?live=1] /readparams /clear " +
                        "/params.json?q=&limit=&edit=1&group= /params/read[.json]?name= /params/write?name=&value= " +
                        "/params/info[.json]?name= /params/reset?name=[&fallback=1] /profile/detect " +
                        "/table /table/dump /table/status /table.json /table/stop /table/save[&partial=1] /table/files /table/load?name= " +
                        "(POST /params/load?name=) /paramsets.json /paramset/load?id= /paramgroups.json " +
                        "/capstart?port= /capstop /capstatus /capframes?since= " +
                        "/send?port=&hex=&read= /cap?port=&ms= /probe?port=&hex=&ms= " +
                        "/duss/scan /duss/probe[?peer=] /duss/version /duss/req[?hex=|set=&id=&recv=&type=&payload=][&dgram=0&pabs=0&bind=0&nc=0&peer=&src=&ms=&wantset=&wantid=] /duss/send?hex= " +
                        "/records /records.json /record?name= /records.zip " +
                        "/records/grant /records/forget /records/download /records/perm " +
                        "/movies /movies.json /movie?id=[&dl=1] /movies/grant /movies/perm /screen/perm  (UI at /)"
                else -> "unknown: $p"
            }
        } catch (e: Exception) { "error: ${e.message}" }
    }

    /** Send [wire] on one socket, then read the reply stream for [ms]; return all bytes as hex. */
    private fun probe(port: Int, wire: ByteArray, ms: Int): String {
        return try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 300
                s.getOutputStream().apply { write(wire); flush() }
                val end = System.currentTimeMillis() + ms
                val out = ArrayList<Byte>()
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    val r = try { s.getInputStream().read(buf) } catch (e: Exception) { -1 }
                    if (r < 0) break
                    for (i in 0 until r) out.add(buf[i])
                }
                out.toByteArray().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) { "probe error: ${e.message}" }
    }

    /** Connect to 127.0.0.1:port, read for ms, return captured bytes as hex. */
    private fun capture(port: Int, ms: Int): String {
        return try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 300
                val end = System.currentTimeMillis() + ms
                val out = ArrayList<Byte>()
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    val r = try { s.getInputStream().read(buf) } catch (e: Exception) { -1 }
                    if (r < 0) break
                    for (i in 0 until r) out.add(buf[i])
                }
                out.toByteArray().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) { "cap error: ${e.message}" }
    }

    /** Parse a query arg as decimal or `0x..` hex; null if absent/unparseable. */
    private fun intArg(s: String?): Int? {
        val t = s?.trim() ?: return null
        return if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2).toIntOrNull(16) else t.toIntOrNull()
    }

    /**
     * Generic DUSS transaction from the web console. Either pass a full wire frame
     * as `hex=…`, or the DUML fields (`set,id,recv,type,payload` — each decimal or
     * `0x..`) and it is built here with correct CRCs. The report's [inference]
     * points are all query-flippable: `dgram=0` for SOCK_STREAM, `pabs=1` to
     * connect the peer in the abstract namespace, `bind=0` to skip the source bind,
     * `peer=`/`src=` to try other mailbox names, `ms=` the read window,
     * `wantset=`/`wantid=` to match a specific reply out of telemetry.
     */
    private fun dussReq(path: String): String {
        val hexArg = query(path, "hex")
        val wire = if (hexArg != null)
            runCatching { DumlWire.hex(hexArg) }.getOrElse { return "bad hex: ${it.message}" }
        else DumlNative.nativeBuildFrame(
            intArg(query(path, "sender")) ?: DussBus.SRC_ADDR,   // 0x1e so replies route home
            intArg(query(path, "recv")) ?: 0,
            intArg(query(path, "type")) ?: DumlWire.CT_ACK_BEFORE,
            intArg(query(path, "set")) ?: 0x00,
            intArg(query(path, "id")) ?: 0x01,
            query(path, "payload")?.let { runCatching { DumlWire.hex(it) }.getOrNull() } ?: ByteArray(0))
        return DussBus.xact(
            wire,
            dgram = query(path, "dgram") != "0",
            peerAbstract = query(path, "pabs") != "0",   // hardware: mailbox is abstract
            bindSource = query(path, "bind") != "0",
            noConnect = query(path, "nc") != "0",         // hardware: connected recv is silent
            peer = query(path, "peer") ?: DussBus.PEER,
            source = query(path, "src") ?: DussBus.SOURCE,
            readMs = query(path, "ms")?.toIntOrNull()?.coerceIn(0, MAX_WINDOW_MS) ?: DussBus.READ_MS,
            wantSet = intArg(query(path, "wantset")) ?: -1,
            wantId = intArg(query(path, "wantid")) ?: -1,
            tag = "duss/req")
    }

    // Self-contained troubleshooting page served at / (open it from a PC browser).
    // No '$' anywhere (Kotlin raw string), JS uses string concatenation only.
    private val UI_HTML = """
<!doctype html><html><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>DJI_FCC_GPSOFF diag</title>
<style>
 body{background:#12141c;color:#eceef3;font-family:system-ui,Segoe UI,Roboto,sans-serif;margin:0;padding:12px}
 h1{font-size:17px;margin:0 0 4px} .sub{color:#8a93a6;font-size:12px;margin:0 0 12px}
 .g{display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:8px;margin-bottom:10px}
 button{border:0;border-radius:12px;padding:12px;color:#fff;font-size:14px;font-weight:600;cursor:pointer}
 .b{background:#4c6fff}.g1{background:#22c993}.r{background:#ff6b6b}.a{background:#f5a623}.s{background:#2a3042}
 .nav{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:12px}
 .nav button{background:#1a1e2b;color:#cfd3dd;padding:9px 12px;font-size:13px;border-radius:10px}
 .nav button.on{background:#4c6fff;color:#fff}
 .page{display:none}.page.on{display:block}
 .card{background:#0c0e15;border:1px solid #222839;border-radius:12px;padding:10px 12px;margin-bottom:10px}
 .card h2{font-size:13px;margin:0 0 8px;color:#cfd3dd;font-weight:600}
 .st{font-family:monospace;font-size:12px;color:#8a93a6;margin-top:2px}
 .st b{color:#eceef3}
 .pbar{display:flex;justify-content:space-between;align-items:center;margin-bottom:6px}
 .pbar span{color:#8a93a6;font-size:12px;font-family:monospace}
 .x{background:#2a3042;padding:6px 11px;font-size:12px;border-radius:8px}
 #out{background:#0c0e15;border-radius:10px;padding:8px 10px;font-family:monospace;font-size:12px;white-space:pre-wrap;min-height:22px;margin-bottom:10px}
 .row{display:flex;gap:8px;margin-bottom:10px}
 input{flex:1;background:#0c0e15;color:#eceef3;border:1px solid #2a3042;border-radius:10px;padding:10px}
 .row select{flex:1;min-width:0;background:#0c0e15;color:#eceef3;border:1px solid #2a3042;border-radius:10px;padding:10px}
 #pgroup{flex:0 1 auto;max-width:46%}
 .hd{font-size:12px;color:#8a93a6;margin:6px 2px 4px}
 .hd a{margin-left:10px}
 .cnt{color:#8a93a6;font-size:11px;margin:0 2px 6px}
 .logwrap{background:#0c0e15;border-radius:10px;height:44vh;overflow:auto}
 table{border-collapse:collapse;width:100%;font-family:monospace;font-size:11px}
 thead th{position:sticky;top:0;background:#1a1e2b;text-align:left;padding:4px 6px;border-bottom:1px solid #2a3042}
 thead input,thead select{width:100%;box-sizing:border-box;background:#0c0e15;color:#eceef3;border:1px solid #2a3042;border-radius:6px;padding:3px 5px;font-size:11px}
 tbody td{padding:2px 6px;border-bottom:1px solid #161a26;vertical-align:top;word-break:break-all}
 td.c0{white-space:nowrap;color:#8a93a6}td.c2{white-space:nowrap;color:#9fb3ff}
 td.c1{white-space:nowrap;font-weight:600}
 tr.lTX td.c1{color:#f5a623}tr.lRX td.c1{color:#22c993}tr.lERR td.c1{color:#ff6b6b}tr.lWARN td.c1{color:#f5d76e}tr.lINFO td.c1{color:#8a93a6}
 #recs{margin-bottom:10px}
 #recs table{width:100%;border-collapse:collapse;font-size:12px}
 #recs td{padding:5px 6px;border-bottom:1px solid #222839;vertical-align:top}
 #recs td:nth-child(2),#recs td:nth-child(3),#recs td:nth-child(4){color:#8a93a6;white-space:nowrap;width:1%}
 #recs a{color:#7aa2ff;text-decoration:none}
 #movs{margin-bottom:10px}
 #movs table{width:100%;border-collapse:collapse;font-size:12px}
 #movs td{padding:5px 6px;border-bottom:1px solid #222839;vertical-align:top}
 #movs td:nth-child(n+2){color:#8a93a6;white-space:nowrap;width:1%}
 #movs a{color:#7aa2ff;text-decoration:none}
 #plist{margin-bottom:10px;max-height:60vh;overflow:auto}
 #plist table{width:100%;border-collapse:separate;border-spacing:0;font-size:12px}
 /* The header pins to the top of #plist (the scroll container), not the page —
    separate borders + a solid background so rows don't bleed through it. */
 #plist thead th{position:sticky;top:0;z-index:1;background:#1a1e2b;color:#8a93a6;
   text-align:left;white-space:nowrap;padding:6px;border-bottom:1px solid #2a3042;font-weight:600}
 #plist td{padding:4px 6px;border-bottom:1px solid #222839;vertical-align:middle;font-family:monospace}
 #plist td:nth-child(2),#plist td:nth-child(3),#plist td:nth-child(4){color:#8a93a6;white-space:nowrap}
 #plist tr.ro td{color:#5b6478}
 #plist td.rot{color:#5b6478;font-style:italic;white-space:nowrap}
 /* Current value: grey while it is only the catalog's number, green once it has
    actually been read off the aircraft. */
 #plist td.cur{color:#5b6478;white-space:nowrap}
 #plist td.cur.live{color:#22c993}
 #plist a{color:#7aa2ff;text-decoration:none;margin-right:8px}
 #plist input{width:96px;padding:4px 6px;font-family:monospace}
 .chk{display:flex;align-items:center;gap:7px;color:#8a93a6;font-size:12px;margin:-4px 2px 10px}
 .chk input{flex:0 0 auto;width:auto;padding:0}
 .em{color:#f5a623;font-size:12px;line-height:1.5}
 .cap{background:#0c0e15;border:1px solid #2a3042;border-radius:10px;padding:8px 10px;margin-bottom:10px}
 .caphd{font-size:12px;color:#f5a623;margin-bottom:8px}
 .capstat{font-family:monospace;font-size:11px;color:#8a93a6;margin-top:4px}
 .capframes{font-family:monospace;font-size:11px;margin-top:6px;max-height:20vh;overflow:auto}
 .cf{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;padding:1px 0}
 .cf.aux{color:#ff9f43}.cf.main{color:#7aa2ff}
 .snbar{background:#0c0e15;border:1px solid #2a3042;border-radius:10px;padding:9px 11px;font-family:monospace;font-size:13px;color:#eceef3;margin-bottom:10px}
 .snbar b{color:#22c993;letter-spacing:.5px}
 .snhint{color:#8a93a6;font-size:11px}
</style></head><body>
<h1>&#9889; DJI_FCC_GPSOFF</h1>
<div id=sn class=snbar>drone serial: &mdash;</div>
<div id=fg class=snbar>aircraft: &mdash;</div>
<div class=nav>
 <button id=nav-main class=on onclick="tab('main')">Main</button>
 <button id=nav-params onclick="tab('params')">Parameters</button>
 <button id=nav-logs onclick="tab('logs')">Logs &amp; send</button>
 <button id=nav-capture onclick="tab('capture')">Capture</button>
 <button id=nav-media onclick="tab('media')">Screens / video</button>
 <button id=nav-records onclick="tab('records')">Flight logs</button>
</div>
<div id=out>ready</div>

<div id=page-main class="page on">
 <div class=card><h2>&#128225; Radio / FCC</h2>
  <div class=g>
   <button class=g1 onclick="go('/fcc')">Apply FCC</button>
   <button class="b rd" onclick="go('/country')">Check country</button>
  </div>
  <div class=st id=st-radio>&mdash;</div>
 </div>
 <div class=card><h2>&#128760; Aircraft</h2>
  <div class=g>
   <button class=g1 onclick="go('/ledon')">LED on</button>
   <button class=s onclick="go('/ledoff')">LED off</button>
   <button class=g1 onclick="go('/gpson')">GPS on</button>
   <button class=s onclick="go('/gpsoff')">GPS off</button>
   <button class="b rd" onclick="readState()">Read state</button>
  </div>
  <div class=st id=st-state>state: &mdash;</div>
 </div>
 <div class=card><h2>&#128241; Device &amp; profile</h2>
  <div class=g>
   <button class=b id=pf-lito onclick="setProfile(1)">Profile: Lito</button>
   <button class=s id=pf-other onclick="setProfile(0)">Profile: other</button>
   <button class=s onclick="go('/identity/forget')">Forget drone</button>
   <button class=s onclick="go('/a11y')">Enable a11y</button>
  </div>
  <div class=st id=st-device>&mdash;</div>
 </div>
 <div class=card><h2>&#128736; Services</h2>
  <div class=g>
   <button class=a onclick="go('/keepon?mode=home_point')">Keepalive: home point</button>
   <button class=a onclick="go('/keepon?mode=periodic')">Keepalive: 5&#8201;s check</button>
   <button class=s onclick="go('/keepoff')">Keepalive off</button>
   <button class=g1 onclick="go('/overlayon')">Overlay on</button>
   <button class=s onclick="go('/overlayoff')">Overlay off</button>
  </div>
  <div class=hd>Auto-start when the app launches</div>
  <div class=g>
   <button class=b id=au-ka onclick="setAuto('ka')">Keepalive: &mdash;</button>
   <button class=b id=au-ov onclick="setAuto('ov')">Overlay: &mdash;</button>
   <button class=b id=au-diag onclick="setAuto('diag')">Diag: &mdash;</button>
  </div>
  <div class=st id=st-svc>&mdash;</div>
 </div>
</div>

<div id=page-params class=page>
 <div class=card><h2>&#9881; Parameters</h2>
  <div class=em id=pbackup>&#9888; Before changing parameters, back them up with the desktop app (DJI Assistant 2) over USB &mdash; so the originals can be restored if something goes wrong.</div>
  <div class=cnt>Load a bundled set or a DJI Param Studio .dhp / .dhv2params export, then read/write by name (writes go to 40008).</div>
  <div class=row><select id=pset></select><button class=g1 onclick="loadSet()">Load set</button></div>
  <div class=row><input type=file id=pfile accept=".dhp,.dhv2params,.json,application/json,text/plain"><button class=b onclick="loadParamsFile()">Load file</button></div>
  <div class=cap>
   <div class=caphd>&#128225; Read the catalog off the AIRCRAFT (03:E1)</div>
   <div class=cnt>For a drone no bundled set covers &mdash; or to check a set against the firmware in front of you.
    Walks every slot in the table by index, roughly 2 minutes for 1594. It uses DJI Fly&rsquo;s port, so
    <b>stop DJI Fly first</b>: with Fly running the windows miss and it needs extra passes.</div>
   <div class=row>
    <button class=g1 id=dumpbtn onclick="dumpStart()">Read from aircraft</button>
    <button class=s onclick="go('/table')">Table fingerprint</button>
    <button class=r onclick="dumpStop()">Stop</button>
   </div>
   <div id=dumpstat class=capstat>not running</div>
   <div class=row>
    <button class=b id=dumpsave onclick="dumpSave(0)">Save &amp; use as catalog</button>
    <button class=s onclick="dumpFiles()">Saved dumps</button>
   </div>
   <div id=dumpfiles class=capstat></div>
  </div>
  <div class=row><input id=pq placeholder="search parameter name" oninput="pqTyped()"><select id=pgroup onchange="loadParams()" title="filter by parameter group"><option value="">All groups</option></select><button class=s onclick="loadParams()">Search</button></div>
  <label class=chk><input type=checkbox id=pedit onchange="loadGroups();loadParams()"> editable only &mdash; hides entries with no room to move (min = max)</label>
  <div id=pghint class=cnt></div>
  <div id=plist></div>
 </div>
</div>

<div id=page-logs class=page>
 <div class=card><h2>&#128228; Send a raw DUML frame</h2>
  <div class=row><input id=port placeholder="port (40008 / 40009)"><input id=hex placeholder="raw frame hex 55..."><button class=b onclick="snd()">Send</button></div>
  <div class=row><input id=pbhex placeholder="probe hex"><input id=pbport placeholder="port 40007"><input id=pbms placeholder="ms 1000"><button class=a onclick="probe()">Probe (full reply)</button></div>
 </div>
 <div class=hd>Log &mdash; per-column filters<a href="#" onclick="clearLog();return false">clear</a></div>
 <div class=cnt id=cnt>0 / 0</div>
 <div class=logwrap><table>
  <thead>
   <tr><th>time</th><th>level</th><th>port</th><th>message</th></tr>
   <tr>
    <th><input id=ft oninput="render()" placeholder="time"></th>
    <th><select id=fl onchange="render()"><option value="">all</option><option>TX</option><option>RX</option><option>INFO</option><option>WARN</option><option>ERR</option></select></th>
    <th><input id=fp oninput="render()" placeholder="port"></th>
    <th><input id=fm oninput="render()" placeholder="text / hex"></th>
   </tr>
  </thead>
  <tbody id=lb></tbody>
 </table></div>
</div>

<div id=page-capture class=page>
 <div class=cap>
  <div class=caphd>&#128225; Live hijack read &mdash; a second reader on DJI Fly&rsquo;s port. Records main + aux frames; the browser builds the .pcap. Intrusive on 40007.</div>
  <div class=row>
   <input id=capport placeholder="aux port" value="40007">
   <button id=capbtn class=g1 onclick="capToggle()">Enable capture</button>
   <button class=b onclick="capDownload()">Download .pcap</button>
   <button class=s onclick="capClear()">Clear</button>
  </div>
  <div id=capstat class=capstat>capture off</div>
  <div id=capframes class=capframes></div>
 </div>
 <div class=cap>
  <div class=caphd>&#128268; DUSS firmware bus &mdash; /duss/mb/ mailbox (experimental, RC firmware only)</div>
  <div class=cnt>The controller&rsquo;s own internal DUML bus, independent of DJI Fly&rsquo;s TCP ports. This checks whether an ordinary app may reach the router mailbox <b>0x205</b>. Run them in order: <b>Scan</b> shows which /duss sockets the kernel has, <b>Probe</b> tries connecting each way, <b>Version</b> sends a harmless VersionInquiry and waits for a reply.</div>
  <div class=g>
   <button class=b onclick="go('/duss/scan')">Scan sockets</button>
   <button class=b onclick="go('/duss/probe')">Probe 0x205</button>
   <button class=g1 onclick="go('/duss/version')">VersionInquiry (safe)</button>
  </div>
  <div class=row>
   <input id=dusshex placeholder="raw frame hex 55… (blank = VersionInquiry)">
   <button class=a onclick="dussReq()">Send / request</button>
  </div>
  <div class=cnt>Defaults (from RC hardware): DGRAM, abstract peer, source bound, sendto+recvfrom. URL options (append &amp;key=val): <b>dgram=0</b> STREAM &middot; <b>pabs=0</b> pathname peer &middot; <b>bind=0</b> no source &middot; <b>nc=0</b> connect+recv &middot; <b>ms=</b> window &middot; <b>peer=</b>/<b>src=</b> other names &middot; <b>set=/id=/recv=/type=/payload=</b> build a frame &middot; <b>wantset=/wantid=</b> match a reply.</div>
 </div>
</div>

<div id=page-media class=page>
 <div class=g>
  <button class=b onclick="shot()">&#128247; Screenshot</button>
  <button class=b onclick="loadMovs()">&#127909; Screen recordings</button>
 </div>
 <div id=shotwrap style="display:none;margin-top:10px">
  <div class=pbar><span>&#128247; screenshot</span><button class=x onclick="closeShot()">&#10005; close</button></div>
  <img id=shotimg style="width:100%;max-height:70vh;object-fit:contain;background:#000;border-radius:10px" alt="screen">
  <div id=shotnow class=cnt></div>
 </div>
 <div id=movwrap style="display:none;margin-top:10px">
  <div class=pbar><span>&#127909; video</span><button class=x onclick="closeMov()">&#10005; close</button></div>
  <video id=movplayer controls playsinline style="width:100%;max-height:52vh;background:#000;border-radius:10px"></video>
  <div id=movnow class=cnt></div>
 </div>
 <div id=movs></div>
</div>

<div id=page-records class=page>
 <div class=g><button class=b onclick="loadRecs()">&#128194; Load flight logs</button></div>
 <div id=recs></div>
</div>
<script>
 var LOG=[];
 // Returns the promise so a caller can chain (the params editor refreshes a row
 // once the write has reported).
 function go(p){document.getElementById('out').textContent=p+' ...';
  return fetch(p).then(function(r){return r.text()}).then(function(t){document.getElementById('out').textContent=t});}
 function sz(n){if(n<1024)return n+' B';if(n<1048576)return (n/1024).toFixed(1)+' KB';return (n/1048576).toFixed(1)+' MB';}
 function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}
 var TAB='main';var APPSTATE={};
 function tab(name){TAB=name;
  var ps=document.getElementsByClassName('page');for(var i=0;i<ps.length;i++)ps[i].className='page';
  document.getElementById('page-'+name).className='page on';
  var ns=document.querySelectorAll('.nav button');for(var i=0;i<ns.length;i++)ns[i].className='';
  document.getElementById('nav-'+name).className='on';
  // Media / flight-log lists load on their own buttons (which auto-request access
  // on the RC) — not on a bare tab switch, so browsing tabs never pops a prompt.
  if(name=='logs')poll();
  else if(name=='main')pollMain();
  else if(name=='capture')capPoll();
  else if(name=='params'){loadSets();loadGroups();loadParams();dumpPoll();}
 }
 function pollMain(){
  fetch('/appstate').then(function(r){return r.json()}).then(function(s){APPSTATE=s;
   document.getElementById('pf-lito').className=s.lito?'g1':'s';
   document.getElementById('pf-other').className=s.lito?'s':'g1';
   function ab(id,on,lbl){var b=document.getElementById(id);if(!b)return;b.textContent=lbl+': '+(on?'ON':'off');b.className=on?'g1':'b';}
   ab('au-ka',s.autoKeepalive,'Keepalive');ab('au-ov',s.autoOverlay,'Overlay');ab('au-diag',s.autoDiag,'Diag');
   document.getElementById('st-device').innerHTML='profile: <b>'+(s.lito?'Lito X1':'other DJI')+'</b>';
   document.getElementById('st-svc').innerHTML='keepalive <b>'+(s.keepaliveRunning?('on ('+esc(s.keepaliveActiveMode||'')+')'):'off')+
    '</b> &middot; overlay <b>'+(s.overlayRunning?'on':'off')+'</b> &middot; a11y <b>'+(s.a11y?'on':'off')+'</b>';
  }).catch(function(){});
  Promise.all([
   fetch('/link').then(function(r){return r.json()}).catch(function(){return null}),
   fetch('/homepoint').then(function(r){return r.json()}).catch(function(){return null})
  ]).then(function(a){var lk=a[0]||{},hp=a[1]||{};
   document.getElementById('st-radio').innerHTML='link <b>'+(lk.connected?'connected':'no telemetry')+
    '</b> &middot; home point <b>'+(hp.recorded?'recorded':'no')+'</b> &middot; frames '+(lk.frames||0);
  }).catch(function(){});
 }
 function setProfile(l){go('/profile?lito='+l);setTimeout(pollMain,300);}
 function setAuto(w){var cur=w=='ka'?APPSTATE.autoKeepalive:(w=='ov'?APPSTATE.autoOverlay:APPSTATE.autoDiag);
  go('/setauto?'+w+'='+(cur?'0':'1'));setTimeout(pollMain,300);}
 function readState(){var e=document.getElementById('st-state');e.textContent='reading…';
  fetch('/state').then(function(r){return r.json()}).then(function(s){
   function b(v){return v===true?'on':(v===false?'off':'?');}
   e.innerHTML='LED <b>'+b(s.led)+'</b> &middot; GPS <b>'+b(s.gps)+'</b> &middot; mode <b>'+(s.cine===true?'Cine':(s.cine===false?'ATTI':'?'))+
    '</b> &middot; '+(s.connected?'drone connected':'no read (Fly active / no drone)');
  }).catch(function(x){e.textContent='state error: '+x;});}
 function probe(){var hx=document.getElementById('pbhex').value;if(!hx){document.getElementById('out').textContent='enter hex to probe';return;}
  var po=document.getElementById('pbport').value||'40007';var ms=document.getElementById('pbms').value||'1000';
  go('/probe?port='+po+'&hex='+encodeURIComponent(hx)+'&ms='+ms);}
 function dussReq(){var hx=document.getElementById('dusshex').value.trim();
  go('/duss/req'+(hx?('?hex='+encodeURIComponent(hx)):''));}
 function clearLog(){go('/clear');LOG=[];render();}
 // Check a folder/media permission and, if it is NOT granted, auto-open the grant
 // prompt on the RC screen (don't wait for the user to click a button). onGranted
 // runs when access is present; onDenied after the prompt was opened.
 function ensurePerm(permPath,grantPath,onGranted,onDenied){
  fetch(permPath).then(function(r){return r.text()}).then(function(t){
   if(t.indexOf('granted')>=0){onGranted();}else{go(grantPath);onDenied();}
  }).catch(function(){onGranted();});}   // probe failed — just try, worst case it's empty
 function hideLink(id){return '<a href="#" onclick="document.getElementById(\''+id+'\').innerHTML=\'\';return false">hide</a>';}
 function recsHdr(n){return '<div class=hd>DJI Fly flight records'+(n>=0?' &mdash; '+n+' file(s)':'')+
   '<a href="/records.zip">download all (.zip)</a>'+
   '<a href="#" onclick="go(\'/records/grant\');return false">grant access on RC</a>'+
   '<a href="#" onclick="go(\'/records/download\');return false">copy to Downloads</a>'+
   '<a href="/records">why empty?</a>'+hideLink('recs')+'</div>';}
 function loadRecs(){
  var e=document.getElementById('recs');e.innerHTML='<div class=hd>reading flight records&hellip;</div>';
  ensurePerm('/records/perm','/records/grant',function(){
   fetch('/records.json').then(function(r){return r.json()}).then(function(a){
    var h=recsHdr(a.length);
    if(!a.length){h+='<div class=em>Nothing visible. On Android 11 another app&rsquo;s Android/data is hidden &mdash;'+
     ' tap &laquo;grant access on RC&raquo;, then pick Android/data/dji.go.v5/files/FlightRecord in the picker on the controller screen.</div>';}
    else{h+='<table>';
     for(var i=0;i<a.length;i++){var r=a[i];
      h+='<tr><td><a href="/record?name='+encodeURIComponent(r.n)+'">'+esc(r.n)+'</a></td><td>'+sz(r.s)+
         '</td><td>'+new Date(r.m).toLocaleString()+'</td><td>'+esc(r.src)+'</td></tr>';}
     h+='</table>';}
    e.innerHTML=h;
   }).catch(function(x){e.innerHTML='<div class=em>records error: '+x+'</div>'});
  },function(){
   e.innerHTML=recsHdr(-1)+'<div class=em>Access not granted &mdash; a folder picker was opened on the RC screen. '+
    'Pick Android/data/dji.go.v5/files/FlightRecord there, then tap Flight logs again.</div>';});}
 var MOVS=[];
 function dur(ms){if(!ms)return '';var s=Math.round(ms/1000);var m=Math.floor(s/60);s=s%60;return m+':'+(s<10?'0':'')+s;}
 function movsHdr(n){return '<div class=hd>Screen recordings'+(n>=0?' &mdash; '+n+' file(s)':'')+
   '<a href="#" onclick="loadMovs();return false">refresh</a>'+
   '<a href="#" onclick="go(\'/movies/grant\');return false">grant access on RC</a>'+
   '<a href="/movies">why empty?</a>'+hideLink('movs')+'</div>';}
 function loadMovs(){
  var e=document.getElementById('movs');e.innerHTML='<div class=hd>reading screen recordings&hellip;</div>';
  ensurePerm('/movies/perm','/movies/grant',function(){
   fetch('/movies.json').then(function(r){return r.json()}).then(function(a){
    MOVS=a;
    var h=movsHdr(a.length);
    if(!a.length){h+='<div class=em>Nothing visible. Allow media access on the RC (or tap &laquo;grant access on RC&raquo;), '+
     'then refresh. Internal &laquo;Movies&raquo; and the SD card&rsquo;s &laquo;Movies&raquo; are both scanned.</div>';}
    else{h+='<table>';
     for(var i=0;i<a.length;i++){var r=a[i];
      h+='<tr><td><a href="#" onclick="playMov('+i+');return false">&#9654; '+esc(r.n)+'</a></td>'+
         '<td>'+sz(r.s)+'</td><td>'+dur(r.dur)+'</td><td>'+new Date(r.m).toLocaleString()+'</td><td>'+esc(r.src)+
         '</td><td><a href="/movie?id='+encodeURIComponent(r.id)+'&dl=1">save</a></td></tr>';}
     h+='</table>';}
    e.innerHTML=h;
   }).catch(function(x){e.innerHTML='<div class=em>movies error: '+x+'</div>'});
  },function(){
   e.innerHTML=movsHdr(-1)+'<div class=em>Media access not granted &mdash; a permission prompt was opened on the RC screen. '+
    'Allow it, then tap Screen recordings again.</div>';});}
 function playMov(i){var r=MOVS[i];if(!r)return;
  var w=document.getElementById('movwrap');var v=document.getElementById('movplayer');
  w.style.display='block';document.getElementById('movnow').textContent='playing: '+r.n;
  v.src='/movie?id='+encodeURIComponent(r.id);v.play().catch(function(){});
  w.scrollIntoView({behavior:'smooth',block:'nearest'});}
 function closeMov(){var v=document.getElementById('movplayer');
  try{v.pause();}catch(e){}v.removeAttribute('src');v.load();   // stop the stream / spinner
  document.getElementById('movnow').textContent='';
  document.getElementById('movwrap').style.display='none';}
 function closeShot(){var i=document.getElementById('shotimg');
  i.removeAttribute('src');document.getElementById('shotnow').textContent='';
  document.getElementById('shotwrap').style.display='none';}
 var PARAMS=[];
 // Repeated in every write/reset confirm — the same just-in-time reminder the
 // in-app editor injects into its confirm dialog, so a user who scrolled past the
 // top banner still sees it at the moment a value is committed to the FC.
 var PBACKUP='⚠ Back up your parameters with the desktop app (DJI Assistant 2) over USB first — so the originals can be restored if something goes wrong.';
 // A loaded catalog changes which groups exist, so a fresh load resets the group
 // filter and repopulates the dropdown before re-rendering the rows.
 function afterCatalogLoad(){document.getElementById('pgroup').value='';loadGroups();loadParams();}
 function loadParamsFile(){var f=document.getElementById('pfile').files[0];
  if(!f){document.getElementById('out').textContent='choose a .dhp/.dhv2params file first';return;}
  document.getElementById('out').textContent='uploading '+f.name+'…';
  var fr=new FileReader();
  fr.onload=function(){fetch('/params/load?name='+encodeURIComponent(f.name),{method:'POST',body:fr.result})
    .then(function(r){return r.text()}).then(function(t){document.getElementById('out').textContent=t;afterCatalogLoad();});};
  fr.onerror=function(){document.getElementById('out').textContent='could not read the file';};
  fr.readAsText(f);}
 // ---- read the catalog off the aircraft (03:E0 sizing + 03:E1 walk) ----
 var DUMPTIMER=0;var DUMPSEEN=false;
 function dumpStart(){
  if(!confirm('Read every parameter from the aircraft?\n\nThis walks all table slots over DJI Fly\'s port and takes about two minutes.\nStop DJI Fly first for a clean run — with Fly running it needs extra passes.'))return;
  DUMPSEEN=false;
  document.getElementById('dumpstat').textContent='starting — sizing the table with 03:E0…';
  // Start polling only AFTER the server has answered. Polling straight away raced the
  // request: the first status arrived while /table/dump was still in flight, reported
  // running:false, and the "finished" branch below killed the poller — the readout then
  // sat at zeros for the whole run.
  go('/table/dump').then(function(){
   clearInterval(DUMPTIMER);DUMPTIMER=setInterval(dumpPoll,1500);dumpPoll();});}
 function dumpStop(){go('/table/stop');clearInterval(DUMPTIMER);DUMPTIMER=0;setTimeout(dumpPoll,400);}
 function dumpPoll(){
  fetch('/table/status').then(function(r){return r.json()}).then(function(s){
   var pct=s.total?Math.floor(100*s.resolved/s.total):0;
   document.getElementById('dumpstat').textContent=
    (s.running?'READING ':'')+s.resolved+'/'+s.total+' ('+pct+'%) | named '+s.named+
    ' | empty '+s.empty+' | unread '+s.unknown+' | pass '+s.pass+' | '+Math.round(s.elapsedMs/1000)+'s | '+s.note;
   var b=document.getElementById('dumpbtn');
   b.textContent=s.running?'Reading…':'Read from aircraft';b.disabled=!!s.running;
   var sv=document.getElementById('dumpsave');
   // "unread" is not "empty" — a slot that never answered is unknown, and saving it as a
   // complete catalog would hide the gap. Make the button say so.
   sv.textContent=(s.unknown>0&&!s.running&&s.named>0)?('Save anyway ('+s.unknown+' unread)'):'Save & use as catalog';
   if(s.running)DUMPSEEN=true;
   // Only stop once the run has actually been observed running, so a status fetched
   // before it got going can never end the polling.
   if(!s.running&&DUMPSEEN&&DUMPTIMER){clearInterval(DUMPTIMER);DUMPTIMER=0;}
  }).catch(function(){});}
 function dumpSave(){
  fetch('/table/status').then(function(r){return r.json()}).then(function(s){
   var q=(s.unknown>0)?'?partial=1':'';
   if(s.unknown>0&&!confirm(s.unknown+' slot(s) never answered. Save an incomplete catalog?\n\nThe gap is recorded in the file name.'))return;
   go('/table/save'+q).then(afterCatalogLoad);});}
 function dumpFiles(){
  fetch('/table/files').then(function(r){return r.text()}).then(function(t){
   var e=document.getElementById('dumpfiles');
   if(t.indexOf('no saved dumps')>=0){e.textContent=t;return;}
   var ls=t.split('\n'),h='';
   for(var i=0;i<ls.length;i++){if(!ls[i].trim())continue;
    var nm=ls[i].split('  ')[0];
    h+='<div><a href="#" onclick="dumpLoad(\''+nm+'\');return false">load</a> &nbsp;'+esc(ls[i])+'</div>';}
   e.innerHTML=h;});}
 function dumpLoad(n){go('/table/load?name='+encodeURIComponent(n)).then(afterCatalogLoad);}
 // ---- bundled parameter sets (shipped in the APK) ----
 function loadSets(){var sel=document.getElementById('pset');if(sel.options.length>1)return;   // once is enough
  fetch('/paramsets.json').then(function(r){return r.json()}).then(function(a){
   var h='<option value="">&#128230; Load a bundled set…</option>';
   for(var i=0;i<a.length;i++){var s=a[i];
    h+='<option value="'+s.id+'">'+esc(s.label)+' &middot; '+s.count+(s.common?' common':(' params'+(s.unique?' &middot; '+s.unique+' uniq':'')))+'</option>';}
   sel.innerHTML=h;
  }).catch(function(){});}
 function loadSet(){var id=document.getElementById('pset').value;
  if(!id){document.getElementById('out').textContent='choose a bundled set first';return;}
  document.getElementById('out').textContent='loading set…';
  fetch('/paramset/load?id='+encodeURIComponent(id)).then(function(r){return r.text()})
   .then(function(t){document.getElementById('out').textContent=t;afterCatalogLoad();})
   .catch(function(x){document.getElementById('out').textContent='set load failed: '+x;});}
 // ---- semantic group filter (populated from the loaded catalog) ----
 var PGROUPS={};
 function loadGroups(){var ed=document.getElementById('pedit').checked;
  fetch('/paramgroups.json'+(ed?'?edit=1':'')).then(function(r){return r.json()}).then(function(gs){
   var sel=document.getElementById('pgroup');var cur=sel.value;PGROUPS={};
   var h='<option value="">All groups</option>';
   for(var i=0;i<gs.length;i++){var g=gs[i];PGROUPS[g.id]=g;
    h+='<option value="'+g.id+'">'+esc(g.label)+' &middot; '+g.n+'</option>';}
   sel.innerHTML=h;sel.value=cur;if(sel.value!==cur)sel.value='';   // keep the pick if it still exists
   pghint();
  }).catch(function(){});}
 function pghint(){var g=PGROUPS[document.getElementById('pgroup').value];
  document.getElementById('pghint').textContent=g?g.desc:'';}
 // The whole catalog renders at once (~950 rows), so re-rendering on every
 // keystroke would stutter — coalesce typing into one fetch.
 var PQ_TIMER=0;
 function pqTyped(){clearTimeout(PQ_TIMER);PQ_TIMER=setTimeout(loadParams,180);}
 // lim is the row cap: undefined = every match (the server default), N = first N.
 function loadParams(lim){var e=document.getElementById('plist');
  var q=document.getElementById('pq').value||'';
  var ed=document.getElementById('pedit').checked;
  var gr=document.getElementById('pgroup').value||'';pghint();
  fetch('/params.json?q='+encodeURIComponent(q)+(ed?'&edit=1':'')+(gr?'&group='+encodeURIComponent(gr):'')+(lim===undefined?'':'&limit='+lim))
   .then(function(r){return r.json()}).then(function(d){
   PARAMS=d.params||[];
   var m=(d.matched===undefined?d.shown:d.matched);
   var h='<div class=hd>Parameters'+(d.src?' &mdash; '+esc(d.src):'')+' &middot; '+d.total+' loaded'+
     (d.locked?' ('+d.locked+' read-only)':'')+' &middot; showing '+d.shown+
     (d.shown<m?' of '+m+' matching <a href="#" onclick="loadParams(0);return false">show all</a>':'')+hideLink('plist')+'</div>';
   if(!d.total){h+='<div class=em>No catalog loaded. Pick a bundled set and tap Load set, or choose a .dhp / .dhv2params export and tap Load file.</div>';e.innerHTML=h;return;}
   h+='<table><thead><tr><th>Name</th><th>Type</th><th>Range</th><th>Default</th>'+
      '<th>Current</th><th>New value</th><th>Actions</th></tr></thead><tbody>';
   for(var i=0;i<PARAMS.length;i++){var p=PARAMS[i];
    var rng=(p.min===''&&p.max==='')?'':(p.min+' … '+p.max);
    // "Current" starts as the catalog's own value and stays grey — that number came
    // out of the export, not off the aircraft. Only a live read turns it solid.
    h+='<tr'+(p.ro?' class=ro':'')+'><td>'+esc(p.name)+'</td><td>'+esc(p.tname||'')+'</td>'+
       '<td>'+esc(rng)+'</td><td>'+esc(p.def)+'</td>'+
       '<td class=cur id="pc'+i+'" title="from the catalog file &mdash; not read from the aircraft">'+esc(p.value)+'</td>'+
       (p.ro
         ? '<td colspan=2 class=rot>read-only (no range)</td>'
         : '<td><input id="pv'+i+'" value="'+esc(p.value)+'"></td>'+
           '<td><a href="#" onclick="pwrite('+i+');return false">write</a>'+
           '<a href="#" onclick="pread('+i+');return false">read</a>'+
           '<a href="#" onclick="preset('+i+');return false">reset</a></td>')+
       '</tr>';}
   h+='</tbody></table>';e.innerHTML=h;
  }).catch(function(x){e.innerHTML='<div class=em>params error: '+x+'</div>'});}
 // Fill one row's Current cell from the aircraft. quiet=true keeps the write's own
 // (more detailed) report in the output box instead of overwriting it.
 function pread(i,quiet){var p=PARAMS[i];if(!p)return Promise.resolve();
  var c=document.getElementById('pc'+i);if(!c)return Promise.resolve();
  if(!quiet)document.getElementById('out').textContent='reading '+p.name+' ...';
  c.className='cur';c.textContent='reading…';
  return fetch('/params/read.json?name='+encodeURIComponent(p.name))
   .then(function(r){return r.json()}).then(function(d){
    if(d.value!==undefined){c.textContent=d.value;c.className='cur live';
     c.title='read from the aircraft: '+d.hex+' ('+d.bytes+' B)';
     if(!quiet)document.getElementById('out').textContent=p.name+' = '+d.value+'  ('+d.hex+', '+d.bytes+' B)';}
    else{c.textContent=p.value;c.className='cur';
     c.title='no answer — still the catalog value';
     if(!quiet)document.getElementById('out').textContent=p.name+': '+(d.error||'no answer');}
   }).catch(function(x){c.textContent=p.value;c.className='cur';c.title='read failed: '+x;});}
 function pwrite(i){var p=PARAMS[i];if(!p)return;var v=document.getElementById('pv'+i).value;
  if(!confirm('Write '+p.name+' = '+v+' ?  (decimal or 0x.. raw; writes to 40008)\n\nThe aircraft is read first — the reply pins the byte width and gives the "was" value.\n\n'+PBACKUP))return;
  go('/params/write?name='+encodeURIComponent(p.name)+'&value='+encodeURIComponent(v))
   .then(function(){return pread(i,true);});}
 // Reset writes the catalog's own default — the server re-reads it rather than
 // trusting the row, so an edited input box can't be sent as "the default".
 function preset(i){var p=PARAMS[i];if(!p)return;
  if(p.def===''){alert(p.name+' has no default in this catalog');return;}
  if(!confirm('Reset '+p.name+' to its catalog default '+p.def+' ?\n\nThis is a normal 03:F9 write-by-hash of the default value — the FC has no separate reset command, and the default comes from '+(document.getElementById('pfile').files[0]?document.getElementById('pfile').files[0].name:'the loaded catalog')+'.\n\n'+PBACKUP))return;
  go('/params/reset?name='+encodeURIComponent(p.name)).then(function(){return pread(i,true);});}
 function shot(){var i=document.getElementById('shotimg');var w=document.getElementById('shotwrap');
  w.style.display='block';w.scrollIntoView({behavior:'smooth',block:'nearest'});
  // Screenshots need the accessibility service; if it's off, auto-open the a11y
  // settings on the RC instead of just failing.
  ensurePerm('/screen/perm','/a11y',function(){
   document.getElementById('shotnow').textContent='capturing screen…';
   i.onload=function(){document.getElementById('shotnow').textContent='screen @ '+new Date().toLocaleTimeString();};
   i.onerror=function(){document.getElementById('shotnow').textContent='screenshot failed — enable a11y (Android 11+), then retry';};
   i.src='/screen?t='+Date.now();
  },function(){
   document.getElementById('shotnow').textContent='accessibility is off — a settings screen was opened on the RC; enable “DJI_FCC_GPSOFF — model & foreground”, then tap Screenshot again';});}
 function snd(){var po=document.getElementById('port').value||'40008';var hx=document.getElementById('hex').value;
  go('/send?port='+po+'&hex='+encodeURIComponent(hx)+'&read=200');}
 function splitPort(m){var x=m.match(/^p(\d+)\s+([\s\S]*)/);return x?[x[1],x[2]]:['',m];}
 function render(){
  var ft=document.getElementById('ft').value.toLowerCase();
  var fl=document.getElementById('fl').value;
  var fp=document.getElementById('fp').value.toLowerCase();
  var fm=document.getElementById('fm').value.toLowerCase();
  var rows=[];
  for(var i=0;i<LOG.length;i++){var e=LOG[i];var sp=splitPort(e.m);var po=sp[0];var msg=sp[1];
   if(fl&&e.l!==fl)continue;
   if(ft&&e.t.toLowerCase().indexOf(ft)<0)continue;
   if(fp&&po.toLowerCase().indexOf(fp)<0)continue;
   if(fm&&msg.toLowerCase().indexOf(fm)<0)continue;
   rows.push('<tr class=l'+e.l+'><td class=c0>'+e.t+'</td><td class=c1>'+e.l+'</td><td class=c2>'+esc(po)+'</td><td>'+esc(msg)+'</td></tr>');}
  var shown=rows;if(shown.length>800)shown=shown.slice(shown.length-800);
  document.getElementById('lb').innerHTML=shown.join('');
  document.getElementById('cnt').textContent=shown.length+' shown / '+LOG.length+' total';}
 function poll(){fetch('/logjson').then(function(r){return r.text()}).then(function(t){
  var w=document.querySelector('.logwrap');var at=w.scrollTop+w.clientHeight>=w.scrollHeight-30;
  try{LOG=JSON.parse(t)}catch(e){}render();if(at)w.scrollTop=w.scrollHeight;});}
 // ---- live hijack-read capture -> in-browser pcap ----
 var caps=[];var capCursor=0;var capOn=false;var capTimer=null;
 function capToggle(){
  var b=document.getElementById('capbtn');
  if(!capOn){var po=document.getElementById('capport').value||'40007';
   fetch('/capstart?port='+po).then(function(r){return r.text()}).then(function(t){
    document.getElementById('out').textContent=t;capOn=true;b.textContent='Disable capture';b.className='r';
    capPoll();});   // the global 1s poller (below) drives capframes once capOn is set
  }else{fetch('/capstop').then(function(r){return r.text()}).then(function(t){
    document.getElementById('out').textContent=t;capOn=false;b.textContent='Enable capture';b.className='g1';});}
 }
 function capPoll(){
  fetch('/capstatus').then(function(r){return r.json()}).then(function(s){
   document.getElementById('capstat').textContent=
    (s.capturing?'CAPTURING':'idle')+' | aux '+(s.auxRunning?('up:'+s.auxPort):'down')+
    ' | device buf '+s.buffered+' | pulled '+caps.length+' | dropped '+s.dropped;
  }).catch(function(){});
  if(!capOn)return;
  fetch('/capframes?since='+capCursor).then(function(r){return r.json()}).then(function(a){
   if(!a.length)return;
   for(var i=0;i<a.length;i++){caps.push({t:a[i].t,r:a[i].r,h:a[i].h});if(a[i].id>capCursor)capCursor=a[i].id;}
   renderCapTail();
  }).catch(function(){});
 }
 function renderCapTail(){
  var e=document.getElementById('capframes');var n=caps.length;var start=Math.max(0,n-14);var h='';
  for(var i=start;i<n;i++){var c=caps[i];
   h+='<div class="cf '+(c.r==1?'aux':'main')+'">'+(c.r==1?'aux ':'main ')+
      c.h.substring(0,56)+(c.h.length>56?'&hellip;':'')+'</div>';}
  e.innerHTML=h;
 }
 function capClear(){caps=[];capCursor=0;renderCapTail();
  document.getElementById('out').textContent='browser buffer cleared (device keeps capturing)';}
 function capDownload(){
  if(!caps.length){document.getElementById('out').textContent='no frames captured yet';return;}
  var total=24;for(var i=0;i<caps.length;i++)total+=16+caps[i].h.length/2;
  var buf=new ArrayBuffer(total);var dv=new DataView(buf);var o=0;
  dv.setUint32(0,0xa1b2c3d4,true);dv.setUint16(4,2,true);dv.setUint16(6,4,true);
  dv.setInt32(8,0,true);dv.setUint32(12,0,true);dv.setUint32(16,65535,true);dv.setUint32(20,147,true);o=24;
  for(var i=0;i<caps.length;i++){var c=caps[i];var len=c.h.length/2;
   var sec=Math.floor(c.t/1000000);var usec=c.t%1000000;
   dv.setUint32(o,sec,true);dv.setUint32(o+4,usec,true);dv.setUint32(o+8,len,true);dv.setUint32(o+12,len,true);o+=16;
   for(var j=0;j<len;j++)dv.setUint8(o+j,parseInt(c.h.substr(j*2,2),16));o+=len;}
  var blob=new Blob([buf],{type:'application/vnd.tcpdump.pcap'});
  var url=URL.createObjectURL(blob);var a=document.createElement('a');
  a.href=url;a.download='duml-hijack-'+Date.now()+'.pcap';a.click();URL.revokeObjectURL(url);
  document.getElementById('out').textContent='pcap saved: '+caps.length+' frames (LINKTYPE_USER0/147, raw DUML wire)';}
 // ---- passive drone serial from the hijack-read stream (refresh ~10s) ----
 function snPoll(){fetch('/serialwatch').then(function(r){return r.json()}).then(function(s){
  var e=document.getElementById('sn');if(!e)return;
  if(!s.serial){e.innerHTML='drone serial: &mdash; <span class=snhint>(enable capture &mdash; caught from DJI Fly&rsquo;s stream on 40007)</span>';return;}
  var age=Math.max(0,Math.floor(s.ageMs/1000));
  e.innerHTML='drone serial: <b>'+esc(s.serial)+'</b> <span class=snhint>&middot; via '+(s.route==1?'aux/hijack':'main')+' &middot; seen '+age+'s ago</span>';
 }).catch(function(){});}
 // ---- aircraft model + foreground gate (refresh ~3s) ----
 function fgPoll(){
  Promise.all([
   fetch('/foreground').then(function(r){return r.json()}).catch(function(){return null}),
   fetch('/identity').then(function(r){return r.json()}).catch(function(){return null})
  ]).then(function(a){
   var fgv=a[0]||{},id=a[1]||{};var e=document.getElementById('fg');if(!e)return;
   function caps(d){var c=[];if(d.allowAOA)c.push('AOA');if(d.canUseVPN)c.push('VPN');if(d.hasEndpoints)c.push('EP');return c.join('+')||'no AOA/VPN';}
   var d=id.drone,rc=id.rc,parts=[];
   if(d){parts.push('drone: <b>'+esc(d.name||d.code)+'</b> ['+esc(d.code)+'] <span class=snhint>&middot; '+
    caps(d)+' &middot; '+esc(String(d.license||''))+' &middot; via '+esc(String(d.source||''))+'</span>');}
   else{parts.push('drone: &mdash; <span class=snhint>(open DJI Fly so its screen name is read)</span>');}
   if(rc){parts.push('RC: <b>'+esc(rc.name||rc.code)+'</b> ['+esc(rc.code)+']');}
   var ac=parts.join(' &nbsp;&middot;&nbsp; ');
   var block=(fgv.readsAllowed===false);
   var fghint;
   if(!fgv.serviceOn){fghint='foreground: unknown <span class=snhint>(enable the accessibility service to gate reads)</span>';}
   else if(block){fghint='<b style="color:#ff6b6b">DJI Fly active &mdash; reads blocked</b>';}
   else {fghint='foreground: '+esc(String(fgv.label||''))+' <span class=snhint>&middot; reads allowed</span>';}
   e.innerHTML=ac+' &nbsp;<span class=snhint>&middot;</span>&nbsp; '+fghint;
   var rds=document.getElementsByClassName('rd');
   for(var i=0;i<rds.length;i++){rds[i].disabled=block;rds[i].style.opacity=block?'0.4':'1';
    rds[i].style.cursor=block?'not-allowed':'pointer';}
  }).catch(function(){});
 }
 snPoll();setInterval(snPoll,10000);
 fgPoll();setInterval(fgPoll,3000);
 pollMain();
 // Poll only what the active tab needs — no periodic 40007/40009 reads from here.
 setInterval(function(){if(TAB=='logs')poll();else if(TAB=='main')pollMain();else if(TAB=='capture')capPoll();},1500);
</script></body></html>
""".trimIndent()
}
