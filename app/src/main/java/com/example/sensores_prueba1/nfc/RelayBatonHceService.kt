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
 * Responde de forma 100% automática a otros dispositivos (Android e iPhone)
 * cuando seleccionan el AID del testigo de relevos: F072656C61793031.
 */
class RelayBatonHceService : HostApduService() {

    companion object {
        private const val TAG = "RelayBatonHce"
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.isEmpty()) {
            Log.w(TAG, "Comando APDU nulo o vacío recibido")
            return ApduConstants.STATUS_FAILED
        }

        val apduHex = commandApdu.toHexString()
        Log.d(TAG, "APDU entrante: $apduHex (longitud=${commandApdu.size})")

        // 1. Verificar comando SELECT AID
        if (isSelectAidCommand(commandApdu)) {
            Log.i(TAG, "AID del Testigo seleccionado por dispositivo lector remoto.")
            return ApduConstants.STATUS_SUCCESS
        }

        // 2. Parsear Instrucción (INS)
        if (commandApdu.size < 4) {
            return ApduConstants.STATUS_FAILED
        }

        val ins = commandApdu[1]
        return when (ins) {
            ApduConstants.INS_GET_BATON -> handleGetBatonCommand()
            ApduConstants.INS_PASS_BATON -> handlePassBatonCommand(commandApdu)
            ApduConstants.INS_PING -> ApduConstants.STATUS_SUCCESS
            else -> {
                Log.w(TAG, "Instrucción no reconocida: 0x%02X".format(ins))
                ApduConstants.STATUS_UNKNOWN_CMD
            }
        }
    }

    /**
     * El dispositivo lector remoto (relevista en espera) solicita el testigo.
     * Este dispositivo debe estar portando el testigo para entregarlo.
     */
    private fun handleGetBatonCommand(): ByteArray {
        val isCarrying = BatonManager.isCarryingBaton.value
        val baton = BatonManager.currentBaton.value

        return if (isCarrying && baton != null) {
            val jsonPayload = baton.toJsonString().toByteArray(StandardCharsets.UTF_8)
            Log.i(TAG, "Entregando testigo a lector remoto vía GET_BATON: ${baton.toJsonString()}")

            // Notificar salida del testigo en BatonManager
            BatonManager.onBatonTransferredOut("Lector remoto obtuvo el testigo vía APDU")

            // Respuesta = [Bytes de Payload] + [90 00]
            jsonPayload + ApduConstants.STATUS_SUCCESS
        } else {
            Log.w(TAG, "Solicitud GET_BATON rechazada: Este dispositivo no porta el testigo activo")
            ApduConstants.STATUS_FAILED
        }
    }

    /**
     * El dispositivo lector remoto (corredor entrante) entrega el testigo a este dispositivo (relevista en espera).
     */
    private fun handlePassBatonCommand(commandApdu: ByteArray): ByteArray {
        try {
            if (commandApdu.size < 5) return ApduConstants.STATUS_FAILED
            val lc = commandApdu[4].toInt() and 0xFF
            if (commandApdu.size < 5 + lc) return ApduConstants.STATUS_FAILED

            val payloadBytes = commandApdu.copyOfRange(5, 5 + lc)
            val jsonString = String(payloadBytes, StandardCharsets.UTF_8)
            val incomingBaton = BatonData.fromJsonString(jsonString)

            return if (incomingBaton != null) {
                val current = BatonManager.currentBaton.value

                // Validar correspondencia de grupo de carrera
                if (current != null && current.raceId.isNotBlank() && incomingBaton.raceId.isNotBlank() &&
                    !incomingBaton.raceId.equals(current.raceId, ignoreCase = true)
                ) {
                    Log.e(TAG, "Carrera no coincide en HCE: ${incomingBaton.raceId} != ${current.raceId}")
                    BatonManager.onHandshakeFailed("Carrera '${incomingBaton.raceId}' no coincide con local '${current.raceId}'")
                    return ApduConstants.STATUS_FAILED
                }

                Log.i(TAG, "Testigo recibido con éxito vía PASS_BATON: $jsonString")
                BatonManager.onBatonReceivedIn(incomingBaton)
                ApduConstants.STATUS_SUCCESS
            } else {
                Log.e(TAG, "Fallo al parsear JSON del testigo entrante en HCE")
                ApduConstants.STATUS_FAILED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando comando PASS_BATON", e)
            return ApduConstants.STATUS_FAILED
        }
    }

    private fun isSelectAidCommand(commandApdu: ByteArray): Boolean {
        if (commandApdu.size < 5) return false
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
            DEACTIVATION_LINK_LOSS -> "Enlace perdido (dispositivo fuera de rango NFC)"
            DEACTIVATION_DESELECTED -> "Deseleccionado"
            else -> "Razón: $reason"
        }
        Log.d(TAG, "Servicio HCE desactivado: $reasonStr")
    }
}
