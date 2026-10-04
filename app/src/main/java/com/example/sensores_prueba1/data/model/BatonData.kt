package com.example.sensores_prueba1.data.model

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
        fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return buildString {
            append("{")
            append("\"raceId\":\"").append(escape(raceId)).append("\",")
            append("\"teamId\":\"").append(escape(teamId)).append("\",")
            append("\"legIndex\":").append(legIndex).append(",")
            append("\"runnerName\":\"").append(escape(runnerName)).append("\",")
            append("\"timestampMs\":").append(timestampMs).append(",")
            append("\"signatureToken\":\"").append(escape(signatureToken)).append("\"")
            append("}")
        }
    }

    companion object {
        fun fromJsonString(json: String): BatonData? {
            return try {
                fun extractString(key: String): String {
                    val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
                    return pattern.find(json)?.groupValues?.get(1) ?: ""
                }
                fun extractLong(key: String, default: Long): Long {
                    val pattern = "\"$key\"\\s*:\\s*([0-9]+)".toRegex()
                    return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: default
                }
                fun extractInt(key: String, default: Int): Int {
                    val pattern = "\"$key\"\\s*:\\s*([0-9]+)".toRegex()
                    return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: default
                }

                val raceId = extractString("raceId").ifEmpty { "default_race" }
                val teamId = extractString("teamId").ifEmpty { "team_alpha" }
                val legIndex = extractInt("legIndex", 1)
                val runnerName = extractString("runnerName").ifEmpty { "Runner" }
                val timestampMs = extractLong("timestampMs", System.currentTimeMillis())
                val signatureToken = extractString("signatureToken")

                BatonData(
                    raceId = raceId,
                    teamId = teamId,
                    legIndex = legIndex,
                    runnerName = runnerName,
                    timestampMs = timestampMs,
                    signatureToken = signatureToken
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
