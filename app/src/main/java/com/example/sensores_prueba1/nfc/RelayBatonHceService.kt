package com.example.sensores_prueba1.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import com.example.sensores_prueba1.data.model.BatonData
import com.example.sensores_prueba1.nfc.ApduConstants.toHexString
import java.nio.charset.StandardCharsets

/**
 * Host Card Emulation (HCE) service for the Relay Race.
 *
 * Responds to incoming NFC Readers (both iPhone CoreNFC and Android IsoDep readers)
 * when they select the Relay Baton AID: F072656C61793031.
 */
class RelayBatonHceService : HostApduService() {

    companion object {
        private const val TAG = "RelayBatonHce"
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.isEmpty()) {
            Log.w(TAG, "Received null or empty APDU command")
            return ApduConstants.STATUS_FAILED
        }

        val apduHex = commandApdu.toHexString()
        Log.d(TAG, "Incoming APDU: $apduHex (length=${commandApdu.size})")

        // 1. Check for SELECT AID Command
        if (isSelectAidCommand(commandApdu)) {
            Log.i(TAG, "AID Selected by NFC Reader! Ready for baton exchange.")
            // Return STATUS_SUCCESS (90 00) acknowledging selection
            return ApduConstants.STATUS_SUCCESS
        }

        // 2. Parse Instruction (INS)
        if (commandApdu.size < 4) {
            return ApduConstants.STATUS_FAILED
        }

        val ins = commandApdu[1]
        return when (ins) {
            ApduConstants.INS_GET_BATON -> handleGetBatonCommand()
            ApduConstants.INS_PASS_BATON -> handlePassBatonCommand(commandApdu)
            ApduConstants.INS_PING -> ApduConstants.STATUS_SUCCESS
            else -> {
                Log.w(TAG, "Unknown instruction: 0x%02X".format(ins))
                ApduConstants.STATUS_UNKNOWN_CMD
            }
        }
    }

    /**
     * Handles reader requesting the baton (Reader is waiting runner, this device is incoming runner).
     */
    private fun handleGetBatonCommand(): ByteArray {
        val baton = BatonManager.currentBaton.value
        return if (baton != null) {
            val jsonPayload = baton.toJsonString().toByteArray(StandardCharsets.UTF_8)
            Log.i(TAG, "Transmitting baton to Reader: ${baton.toJsonString()}")

            // Notify BatonManager of handoff
            BatonManager.onBatonTransferredOut("Reader fetched baton")

            // Response = [Payload Bytes] + [90 00]
            jsonPayload + ApduConstants.STATUS_SUCCESS
        } else {
            Log.w(TAG, "No active baton data available to send")
            ApduConstants.STATUS_FAILED
        }
    }

    /**
     * Handles reader writing the baton (Reader is incoming runner, this device is waiting runner).
     */
    private fun handlePassBatonCommand(commandApdu: ByteArray): ByteArray {
        try {
            // APDU format: [CLA(1)] [INS(1)] [P1(1)] [P2(1)] [Lc(1)] [Data(Lc)] [Le(optional)]
            if (commandApdu.size < 5) return ApduConstants.STATUS_FAILED
            val lc = commandApdu[4].toInt() and 0xFF
            if (commandApdu.size < 5 + lc) return ApduConstants.STATUS_FAILED

            val payloadBytes = commandApdu.copyOfRange(5, 5 + lc)
            val jsonString = String(payloadBytes, StandardCharsets.UTF_8)
            val incomingBaton = BatonData.fromJsonString(jsonString)

            return if (incomingBaton != null) {
                Log.i(TAG, "Received incoming baton from Reader: $jsonString")
                BatonManager.onBatonReceivedIn(incomingBaton)
                ApduConstants.STATUS_SUCCESS
            } else {
                Log.e(TAG, "Failed to parse incoming baton payload")
                ApduConstants.STATUS_FAILED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling PASS_BATON command", e)
            return ApduConstants.STATUS_FAILED
        }
    }

    private fun isSelectAidCommand(commandApdu: ByteArray): Boolean {
        if (commandApdu.size < 5) return false
        // Header must match 00 A4 04 00
        val isHeaderMatch = commandApdu[0] == 0x00.toByte() &&
                commandApdu[1] == 0xA4.toByte() &&
                commandApdu[2] == 0x04.toByte() &&
                commandApdu[3] == 0x00.toByte()

        if (!isHeaderMatch) return false

        val aidLength = commandApdu[4].toInt() and 0xFF
        if (commandApdu.size < 5 + aidLength) return false

        val aidBytes = commandApdu.copyOfRange(5, 5 + aidLength)
        val aidHex = aidBytes.toHexString()

        return aidHex.equals(ApduConstants.RELAY_AID_HEX, ignoreCase = true)
    }

    override fun onDeactivated(reason: Int) {
        val reasonStr = when (reason) {
            DEACTIVATION_LINK_LOSS -> "Link Lost (Device moved out of NFC field)"
            DEACTIVATION_DESELECTED -> "Deselected (Another AID was selected)"
            else -> "Reason: $reason"
        }
        Log.d(TAG, "NFC HCE Service deactivated: $reasonStr")
    }
}
