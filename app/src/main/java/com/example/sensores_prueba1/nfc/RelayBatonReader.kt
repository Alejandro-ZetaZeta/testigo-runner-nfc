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
 * Controlador del Modo Lector NFC de Android.
 *
 * Detecta tanto teléfonos Android (con servicio HCE RelayBaton) como iPhones
 * o etiquetas NFC físicas al hacer contacto físico dorso con dorso.
 */
class RelayBatonReader(private val activity: Activity) : NfcAdapter.ReaderCallback {

    companion object {
        private const val TAG = "RelayBatonReader"
        private const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
    }

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    var onPhysicalContactDetected: ((tagIdHex: String) -> Unit)? = null

    fun startScanning() {
        if (nfcAdapter == null || !nfcAdapter.isEnabled) {
            Log.e(TAG, "NFC no está disponible o no está encendido")
            BatonManager.onHandshakeFailed("NFC está apagado o no es compatible")
            return
        }
        val options = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 150)
        }
        nfcAdapter.enableReaderMode(activity, this, READER_FLAGS, options)
        Log.i(TAG, "Lector NFC activo escaneando dispositivos/iPhones cercanos...")
    }

    fun stopScanning() {
        try {
            nfcAdapter?.disableReaderMode(activity)
            Log.i(TAG, "Lector NFC detenido")
        } catch (e: Exception) {
            Log.e(TAG, "Error al detener Lector NFC", e)
        }
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) return
        val tagIdHex = tag.id.toHexString()
        Log.i(TAG, "¡Contacto NFC detectado! ID: $tagIdHex")

        // Disparar retroalimentación inmediata de contacto físico
        activity.runOnUiThread {
            onPhysicalContactDetected?.invoke(tagIdHex)
        }

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            // Detección por proximidad de contacto físico (iPhone u otra etiqueta NFC)
            handleProximityTapHandoff(tagIdHex)
            return
        }

        try {
            isoDep.connect()
            isoDep.timeout = 4000

            // 1. Enviar SELECT AID APDU
            val selectCommand = ApduConstants.buildSelectAidApdu()
            val selectResponse = isoDep.transceive(selectCommand)

            if (!isSuccessResponse(selectResponse)) {
                // Si el dispositivo (como un iPhone) no aloja el AID específico pero hizo contacto físico:
                Log.w(TAG, "SELECT AID no coincidió, ejecutando traspaso por contacto físico de iPhone/etiqueta.")
                handleProximityTapHandoff(tagIdHex)
                return
            }

            // 2. Intercambio de protocolo APDU completo
            if (!BatonManager.isCarryingBaton.value) {
                fetchBatonFromTarget(isoDep)
            } else {
                passBatonToTarget(isoDep)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error de comunicación APDU, recurriendo a traspaso por contacto físico", e)
            handleProximityTapHandoff(tagIdHex)
        } finally {
            try {
                isoDep.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun handleProximityTapHandoff(tagIdHex: String) {
        val current = BatonManager.currentBaton.value
        val isCarrying = BatonManager.isCarryingBaton.value

        activity.runOnUiThread {
            if (isCarrying && current != null) {
                val nextLeg = current.legIndex + 1
                val updatedBaton = current.copy(
                    legIndex = nextLeg,
                    runnerName = "Corredor $nextLeg",
                    timestampMs = System.currentTimeMillis(),
                    signatureToken = "SIG-TAP-${tagIdHex.take(6)}-$nextLeg"
                )
                BatonManager.onBatonTransferredOut("Contacto NFC con iPhone / Dispositivo")
                BatonManager.updateBaton(updatedBaton, carrying = false)
            } else if (current != null) {
                val updatedBaton = current.copy(
                    timestampMs = System.currentTimeMillis(),
                    signatureToken = "SIG-RECV-${tagIdHex.take(6)}"
                )
                BatonManager.onBatonReceivedIn(updatedBaton)
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
                Log.i(TAG, "Testigo recibido por protocolo APDU: $jsonString")
                activity.runOnUiThread {
                    BatonManager.onBatonReceivedIn(baton)
                }
            } else {
                Log.e(TAG, "Error al parsear JSON de testigo")
            }
        } else {
            Log.e(TAG, "GET_BATON falló, respuesta: ${response.toHexString()}")
        }
    }

    private fun passBatonToTarget(isoDep: IsoDep) {
        val baton = BatonManager.currentBaton.value ?: return
        val payloadBytes = baton.toJsonString().toByteArray(StandardCharsets.UTF_8)
        val passCommand = ApduConstants.buildPassBatonApdu(payloadBytes)

        val response = isoDep.transceive(passCommand)
        if (isSuccessResponse(response)) {
            Log.i(TAG, "Testigo transferido por protocolo APDU")
            activity.runOnUiThread {
                BatonManager.onBatonTransferredOut("Lector NFC envió testigo")
            }
        } else {
            Log.e(TAG, "PASS_BATON falló, respuesta: ${response.toHexString()}")
        }
    }

    private fun isSuccessResponse(response: ByteArray): Boolean {
        if (response.size < 2) return false
        val sw1 = response[response.size - 2]
        val sw2 = response[response.size - 1]
        return sw1 == 0x90.toByte() && sw2 == 0x00.toByte()
    }
}
