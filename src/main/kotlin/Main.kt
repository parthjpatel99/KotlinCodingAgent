package org.example

import kotlin.system.exitProcess

fun main() {
    val apiKey = System.getenv("OPENAI_API_KEY")
    if (apiKey.isNullOrBlank()) {
        println("Error: OPENAI_API_KEY environment variable not set")
        exitProcess(1)
    }
    Agent(OpenAiClient(apiKey)).run()
}