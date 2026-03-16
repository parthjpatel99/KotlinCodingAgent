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

private const val YOU_COLOR       = "\u001b[94m"
private const val ASSISTANT_COLOR = "\u001b[93m"
private const val RESET_COLOR     = "\u001b[0m"

private val SYSTEM_PROMPT_TEMPLATE = """
You are a helpful coding assistant whose goal is to help complete coding tasks.
Your current working directory is: {cwd}

You have access to a series of tools you can execute. Here are the tools you have access to:

{tool_list_str}

Rules:
- When you want to use a tool, reply with EXACTLY ONE line in the format: 'tool: TOOL_NAME({JSON_ARGS})' and nothing else.
- Only call ONE tool per response. Wait for the result before calling the next tool.
- Use compact single-line JSON with double quotes. Escape any double quotes inside string values with \".
- After receiving a tool_result(...) message, continue the task.
- If no tool is needed, respond normally.
""".trimIndent()

class Agent(private val client: OpenAiClient) {

    fun run() {
        val cwd = System.getProperty("user.dir") ?: "unknown"
        val systemPrompt = SYSTEM_PROMPT_TEMPLATE
            .replace("{cwd}", cwd)
            .replace("{tool_list_str}", getToolListString())
        val conversation = mutableListOf(ChatMessage("system", systemPrompt))

        while (true) {
            print("${YOU_COLOR}You${RESET_COLOR}: ")
            val userInput = readLine() ?: break
            if (userInput.isBlank()) continue
            conversation.add(ChatMessage("user", userInput.trim()))

            var iterations = 0
            while (iterations < 20) {
                iterations++

                val response = try {
                    client.chat(conversation)
                } catch (e: Exception) {
                    println("Error: ${e.message}")
                    break
                }

                println("${ASSISTANT_COLOR}Assistant${RESET_COLOR}: $response")
                conversation.add(ChatMessage("assistant", response))

                val invocations = parseToolInvocations(response).take(5)
                if (invocations.isEmpty()) {
                    // Model tried to call a tool but all lines failed to parse — tell it
                    if (response.lines().any { it.trim().startsWith("tool:") }) {
                        conversation.add(ChatMessage("user", "tool_result({\"error\": \"tool call could not be parsed — use compact single-line JSON with escaped double quotes\"})"))
                        continue
                    }
                    break
                }

                for ((name, args) in invocations) {
                    val toolFn = toolRegistry[name]
                    val resultMap = if (toolFn != null) {
                        toolFn(args)
                    } else {
                        mapOf("error" to "unknown tool: $name")
                    }
                    val json = mapper.writeValueAsString(resultMap)
                    conversation.add(ChatMessage("user", "tool_result($json)"))
                }
            }

            if (iterations >= 20) {
                println("[Warning: max tool iterations reached]")
            }
        }
    }
}