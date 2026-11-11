package com.dngro.transit.data

import com.dngro.transit.presentation.WearBusList
import com.dngro.transit.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.serialization.json.Json

/**
 * A parser that uses Gemini to extract structured transit data from raw MTA SIRI JSON.
 * This handles the deep nesting and varied status formats of the MTA API.
 */
class MtaGeminiParser(apiKey: String) {

    private val model = GenerativeModel(
        modelName = "gemini-1.5-flash", // Use stable model name
        apiKey = apiKey,
        generationConfig = generationConfig {
            responseMimeType = "application/json"
        }
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses raw MTA SIRI JSON into a clean [WearBusList] using Gemini.
     */
    suspend fun parseMtaDataWithGemini(rawMtaJson: String): WearBusList {
        val response = model.generateContent(
            content {
                text("Extract all active buses, their route name, destination, presentable distance status, and number of stops away from this raw MTA SIRI response:")
                text(rawMtaJson)
            }
        )
        
        val cleanJsonString = response.text ?: "{ \"buses\": [] }"
        return try {
            json.decodeFromString<WearBusList>(cleanJsonString)
        } catch (e: Exception) {
            e.printStackTrace()
            WearBusList(emptyList())
        }
    }
}
