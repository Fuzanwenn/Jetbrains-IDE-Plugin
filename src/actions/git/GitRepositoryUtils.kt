package com.example.jetbrainsplugin.actions.git

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

private val logger = com.intellij.openapi.diagnostic.Logger.getInstance("GitRepositoryUtils")

/**
 * Retrieves the JGit Repository for the given IntelliJ Project.
 * This function uses the GitRepositoryManager to find the repository root,
 * then builds a Repository instance by locating the .git directory at that root.
 *
 * @param project the current IntelliJ Project.
 * @return a valid JGit Repository instance.
 * @throws IllegalArgumentException if no Git repository is found in the project.
 */
fun getRepository(project: Project): Repository {
    // Get the GitRepository from IntelliJ's GitRepositoryManager
    val repositoryManager = GitRepositoryManager.getInstance(project)
    val gitRepository = repositoryManager.repositories.firstOrNull()
        ?: throw IllegalArgumentException("No Git repository found in the project.")

    // Get the repository root as a File.
    val rootPath = gitRepository.root.path
    val gitDir = File(rootPath, ".git")
    if (!gitDir.exists()) {
        throw IllegalArgumentException("No .git directory found at $gitDir")
    }

    // Build and return the Repository instance using JGit.
    return FileRepositoryBuilder()
        .setGitDir(gitDir)
        .setWorkTree(File(rootPath))
        .readEnvironment()
        .setMustExist(true)
        .build()
}

/**
 * Retrieves the remote name for the given branch from the repository configuration.
 *
 * @param repository the JGit Repository.
 * @param branchName the local branch name.
 * @return the remote name if defined, or null otherwise.
 */
fun getRemoteName(repository: Repository, branchName: String): String? {
    return repository.config.getString("branch", branchName, "remote")
}

/**
 * Retrieves the remote merge reference for the given branch from the repository configuration.
 *
 * @param repository the JGit Repository.
 * @param branchName the local branch name.
 * @return the merge ref (e.g., refs/heads/main) if defined, or null otherwise.
 */
fun getRemoteMergeRef(repository: Repository, branchName: String): String? {
    return repository.config.getString("branch", branchName, "merge")
}

/**
 * Extracts the branch name from a merge reference.
 *
 * @param mergeRef the merge reference string (e.g., "refs/heads/main").
 * @return the branch name (e.g., "main").
 */
fun extractBranchNameFromMergeRef(mergeRef: String): String {
    return mergeRef.substringAfterLast("/")
}

fun getLatestBranch(project: Project): String? {
    val repository = getRepository(project)
    val currentBranch = repository.branch

    // Dynamically retrieve the remote name.
    val remoteName = getRemoteName(repository, currentBranch)
    if (remoteName == null) {
        logger.error("Error: Remote not defined for branch '$currentBranch'.")
        return null
    }

    // Retrieve the merge reference (i.e. the remote branch that the current branch is tracking).
    val mergeRef = getRemoteMergeRef(repository, currentBranch)
    if (mergeRef == null) {
        logger.error("Error: Merge ref not defined for branch '$currentBranch'.")
        return null
    }

    // Extract the branch name from the merge reference.
    return extractBranchNameFromMergeRef(mergeRef)
}

/**
 * Retrieves the latest commit that has been pushed to the remote repository.
 * This commit is the tip of the remote tracking branch for the current branch.
 *
 * @param project the IntelliJ Project.
 * @return the latest pushed commit as a RevCommit, or null if not found.
 */
fun getLatestCommit(project: Project): RevCommit? {
    val repository = getRepository(project)
    val currentBranch = repository.branch

    // Dynamically retrieve the remote name.
    val remoteName = getRemoteName(repository, currentBranch)
    if (remoteName == null) {
        logger.error("Error: Remote not defined for branch '$currentBranch'.")
        return null
    }

    // Retrieve the merge reference (i.e. the remote branch that the current branch is tracking).
    val mergeRef = getRemoteMergeRef(repository, currentBranch)
    if (mergeRef == null) {
        logger.error("Error: Merge ref not defined for branch '$currentBranch'.")
        return null
    }

    // Extract the branch name from the merge reference.
    val branchName = extractBranchNameFromMergeRef(mergeRef)
    val remoteRefName = "refs/remotes/$remoteName/$branchName"

    // Look up the remote reference.
    val remoteRef = repository.findRef(remoteRefName)
    if (remoteRef == null) {
        logger.error("Error: Remote reference not found for '$remoteRefName'.")
        return null
    }

    // Parse and return the commit pointed to by the remote reference.
    RevWalk(repository).use { revWalk ->
        return revWalk.parseCommit(remoteRef.objectId)
    }
}
