package com.example.sensores_prueba1.nfc

/**
 * Constants and helpers for ISO 7816-4 APDU communication with the Relay Baton.
 */
object ApduConstants {
    // Custom AID for the Relay Baton: F072656C61793031 ("relay01" in hex with F0 proprietary prefix)
    const val RELAY_AID_HEX = "F072656C61793031"

    // APDU Status Words (SW1, SW2)
    val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte()) // 9000: Success
    val STATUS_FAILED = byteArrayOf(0x6F.toByte(), 0x00.toByte())  // 6F00: Generic error
    val STATUS_UNKNOWN_CMD = byteArrayOf(0x6D.toByte(), 0x00.toByte()) // 6D00: Instruction not supported
    val STATUS_CLA_NOT_SUPPORTED = byteArrayOf(0x6E.toByte(), 0x00.toByte()) // 6E00: Class not supported

    // APDU Command Instructions (INS)
    const val INS_SELECT: Byte = 0xA4.toByte()      // 00 A4 04 00 [Lc] [AID]
    const val INS_GET_BATON: Byte = 0x10.toByte()   // 80 10 00 00 [Le] - Reader requesting baton payload
    const val INS_PASS_BATON: Byte = 0x20.toByte()  // 80 20 00 00 [Lc] [Data] - Reader pushing baton payload
    const val INS_PING: Byte = 0x01.toByte()        // 80 01 00 00 - Proximity ping

    // Standard ISO 7816-4 SELECT APDU Header (00 A4 04 00)
    val SELECT_APDU_HEADER = byteArrayOf(0x00.toByte(), 0xA4.toByte(), 0x04.toByte(), 0x00.toByte())

    /**
     * Builds a SELECT AID APDU command byte array for readers.
     */
    fun buildSelectAidApdu(aidHex: String = RELAY_AID_HEX): ByteArray {
        val aidBytes = hexStringToByteArray(aidHex)
        val header = SELECT_APDU_HEADER
        val lc = aidBytes.size.toByte()
        val le = 0x00.toByte()
        return header + byteArrayOf(lc) + aidBytes + byteArrayOf(le)
    }

    /**
     * Builds a GET_BATON APDU command.
     */
    fun buildGetBatonApdu(): ByteArray {
        return byteArrayOf(
            0x80.toByte(), // CLA
            INS_GET_BATON, // INS
            0x00.toByte(), // P1
            0x00.toByte(), // P2
            0x00.toByte()  // Le
        )
    }

    /**
     * Builds a PASS_BATON APDU command with a payload.
     */
    fun buildPassBatonApdu(payload: ByteArray): ByteArray {
        val header = byteArrayOf(
            0x80.toByte(),
            INS_PASS_BATON,
            0x00.toByte(),
            0x00.toByte()
        )
        val lc = payload.size.toByte()
        val le = 0x00.toByte()
        return header + byteArrayOf(lc) + payload + byteArrayOf(le)
    }

    /**
     * Converts a byte array to a hex string for logging and verification.
     */
    fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }

    /**
     * Converts a hex string to a byte array.
     */
    fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
