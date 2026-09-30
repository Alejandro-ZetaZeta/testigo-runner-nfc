package com.example.sensores_prueba1.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import com.example.sensores_prueba1.data.model.BatonData
import com.example.sensores_prueba1.nfc.ApduConstants.toHexString
import java.nio.charset.StandardCharsets

/**
 * Controller for Android NFC Reader Mode.
 *
 * Connects to other phones (Android running RelayBatonHceService) or physical NFC ISO-DEP tags,
 * sends the SELECT AID APDU, and exchanges the baton payload.
 */
class RelayBatonReader(private val activity: Activity) : NfcAdapter.ReaderCallback {

    companion object {
        private const val TAG = "RelayBatonReader"
        private const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    }

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    fun startScanning() {
        if (nfcAdapter == null || !nfcAdapter.isEnabled) {
            Log.e(TAG, "NFC is not supported or not enabled")
            BatonManager.onHandshakeFailed("NFC is disabled or unsupported")
            return
        }
        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        }
        nfcAdapter.enableReaderMode(activity, this, READER_FLAGS, options)
        Log.i(TAG, "NFC Reader mode enabled, scanning for Relay Baton...")
    }

    fun stopScanning() {
        try {
            nfcAdapter?.disableReaderMode(activity)
            Log.i(TAG, "NFC Reader mode stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling NFC Reader mode", e)
        }
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) return
        Log.i(TAG, "NFC Tag discovered: ${tag.id.toHexString()}")

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            Log.w(TAG, "Tag does not support IsoDep (ISO 14443-4)")
            return
        }

        try {
            isoDep.connect()
            isoDep.timeout = 5000

            // 1. Send SELECT AID APDU
            val selectCommand = ApduConstants.buildSelectAidApdu()
            Log.d(TAG, "Sending SELECT APDU: ${selectCommand.toHexString()}")
            val selectResponse = isoDep.transceive(selectCommand)
            Log.d(TAG, "SELECT APDU Response: ${selectResponse.toHexString()}")

            if (!isSuccessResponse(selectResponse)) {
                Log.w(TAG, "SELECT AID failed or AID not matched")
                return
            }

            // 2. Decide whether to Fetch Baton (Reader is waiting runner) or Pass Baton (Reader is incoming runner)
            if (!BatonManager.isCarryingBaton.value) {
                // Fetch baton from the incoming runner
                fetchBatonFromTarget(isoDep)
            } else {
                // Pass baton to the waiting runner
                passBatonToTarget(isoDep)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during NFC ISO-DEP communication", e)
            BatonManager.onHandshakeFailed("NFC communication error: ${e.localizedMessage}")
        } finally {
            try {
                isoDep.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun fetchBatonFromTarget(isoDep: IsoDep) {
        val getCommand = ApduConstants.buildGetBatonApdu()
        val response = isoDep.transceive(getCommand)

        if (response.size >= 2 && isSuccessResponse(response.copyOfRange(response.size - 2, response.size))) {
            val payloadBytes = response.copyOfRange(0, response.size - 2)
            val jsonString = String(payloadBytes, StandardCharsets.UTF_8)
            val baton = BatonData.fromJsonString(jsonString)

            if (baton != null) {
                Log.i(TAG, "Successfully received baton via Reader Mode: $jsonString")
                BatonManager.onBatonReceivedIn(baton)
            } else {
                Log.e(TAG, "Failed to parse received baton payload")
            }
        } else {
            Log.e(TAG, "GET_BATON failed, response: ${response.toHexString()}")
        }
    }

    private fun passBatonToTarget(isoDep: IsoDep) {
        val baton = BatonManager.currentBaton.value ?: return
        val payloadBytes = baton.toJsonString().toByteArray(StandardCharsets.UTF_8)
        val passCommand = ApduConstants.buildPassBatonApdu(payloadBytes)

        val response = isoDep.transceive(passCommand)
        if (isSuccessResponse(response)) {
            Log.i(TAG, "Successfully passed baton via Reader Mode")
            BatonManager.onBatonTransferredOut("Reader pushed baton")
        } else {
            Log.e(TAG, "PASS_BATON failed, response: ${response.toHexString()}")
        }
    }

    private fun isSuccessResponse(response: ByteArray): Boolean {
        if (response.size < 2) return false
        val sw1 = response[response.size - 2]
        val sw2 = response[response.size - 1]
        return sw1 == 0x90.toByte() && sw2 == 0x00.toByte()
    }
}
