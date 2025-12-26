package com.example.jetbrainsplugin.actions

import com.example.jetbrainsplugin.actions.git.getChangedFiles
import com.example.jetbrainsplugin.actions.git.getLatestCommit
import com.example.jetbrainsplugin.parser.applyDiffToBaseline
import com.example.jetbrainsplugin.parser.gumtree.ThreeVersionAST
import com.example.jetbrainsplugin.parser.gumtree.Matcher
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorMouseAdapter
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color
import java.io.File

private val logger = com.intellij.openapi.diagnostic.Logger.getInstance("ApplyDiff")

fun applyDiffCodeToEditor(diff: String, project: Project): String {
    val basePath = project.basePath ?: return "Project base path is not set."
    val diffMap = splitDiff(diff)
    var lastModifiedFile: VirtualFile? = null

    val commit = getLatestCommit(project)
    val userModifiedFiles = commit?.let { getChangedFiles(project, it) }
    val (intersectionFiles, remainingPaths) = intersectChangedFiles(userModifiedFiles ?: emptyList(), diffMap, project)

    logger.info("Intersection files: $intersectionFiles")
    logger.info("Remaining paths: $remainingPaths")

    ApplicationManager.getApplication().invokeLater {
        diffMap.forEach { (filePath, diffText) ->
            if (filePath in remainingPaths) {
                logger.info("No direct changes detected on diff path: $filePath")

                // Normalize paths: use project's basePath as repository root.
                val normalizedBasePath = basePath.trimEnd(File.separatorChar)
                val normalizedFilePath = filePath.trim()
                val baselineFile = File(normalizedBasePath + File.separator + normalizedFilePath)
                if (!baselineFile.exists()) {
                    logger.info("File does not exist: ${baselineFile.absolutePath}")
                    return@forEach
                }

                // Refresh the VirtualFile for baselineFile.
                ApplicationManager.getApplication().invokeAndWait {
                    VfsUtil.markDirtyAndRefresh(false, true, true, baselineFile)
                }

                // Apply the diff using Git API.
                val patchedFile = applyDiffToBaseline(baselineFile, diffText)
                if (patchedFile == null) {
                    logger.info("❌ Failed to apply diff using Git API for file: $filePath")
                    return@forEach
                }

                // Step 1: Locate the root of the temporary Git repo (the parent of .git)
                val tempRepoRoot = generateSequence(patchedFile.parentFile) { it.parentFile }
                    .firstOrNull { File(it, ".git").exists() }

                if (tempRepoRoot == null) {
                    logger.info("❌ Could not locate temp Git repo root for ${patchedFile.path}")
                    return@forEach
                }

                val relativePath = try {
                    patchedFile.relativeTo(tempRepoRoot).invariantSeparatorsPath
                } catch (e: Exception) {
                    logger.info("❌ Failed to compute relative path: ${e.message}")
                    return@forEach
                }

                val realFile = File(project.basePath, relativePath)
                val virtualFile = VfsUtil.findFileByIoFile(realFile, true)

                if (virtualFile != null) {
                    val patchedContent = patchedFile.readText(Charsets.UTF_8)
                    applyJavaCodeToFile(realFile, patchedContent)


                    ApplicationManager.getApplication().invokeAndWait {
                        VfsUtil.markDirtyAndRefresh(false, true, true, realFile)
                    }

                    val editor = FileEditorManager.getInstance(project)
                        .openFile(virtualFile, true)
                        .let { FileEditorManager.getInstance(project).selectedTextEditor }

                    if (editor != null) {
                        highlightChanges(editor, diffText, project, true)
                    }

                    // If you have a highlightChanges function, call it with the appropriate changedRanges.
                    // For example, if you computed changedRanges via another mechanism:
                    // editor?.let { highlightChanges(it, changedRanges) }
                    lastModifiedFile = virtualFile
                    logger.info("✅ Diff applied and file opened: ${virtualFile.path}")
                } else {
                    logger.info("⚠️ Unable to locate VirtualFile for patched file: ${patchedFile.absolutePath}")
                }
            }
        }

        if (intersectionFiles.isNotEmpty()) {
            logger.info("Starting three-way merge for intersection files.")
            val repositoryRoot = File(project.basePath!!)
            val threeVersionAST = ThreeVersionAST(project)

            intersectionFiles.forEach { file ->
                val baseAST = threeVersionAST.getBaseAST(file)
                val modifiedAST = threeVersionAST.getModifiedAST(file)

                val relativePath = try {
                    file.relativeTo(repositoryRoot).invariantSeparatorsPath
                } catch (e: IllegalArgumentException) {
                    logger.info("⚠️ Unable to resolve relative path for ${file.path}")
                    return@forEach
                }

                val diffLines = diffMap[relativePath]
                if (diffLines == null) {
                    logger.info("No diff lines found for file: ${relativePath}")
                    return@forEach
                }

                val patchedAST = threeVersionAST.getPatchedAST(file, diffLines)
                if (patchedAST != null) {
                    logger.info("Patched AST: ${patchedAST.toTreeString()}")
                }

                val matcher = Matcher()
                if (baseAST != null && modifiedAST != null && patchedAST != null) {
                    // Perform the three-way merge.
                    val finalCode = matcher.performMergeOnTrees(baseAST, modifiedAST, patchedAST)
                    // Write the merged code back into the file.
                    val cleanedCode = finalCode
                        .trim()
                        .removePrefix("```java")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()
                    val virtualFile = VfsUtil.findFileByIoFile(file, true)
                    val document = FileDocumentManager.getInstance().getDocument(virtualFile!!)

                    WriteCommandAction.runWriteCommandAction(project) {
                        document!!.setText(cleanedCode)
                    }

                    // Refresh the file in the VFS.
                    ApplicationManager.getApplication().invokeAndWait {
                        VfsUtil.markDirtyAndRefresh(false, true, true, file)
                    }
                    // Now, refresh the file and highlight the changes using the diff text.
                    ApplicationManager.getApplication().invokeLater {
                        // Retrieve the VirtualFile for the patched file.
                        val virtualFile = VfsUtil.findFileByIoFile(file, true)
                        if (virtualFile != null) {
                            // Open the file in the editor.
                            val editor = FileEditorManager.getInstance(project)
                                .openFile(virtualFile, true)
                                .let { FileEditorManager.getInstance(project).selectedTextEditor }
                            if (editor != null) {
                                // Call our highlightChanges function, passing in the diff text.
                                highlightChanges(editor, diffLines, project, false, sourceCode = cleanedCode)
                            } else {
                                logger.info("Unable to obtain editor for file: ${virtualFile.path}")
                            }
                        } else {
                            logger.info("Unable to locate VirtualFile for: ${file.absolutePath}")
                        }
                    }
                } else {
                    logger.info("AST merge conditions not met for file: ${relativePath}")
                }
            }
        }

        lastModifiedFile?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }

    return "Diff applied successfully."
}

private fun applyJavaCodeToFile(file: File, javaCode: String) {
    try {
        val cleanedCode = javaCode
            .trim()
            .removePrefix("```java")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        // Ensure the parent directory exists
        file.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }

        // Write cleaned Java code to file
        file.writeText(cleanedCode)
        logger.info("Successfully applied Java code to file: ${file.absolutePath}")
    } catch (e: Exception) {
        logger.info("Error applying Java code to file: ${e.message}")
        // Optionally, rethrow the exception or handle it as needed.
    }
}


private fun splitDiff(diff: String): Map<String, String> {
    val fileDiffs = mutableMapOf<String, String>()
    var currentFile: String? = null
    var currentDiffBuilder = StringBuilder()

    // Remove any markdown code fence markers (e.g., "```" or "```diff")
    val filteredLines = diff.lines().filter { !it.trim().startsWith("```") }

    filteredLines.forEach { line ->
        when {
            line.startsWith("diff --git") -> {
                // If we're finishing a previous diff block, validate and store it.
                if (currentFile != null && currentDiffBuilder.isNotEmpty()) {
                    val diffText = currentDiffBuilder.toString().trim()
                    // Validate: must start with "diff --git" and contain "+++ b/"
                    if (diffText.startsWith("diff --git") && diffText.contains("+++ b/")) {
                        fileDiffs[currentFile!!] = diffText
                    }
                }
                // Start a new diff block.
                currentDiffBuilder = StringBuilder()
                currentDiffBuilder.appendLine(line)
                // Reset currentFile; it will be set when encountering "+++ b/"
                currentFile = null
            }
            line.startsWith("+++ b/") -> {
                // Extract the file's relative path from the diff marker.
                currentFile = line.removePrefix("+++ b/").trim()
                currentDiffBuilder.appendLine(line)
            }
            else -> {
                // Append the line to the current diff block.
                currentDiffBuilder.appendLine(line)
            }
        }
    }

    // After processing all lines, check if the last diff block is valid.
    if (currentFile != null && currentDiffBuilder.isNotEmpty()) {
        val diffText = currentDiffBuilder.toString().trim()
        if (diffText.startsWith("diff --git") && diffText.contains("+++ b/")) {
            fileDiffs[currentFile!!] = diffText
        }
    }

    return fileDiffs
}

private val CHANGE_HIGHLIGHTERS_KEY = Key.create<MutableList<RangeHighlighter>>("MY_PLUGIN_CHANGE_HIGHLIGHTERS")
private val MOUSE_LISTENER_ADDED_KEY = Key.create<Boolean>("MY_PLUGIN_MOUSE_LISTENER_ADDED")

private fun highlightChanges(editor: Editor, diffText: String, project: Project, isDirectApply: Boolean, sourceCode: String = "") {
    val document = editor.document
    val markupModel = editor.markupModel

    // Clear previous highlights added by this plugin
    clearChangeHighlights(editor)


    val changedLineOffsets = if (isDirectApply) {
        extractChangedLineOffsets(document, diffText)
    } else {
        extractChangedLineOffsetsFromCode(sourceCode, diffText)
    }

    val highlightAttributes = TextAttributes().apply {
        backgroundColor = Color(255, 255, 153) // pale yellow
    }

    val highlighters = mutableListOf<RangeHighlighter>()

    for ((startOffset, endOffset) in changedLineOffsets) {
        val highlighter = markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            HighlighterLayer.SELECTION - 1,
            highlightAttributes,
            HighlighterTargetArea.LINES_IN_RANGE
        )
        highlighters.add(highlighter)
    }

    editor.putUserData(CHANGE_HIGHLIGHTERS_KEY, highlighters)

    // Register editor mouse click listener only once per editor
    if (editor.getUserData(MOUSE_LISTENER_ADDED_KEY) != true) {
        val listener = object : EditorMouseAdapter() {
            override fun mouseClicked(event: EditorMouseEvent) {
                if (event.editor == editor) {
                    clearChangeHighlights(editor)
                }
            }
        }

        EditorFactory.getInstance().eventMulticaster.addEditorMouseListener(listener, project)
        editor.putUserData(MOUSE_LISTENER_ADDED_KEY, true)
    }

    if (changedLineOffsets.isNotEmpty()) {
        editor.caretModel.moveToOffset(changedLineOffsets.first().first)
    }
}

private fun clearChangeHighlights(editor: Editor) {
    val markupModel = editor.markupModel
    val highlighters = editor.getUserData(CHANGE_HIGHLIGHTERS_KEY)
    highlighters?.forEach { markupModel.removeHighlighter(it) }
    editor.putUserData(CHANGE_HIGHLIGHTERS_KEY, null)
}


private fun extractChangedLineOffsets(document: Document, diffText: String): List<Pair<Int, Int>> {
    val offsets = mutableListOf<Pair<Int, Int>>()

    // Match hunk headers, allowing trailing text
    val hunkHeaderRegex = Regex("@@ -(\\d+),(\\d+) \\+(\\d+),(\\d+) @@.*")

    val lines = diffText.lines()
    var i = 0
    while (i < lines.size) {
        val match = hunkHeaderRegex.matchEntire(lines[i])
        if (match != null) {
            val (_, _, newStartStr, _) = match.destructured
            var newLine = newStartStr.toInt() // 1-based

            i++ // move into the hunk body
            while (i < lines.size) {
                val line = lines[i]

                // Stop at next hunk or file diff
                if (line.startsWith("@@") || line.startsWith("diff --git")) {
                    i--
                    break
                }

                when {
                    line.startsWith(" ") -> {
                        newLine++
                    }

                    line.startsWith("-") -> {
                        // Look ahead: if next line is '+', treat as modification
                        if (i + 1 < lines.size && lines[i + 1].startsWith("+")) {
                            val lineIndex = newLine - 1
                            if (lineIndex in 0 until document.lineCount) {
                                val startOffset = document.getLineStartOffset(lineIndex)
                                val endOffset = document.getLineEndOffset(lineIndex)
                                offsets.add(startOffset to endOffset)
                            }
                            newLine++ // Treat as replacement (advance newLine even though it's a `-`)
                            i++ // Skip next '+'
                        }
                        // If it's just a removal without '+', ignore it
                    }

                    line.startsWith("+") -> {
                        // Regular addition
                        val lineIndex = newLine - 1
                        if (lineIndex in 0 until document.lineCount) {
                            val startOffset = document.getLineStartOffset(lineIndex)
                            val endOffset = document.getLineEndOffset(lineIndex)
                            offsets.add(startOffset to endOffset)
                        }
                        newLine++
                    }
                }

                i++
            }
        }
        i++
    }

    return offsets
}

private fun extractChangedLineOffsetsFromCode(javaCode: String, diffText: String): List<Pair<Int, Int>> {
    val offsets = mutableListOf<Pair<Int, Int>>()

    val codeLines = javaCode.lines()
    val lineStartOffsets = mutableListOf<Int>()
    var cumulativeOffset = 0
    for (line in codeLines) {
        lineStartOffsets.add(cumulativeOffset)
        cumulativeOffset += line.length + 1 // Assuming \n
    }

    // Step 1: Collect all actual added lines (ignore "+++" headers)
    val addedLines = diffText.lines()
        .filter { it.startsWith("+") && !it.startsWith("+++") }
        .map { it.substring(1).trim() }

    // Step 2: For each added line, find first matching line in code
    val matchedIndices = mutableSetOf<Int>() // Prevent duplicates
    for (added in addedLines) {
        for ((index, codeLine) in codeLines.withIndex()) {
            if (index !in matchedIndices && codeLine.trim() == added) {
                val start = lineStartOffsets[index]
                val end = start + codeLine.length
                offsets.add(start to end)
                matchedIndices.add(index)
                break
            }
        }
    }
    logger.info("offsets: $offsets")
    return offsets
}




/**
 * Given the list of changed File objects (from getChangedFiles) and the diff map (from parseDiff),
 * returns a Pair containing:
 *  - A List<File> that are present in both the changedFiles list and the diff map (intersection).
 *  - A List<String> (file paths) for the rest of the files that appear in the diff map but not in changedFiles.
 *
 * The repository root is derived from the project's basePath.
 */
fun intersectChangedFiles(
    changedFiles: List<File>,
    diffMap: Map<String, String>,
    project: Project
): Pair<List<File>, List<String>> {
    // Get the repository root from the project's base path.
    val basePath = project.basePath
    if (basePath == null) {
        logger.info("⚠️ Project base path is not defined.")
        return Pair(emptyList(), diffMap.keys.toList())
    }
    val repositoryRoot = File(basePath)

    // Map changedFiles by their relative path.
    val changedFilesByRelativePath = changedFiles.associateBy { file ->
        try {
            file.relativeTo(repositoryRoot).invariantSeparatorsPath
        } catch (e: IllegalArgumentException) {
            // If relativeTo fails, fallback to absolute path.
            file.path
        }
    }

    val intersectionFiles = mutableListOf<File>()
    val remainingPaths = mutableListOf<String>()

    // Iterate over all diff keys (which are relative file paths).
    for ((diffPath, _) in diffMap) {
        if (changedFilesByRelativePath.containsKey(diffPath)) {
            changedFilesByRelativePath[diffPath]?.let { intersectionFiles.add(it) }
        } else {
            remainingPaths.add(diffPath)
        }
    }

    logger.info("Intersection files: $intersectionFiles")
    logger.info("Remaining paths: $remainingPaths")

    return Pair(intersectionFiles, remainingPaths)
}
