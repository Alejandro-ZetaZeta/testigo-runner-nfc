package com.example.sensores_prueba1.data.model

import org.json.JSONObject

/**
 * Data model representing the digital baton payload exchanged over NFC.
 */
data class BatonData(
    val raceId: String,
    val teamId: String,
    val legIndex: Int,
    val runnerName: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val signatureToken: String = ""
) {
    fun toJsonString(): String {
        return JSONObject().apply {
            put("raceId", raceId)
            put("teamId", teamId)
            put("legIndex", legIndex)
            put("runnerName", runnerName)
            put("timestampMs", timestampMs)
            put("signatureToken", signatureToken)
        }.toString()
    }

    companion object {
        fun fromJsonString(json: String): BatonData? {
            return try {
                val obj = JSONObject(json)
                BatonData(
                    raceId = obj.optString("raceId", "default_race"),
                    teamId = obj.optString("teamId", "team_alpha"),
                    legIndex = obj.optInt("legIndex", 1),
                    runnerName = obj.optString("runnerName", "Runner"),
                    timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
                    signatureToken = obj.optString("signatureToken", "")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
