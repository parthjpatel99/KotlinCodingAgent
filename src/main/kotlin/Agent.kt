package org.example

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

data class ChatMessage(val role: String, val content: String)

val mapper = jacksonObjectMapper()

fun parseToolInvocations(text: String): List<Pair<String, Map<String, Any>>> {
    val invocations = mutableListOf<Pair<String, Map<String, Any>>>()
    for (rawLine in text.lines()) {
        val line = rawLine.trim()
        if (!line.startsWith("tool:")) continue
        try {
            val after = line.removePrefix("tool:").trim()
            val name  = after.substringBefore("(").trim()
            val rest  = after.substringAfter("(")
            if (!rest.endsWith(")")) continue
            val jsonStr = rest.dropLast(1).trim()
            @Suppress("UNCHECKED_CAST")
            val args = mapper.readValue(jsonStr, Map::class.java) as Map<String, Any>
            invocations.add(name to args)
        } catch (e: Exception) {
            continue
        }
    }
    return invocations
}