# Kotlin Coding Agent Implementation Plan

> **Implementation style:** Tests are pre-written. You implement everything else. Read each failing test carefully — the test *is* the spec for that unit.

**Goal:** Build a CLI coding agent in Kotlin that uses OpenAI's gpt-4o model to execute file-system tasks via a prompt-engineered agentic loop.

**Architecture:** `Agent.kt` drives an outer/inner conversation loop; `Tools.kt` provides three file-system tools dispatched by parsing plain-text model output; `OpenAiClient.kt` wraps the `openai-java` SDK. Tool calls stay as plain user-role strings — the SDK's native tool API is not used.

**Tech Stack:** Kotlin 2.3.0 / JVM 21, `com.openai:openai-java:4.28.0`, `com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3`, Kotlin Test + JUnit Platform

---

## File Map

| File | Who | Responsibility |
|------|-----|---------------|
| `build.gradle.kts` | You | Add dependencies + application plugin |
| `src/main/kotlin/Tools.kt` | You | Tool functions, registry, system-prompt string |
| `src/main/kotlin/Agent.kt` | You | ChatMessage type, mapper, parser, Agent loop |
| `src/main/kotlin/OpenAiClient.kt` | You | openai-java SDK wrapper |
| `src/main/kotlin/Main.kt` | You | Entry point wiring |
| `src/test/kotlin/ToolsTest.kt` | ✅ Pre-written | Unit tests for all three tools |
| `src/test/kotlin/AgentParserTest.kt` | ✅ Pre-written | Unit tests for tool invocation parser |

---

## Step 0: Pre-written Tests (already done)

The test files are already written at:
- `src/test/kotlin/ToolsTest.kt` — 12 tests covering `readFileTool`, `listFilesTool`, `editFileTool`
- `src/test/kotlin/AgentParserTest.kt` — 7 tests covering `parseToolInvocations`

Run them at any time with:
```bash
./gradlew test          # all tests
./gradlew test --tests "ToolsTest"          # tools only
./gradlew test --tests "AgentParserTest"    # parser only
```

---

## Phase 1: Project Setup

- [ ] **Update `build.gradle.kts`**

  Add the `application` plugin, `mainClass`, and new dependencies. The full final file should look like:

  ```kotlin
  plugins {
      kotlin("jvm") version "2.3.0"
      application
  }

  group = "org.example"
  version = "1.0-SNAPSHOT"

  application {
      mainClass.set("org.example.MainKt")
  }

  repositories {
      mavenCentral()
  }

  dependencies {
      implementation("com.openai:openai-java:4.28.0")
      implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")
      testImplementation(kotlin("test"))
  }

  kotlin {
      jvmToolchain(21)
  }

  tasks.test {
      useJUnitPlatform()
  }
  ```

- [ ] **Verify:** `./gradlew build` → `BUILD SUCCESSFUL`

---

## Phase 2: `Tools.kt`

**File to create:** `src/main/kotlin/Tools.kt`

The tests in `ToolsTest.kt` define exactly what each function must do. Run them after implementing each tool:

```bash
./gradlew test --tests "ToolsTest.readFileTool*"
./gradlew test --tests "ToolsTest.listFilesTool*"
./gradlew test --tests "ToolsTest.editFileTool*"
```

### What to implement

**`typealias ToolFn`** — type alias for all tool functions:
```kotlin
typealias ToolFn = (Map<String, Any>) -> Map<String, Any>
```

**`resolvePath(rawStr: String): Path`** — converts a raw string to an absolute, normalized `Path`. If blank, default to `"."`.

**`readFileTool(args: Map<String, Any>): Map<String, Any>`**
- Extract `"filename"` from args (default `""`)
- Read the file at that path as UTF-8 text
- Return `{"file_path": <absolute path>, "content": <text>}`
- On any error: return `{"error": <message>}`

**`listFilesTool(args: Map<String, Any>): Map<String, Any>`**
- Extract `"path"` from args (default `"."`)
- List entries one level deep (shallow, non-recursive)
- Return `{"path": <abs path>, "files": [{"filename": <name>, "type": "file"|"dir"}, ...]}`
- If path is not a directory or any error: return `{"error": <message>}`

**`editFileTool(args: Map<String, Any>): Map<String, Any>`**
- Extract `"path"`, `"old_str"` (default `""`), `"new_str"` (default `""`)
- If `old_str == ""`:
  - If parent directory doesn't exist: return `{"error": "parent directory does not exist: <parent>"}`
  - Otherwise: write/overwrite file with `new_str` (UTF-8), return `{"path": ..., "action": "created_file"}`
- If `old_str` found in file: replace first occurrence, return `{"path": ..., "action": "edited"}`
- If `old_str` not found: return `{"path": ..., "action": "old_str not found"}` — no write
- On IO error: return `{"error": <message>}`

**Kotlin hints:**
- Use `Files.readString(path, Charsets.UTF_8)` and `Files.writeString(path, text, Charsets.UTF_8)` from `java.nio.file.Files`
- Use `Files.list(dir).use { stream -> stream.map {...}.toList() }` — the `use {}` closes the stream
- `String.replaceFirst(oldValue, newValue)` replaces the first occurrence

**`toolRegistry`** — top-level `val` mapping tool names to functions:
```kotlin
val toolRegistry: Map<String, ToolFn> = mapOf(
    "read_file"  to ::readFileTool,
    "list_files" to ::listFilesTool,
    "edit_file"  to ::editFileTool
)
```

**`getToolListString(): String`** — returns this exact hardcoded string (the model reads this):

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

Use a raw Kotlin string (`"""..."""`) and `.trimIndent()`.

- [ ] **Verify:** `./gradlew test --tests "ToolsTest"` → 12 tests PASS

---

## Phase 3: `Agent.kt` — Types, Mapper, and Parser

**File to create:** `src/main/kotlin/Agent.kt`

Start with just the types and parser — the `Agent` class comes in Phase 5.

```bash
./gradlew test --tests "AgentParserTest"
```

### What to implement

**`data class ChatMessage`** — simple message with a role and content string. Valid roles: `"system"`, `"user"`, `"assistant"`.

**`val mapper`** — top-level Jackson object mapper. Use `jacksonObjectMapper()` from `com.fasterxml.jackson.module.kotlin` (this auto-registers the Kotlin module, enabling proper `Map<String, Any>` handling).

**`fun parseToolInvocations(text: String): List<Pair<String, Map<String, Any>>>`**

The model emits tool calls as plain text lines in this exact format:
```
tool: TOOL_NAME({"arg1": "val1", "arg2": "val2"})
```

Parse each line of `text`:
1. Trim whitespace
2. Skip if doesn't start with `"tool:"`
3. Remove `"tool:"` prefix, trim → `after`
4. `name = after.substringBefore("(").trim()`
5. `rest = after.substringAfter("(")`
6. Skip if `rest` doesn't end with `")"`
7. `jsonStr = rest.dropLast(1).trim()`
8. Parse `jsonStr` as `Map<String, Any>` using `mapper`; skip on failure

Return list of `(name, args)` pairs.

**Kotlin hints:**
- Use `text.lines()` to split by newline
- `mapper.readValue(jsonStr, Map::class.java)` returns `Map<*, *>` — you'll need an `@Suppress("UNCHECKED_CAST")` and `as Map<String, Any>`
- Wrap the whole per-line logic in `try/catch(Exception)` and `continue` on failure

- [ ] **Verify:** `./gradlew test --tests "AgentParserTest"` → 7 tests PASS
- [ ] **Verify:** `./gradlew test` → all 19 tests PASS

---

## Phase 4: `OpenAiClient.kt`

**File to create:** `src/main/kotlin/OpenAiClient.kt`

No unit tests — this wraps an HTTP client. The E2E smoke test in Phase 6 is the verification.

### What to implement

```kotlin
class OpenAiClient(apiKey: String) {
    fun chat(messages: List<ChatMessage>): String
}
```

**Setup:**
```kotlin
private val client: OpenAIClient = OpenAIOkHttpClient.builder()
    .apiKey(apiKey)
    .build()
```

**`chat()` implementation:**
- Build a `ChatCompletionCreateParams` using the builder pattern
- Set model to `ChatModel.GPT_4O` and `maxCompletionTokens(2000L)`
- For each `ChatMessage`, add it to the builder based on role:
  - `"system"` → `builder.addSystemMessage(content)` *(if compile error, try `addDeveloperMessage`)*
  - `"assistant"` → `builder.addAssistantMessage(content)`
  - `"user"` → `builder.addUserMessage(content)`
- Call `client.chat().completions().create(params)`
- Extract the response: `completion.choices().firstOrNull()?.message()?.content()?.orElse("") ?: ""`

**Kotlin hints — Imports you'll need:**
```kotlin
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
```

**Agentic AI note:** This is the "brain" boundary — everything outside this class is pure Kotlin/JVM logic. All the interesting agent behavior (the loop, tool parsing, conversation history) lives in `Agent.kt`. Keeping the LLM call isolated here makes the agent easy to test and swap out.

- [ ] **Verify:** `./gradlew build` → `BUILD SUCCESSFUL`

---

## Phase 5: `Agent.kt` — The Run Loop

**File to modify:** `src/main/kotlin/Agent.kt` (append to existing content from Phase 3)

This is the core of the coding agent — an **agentic loop**. Study it carefully.

### System prompt constants

```kotlin
private const val YOU_COLOR       = "\u001b[94m"
private const val ASSISTANT_COLOR = "\u001b[93m"
private const val RESET_COLOR     = "\u001b[0m"
```

The system prompt template (use a raw string, replace `{tool_list_str}` with `.replace()` — not `${}` interpolation, since the braces would conflict):

```
You are a helpful coding assistant whose goal is to help complete coding tasks.
You have access to a series of tools you can execute. Here are the tools you have access to:

{tool_list_str}

When you want to use a tool reply with exactly one line in the format: 'tool: TOOL_NAME({JSON_ARGS})' and nothing else.
Use compact single-line JSON with double quotes. After receiving a tool_result(...) message, continue the task.
If no tool is needed, respond normally.
```

### The `Agent` class

```kotlin
class Agent(private val client: OpenAiClient) {
    fun run()
}
```

**`run()` loop structure:**

```
Outer loop (one iteration per user message):
  ├── print "You: " prompt (no newline)
  ├── readLine() — if null (EOF), break
  ├── append user message to conversation
  └── Inner loop (max 10 iterations):
        ├── call client.chat(conversation)        → response string
        ├── print response
        ├── append response as "assistant" message
        ├── parseToolInvocations(response)
        │     ├── if tools found:
        │     │     for each (name, args):
        │     │       - look up in toolRegistry
        │     │       - execute (unknown tool → {"error": "unknown tool: $name"})
        │     │       - serialize result: mapper.writeValueAsString(resultMap)
        │     │       - append as user message: "tool_result($json)"
        │     │     continue inner loop
        │     └── if no tools: break inner loop
        └── if iterations hit 10: print warning, break to outer loop
```

**Error handling:**
- Wrap `client.chat(conversation)` in `try/catch(Exception)` — on exception: print `"Error: ${e.message}"`, break inner loop
- API errors surface here; the outer loop continues so the user can try again

**Agentic AI note:** This pattern — LLM call → parse action → execute → feed result back → repeat — is the foundation of every tool-using agent. The inner loop is the "reasoning step": the model sees tool results and decides whether to call more tools or respond. The `parseToolInvocations` function is what makes this a *text-based* agent vs one using the API's native tool-call feature.

- [ ] **Verify:** `./gradlew build` → `BUILD SUCCESSFUL`
- [ ] **Verify:** `./gradlew test` → all 19 tests PASS

---

## Phase 6: `Main.kt` — Entry Point

**File to modify:** `src/main/kotlin/Main.kt` (replace existing content)

### What to implement

```kotlin
fun main() { ... }
```

- Read `OPENAI_API_KEY` from `System.getenv("OPENAI_API_KEY")`
- If null or blank: print `"Error: OPENAI_API_KEY environment variable not set"` and call `exitProcess(1)`
- Create `Agent(OpenAiClient(apiKey))` and call `.run()`

**Kotlin hints:**
- `String?.isNullOrBlank()` handles both null and blank in one check
- `exitProcess(1)` is in `kotlin.system`

- [ ] **Verify:** `./gradlew build` → `BUILD SUCCESSFUL`, 19 tests PASS

---

## Phase 7: End-to-End Smoke Test

- [ ] **Set your API key and run:**

```bash
export OPENAI_API_KEY=your-key-here
./gradlew run --console=plain
```

Expected: `You:` prompt appears in blue.

- [ ] **Test a read-only task** — type:

```
List the files in the current directory
```

Expected flow:
1. Agent emits `tool: list_files({"path": "."})`
2. Agent executes `listFilesTool`, gets file list
3. Agent appends `tool_result({...})` to conversation
4. Model reads the result and replies in natural language

- [ ] **Test a file creation task** — type:

```
Create a file called hello.txt containing "Hello from the agent"
```

Verify: `cat hello.txt` → `Hello from the agent`

- [ ] **Exit** with `Ctrl+D`. Clean up: `rm hello.txt`

---

## Agentic AI Concepts to Observe While Building

| Concept | Where it appears |
|---------|-----------------|
| **System prompt** | `getToolListString()` + template in `Agent.kt` — the model's "rules of engagement" |
| **Conversation history** | `MutableList<ChatMessage>` — the model has no memory; you pass it every time |
| **Tool use via prompting** | `parseToolInvocations` — no SDK magic, just text parsing |
| **Agentic loop** | The inner `while` loop — model acts until it decides it's done |
| **Tool result injection** | `tool_result(...)` messages fed back as "user" — closing the loop |
| **Loop guard** | Max 10 iterations — prevents runaway tool chains |
