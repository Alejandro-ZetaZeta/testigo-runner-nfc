package com.example.sensores_prueba1.nfc

import android.app.Activity
import android.content.ComponentName
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import com.example.sensores_prueba1.data.model.BatonData
import com.example.sensores_prueba1.nfc.ApduConstants.toHexString
import java.nio.charset.StandardCharsets

/**
 * Controlador del Modo Lector NFC de Android y gestión de emulación HCE.
 *
 * Funciona de forma inteligente y adaptativa según el rol:
 * - Si el dispositivo está EN ESPERA (Relevista): el Lector NFC escanea activamente
 *   el teléfono del corredor entrante (que emula una tarjeta HCE) para extraer el testigo vía GET_BATON.
 * - Si el dispositivo es PORTADOR: emula la tarjeta HCE del testigo con prioridad de primer plano
 *   para ser leído al instante por el relevista en espera.
 */
class RelayBatonReader(private val activity: Activity) : NfcAdapter.ReaderCallback {

    companion object {
        private const val TAG = "RelayBatonReader"
        // Optimizado para ISO 14443-4 Type A/B con omisión de NDEF para lectura ultra rápida (<50ms)
        private const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
    }

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    var onPhysicalContactDetected: ((tagIdHex: String) -> Unit)? = null
    private var isScanningActive = false

    fun isNfcAvailable(): Boolean = nfcAdapter != null
    fun isNfcEnabled(): Boolean = nfcAdapter?.isEnabled == true

    /**
     * Inicia el escaneo en Modo Lector NFC.
     */
    fun startScanning() {
        if (nfcAdapter == null || !nfcAdapter.isEnabled) {
            Log.w(TAG, "NFC no está disponible o no está encendido en ajustes del sistema")
            return
        }
        if (isScanningActive) return

        try {
            val options = Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 100)
            }
            nfcAdapter.enableReaderMode(activity, this, READER_FLAGS, options)
            isScanningActive = true
            Log.i(TAG, "Lector NFC activo escaneando dispositivos de relevo...")
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar Reader Mode", e)
        }
    }

    /**
     * Detiene el escaneo en Modo Lector NFC, liberando el chip para modo escucha HCE puro.
     */
    fun stopScanning() {
        if (!isScanningActive) return
        try {
            nfcAdapter?.disableReaderMode(activity)
            isScanningActive = false
            Log.i(TAG, "Lector NFC detenido (Modo HCE puro activo)")
        } catch (e: Exception) {
            Log.e(TAG, "Error al detener Lector NFC", e)
        }
    }

    /**
     * Registra RelayBatonHceService como servicio HCE prioritario en primer plano.
     * Esencial en Android para que las solicitudes APDU con AID de categoría "other"
     * se enruten directamente a esta aplicación.
     */
    fun setPreferredHceService(activity: Activity) {
        if (nfcAdapter == null || !nfcAdapter.isEnabled) return
        try {
            val cardEmulation = CardEmulation.getInstance(nfcAdapter)
            val component = ComponentName(activity, RelayBatonHceService::class.java)
            val success = cardEmulation.setPreferredService(activity, component)
            Log.i(TAG, "HCE setPreferredService resultado: $success")
        } catch (e: Exception) {
            Log.e(TAG, "Error al registrar HCE preferred service", e)
        }
    }

    /**
     * Desregistra el servicio HCE preferido al pausar la actividad.
     */
    fun unsetPreferredHceService(activity: Activity) {
        if (nfcAdapter == null || !nfcAdapter.isEnabled) return
        try {
            val cardEmulation = CardEmulation.getInstance(nfcAdapter)
            val success = cardEmulation.unsetPreferredService(activity)
            Log.i(TAG, "HCE unsetPreferredService resultado: $success")
        } catch (e: Exception) {
            Log.e(TAG, "Error al desregistrar HCE preferred service", e)
        }
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) return
        val tagIdHex = tag.id?.toHexString() ?: "DESCONOCIDO"
        Log.i(TAG, "¡Contacto NFC detectado! ID: $tagIdHex")

        // Retroalimentación visual y auditiva inmediata de contacto físico
        activity.runOnUiThread {
            onPhysicalContactDetected?.invoke(tagIdHex)
        }

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            // Detección por proximidad de contacto físico con iPhone o etiqueta NFC física
            Log.d(TAG, "Tag sin IsoDep, ejecutando protocolo de proximidad")
            handleProximityTapHandoff(tagIdHex)
            return
        }

        try {
            isoDep.connect()
            isoDep.timeout = 4000

            // 1. Enviar SELECT AID APDU para conectar con RelayBatonHceService
            val selectCommand = ApduConstants.buildSelectAidApdu()
            val selectResponse = isoDep.transceive(selectCommand)

            if (!isSuccessResponse(selectResponse)) {
                Log.w(TAG, "SELECT AID no coincidió (${selectResponse.toHexString()}), recurriendo a contacto físico.")
                handleProximityTapHandoff(tagIdHex)
                return
            }

            Log.i(TAG, "Conexión APDU establecida con éxito con dispositivo remoto")

            // 2. Intercambio de protocolo APDU bidireccional
            if (BatonManager.isCarryingBaton.value) {
                passBatonToTarget(isoDep)
            } else {
                fetchBatonFromTarget(isoDep)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Excepción durante transceive APDU, recurriendo a proximidad: ${e.message}")
            handleProximityTapHandoff(tagIdHex)
        } finally {
            try {
                if (isoDep.isConnected) {
                    isoDep.close()
                }
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
                BatonManager.onBatonTransferredOut("Contacto NFC con dispositivo ($tagIdHex)")
                BatonManager.updateBaton(updatedBaton, carrying = false)
            } else if (current != null) {
                val nextLeg = current.legIndex
                val updatedBaton = current.copy(
                    legIndex = nextLeg,
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
            val incomingBaton = BatonData.fromJsonString(jsonString)

            if (incomingBaton != null) {
                val current = BatonManager.currentBaton.value
                if (current != null && current.raceId.isNotBlank() && incomingBaton.raceId.isNotBlank() &&
                    !incomingBaton.raceId.equals(current.raceId, ignoreCase = true)
                ) {
                    activity.runOnUiThread {
                        BatonManager.onHandshakeFailed("Carrera '${incomingBaton.raceId}' no coincide con '${current.raceId}'")
                    }
                    return
                }

                Log.i(TAG, "Testigo recibido con éxito vía GET_BATON: $jsonString")
                activity.runOnUiThread {
                    BatonManager.onBatonReceivedIn(incomingBaton)
                }
            } else {
                Log.e(TAG, "Error al parsear JSON del testigo en fetchBatonFromTarget: $jsonString")
            }
        } else {
            Log.e(TAG, "GET_BATON falló o fue rechazado, respuesta: ${response.toHexString()}")
        }
    }

    private fun passBatonToTarget(isoDep: IsoDep) {
        val baton = BatonManager.currentBaton.value ?: return
        val payloadBytes = baton.toJsonString().toByteArray(StandardCharsets.UTF_8)
        val passCommand = ApduConstants.buildPassBatonApdu(payloadBytes)

        val response = isoDep.transceive(passCommand)
        if (isSuccessResponse(response)) {
            Log.i(TAG, "Testigo entregado con éxito por PASS_BATON")
            activity.runOnUiThread {
                BatonManager.onBatonTransferredOut("Lector NFC entregó testigo al receptor HCE")
            }
        } else {
            Log.e(TAG, "PASS_BATON rechazado o fallido, respuesta: ${response.toHexString()}")
            activity.runOnUiThread {
                BatonManager.onHandshakeFailed("Receptor rechazó la entrega del testigo")
            }
        }
    }

    private fun isSuccessResponse(response: ByteArray): Boolean {
        if (response.size < 2) return false
        val sw1 = response[response.size - 2]
        val sw2 = response[response.size - 1]
        return sw1 == 0x90.toByte() && sw2 == 0x00.toByte()
    }
}
