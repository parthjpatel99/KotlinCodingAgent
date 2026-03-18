# Kotlin Coding Agent — Design Spec

**Date:** 2026-03-15
**Status:** Approved

## Overview

A CLI coding agent in Kotlin that connects to the OpenAI API via the official `openai-java` SDK. It runs an interactive agentic loop: reads user input, calls the model, parses tool invocations from plain text, executes tools, feeds results back, until the model responds without tools.

**Key design note:** The agent uses prompt-engineering to get the model to emit tool calls as plain text (`tool: NAME({json})`). The OpenAI SDK's native tool/function-call mechanism is intentionally NOT used.

## Goals

- Replicate the behavior of the Python reference agent in idiomatic Kotlin
- Showcase clean Kotlin project structure on GitHub

## Out of Scope (v1)

- Additional tools beyond `read_file`, `list_files`, `edit_file`
- Streaming responses
- Persistent conversation history across sessions
- Binary file handling / file size limits

---

## File Structure

```
src/main/kotlin/
├── Main.kt
├── Agent.kt
├── Tools.kt
└── OpenAiClient.kt
```

---

## Types

### `ChatMessage` (defined in `Agent.kt`)

```kotlin
data class ChatMessage(val role: String, val content: String)
```

Valid roles: `"system"`, `"user"`, `"assistant"`.

**Tool results are intentionally sent as plain `"user"`-role string messages** — not using the SDK's native tool-call types. The model is prompted to interpret `tool_result({json})` strings as tool outputs.

`OpenAiClient` maps roles to SDK types:

- `"system"` → `ChatCompletionSystemMessageParam`
- `"user"` → `ChatCompletionUserMessageParam`
- `"assistant"` → `ChatCompletionAssistantMessageParam`

---

## Data Flow

1. `Main.kt` reads `OPENAI_API_KEY`, instantiates `OpenAiClient` and `Agent`, calls `Agent.run()`.
2. `Agent.run()` builds system prompt via `getToolListString()`, initializes conversation with system message, enters outer loop.
3. `print("${YOU_COLOR}You${RESET_COLOR}: ")` (no newline), `readLine() ?: break` reads user input. Append as `"user"` message.
4. **Inner loop** (max 10 iterations per user turn):
   a. `client.chat(conversation)` → response string.
   b. Print `"${ASSISTANT_COLOR}Assistant${RESET_COLOR}: $response"`.
   c. Append response as `"assistant"` message.
   d. Parse response for `tool:` lines.
   e. If tools found: execute sequentially; for each, append one `"user"` message with content `"tool_result($json)"`; continue inner loop.
   f. If no tools: break inner loop.
5. If inner loop reaches 10 iterations: print `"[Warning: max tool iterations reached]"`, break to outer loop.
6. `readLine()` returns `null` → break outer loop, return.

---

## Components

### `OpenAiClient.kt`

```kotlin
class OpenAiClient(apiKey: String) {
    fun chat(messages: List<ChatMessage>): String
}
```

- Converts `List<ChatMessage>` to SDK message types before each call.
- **Only uses plain text completion** — does NOT configure SDK tool definitions. Extracts the response via `.choices()[0].message().content().orElse("")`.
- If the content field is absent/null: return `""` (empty string).
- Model constant: `"gpt-4o"`.
- `.maxCompletionTokens(2000L)`.
- Initialised with `OpenAIOkHttpClient.builder().apiKey(apiKey).build()`.
- If the API returns a context-length error or any `OpenAIException`: rethrow as-is (Agent.kt prints and breaks inner loop — see error handling).

### `Tools.kt`

```kotlin
typealias ToolFn = (Map<String, Any>) -> Map<String, Any>
```

All tool functions have signature `(Map<String, Any>) -> Map<String, Any>`. Each extracts its own parameters from the input map using `args["key"] as? String ?: ""` — all tool parameters are strings, so this cast is always safe for well-formed model output. Missing keys default to `""`. Each wraps its entire body in `try { ... } catch (e: Exception) { mapOf("error" to (e.message ?: "unknown error")) }`.

**JSON parsing and serialization:** use `jacksonObjectMapper()` (from `jackson-module-kotlin`) rather than `ObjectMapper()` directly, to ensure the Kotlin module is registered. A single shared `val mapper = jacksonObjectMapper()` is defined as a top-level val in `Agent.kt` and reused for both parsing tool invocations and serializing tool results.

**Path resolution:** `Path.of(rawStr.ifBlank { "." }).toAbsolutePath().normalize()`

**File encoding:** UTF-8 throughout.

#### `readFileTool`

- Input key: `"filename"`
- Returns: `{"file_path": "<abs path>", "content": "<text>"}`
- On any error: `{"error": "<message>"}`

#### `listFilesTool`

- Input key: `"path"`
- **Shallow listing** (one level, non-recursive).
- Returns: `{"path": "<abs path>", "files": [{"filename": "<name>", "type": "file"|"dir"}, ...]}`
- Empty directory: `{"path": "...", "files": []}`
- Path not found, not a directory, or any IO error: caught by outer try/catch → `{"error": "<message>"}`

#### `editFileTool`

- Input keys: `"path"`, `"old_str"` (default `""`), `"new_str"` (default `""`)

| Condition | Action | Return |
|-----------|--------|--------|
| `old_str == ""` and parent dir does not exist | No write | `{"error": "parent directory does not exist: <path>"}` |
| `old_str == ""` and parent exists | Write/overwrite with `new_str` (UTF-8) | `{"path": "...", "action": "created_file"}` — always `"created_file"` regardless of whether file previously existed. Intentional: matches Python reference exactly. |
| `old_str` found | Replace first occurrence, write (UTF-8) | `{"path": "...", "action": "edited"}` |
| `old_str` not found | No write | `{"path": "...", "action": "old_str not found"}` — uses `"action"` key (not `"error"`) matching Python reference |
| `path` resolves to a directory or any other IO error | Caught by outer try/catch | `{"error": "<message>"}` |

Parent directory check is explicit (`Files.exists(path.parent)`), performed before the write attempt.

#### Tool Registry

```kotlin
val toolRegistry: Map<String, ToolFn> = mapOf(
    "read_file"  to ::readFileTool,
    "list_files" to ::listFilesTool,
    "edit_file"  to ::editFileTool
)
```

#### `getToolListString(): String`

Returns the following **hardcoded string** (KDoc is not available via reflection at runtime):

```
TOOL
===
    Name: read_file
    Description: Gets the full content of a file provided by the user.
    Signature: (filename: String)
===============
TOOL
===
    Name: list_files
    Description: Lists the files in a directory provided by the user.
    Signature: (path: String)
===============
TOOL
===
    Name: edit_file
    Description: Replaces first occurrence of old_str with new_str in file. If old_str is empty, creates/overwrites file with new_str.
    Signature: (path: String, old_str: String, new_str: String)
===============
```

### `Agent.kt`

```kotlin
class Agent(private val client: OpenAiClient) {
    fun run()
}
```

- `conversation: MutableList<ChatMessage>` — system message first.
- **Tool result serialization:** `mapper.writeValueAsString(resultMap)` → content = `"tool_result($json)"`. One `"user"` message appended per tool result. (`mapper` = shared `jacksonObjectMapper()` top-level val.)
- **Tool dispatch:** look up in `toolRegistry`; if not found → result map = `mapOf("error" to "unknown tool: $name")`, appended as tool_result, loop continues.
- **API exceptions from `client.chat()`:** caught in the inner loop with `try/catch(Exception)`; print `"Error: ${e.message}"`, break inner loop.
- **Context-window / other API errors:** handled by the same catch block — print and break.
- Terminal constants: `YOU_COLOR = "\u001b[94m"`, `ASSISTANT_COLOR = "\u001b[93m"`, `RESET_COLOR = "\u001b[0m"`

### `Main.kt`

```kotlin
fun main() {
    val apiKey = System.getenv("OPENAI_API_KEY")
    if (apiKey.isNullOrBlank()) {
        println("Error: OPENAI_API_KEY environment variable not set")
        exitProcess(1)
    }
    Agent(OpenAiClient(apiKey)).run()
}
```

---

## System Prompt Construction

The system prompt string is built by calling `.replace("{tool_list_str}", getToolListString())` on the template below. Kotlin raw string (`"""..."""`) is used to avoid escaping; `{tool_list_str}` is a literal brace placeholder replaced via `.replace()`, not a Kotlin string template `${}`.

Template:

```
You are a helpful coding assistant whose goal is to help complete coding tasks.
You have access to a series of tools you can execute. Here are the tools you have access to:

{tool_list_str}

When you want to use a tool reply with exactly one line in the format: 'tool: TOOL_NAME({JSON_ARGS})' and nothing else.
Use compact single-line JSON with double quotes. After receiving a tool_result(...) message, continue the task.
If no tool is needed, respond normally.
```

---

## Tool Invocation Parsing

For each line in the assistant response:

1. `val line = rawLine.trim()`
2. If `!line.startsWith("tool:")` → skip
3. `val after = line.removePrefix("tool:").trim()`
4. `val name = after.substringBefore("(").trim()`
5. `val rest = after.substringAfter("(")`
6. If `!rest.endsWith(")")` → skip (malformed line)
7. `val jsonStr = rest.dropLast(1).trim()`
8. Parse `jsonStr` as `Map<String, Any>` via `mapper.readValue(jsonStr)`; on `JsonProcessingException` → skip line

**Known limitation:** if a JSON string value ends with `)`, `dropLast(1)` will produce malformed JSON and the line will be silently skipped. This matches the Python reference behavior and is acceptable for v1.

---

## Dependencies

Add to the existing `build.gradle.kts` `dependencies` block:

```kotlin
dependencies {
    implementation("com.openai:openai-java:4.28.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")
    testImplementation(kotlin("test"))
}
```

---

## Error Handling Summary

| Scenario | Behavior |
|----------|----------|
| Missing `OPENAI_API_KEY` | `println` error, `exitProcess(1)` |
| Tool not in registry | `{"error": "unknown tool: <name>"}` appended as tool_result, continue |
| Missing/wrong-type keys in tool | Tool's try/catch returns `{"error": "..."}` |
| IO errors in tools | Tool's try/catch returns `{"error": "..."}` |
| `old_str` not found in `edit_file` | `{"action": "old_str not found"}`, no write |
| API exception in `chat()` | Print `"Error: <message>"`, break inner loop |
| Inner loop hits 10 iterations | Print warning, break to outer loop |
| `readLine()` returns null | Break outer loop, exit cleanly |
