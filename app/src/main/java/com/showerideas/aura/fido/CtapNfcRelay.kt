package com.showerideas.aura.fido

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import timber.log.Timber

/**
 * Task 86 — NFC CTAP2 relay to hardware security key.
 *
 * AURA receives a CTAP2 APDU from a reader via NFC HCE (AID A0000006472F0001,
 * detected by [AuraHceService]), forwards it to a paired hardware security key
 * (YubiKey NFC, SoloKey) via NFC reader mode, and returns the response.
 * The phone is a transparent relay — no CTAP2 interpretation occurs here.
 *
 * Relay latency requirement: < 2 seconds RTT (CTAP2 timeout tolerance).
 *
 * Enterprise toggle: [BuildConfig.ENABLE_HW_KEY_RELAY] must be true.
 * Enable via MDM policy key `fido2HardwareKeyRelay: true`.
 *
 * See: FIDO CTAP2 spec §3.4 (NFC transport binding)
 * See: ROADMAP §Task 86
 */
object CtapNfcRelay {

    /** CTAP2 NFC AID — standard FIDO2 authenticator AID. */
    const val CTAP2_AID = "A0000006472F0001"
    private const val RELAY_TIMEOUT_MS = 1800  // 1.8 s — within CTAP2 tolerance

    /**
     * Forward a raw APDU [command] to a hardware key on [hwKeyTag] and return the response.
     *
     * @param hwKeyTag  NFC [Tag] of the hardware security key, already discovered via reader mode.
     * @param command   Raw APDU bytes from the CTAP2 reader/relying party.
     * @return APDU response bytes from the hardware key, or null on error.
     */
    fun relay(hwKeyTag: Tag, command: ByteArray): ByteArray? {
        val isoDep = IsoDep.get(hwKeyTag) ?: run {
            Timber.e("CtapNfcRelay: tag does not support ISO-DEP")
            return null
        }
        return try {
            isoDep.connect()
            isoDep.timeout = RELAY_TIMEOUT_MS
            val response = isoDep.transceive(command)
            Timber.d("CtapNfcRelay: relayed ${command.size} bytes → received ${response.size} bytes")
            response
        } catch (e: Exception) {
            Timber.e(e, "CtapNfcRelay: relay failed")
            null
        } finally {
            try { isoDep.close() } catch (_: Exception) {}
        }
    }

    /**
     * Detect if [apduBytes] is addressed to the CTAP2 AID.
     * Used by [AuraHceService] to route CTAP2 APDUs to this relay.
     */
    fun isCtap2Apdu(apduBytes: ByteArray): Boolean {
        if (apduBytes.size < 5) return false
        // SELECT AID APDU: CLA=00 INS=A4 P1=04 P2=00 Lc=len AID...
        if (apduBytes[0] != 0x00.toByte() || apduBytes[1] != 0xA4.toByte()) return false
        val aidLen = apduBytes[4].toInt() and 0xFF
        if (apduBytes.size < 5 + aidLen) return false
        val aidHex = apduBytes.copyOfRange(5, 5 + aidLen)
            .joinToString("") { "%02X".format(it) }
        return aidHex == CTAP2_AID
    }
}
