package com.example.sensores_prueba1

import com.example.sensores_prueba1.data.model.BatonData
import com.example.sensores_prueba1.nfc.BatonManager
import com.example.sensores_prueba1.nfc.HandoffEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatonManagerUnitTest {

    @Test
    fun testPreInicioPhaseLeg1CarrierRole() {
        // Corredor Inicial (Etapa 1) -> debe iniciar portando el testigo (isCarryingBaton = true)
        BatonManager.setupGroupRunner(
            raceId = "CARRERA-NACIONAL-2026",
            teamId = "EQUIPO-ORO",
            runnerName = "Atleta 1",
            legIndex = 1
        )

        val baton = BatonManager.currentBaton.value
        assertEquals("CARRERA-NACIONAL-2026", baton?.raceId)
        assertEquals("EQUIPO-ORO", baton?.teamId)
        assertEquals(1, baton?.legIndex)
        assertEquals("Atleta 1", baton?.runnerName)
        assertTrue(BatonManager.isCarryingBaton.value)
    }

    @Test
    fun testPreInicioPhaseLeg2WaitingRole() {
        // Relevista en Espera (Etapa 2+) -> debe iniciar en espera (isCarryingBaton = false)
        BatonManager.setupGroupRunner(
            raceId = "CARRERA-NACIONAL-2026",
            teamId = "EQUIPO-ORO",
            runnerName = "Atleta 2",
            legIndex = 2
        )

        val baton = BatonManager.currentBaton.value
        assertEquals("CARRERA-NACIONAL-2026", baton?.raceId)
        assertEquals("EQUIPO-ORO", baton?.teamId)
        assertEquals(2, baton?.legIndex)
        assertEquals("Atleta 2", baton?.runnerName)
        assertFalse(BatonManager.isCarryingBaton.value)
    }

    @Test
    fun testAutomaticHandoffWithRaceIdValidation() = runBlocking {
        // Configurar Relevista 2 para carrera "GRUPO-A"
        BatonManager.setupGroupRunner(
            raceId = "GRUPO-A",
            teamId = "TEAM-1",
            runnerName = "Corredor 2",
            legIndex = 2
        )

        // 1. Intento de traspaso con carrera incompatible "GRUPO-B" -> debe ser rechazado
        val invalidBaton = BatonData(
            raceId = "GRUPO-B",
            teamId = "TEAM-1",
            legIndex = 1,
            runnerName = "Corredor 1"
        )
        BatonManager.onBatonReceivedIn(invalidBaton)
        val errorEvent = BatonManager.handoffEvents.replayCache.lastOrNull()
        assertTrue(errorEvent is HandoffEvent.HandshakeError)
        assertFalse(BatonManager.isCarryingBaton.value)

        // 2. Traspaso con carrera correcta "GRUPO-A" -> debe tener éxito
        val validBaton = BatonData(
            raceId = "GRUPO-A",
            teamId = "TEAM-1",
            legIndex = 1,
            runnerName = "Corredor 1"
        )
        BatonManager.onBatonReceivedIn(validBaton)
        val recvEvent = BatonManager.handoffEvents.replayCache.lastOrNull()
        assertTrue(recvEvent is HandoffEvent.BatonReceived)
        assertTrue(BatonManager.isCarryingBaton.value)
        assertEquals(2, BatonManager.currentBaton.value?.legIndex)
        assertEquals("Corredor 2", BatonManager.currentBaton.value?.runnerName)

        // 3. Salida de testigo a la siguiente etapa
        BatonManager.onBatonTransferredOut("Prueba de entrega a relevo 3")
        val sentEvent = BatonManager.handoffEvents.replayCache.lastOrNull()
        assertTrue(sentEvent is HandoffEvent.BatonSent)
        assertFalse(BatonManager.isCarryingBaton.value)
    }

    @Test
    fun testBatonDataJsonSerialization() {
        val original = BatonData(
            raceId = "RELAY-2026",
            teamId = "ALPHA",
            legIndex = 3,
            runnerName = "Speedster",
            timestampMs = 123456789L,
            signatureToken = "TOKEN-XYZ"
        )

        val json = original.toJsonString()
        val deserialized = BatonData.fromJsonString(json)

        assertEquals(original.raceId, deserialized?.raceId)
        assertEquals(original.teamId, deserialized?.teamId)
        assertEquals(original.legIndex, deserialized?.legIndex)
        assertEquals(original.runnerName, deserialized?.runnerName)
        assertEquals(original.timestampMs, deserialized?.timestampMs)
        assertEquals(original.signatureToken, deserialized?.signatureToken)
    }

    @Test
    fun testApduCommandsFormatting() {
        val selectApdu = com.example.sensores_prueba1.nfc.ApduConstants.buildSelectAidApdu()
        val selectHex = com.example.sensores_prueba1.nfc.ApduConstants.run { selectApdu.toHexString() }
        assertTrue(selectHex.startsWith("00A4040008F072656C61793031"))

        val getBatonApdu = com.example.sensores_prueba1.nfc.ApduConstants.buildGetBatonApdu()
        val getBatonHex = com.example.sensores_prueba1.nfc.ApduConstants.run { getBatonApdu.toHexString() }
        assertEquals("8010000000", getBatonHex)

        val passPayload = "TEST_PAYLOAD".toByteArray()
        val passApdu = com.example.sensores_prueba1.nfc.ApduConstants.buildPassBatonApdu(passPayload)
        val passHex = com.example.sensores_prueba1.nfc.ApduConstants.run { passApdu.toHexString() }
        assertTrue(passHex.startsWith("802000000C"))
    }
}
