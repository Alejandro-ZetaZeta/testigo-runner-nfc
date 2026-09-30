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
}

/**
 * Singleton que gestiona el estado activo del testigo, el progreso del relevo y eventos NFC.
 */
object BatonManager {

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

    private val _handoffEvents = MutableSharedFlow<HandoffEvent>(extraBufferCapacity = 64)
    val handoffEvents: SharedFlow<HandoffEvent> = _handoffEvents.asSharedFlow()

    private val _isCarryingBaton = MutableStateFlow(true)
    val isCarryingBaton: StateFlow<Boolean> = _isCarryingBaton.asStateFlow()

    fun updateBaton(baton: BatonData, carrying: Boolean) {
        _currentBaton.value = baton
        _isCarryingBaton.value = carrying
    }

    fun onBatonTransferredOut(readerInfo: String = "") {
        val baton = _currentBaton.value ?: return
        _isCarryingBaton.value = false
        _handoffEvents.tryEmit(HandoffEvent.BatonSent(baton))
    }

    fun onBatonReceivedIn(incomingBaton: BatonData) {
        _currentBaton.value = incomingBaton
        _isCarryingBaton.value = true
        _handoffEvents.tryEmit(HandoffEvent.BatonReceived(incomingBaton))
    }

    fun onHandshakeFailed(reason: String) {
        _handoffEvents.tryEmit(HandoffEvent.HandshakeError(reason))
    }
}
