package org.example

import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

typealias ToolFn = (Map<String, Any>) -> Map<String, Any>

private fun resolvePath(rawStr: String): Path =
    Path.of(rawStr.ifBlank { "." }).toAbsolutePath().normalize()

fun readFileTool(args: Map<String, Any>): Map<String, Any> = try {
    val filename = args["filename"] as? String ?: ""
    val path = resolvePath(filename)
    val content = Files.readString(path, Charsets.UTF_8)
    mapOf("file_path" to path.toString(), "content" to content)
} catch (e: Exception) {
    mapOf("error" to (e.message ?: "unknown error"))
}

fun listFilesTool(args: Map<String, Any>): Map<String, Any> = try {
    val pathStr = args["path"] as? String ?: "."
    val dir = resolvePath(pathStr)
    if (!Files.isDirectory(dir)) {
        return mapOf("error" to "not a directory: $dir")
    }
    val files = Files.list(dir).use { stream ->
        stream.map { entry ->
            mapOf(
                "filename" to entry.fileName.toString(),
                "type" to if (Files.isDirectory(entry)) "dir" else "file"
            )
        }.toList()
    }
    mapOf("path" to dir.toString(), "files" to files)
} catch (e: Exception) {
    mapOf("error" to (e.message ?: "unknown error"))
}

fun editFileTool(args: Map<String, Any>): Map<String, Any> = try {
    val pathStr = args["path"] as? String ?: ""
    val oldStr  = args["old_str"] as? String ?: ""
    val newStr  = args["new_str"] as? String ?: ""
    val path = resolvePath(pathStr)

    if (oldStr == "") {
        val parent = path.parent
        if (parent != null && !Files.exists(parent)) {
            return mapOf("error" to "parent directory does not exist: $parent")
        }
        Files.writeString(path, newStr, Charsets.UTF_8)
        mapOf("path" to path.toString(), "action" to "created_file")
    } else {
        val original = Files.readString(path, Charsets.UTF_8)
        if (original.indexOf(oldStr) == -1) {
            mapOf("path" to path.toString(), "action" to "old_str not found")
        } else {
            Files.writeString(path, original.replaceFirst(oldStr, newStr), Charsets.UTF_8)
            mapOf("path" to path.toString(), "action" to "edited")
        }
    }
} catch (e: Exception) {
    mapOf("error" to (e.message ?: "unknown error"))
}

val toolRegistry: Map<String, ToolFn> = mapOf(
    "read_file"  to ::readFileTool,
    "list_files" to ::listFilesTool,
    "edit_file"  to ::editFileTool
)

fun getToolListString(): String = """
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
""".trimIndent()