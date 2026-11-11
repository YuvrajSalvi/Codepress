package com.codepress.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** Parses [text] as a JSON object, or returns null when it is not one. */
fun parseObject(text: String): JsonObject? =
    try {
        AppJson.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        null
    }
