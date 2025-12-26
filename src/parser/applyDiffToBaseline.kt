package com.example.jetbrainsplugin.parser

import org.eclipse.jgit.api.Git
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory

private val logger = com.intellij.openapi.diagnostic.Logger.getInstance("applyDiffToBaseline")

/**
 * Applies a diff (patch) to the baseline version of a file.
 *
 * This function assumes the diff text contains only one file change.
 * It extracts the target file's relative path from the diff text (e.g., "src/main/gumtree/duke/Storage.gumtree"),
 * creates a temporary Git repository with the baseline file committed,
 * applies the diff patch using the Git API, and returns the patched File.
 *
 * @param baselineFile The File object representing the baseline version.
 * @param diffText The unified diff text (containing only one file change).
 * @return The File object with the applied diff change, or null if an error occurs.
 */
fun applyDiffToBaseline(
    baselineFile: File,
    diffText: String
): File? {
    // Extract the target file's relative path from the diff text using the "+++ b/" marker.
    val targetRelativePath = diffText.lines().firstOrNull { it.startsWith("+++ b/") }
        ?.removePrefix("+++ b/")?.trim() ?: run {
        logger.info("⚠️ Cannot extract target relative path from diff text.")
        return null
    }

    // Create a temporary directory using kotlin.io.path.createTempDirectory (avoiding wide permissions issues)
    val tempDir = createTempDirectory("temp_repo_").toFile()

    // Construct the file path in the temporary repository.
    val tempBaselineFile = File(tempDir, targetRelativePath)
    tempBaselineFile.parentFile.mkdirs() // Ensure parent directories exist.

    // Copy the baseline file's content into our temporary file.
    fun normalizeLineEndings(content: String): String {
        // Replace CRLF and CR with LF.
        var normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        // Optionally, ensure that the file ends with a newline if the original did.
        if (!normalized.endsWith("\n")) {
            normalized += "\n"
        }
        return normalized
    }


    val normalizedContent = normalizeLineEndings(baselineFile.readText(Charsets.UTF_8))
    tempBaselineFile.writeText(normalizedContent)

    // Initialize a new Git repository in the temporary directory.
    val git = Git.init().setDirectory(tempDir).call()

    // Stage and commit the baseline file as our baseline commit.
    git.add().addFilepattern(targetRelativePath).call()
    git.commit().setMessage("Baseline commit").call()

    // Prepare the diff patch as an InputStream.
    val patchInputStream = ByteArrayInputStream(diffText.toByteArray(Charsets.UTF_8))

    return try {
        // Apply the patch using JGit.
        git.apply().setPatch(patchInputStream).call()
        logger.info("✅ Patch applied successfully.")
        // Return the file from the temporary repository that now contains the applied diff.
        tempBaselineFile
    } catch (e: Exception) {
        logger.info("❌ Error applying patch: ${e.message}")
        debugPatchMismatch(baselineFile, diffText)
        null
    }
}

/**
 * Debugs a diff application failure by comparing the hunk header’s expected baseline context with
 * the actual lines in the baseline file.
 *
 * It reads the baseline file, extracts the hunk header from the diff text (using a regex),
 * then prints:
 *  - The hunk header details (starting line and line count for baseline),
 *  - The corresponding lines from the baseline file,
 *  - The hunk body lines as provided in the diff.
 *
 * This output should help diagnose why the patch tool complains about a hunk mismatch.
 *
 * @param baselineFile The baseline file that the patch is to be applied to.
 * @param diffText The diff text (unified diff) that failed to apply.
 */
fun debugPatchMismatch(baselineFile: File, diffText: String) {
    // Read baseline content and split into lines (preserving line numbers as 1-indexed)
    val baselineContent = baselineFile.readText(Charsets.UTF_8)
    val baselineLines = baselineContent.split("\n")

    // Use a regex to extract the first hunk header in the diff.
    val hunkHeaderRegex = Regex("@@ -(\\d+),(\\d+) \\+(\\d+),(\\d+) @@")
    val hunkHeaderMatch = hunkHeaderRegex.find(diffText)
    if (hunkHeaderMatch == null) {
        logger.info("❌ No hunk header found in diff text.")
        return
    }
    val (baseStartStr, baseCountStr, patchStartStr, patchCountStr) = hunkHeaderMatch.destructured
    val baseStart = baseStartStr.toInt()
    val baseCount = baseCountStr.toInt()
    val patchStart = patchStartStr.toInt()
    val patchCount = patchCountStr.toInt()

    logger.info("=== Debug: Patch Hunk Analysis ===")
    logger.info("Hunk header: @@ -$baseStart,$baseCount +$patchStart,$patchCount @@")

    // Split the diff into lines and locate the hunk header line index.
    val diffLines = diffText.lines()
    val headerIndex = diffLines.indexOfFirst { it.startsWith("@@") }
    if (headerIndex == -1) {
        logger.info("❌ Hunk header not found in diff lines.")
        return
    }
    // Extract hunk body: lines immediately following the header until we hit another header or a diff marker.
    val hunkBody = diffLines.drop(headerIndex + 1)
        .takeWhile { !it.startsWith("@@") && !it.startsWith("diff --git") }

    logger.info("\nPatch hunk body (line count: ${hunkBody.size}):")
    hunkBody.forEachIndexed { index, line ->
        logger.info("  Patch line ${index + 1}: $line")
    }

    logger.info("\nExpected baseline context (lines $baseStart to ${baseStart + baseCount - 1}):")
    for (i in (baseStart - 1) until (baseStart - 1 + baseCount)) {
        if (i in baselineLines.indices) {
            logger.info("  Baseline line ${i + 1}: ${baselineLines[i]}")
        } else {
            logger.info("  Baseline line ${i + 1}: [MISSING]")
        }
    }

    if (hunkBody.size != baseCount) {
        logger.info("\n❌ Mismatch: Hunk body line count (${hunkBody.size}) does not match expected baseline count ($baseCount).")
    } else {
        logger.info("\n✅ Hunk body line count matches expected baseline count ($baseCount).")
    }
}