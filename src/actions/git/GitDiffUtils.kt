package com.example.jetbrainsplugin.actions.git

import com.intellij.openapi.project.Project
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.io.File

private val logger = com.intellij.openapi.diagnostic.Logger.getInstance("GitDiffUtils")

/**
 * Retrieves the list of changed files in the working directory relative to the baseline commit.
 * This function returns a list of File objects corresponding to the changed files.
 *
 * @param project the IntelliJ Project.
 * @param baselineCommit the commit representing the pushed baseline.
 * @return a list of File objects for files that have been added or modified (deletions are skipped).
 */
fun getChangedFiles(project: Project, baselineCommit: RevCommit): List<File> {
    val repository = getRepository(project)
    val rootDir = repository.workTree

    val reader: ObjectReader = repository.newObjectReader()
    val oldTreeIter = CanonicalTreeParser().apply {
        reset(reader, baselineCommit.tree)
    }
    val newTreeIter = FileTreeIterator(repository)

    val changedFiles = mutableListOf<File>()
    DiffFormatter(DisabledOutputStream.INSTANCE).use { diffFormatter ->
        diffFormatter.setRepository(repository)
        val diffEntries: List<DiffEntry> = diffFormatter.scan(oldTreeIter, newTreeIter)
        diffEntries.forEach { entry ->
            // For added or modified files, use the new path.
            if (entry.changeType != DiffEntry.ChangeType.DELETE) {
                val file = File(rootDir, entry.newPath)
                logger.info("📄 Changed file after latest commit: ${file.path}")
                changedFiles.add(file)
            }
        }
    }
    return changedFiles
}

/**
 * Retrieves the baseline version (as a String) of the given file from the baseline commit.
 * It computes the file's relative path from the repository's work tree so that it matches
 * the path stored in Git.
 *
 * @param file the changed File (with an absolute path) in the working tree.
 * @param baselineCommit the commit representing the pushed baseline.
 * @param project the IntelliJ Project.
 * @return the content of the file in the baseline commit as a String, or null if not found.
 */
fun getBaselineCode(file: File, baselineCommit: RevCommit, project: Project): String? {
    // Get the repository from the project.
    val repository = getRepository(project)
    // Compute the file's relative path with invariant separators (e.g., "src/main/java/duke/MainWindow.java")
    val relativePath = file.relativeTo(repository.workTree).invariantSeparatorsPath

    // Use TreeWalk to traverse the baseline commit's tree.
    TreeWalk(repository).use { treeWalk ->
        treeWalk.addTree(baselineCommit.tree)
        treeWalk.isRecursive = true
        while (treeWalk.next()) {
            if (treeWalk.pathString == relativePath) {
                val objectId = treeWalk.getObjectId(0)
                val loader = repository.open(objectId)
                return String(loader.bytes, Charsets.UTF_8)
            }
        }
    }
    return null
}

fun printFileContentNicely(file: File) {
    val content = file.readText(Charsets.UTF_8)
    logger.info("====== [${file.name}] ======")
    logger.info(content)
    logger.info("====== End of ${file.name} ======")
}

/**
 * Given a commit and a File object representing the current version of a file,
 * retrieves the version of that file as it was in the commit. Since the commit's tree
 * is not stored as a regular File object, this function extracts the file content and
 * writes it to a temporary file, returning that temporary File.
 *
 * @param project the IntelliJ Project.
 * @param commit the commit from which to retrieve the file.
 * @param currentFile a File object representing the file in the working tree.
 * @return a temporary File containing the file content as stored in the commit, or null if not found.
 */
fun getFileAtCommit(project: Project, commit: RevCommit, currentFile: File): File? {
    // Retrieve the repository using our helper.
    val repository = getRepository(project)
    // Compute the relative path of the file with invariant separators (e.g. "src/main/java/duke/MainWindow.java").
    val relativePath = currentFile.relativeTo(repository.workTree).invariantSeparatorsPath

    // Use TreeWalk to traverse the commit's tree.
    TreeWalk(repository).use { treeWalk ->
        treeWalk.addTree(commit.tree)
        treeWalk.isRecursive = true
        while (treeWalk.next()) {
            if (treeWalk.pathString == relativePath) {
                // Retrieve the blob content.
                val blobLoader = repository.open(treeWalk.getObjectId(0))
                // Create a temporary file to hold the content.
                val tempFile = File.createTempFile("commit_", "_" + currentFile.name)
                tempFile.writeBytes(blobLoader.bytes)
                printFileContentNicely(tempFile)
                return tempFile
            }
        }
    }
    return null
}

