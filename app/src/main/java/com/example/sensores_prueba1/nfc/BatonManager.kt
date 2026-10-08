package com.example.sensores_prueba1.nfc

import com.example.sensores_prueba1.data.model.BatonData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class HandoffEvent {
    data class BatonSent(val baton: BatonData, val timestamp: Long = System.currentTimeMillis()) : HandoffEvent()
    data class BatonReceived(val baton: BatonData, val timestamp: Long = System.currentTimeMillis()) : HandoffEvent()
    data class HandshakeError(val reason: String) : HandoffEvent()
    data class CooldownActive(val remainingSeconds: Int) : HandoffEvent()
}

/**
 * Singleton que gestiona el estado activo del testigo, la sincronización de grupo
 * y los eventos de traspaso NFC 100% automáticos con protección de enfriamiento anti-rebote (5s).
 */
object BatonManager {

    const val HANDOFF_COOLDOWN_MS = 5000L

    @Volatile
    private var lastHandoffTimestamp: Long = 0L

    private val _currentBaton = MutableStateFlow<BatonData?>(
        BatonData(
            raceId = "CARRERA-2026-ALFA",
            teamId = "EQUIPO-ALFA",
            legIndex = 1,
            runnerName = "Corredor 1",
            signatureToken = "SIG-INIT-001"
        )
    )
    val currentBaton: StateFlow<BatonData?> = _currentBaton.asStateFlow()

    private val _handoffEvents = MutableSharedFlow<HandoffEvent>(replay = 1, extraBufferCapacity = 64)
    val handoffEvents: SharedFlow<HandoffEvent> = _handoffEvents.asSharedFlow()

    // Etapa 1 inicia con el testigo (true), Etapas 2+ inician en espera (false)
    private val _isCarryingBaton = MutableStateFlow(true)
    val isCarryingBaton: StateFlow<Boolean> = _isCarryingBaton.asStateFlow()

    // Estado reactivo del tiempo de enfriamiento restante (en segundos)
    private val _cooldownRemainingSeconds = MutableStateFlow(0)
    val cooldownRemainingSeconds: StateFlow<Int> = _cooldownRemainingSeconds.asStateFlow()

    /**
     * Comprueba si el sistema está en el periodo de enfriamiento de 5 segundos tras un traspaso.
     */
    fun isHandoffInCooldown(now: Long = System.currentTimeMillis()): Boolean {
        return (now - lastHandoffTimestamp) < HANDOFF_COOLDOWN_MS
    }

    /**
     * Retorna los milisegundos restantes del periodo de enfriamiento de separación.
     */
    fun getRemainingCooldownMs(now: Long = System.currentTimeMillis()): Long {
        val elapsed = now - lastHandoffTimestamp
        return if (elapsed < HANDOFF_COOLDOWN_MS) HANDOFF_COOLDOWN_MS - elapsed else 0L
    }

    /**
     * Reinicia el periodo de enfriamiento (usado en configuración pre-inicio o pruebas unitarias).
     */
    fun resetCooldown() {
        lastHandoffTimestamp = 0L
        _cooldownRemainingSeconds.value = 0
    }

    /**
     * Actualiza el conteo de segundos restantes para observadores de UI.
     */
    fun setCooldownSeconds(seconds: Int) {
        _cooldownRemainingSeconds.value = maxOf(0, seconds)
    }

    /**
     * Sincronización Pre-Inicio del dispositivo en el grupo de relevos.
     * La portación inicial se determina estrictamente por la etapa:
     * - Etapa 1 (Corredor Inicial) -> isCarryingBaton = true
     * - Etapas 2+ (Relevistas en Espera) -> isCarryingBaton = false
     */
    fun setupGroupRunner(raceId: String, teamId: String, runnerName: String, legIndex: Int) {
        resetCooldown()
        val initialCarrying = (legIndex == 1)
        val initialToken = if (initialCarrying) {
            "SIG-START-LEG1-${System.currentTimeMillis() % 10000}"
        } else {
            "SIG-WAITING-LEG$legIndex"
        }

        val baton = BatonData(
            raceId = raceId.trim(),
            teamId = teamId.trim(),
            legIndex = legIndex,
            runnerName = runnerName.trim(),
            timestampMs = System.currentTimeMillis(),
            signatureToken = initialToken
        )

        _currentBaton.value = baton
        _isCarryingBaton.value = initialCarrying
    }

    /**
     * Actualización manual o forzada del estado del testigo.
     */
    fun updateBaton(baton: BatonData, carrying: Boolean) {
        _currentBaton.value = baton
        _isCarryingBaton.value = carrying
    }

    /**
     * Notificación cuando este dispositivo entrega el testigo al siguiente corredor.
     * Retorna true si el traspaso fue aceptado, false si fue rechazado o está en enfriamiento.
     */
    fun onBatonTransferredOut(readerInfo: String = ""): Boolean {
        if (!_isCarryingBaton.value) return false // Evitar emisiones duplicadas si ya fue transferido

        val now = System.currentTimeMillis()
        if (isHandoffInCooldown(now)) {
            val remainingSec = ((getRemainingCooldownMs(now) + 999) / 1000).toInt()
            _handoffEvents.tryEmit(HandoffEvent.CooldownActive(remainingSec))
            return false
        }

        val baton = _currentBaton.value ?: return false
        lastHandoffTimestamp = now
        _isCarryingBaton.value = false
        _handoffEvents.tryEmit(HandoffEvent.BatonSent(baton))
        return true
    }

    /**
     * Notificación cuando este dispositivo recibe el testigo del corredor anterior.
     * Realiza validación estricta de grupo / carrera y actualiza la etapa.
     * Retorna true si el testigo fue recibido con éxito, false si fue rechazado o está en enfriamiento.
     */
    fun onBatonReceivedIn(incomingBaton: BatonData): Boolean {
        val now = System.currentTimeMillis()
        if (isHandoffInCooldown(now)) {
            val remainingSec = ((getRemainingCooldownMs(now) + 999) / 1000).toInt()
            _handoffEvents.tryEmit(HandoffEvent.CooldownActive(remainingSec))
            return false
        }

        val current = _currentBaton.value

        // Validación estricta de ID de Carrera / Grupo
        if (current != null && current.raceId.isNotBlank() && incomingBaton.raceId.isNotBlank()) {
            if (!incomingBaton.raceId.equals(current.raceId, ignoreCase = true)) {
                onHandshakeFailed("Carrera/Grupo incompatible: '${incomingBaton.raceId}' (esperado: '${current.raceId}')")
                return false
            }
        }

        // Validación de Equipo si ambos están definidos
        if (current != null && current.teamId.isNotBlank() && incomingBaton.teamId.isNotBlank()) {
            if (!incomingBaton.teamId.equals(current.teamId, ignoreCase = true)) {
                onHandshakeFailed("Equipo incompatible: '${incomingBaton.teamId}' (esperado: '${current.teamId}')")
                return false
            }
        }

        // Si ya está portando el testigo para esta misma etapa o superior, ignorar duplicado
        if (_isCarryingBaton.value && current != null && current.legIndex >= (incomingBaton.legIndex + 1)) {
            return false
        }

        val targetLeg = maxOf(incomingBaton.legIndex + 1, current?.legIndex ?: (incomingBaton.legIndex + 1))
        val runnerName = current?.runnerName?.takeIf { it.isNotBlank() } ?: "Corredor $targetLeg"

        val activeBaton = incomingBaton.copy(
            legIndex = targetLeg,
            runnerName = runnerName,
            timestampMs = now,
            signatureToken = "SIG-RECV-LEG$targetLeg-${now % 10000}"
        )

        lastHandoffTimestamp = now
        _currentBaton.value = activeBaton
        _isCarryingBaton.value = true
        _handoffEvents.tryEmit(HandoffEvent.BatonReceived(activeBaton))
        return true
    }

    /**
     * Notificación de fallo en el intercambio de testigo.
     */
    fun onHandshakeFailed(reason: String) {
        _handoffEvents.tryEmit(HandoffEvent.HandshakeError(reason))
    }
}
