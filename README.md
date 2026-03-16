# Kotlin Coding Agent

A minimal AI coding agent built in Kotlin that runs in your terminal. It connects to the OpenAI API and can autonomously read, list, and edit files to complete coding tasks.

## How it works

The agent runs an interactive loop:

1. You type a task (e.g. *"add a docstring to every function in Tools.kt"*)
2. The model reasons about what to do and emits a plain-text tool call
3. The agent executes the tool and feeds the result back to the model
4. Steps 2-3 repeat until the task is done or the model responds normally

Tool calls use plain text - no function-calling API, just prompt engineering:

```
tool: read_file({"filename": "src/main/kotlin/Tools.kt"})
```

This keeps the implementation simple and transparent, and works with any chat model that can follow instructions.

## Demo

<video src="demo.mov" controls width="100%"></video>

## Quick start

**Prerequisites:** JDK 21+, an OpenAI API key

```bash
# 1. Clone the repo
git clone https://github.com/your-username/KotlinCodingAgent.git
cd KotlinCodingAgent

# 2. Set your API key
export OPENAI_API_KEY=sk-...

# 3. Run
./gradlew run -q
```

You should see a `You:` prompt. Type any coding task and watch the agent work.

### Using a .env file

If you prefer to store the key in a file, copy the example template:

```bash
cp .env.example .env
# edit .env and fill in your key
```

Then load it before running:

```bash
export $(cat .env | xargs) && ./gradlew run
```

## Tools

| Tool | Description | Arguments |
|------|-------------|-----------|
| `read_file` | Read the full contents of a file | `filename: String` |
| `list_files` | List files in a directory | `path: String` |
| `edit_file` | Replace text in a file (or create it if `old_str` is empty) | `path: String, old_str: String, new_str: String` |

## Project structure

```
src/
  main/kotlin/
    Main.kt          # Entry point — reads API key, starts agent
    Agent.kt         # Agentic loop + tool-call parser
    OpenAiClient.kt  # Thin wrapper around the openai-java SDK
    Tools.kt         # Tool implementations + registry
  test/kotlin/
    ToolsTest.kt     # Unit tests for all three tools
    AgentParserTest.kt
```

## Running tests

```bash
./gradlew test
```

## Tech stack

- **Kotlin** 2.3 / JVM 21
- **openai-java** SDK 4.28.0 (model set in `OpenAiClient.kt`)
- **Jackson** for JSON serialization of tool arguments and results
- **Gradle** Kotlin DSL

## Extending the agent

Adding a new tool is straightforward:

1. Write a function `fun myTool(args: Map<String, Any>): Map<String, Any>` in `Tools.kt`
2. Register it in `toolRegistry`
3. Add its name, description, and signature to `getToolListString()`

The model will automatically learn to call it from the system prompt.

## Acknowledgements

This project is a Kotlin port of the Python coding agent built in [Mihail Eric's AI Software Development course](https://maven.com/the-modern-software-developer/ai-course). Highly recommended if you want to understand how to build agentic AI systems from the ground up.
