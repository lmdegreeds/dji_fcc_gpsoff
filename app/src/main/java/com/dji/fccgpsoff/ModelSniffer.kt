package com.dji.fccgpsoff

/**
 * Passive model watcher fed by the hijack-read stream (main + aux), the same way
 * [SerialSniffer] catches the serial. Every delivered frame is offered; only
 * VersionInquiry (00:01) replies are parsed, and a resolved code is routed to
 * the right [AircraftIdentity] slot by kind (drone vs RC).
 *
 * This is the best-effort passive path: it only fires when a 00:01 reply
 * actually flows past (e.g. while capture is on and DJI Fly polls versions).
 * [offer] runs on the native RX thread — kept cheap and non-throwing.
 */
object ModelSniffer {

    /** Feed one delivered frame; publishes an identity if it carries a model. */
    fun offer(cmdSet: Int, cmdId: Int, payload: ByteArray, route: Int) {
        if (cmdSet != 0x00 || cmdId != 0x01) return          // version replies only — cheap guard
        val code = runCatching { AircraftModelProbe.parseVersionReply(payload) }.getOrNull() ?: return
        AircraftIdentity.publish(code, null, AircraftIdentity.Source.PASSIVE)
    }
}
