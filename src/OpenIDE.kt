package com.example.jetbrainsplugin

import com.intellij.openapi.application.JBProtocolCommand
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class OpenIDE : JBProtocolCommand("apply-diff") {
    private val logger = Logger.getInstance(OpenIDE::class.java)

    override suspend fun execute(
        target: String?,
        parameters: Map<String, String>,
        fragment: String?
    ): String? = withContext(Dispatchers.IO) {
        logger.info("JBProtocolCommand executed with target: $target, parameters: $parameters, fragment: $fragment")
        logger.debug("JBProtocolCommand executed with target: $target, parameters: $parameters, fragment: $fragment")

        try {
            // Retrieve and validate parameters
            val projectPath = parameters["projectPath"]
                ?: throw IllegalArgumentException("Missing projectPath parameter")
            val diffScriptPath = parameters["diffScriptPath"]
                ?: throw IllegalArgumentException("Missing diffScriptPath parameter")

            logger.debug("Parsed projectPath: $projectPath")
            logger.debug("Parsed diffScriptPath: $diffScriptPath")

            // Read the diff script
            val diffLines = File(diffScriptPath).readLines()

            // Apply the diff code to the target files
            applyDiffToFilesAndOpen(projectPath, diffLines)

            logger.debug("Successfully applied diff code to the target files.")
            return@withContext "Successfully applied diff code to the target files."
        } catch (e: Exception) {
            logger.debug("Error occurred: ${e.message}")
            logger.error("Error processing JBProtocolCommand", e)
            return@withContext "Error: ${e.message}"
        }
    }


    private fun applyDiffToFilesAndOpen(projectPath: String, diffLines: List<String>) {
        val targetFiles = parseTargetFilesFromDiff(diffLines, projectPath)

        val filesToOpen = mutableListOf<String>()
        for ((filePath, fileDiffLines) in targetFiles) {
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("File not found: $filePath")
            }
            filesToOpen.add(filePath)
            applyDiff(file, fileDiffLines)
        }
        logger.debug("Opening IntelliJ with project: $projectPath and modified files.")
    }

    private fun parseTargetFilesFromDiff(diffLines: List<String>, projectPath: String): Map<String, List<String>> {
        val targetFiles = mutableMapOf<String, MutableList<String>>()
        var currentFile: String? = null
        for (line in diffLines) {
            if (line.startsWith("diff --git")) {
                val filePath = line.split(" ").last().removePrefix("b/")
                currentFile = "$projectPath/$filePath"
                targetFiles[currentFile] = mutableListOf()
            } else if (currentFile != null) {
                targetFiles[currentFile]?.add(line)
            }
        }
        return targetFiles
    }

    private fun applyDiff(file: File, diffLines: List<String>) {
        val lines = file.readLines().toMutableList()
        var targetLineIndex = -1
        var lineOffset = 0
        var i = 0
        while (i < diffLines.size) {
            val line = diffLines[i]
            if (line.startsWith("@@")) {
                val targetInfo = line.split(" ")[2].split(",")
                val startLine = targetInfo[0].removePrefix("+").toInt() - 1
                targetLineIndex = startLine + lineOffset
                i++
                while (i < diffLines.size && !diffLines[i].startsWith("@@")) {
                    val diffLine = diffLines[i]
                    when {
                        diffLine.startsWith("+") -> {
                            lines.add(targetLineIndex, diffLine.removePrefix("+"))
                            targetLineIndex++
                            lineOffset++
                        }
                        diffLine.startsWith("-") -> {
                            if (targetLineIndex < lines.size && lines[targetLineIndex].trim() == diffLine.removePrefix("-").trim()) {
                                lines.removeAt(targetLineIndex)
                                lineOffset--
                            }
                        }
                        else -> targetLineIndex++
                    }
                    i++
                }
            } else {
                i++
            }
        }
        file.writeText(lines.joinToString("\n"))
    }
}
