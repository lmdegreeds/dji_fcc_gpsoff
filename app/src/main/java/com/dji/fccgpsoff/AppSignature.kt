package com.dji.fccgpsoff

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Which key this build is signed with (2026-08-19).
 *
 * Android refuses to replace an installed app with an APK signed by a different
 * key — there is no override, and the only way across is uninstall + reinstall,
 * which throws away every setting. This project can produce both kinds: a machine
 * holding `keystore.properties` signs a release build with the real key, and a
 * fresh clone falls back to the debug key (see `app/build.gradle.kts`). Both have
 * been published, so the update check could offer a build the user is unable to
 * install and only find out inside the system installer.
 *
 * The fingerprint is therefore made visible in three places, the same way the
 * version number is:
 *
 *  - in the APK's file name, `…-<buildType>-<8 hex>.apk`, stamped at build time
 *    from the signing config — so an update check can filter release assets
 *    BEFORE downloading one;
 *  - at runtime, from the installed package ([ownTag]) — what a release asset is
 *    matched against;
 *  - in a downloaded file ([ofApk]) — the last check before handing it to the
 *    installer, which also catches a legacy release whose name carries no tag.
 *
 * SHA-256 of the signer's DER certificate: the same number `keytool -list` and
 * `apksigner verify --print-certs` print, so it can be checked by hand.
 */
object AppSignature {

    /** Characters of the SHA-256 that go into a file name. 32 bits of a certificate
     *  hash is far past what telling two keys apart needs, and stays readable. */
    const val TAG_LEN = 8

    /** SHA-256 of this app's signing certificate, lowercase hex, or "" if unknown. */
    fun own(ctx: Context): String = runCatching {
        @Suppress("DEPRECATION")
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                   else PackageManager.GET_SIGNATURES
        digestOf(ctx.packageManager.getPackageInfo(ctx.packageName, flag))
    }.getOrDefault("")

    /** [own] shortened to what a release asset name carries. */
    fun ownTag(ctx: Context): String = tagOf(own(ctx))

    /**
     * SHA-256 of the certificate an APK FILE is signed with, or "" when it cannot
     * be read (a truncated download, or a platform that will not parse it).
     *
     * Parsing an archive is not verifying it — the system installer still does the
     * real check. This only has to answer "is this the same key as ours", which is
     * the one question that decides whether the install can possibly succeed.
     */
    fun ofApk(ctx: Context, apk: File): String = runCatching {
        @Suppress("DEPRECATION")
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                   else PackageManager.GET_SIGNATURES
        val pi = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, flag) ?: return ""
        digestOf(pi)
    }.getOrDefault("")

    fun tagOf(sha: String): String = if (sha.length >= TAG_LEN) sha.substring(0, TAG_LEN) else ""

    /**
     * Whether [apk] can replace the installed app, as far as the signature goes.
     * Returns null when it can (or when either side is unreadable — then the system
     * installer's own verdict is the honest answer, not a guess of ours).
     */
    fun mismatchReason(ctx: Context, apk: File): String? {
        val mine = own(ctx)
        val theirs = ofApk(ctx, apk)
        if (mine.isEmpty() || theirs.isEmpty()) return null
        if (mine == theirs) return null
        return t("этот APK подписан другим ключом (${tagOf(theirs)}), а установленная версия — ${tagOf(mine)}. " +
                 "Android не даёт заменить приложение сборкой с другой подписью: нужно удалить текущую " +
                 "версию и поставить новую вручную — настройки при этом потеряются.",
                 "this APK is signed with a different key (${tagOf(theirs)}) than the installed build (${tagOf(mine)}). " +
                 "Android will not replace an app with a differently-signed build: the current version has to be " +
                 "uninstalled and the new one installed by hand, which loses the settings.")
    }

    /**
     * The DER bytes of the first signer, hashed. `apkContentsSigners` is the current
     * set after any rotation, which is what an install is checked against;
     * `signatures` is the pre-28 equivalent. Multiple signers are not something this
     * project produces, so the first is the one.
     */
    private fun digestOf(pi: PackageInfo): String {
        @Suppress("DEPRECATION")
        val sigs = if (Build.VERSION.SDK_INT >= 28)
            pi.signingInfo?.apkContentsSigners ?: pi.signatures
        else pi.signatures
        val first = sigs?.firstOrNull() ?: return ""
        val sha = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
        return sha.joinToString("") { "%02x".format(it) }
    }
}
