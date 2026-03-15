package org.example

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentParserTest {

    @Test
    fun `parses single tool invocation`() {
        val text = """tool: read_file({"filename": "main.kt"})"""
        val result = parseToolInvocations(text)
        assertEquals(1, result.size)
        assertEquals("read_file", result[0].first)
        assertEquals("main.kt", result[0].second["filename"])
    }

    @Test
    fun `parses multiple tool invocations from multi-line response`() {
        val text = """
            tool: read_file({"filename": "a.kt"})
            tool: list_files({"path": "src"})
        """.trimIndent()
        val result = parseToolInvocations(text)
        assertEquals(2, result.size)
        assertEquals("read_file", result[0].first)
        assertEquals("list_files", result[1].first)
    }

    @Test
    fun `returns empty list when no tool lines present`() {
        val text = "This is a regular response with no tools."
        val result = parseToolInvocations(text)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `skips malformed line missing closing paren`() {
        val text = """tool: edit_file({"path": "x.kt", "old_str": "foo""""
        val result = parseToolInvocations(text)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `skips line with invalid JSON`() {
        val text = """tool: read_file(not valid json)"""
        val result = parseToolInvocations(text)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `handles leading whitespace before tool prefix`() {
        val text = """  tool: list_files({"path": "."})"""
        val result = parseToolInvocations(text)
        assertEquals(1, result.size)
        assertEquals("list_files", result[0].first)
    }

    @Test
    fun `parses edit_file with multiple string arguments`() {
        val text = """tool: edit_file({"path": "f.kt", "old_str": "hello", "new_str": "world"})"""
        val result = parseToolInvocations(text)
        assertEquals(1, result.size)
        val args = result[0].second
        assertEquals("f.kt", args["path"])
        assertEquals("hello", args["old_str"])
        assertEquals("world", args["new_str"])
    }
}
