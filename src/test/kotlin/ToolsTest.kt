package org.example

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolsTest {

    @TempDir
    lateinit var tempDir: Path

    // ── readFileTool ──────────────────────────────────────────────────────────

    @Test
    fun `readFileTool returns file content`() {
        val file = tempDir.resolve("hello.txt")
        file.toFile().writeText("hello world")
        val result = readFileTool(mapOf("filename" to file.toString()))
        assertEquals("hello world", result["content"])
        assertEquals(file.toAbsolutePath().normalize().toString(), result["file_path"])
    }

    @Test
    fun `readFileTool returns error for missing file`() {
        val result = readFileTool(mapOf("filename" to "/nonexistent/path/file.txt"))
        assertTrue(result.containsKey("error"))
    }

    @Test
    fun `readFileTool handles missing key without throwing`() {
        // empty filename — should either succeed (reading cwd) or return error, never throw
        val result = readFileTool(emptyMap())
        assertTrue(result.containsKey("content") || result.containsKey("error"))
    }

    // ── listFilesTool ─────────────────────────────────────────────────────────

    @Test
    fun `listFilesTool returns files and dirs in directory`() {
        tempDir.resolve("a.txt").toFile().writeText("a")
        tempDir.resolve("subdir").toFile().mkdir()
        val result = listFilesTool(mapOf("path" to tempDir.toString()))
        @Suppress("UNCHECKED_CAST")
        val files = result["files"] as List<Map<String, String>>
        val names = files.map { it["filename"] }.toSet()
        assertTrue(names.contains("a.txt"))
        assertTrue(names.contains("subdir"))
        val types = files.associateBy({ it["filename"] }, { it["type"] })
        assertEquals("file", types["a.txt"])
        assertEquals("dir", types["subdir"])
    }

    @Test
    fun `listFilesTool returns empty list for empty directory`() {
        val emptyDir = tempDir.resolve("empty").also { it.toFile().mkdir() }
        val result = listFilesTool(mapOf("path" to emptyDir.toString()))
        @Suppress("UNCHECKED_CAST")
        val files = result["files"] as List<*>
        assertTrue(files.isEmpty())
    }

    @Test
    fun `listFilesTool returns error for non-directory path`() {
        val file = tempDir.resolve("notadir.txt").also { it.toFile().writeText("x") }
        val result = listFilesTool(mapOf("path" to file.toString()))
        assertTrue(result.containsKey("error"))
    }

    @Test
    fun `listFilesTool returns error for non-existent path`() {
        val result = listFilesTool(mapOf("path" to "/nonexistent/dir"))
        assertTrue(result.containsKey("error"))
    }

    // ── editFileTool ──────────────────────────────────────────────────────────

    @Test
    fun `editFileTool creates new file when old_str is empty`() {
        val file = tempDir.resolve("new.txt")
        val result = editFileTool(mapOf("path" to file.toString(), "old_str" to "", "new_str" to "created"))
        assertEquals("created_file", result["action"])
        assertEquals("created", file.toFile().readText())
    }

    @Test
    fun `editFileTool overwrites existing file when old_str is empty`() {
        val file = tempDir.resolve("existing.txt").also { it.toFile().writeText("old content") }
        val result = editFileTool(mapOf("path" to file.toString(), "old_str" to "", "new_str" to "new content"))
        assertEquals("created_file", result["action"])
        assertEquals("new content", file.toFile().readText())
    }

    @Test
    fun `editFileTool replaces only the first occurrence of old_str`() {
        val file = tempDir.resolve("code.txt").also { it.toFile().writeText("foo foo foo") }
        val result = editFileTool(mapOf("path" to file.toString(), "old_str" to "foo", "new_str" to "bar"))
        assertEquals("edited", result["action"])
        assertEquals("bar foo foo", file.toFile().readText())
    }

    @Test
    fun `editFileTool returns old_str not found when string absent`() {
        val file = tempDir.resolve("code.txt").also { it.toFile().writeText("hello world") }
        val result = editFileTool(mapOf("path" to file.toString(), "old_str" to "xyz", "new_str" to "abc"))
        assertEquals("old_str not found", result["action"])
        assertEquals("hello world", file.toFile().readText()) // file unchanged
    }

    @Test
    fun `editFileTool returns error when parent directory missing`() {
        val file = tempDir.resolve("nonexistent_subdir/file.txt")
        val result = editFileTool(mapOf("path" to file.toString(), "old_str" to "", "new_str" to "data"))
        assertTrue(result.containsKey("error"))
        assertTrue((result["error"] as String).contains("parent directory does not exist"))
    }
}
