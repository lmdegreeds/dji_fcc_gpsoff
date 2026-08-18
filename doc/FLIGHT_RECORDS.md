# Feature spec — DJI Fly flight-record download

> Спека для переноса фичи в основную ветку проекта (другая папка). Описывает,
> что делает фича, почему реализована именно так, какие файлы трогает и на
> какие грабли уже наступили. Полный исходник модуля — в приложении A.

Implemented and compile-verified in `dji-fcc-gpsoff` (Gradle 8.9 / AGP, compileSdk 34,
minSdk 24, no AndroidX). Target device: **DJI RC 2, Android 11**.

---

## 1. What it does

Lets the operator pull DJI Fly's flight records off the controller **over Wi-Fi**, from the
app's existing HTTP diagnostics server, without USB and without stopping DJI Fly.

Source directory (this is what the feature is about):

```
/sdcard/Android/data/dji.go.v5/files/FlightRecord/FlightRecord_2026-08-09_[18-28-35].txt
```

Three surfaces:

**a) HTTP endpoints** on the diag server (port 8899 in this project):

| Endpoint | Response | Notes |
| --- | --- | --- |
| `GET /records[?dir=PATH]` | text | listing **plus** a per-route access report (what worked, what didn't) |
| `GET /records.json[?dir=PATH]` | JSON | `[{"n":name,"s":size,"m":modifiedMillis,"src":route}]` |
| `GET /record?name=N[&dir=PATH]` | file | `application/octet-stream` + `Content-Disposition: attachment` |
| `GET /records.zip[?dir=PATH]` | zip | every visible record in one archive |
| `GET /records/grant` | text | opens the SAF folder picker **on the controller screen** |
| `GET /records/forget` | text | drops the stored folder permission |
| `GET /records/download` | text | copies records to `Download/FlightRecord` for MTP pickup |

**b) A section on the served diagnostics page** — a “Flight logs” button that renders a table
(name / size / date / route) with per-file download links, plus “download all (.zip)”,
“grant access on RC”, “copy to Downloads”, “why empty?”.

**c) An in-app card** (“Flight records (DJI Fly)”) with `Grant` / `List` / `→ Downloads`,
because the SAF grant can only be requested from an Activity.

---

## 2. Why it is built this way — the platform constraint

This is the part that dictates the whole design, so port it with the constraint in mind:

- Since **Android 11**, `Android/data/<other-package>/` is hidden from plain file access.
  `java.io.File` on that path returns "does not exist" / `listFiles() == null`.
- **`MANAGE_EXTERNAL_STORAGE` does not lift it.** All-files access explicitly excludes
  `Android/data` and `Android/obb`. Don't add that permission expecting it to help — it only
  adds a scary permission and a Play-policy problem.
- `READ_EXTERNAL_STORAGE` doesn't lift it either on API 30+; it is still worth requesting on
  API ≤ 32 because it makes the plain-File fallback work on older/permissive ROMs.
- The supported route is **SAF**: `ACTION_OPEN_DOCUMENT_TREE` on the `FlightRecord` folder,
  with `takePersistableUriPermission` so the grant survives reboots.
- ⚠ **Open risk:** stock Android 11 DocumentsUI *blocks selecting* `Android/data` in the
  picker. Whether DJI's ROM on RC 2 carries that block is **not yet verified on hardware** —
  it needs one manual test (tap Grant, see if the picker lets you into the folder). If it is
  blocked, no in-app route exists and the fallbacks below are all that's left.
- Fallbacks kept for exactly that reason: plain `File` access (works if the ROM is permissive
  and covers a mod install under a different package), and an explicit `?dir=` path for
  records that were copied somewhere reachable.

`/records` is deliberately a *diagnostic* endpoint, not just a listing: it prints the SAF grant
and the state of every candidate directory (`absent/hidden` / `unreadable` / `readable`), so a
"nothing shows up" report can be triaged remotely without a shell.

---

## 3. Module design — `FlightRecords`

A single stateless `object` (full source in appendix A). Public surface:

```kotlin
data class Rec(name: String, size: Long, modified: Long, src: String,
               file: File? = null, uri: Uri? = null)   // file XOR uri

fun list(ctx, extraDir: String? = null): List<Rec>     // merged, newest first
fun find(ctx, name, extraDir = null): Rec?
fun read(ctx, rec): ByteArray?                         // throws over MAX_FILE
fun zip(ctx, recs): ByteArray                          // throws over MAX_ZIP
fun report(ctx, extraDir = null): String               // /records body
fun json(ctx, extraDir = null): String                 // /records.json body
fun grantIntent(pkg = "dji.go.v5"): Intent             // SAF picker, pre-opened
fun persist(ctx, tree: Uri): String                    // take + store the grant
fun forget(ctx)
fun copyToDownloads(ctx, extraDir = null): String
fun dirs(): List<Pair<String, File>>                   // candidates, for reporting
```

Rules that matter when porting:

- **`name` is a path relative to `FlightRecord/`** and may contain `/` — DJI keeps
  sub-folders (e.g. `MCDatFlightRecord/`). Listing recurses to `MAX_DEPTH = 3`.
- **Merge order is priority order**: `?dir=` → SAF → plain File, first hit per `name` wins,
  duplicates across routes collapse (`LinkedHashMap` keyed by `name`).
- **Candidate packages**: `dji.go.v5` (stock Fly), `dji.go.v6` (the FCC/tweakbox mod's renamed
  package), `dji.go.v4`, `dji.pilot`, `dji.pilot2`, `com.dji.industry.pilot`.
- **All calls do disk/IPC I/O** — never call from the main thread. In this project the diag
  server already runs on `Dispatchers.IO`, and the in-app buttons dispatch to a background scope.
- The SAF grant is **re-validated on every use** against
  `contentResolver.persistedUriPermissions` — a stored URI string alone is not proof the
  permission still exists (user can revoke it; a reinstall drops it).

---

## 4. Integration points

Five files. Everything except `FlightRecords.kt` is small and mechanical.

### 4.1 New file — `FlightRecords.kt`
Drop in as-is; only the `package` line needs changing. Depends on `AppState` (one string
setting) and `DiagLog` (logging) — swap those for the target project's equivalents.

### 4.2 `AppState` — one persisted string

```kotlin
@Volatile var recordsTree: String? = null            // SAF tree uri, null = not granted
// in load():  recordsTree = p.getString("records_tree", null)
fun setRecordsTree(ctx: Context, v: String?) {
    recordsTree = v
    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("records_tree", v).apply()
}
```
The existing helper only handled booleans, hence the inline `putString`.

### 4.3 `DiagServer` — the HTTP layer had to grow

The original handler could only produce a `String` body with a fixed `text/plain` header. File
download needs binary bodies, a content type, `Content-Disposition` and real status codes.
Minimal change that keeps every existing endpoint working:

```kotlin
private class Resp(
    val bytes: ByteArray,
    val ctype: String = "text/plain; charset=utf-8",
    val extra: List<String> = emptyList(),
    val status: String = "200 OK"
)
```

`handle()` picks binary routes first and falls through to the old text router unchanged:

```kotlin
val resp = try {
    when (p0) {
        "/", "/ui"      -> Resp(UI_HTML.toByteArray(), "text/html; charset=utf-8")
        "/records.json" -> Resp(FlightRecords.json(appCtx, query(path, "dir")).toByteArray(),
                                "application/json; charset=utf-8")
        "/record"       -> record(path)
        "/records.zip"  -> recordsZip(path)
        else            -> Resp(runBlocking { route(path, f) }.toByteArray())
    }
} catch (e: Exception) { Resp("error: ${e.message}".toByteArray(), status = "500 Internal Server Error") }

val head = StringBuilder("HTTP/1.1 ${resp.status}\r\nContent-Type: ${resp.ctype}\r\n")
    .append("Access-Control-Allow-Origin: *\r\nContent-Length: ${resp.bytes.size}\r\nConnection: close\r\n")
for (h in resp.extra) head.append(h).append("\r\n")
head.append("\r\n")
```

Download handler — note it resolves the name **against the current listing**, which is what
makes path traversal impossible (no filesystem path ever comes from the request):

```kotlin
private fun record(path: String): Resp {
    val name = query(path, "name") ?: return Resp("missing name".toByteArray(), status = "400 Bad Request")
    val rec = FlightRecords.find(appCtx, name, query(path, "dir"))
        ?: return Resp("not found: $name".toByteArray(), status = "404 Not Found")
    val data = FlightRecords.read(appCtx, rec)
        ?: return Resp("unreadable: $name".toByteArray(), status = "500 Internal Server Error")
    return Resp(data, "application/octet-stream", attachment(rec.name.substringAfterLast('/')))
}

private fun attachment(filename: String) =
    listOf("Content-Disposition: attachment; filename=\"" + filename.replace('"', '_') + "\"")
```

`query()` gained URL-decoding — record names contain `[`, `]`, which browsers percent-encode:

```kotlin
private fun query(path: String, key: String): String? =
    path.substringAfter('?', "").split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
```

Text routes added to the existing `when`:

```kotlin
"/records"          -> FlightRecords.report(appCtx, query(path, "dir"))
"/records/grant"    -> {
    appCtx.startActivity(Intent(appCtx, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        putExtra(MainActivity.EXTRA_ACTION, MainActivity.ACTION_GRANT_RECORDS)
    })
    "folder picker opened on the RC screen — pick FlightRecord and confirm"
}
"/records/forget"   -> { FlightRecords.forget(appCtx); "flight-record access forgotten" }
"/records/download" -> FlightRecords.copyToDownloads(appCtx, query(path, "dir"))
```

### 4.4 Served HTML page

One button, some CSS, one JS function. **The page lives in a Kotlin raw string, so it must not
contain a `$` anywhere** — no JS template literals, no `$(...)`; string concatenation only.
Also: the JS function is `loadRecs()` while the container is `id=recs` — do **not** name them the
same, because element ids become globals on `window` and collide with a function declaration.

```js
function sz(n){if(n<1024)return n+' B';if(n<1048576)return (n/1024).toFixed(1)+' KB';return (n/1048576).toFixed(1)+' MB';}
function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}
function loadRecs(){
 var e=document.getElementById('recs');e.innerHTML='<div class=hd>reading flight records&hellip;</div>';
 fetch('/records.json').then(function(r){return r.json()}).then(function(a){
  var h='<div class=hd>DJI Fly flight records &mdash; '+a.length+' file(s)'+
   '<a href="/records.zip">download all (.zip)</a>'+
   '<a href="#" onclick="go(\'/records/grant\');return false">grant access on RC</a>'+
   '<a href="#" onclick="go(\'/records/download\');return false">copy to Downloads</a>'+
   '<a href="/records">why empty?</a></div>';
  if(!a.length){h+='<div class=em>Nothing visible &mdash; tap &laquo;grant access on RC&raquo; and pick'+
   ' Android/data/dji.go.v5/files/FlightRecord in the picker on the controller screen.</div>';}
  else{h+='<table>';
   for(var i=0;i<a.length;i++){var r=a[i];
    h+='<tr><td><a href="/record?name='+encodeURIComponent(r.n)+'">'+esc(r.n)+'</a></td><td>'+sz(r.s)+
       '</td><td>'+new Date(r.m).toLocaleString()+'</td><td>'+esc(r.src)+'</td></tr>';}
   h+='</table>';}
  e.innerHTML=h;
 }).catch(function(x){e.innerHTML='<div class=em>records error: '+x+'</div>'});}
```

`encodeURIComponent(r.n)` is required — `[`/`]`/`/` in names.

### 4.5 `MainActivity` — the grant flow

A server cannot show a picker; the Activity must. `/records/grant` starts the Activity with an
extra, so it works from a PC browser while the operator watches the controller screen.

```kotlin
companion object {
    const val EXTRA_ACTION = "action"
    const val ACTION_GRANT_RECORDS = "grant_records"
    private const val REQ_RECORDS = 4001
    private const val REQ_READ_STORAGE = 4002
}

// onCreate(): handleIntent(intent)
override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
private fun handleIntent(i: Intent?) {
    if (i?.getStringExtra(EXTRA_ACTION) == ACTION_GRANT_RECORDS) requestRecordsAccess()
}

private fun requestRecordsAccess() {
    if (Build.VERSION.SDK_INT in 23..32 &&
        checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_READ_STORAGE)
        return                                   // picker opens from the permission callback
    }
    openRecordsPicker()
}

private fun openRecordsPicker() {
    runCatching { startActivityForResult(FlightRecords.grantIntent(), REQ_RECORDS) }
        .onFailure { setStatus("no folder picker on this device: ${it.message}") }
}

override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
    super.onRequestPermissionsResult(rc, p, g); if (rc == REQ_READ_STORAGE) openRecordsPicker()
}

override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
    super.onActivityResult(rc, res, data)
    if (rc != REQ_RECORDS) return
    val uri = data?.data
    if (res != RESULT_OK || uri == null) { setStatus("flight-record access not granted"); return }
    scope.launch { val r = FlightRecords.persist(applicationContext, uri); runOnUiThread { setStatus(r) } }
}
```

Card in the controls page (3 buttons + a hint line naming the source path). `List` calls
`FlightRecords.report()`, writes the full report to the diag log and shows the first line as status.

If the target project uses Compose / AndroidX, replace `startActivityForResult` with
`registerForActivityResult(ActivityResultContracts.OpenDocumentTree())` — the semantics are the
same; this project has zero AndroidX deps, hence the classic API.

### 4.6 Manifest

```xml
<!-- On Android 11+ another app's Android/data needs a SAF grant; this only helps
     older/permissive ROMs where the plain-File fallback can work. -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
                 android:maxSdkVersion="32" />
```
and on the launcher activity: `android:launchMode="singleTop"` (so `/records/grant` reaches
`onNewIntent` instead of recreating the Activity).

Do **not** add `MANAGE_EXTERNAL_STORAGE` — see §2.

---

## 5. Non-obvious decisions / gotchas

1. **`$` is forbidden in the served HTML** (Kotlin raw string) — a template literal silently
   turns into a Kotlin interpolation and breaks the build or the page.
2. **JS function name ≠ element id** (`loadRecs()` vs `id=recs`) — named-element globals collide.
3. **Download resolves by listing, not by path.** No user-supplied string ever reaches the
   filesystem, so `../` is meaningless. Keep this property if you refactor.
4. **URL-decode query values** — `[` and `]` in record names arrive percent-encoded.
5. **`SimpleDateFormat` is not thread-safe** and the diag server serves requests concurrently —
   formatting goes through a `synchronized` helper.
6. **Zip and single-file reads are built in memory**, with `MAX_FILE = 32 MB` /
   `MAX_ZIP = 64 MB` guards. Flight records are small text files; `.dat` sub-records are bigger,
   which is what the guard is for. Streaming instead would need chunked encoding — not worth it
   for a LAN debug tool.
7. **`find()` re-lists on every download** (one SAF/FS walk per request). Fine at this scale;
   don't "optimize" it into a cache that goes stale between flights.
8. **`persistedUriPermissions` is checked every time** — a stored URI is not a live permission.
9. **`copyToDownloads` uses the same read routes**, so it is not an access workaround: if
   nothing is visible, nothing gets copied. Its purpose is offline pickup over MTP, not bypass.
10. **No auth on any of this** — same as the rest of the diag server: trusted-LAN debug tool.
    Flight records contain GPS tracks and serials; keep that in mind if the server is ever
    exposed beyond a private Wi-Fi.

---

## 6. Verification done here

- **Compiles**: `gradle --offline :app:compileDebugKotlin` → `BUILD SUCCESSFUL` (Gradle 8.9,
  JDK 21; JDK 25 is rejected by this Gradle).
- **Manifest merges**: `:app:processDebugMainManifest`, merged output contains both additions.
- **Page renders**: extracted `UI_HTML` to a file, stubbed `window.fetch` with three fake
  records, screenshotted in headless Chrome — table, links and formatting verified.
- **JS syntax**: extracted the `<script>` block and ran `node --check`.

**Not yet verified on hardware** (needs the RC 2):
- whether DJI's Android 11 DocumentsUI lets the picker into `Android/data/dji.go.v5`;
- the plain-File fallback (expected to fail on Android 11, kept for other ROMs);
- end-to-end download of a real record over Wi-Fi.

On-device checklist: start the diag service → open `http://<rc-ip>:8899/` → **Flight logs** →
if empty, **grant access on RC** → pick the folder on the controller → reload → download a file
and diff it against the same file pulled over USB.

---

## Appendix A — `FlightRecords.kt` (complete)

```kotlin
package com.dji.fccgpsoff

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Access to DJI Fly's flight records:
 *
 *   /sdcard/Android/data/dji.go.v5/files/FlightRecord/FlightRecord_2026-08-09_[18-28-35].txt
 *
 * Since Android 11 that directory belongs to another app and is hidden from
 * plain file access (MANAGE_EXTERNAL_STORAGE does NOT lift it either), so three
 * routes are tried, best first:
 *
 *   1. SAF — a persisted ACTION_OPEN_DOCUMENT_TREE grant on the FlightRecord
 *      folder. The one route that is supposed to work on RC2 (Android 11);
 *      some ROMs block picking Android/data, hence the fallbacks.
 *   2. Plain File access — works on older/permissive ROMs and for any DJI
 *      package still readable (also covers the dji.go.v6 mod install).
 *   3. An explicit directory passed per request (?dir=/path), for records that
 *      were moved/copied somewhere reachable.
 *
 * Records are then served by [DiagServer] over Wi-Fi (single file or a zip) or
 * copied to the public Download/FlightRecord folder for MTP pickup.
 */
object FlightRecords {

    /** DJI apps that keep flight records in their external files dir. */
    val PACKAGES = listOf(
        "dji.go.v5",              // DJI Fly (stock)
        "dji.go.v6",              // the FCC/tweakbox mod (renamed package)
        "dji.go.v4", "dji.pilot", "dji.pilot2", "com.dji.industry.pilot"
    )

    private const val SUBDIR = "files/FlightRecord"
    private const val MAX_DEPTH = 3
    private const val MAX_FILE = 32L * 1024 * 1024
    private const val MAX_ZIP = 64L * 1024 * 1024

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** One record. [file] xor [uri] is set, depending on which route found it. */
    data class Rec(
        val name: String,          // path relative to FlightRecord/ (may contain '/')
        val size: Long,
        val modified: Long,
        val src: String,           // "saf", a package name, or "dir"
        val file: File? = null,
        val uri: Uri? = null
    )

    // ---------------------------------------------------------------- listing

    fun dirs(): List<Pair<String, File>> {
        val root = Environment.getExternalStorageDirectory()
        return PACKAGES.map { it to File(root, "Android/data/$it/$SUBDIR") }
    }

    /** All records visible through any route, newest first. */
    fun list(ctx: Context, extraDir: String? = null): List<Rec> {
        val out = LinkedHashMap<String, Rec>()
        if (extraDir != null) {
            val d = File(extraDir)
            if (d.isDirectory) runCatching { walk(d, "dir", "", 0, out) }
        }
        safTree(ctx)?.let { tree ->
            runCatching { walkSaf(ctx, tree, DocumentsContract.getTreeDocumentId(tree), "", 0, out) }
                .onFailure { DiagLog.warn("flight records (saf): ${it.message}") }
        }
        for ((pkg, d) in dirs()) {
            if (!d.isDirectory) continue
            runCatching { walk(d, pkg, "", 0, out) }
        }
        return out.values.sortedByDescending { it.modified }
    }

    fun find(ctx: Context, name: String, extraDir: String? = null): Rec? =
        list(ctx, extraDir).firstOrNull { it.name == name }

    private fun walk(dir: File, src: String, prefix: String, depth: Int, out: MutableMap<String, Rec>) {
        if (depth > MAX_DEPTH) return
        for (f in dir.listFiles() ?: return) {
            val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
            if (f.isDirectory) walk(f, src, rel, depth + 1, out)
            else if (!out.containsKey(rel)) out[rel] = Rec(rel, f.length(), f.lastModified(), src, file = f)
        }
    }

    private fun walkSaf(ctx: Context, tree: Uri, docId: String, prefix: String, depth: Int, out: MutableMap<String, Rec>) {
        if (depth > MAX_DEPTH) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val nm = c.getString(1) ?: continue
                val rel = if (prefix.isEmpty()) nm else "$prefix/$nm"
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkSaf(ctx, tree, id, rel, depth + 1, out)
                } else if (!out.containsKey(rel)) {
                    out[rel] = Rec(rel, c.getLong(3), c.getLong(4), "saf",
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, id))
                }
            }
        }
    }

    // ------------------------------------------------------------------ reads

    fun open(ctx: Context, rec: Rec): InputStream? =
        rec.file?.inputStream() ?: rec.uri?.let { ctx.contentResolver.openInputStream(it) }

    fun read(ctx: Context, rec: Rec): ByteArray? {
        if (rec.size > MAX_FILE) throw IllegalStateException("file too large: ${rec.size} B")
        return open(ctx, rec)?.use { it.readBytes() }
    }

    /** All records as one zip, built in memory (records are small text files). */
    fun zip(ctx: Context, recs: List<Rec>): ByteArray {
        val total = recs.sumOf { it.size }
        if (total > MAX_ZIP) throw IllegalStateException("selection too large: $total B")
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for (r in recs) {
                val data = runCatching { read(ctx, r) }.getOrNull() ?: continue
                z.putNextEntry(ZipEntry(r.name).apply { time = r.modified })
                z.write(data)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    // ------------------------------------------------------------------- SAF

    private fun safTree(ctx: Context): Uri? {
        val s = AppState.recordsTree ?: return null
        val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return null
        // The grant is lost if the user revokes it or the picker's provider changes.
        val held = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return if (held) uri else null
    }

    /**
     * Folder picker pre-opened at DJI Fly's FlightRecord directory. The user
     * still has to confirm the selection — that is the whole point of SAF.
     */
    fun grantIntent(pkg: String = "dji.go.v5"): Intent {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val docId = "primary:Android/data/$pkg/$SUBDIR"
            val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        }
        return i
    }

    fun persist(ctx: Context, tree: Uri): String {
        return try {
            ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            AppState.setRecordsTree(ctx, tree.toString())
            val n = list(ctx).size
            DiagLog.info("flight records: granted $tree ($n files)")
            "granted: $n record(s) visible"
        } catch (e: Exception) {
            DiagLog.err("flight records: grant failed: ${e.message}")
            "grant failed: ${e.message}"
        }
    }

    fun forget(ctx: Context) {
        safTree(ctx)?.let { runCatching { ctx.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        AppState.setRecordsTree(ctx, null)
        DiagLog.info("flight records: access forgotten")
    }

    // -------------------------------------------------------------- reporting

    /** Human-readable listing, also used as the /records endpoint body. */
    fun report(ctx: Context, extraDir: String? = null): String {
        val recs = list(ctx, extraDir)
        val sb = StringBuilder()
        sb.append("flight records: ").append(recs.size).append(" file(s)\n")
        sb.append("saf grant: ").append(safTree(ctx)?.toString() ?: "none").append('\n')
        for ((pkg, d) in dirs()) {
            val state = when {
                !d.exists() -> "absent/hidden"
                !d.isDirectory -> "not a dir"
                d.listFiles() == null -> "unreadable"
                else -> "readable"
            }
            sb.append("  ").append(d.absolutePath).append("  → ").append(state).append('\n')
        }
        if (recs.isEmpty()) {
            sb.append("\nNothing visible. On Android 11+ another app's Android/data is hidden:\n")
            sb.append("open the app on the RC → Flight records → Grant access, and pick\n")
            sb.append("Android/data/dji.go.v5/files/FlightRecord in the picker.\n")
        } else {
            sb.append('\n')
            for (r in recs) sb.append(line(r)).append('\n')
        }
        return sb.toString()
    }

    private fun line(r: Rec) =
        "%-52s %9d B  %s  [%s]".format(r.name, r.size, ts(r.modified), r.src)

    /** SimpleDateFormat is not thread-safe and the diag server serves concurrently. */
    private fun ts(t: Long) = synchronized(stamp) { stamp.format(Date(t)) }

    fun json(ctx: Context, extraDir: String? = null): String =
        list(ctx, extraDir).joinToString(",", "[", "]") {
            """{"n":"${esc(it.name)}","s":${it.size},"m":${it.modified},"src":"${esc(it.src)}"}"""
        }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    // ----------------------------------------------------- copy to Downloads

    /**
     * Copy every visible record into the public Download/FlightRecord folder,
     * so they can be pulled over MTP without the diag server running.
     */
    fun copyToDownloads(ctx: Context, extraDir: String? = null): String {
        val recs = list(ctx, extraDir)
        if (recs.isEmpty()) return "nothing to copy — no records visible"
        var ok = 0
        for (r in recs) {
            val flat = r.name.replace('/', '_')
            val data = runCatching { read(ctx, r) }.getOrNull() ?: continue
            val wrote = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, flat)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/FlightRecord")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val res = ctx.contentResolver
                    val uri = res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
                    res.openOutputStream(uri)?.use { it.write(data) }
                    values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                    res.update(uri, values, null, null)
                    true
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FlightRecord")
                    dir.mkdirs(); File(dir, flat).writeBytes(data); true
                }
            }.getOrDefault(false)
            if (wrote) ok++
        }
        val msg = "copied $ok/${recs.size} record(s) to Download/FlightRecord"
        DiagLog.info(msg)
        return msg
    }
}
```
