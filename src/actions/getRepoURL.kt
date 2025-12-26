package com.example.jetbrainsplugin.actions

import com.intellij.openapi.project.Project
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

private val logger = com.intellij.openapi.diagnostic.Logger.getInstance("getRepoURL")

fun getCurrentRepoInfo(project: Project): Pair<String?, String?> {
    val repoManager = GitRepositoryManager.getInstance(project)
    val repositories = repoManager.repositories

    // Check if there is any repository
    if (repositories.isEmpty()) return null to null

    // Assume we are working with the first repository
    val repository = repositories.firstOrNull() ?: return null to null

    // Fetch the remote URL
    val remoteUrl = repository.remotes.firstOrNull()?.firstUrl

    // Fetch the latest commit hash
    val latestCommitHash = getLatestCommitHash(project, repository)

    logger.info("Repo URL is: $remoteUrl")
    return remoteUrl to latestCommitHash
}

private fun getLatestCommitHash(project: Project, repository: GitRepository): String? {
    val handler = GitLineHandler(project, repository.root, GitCommand.LOG)
    handler.addParameters("-n", "1", "--pretty=format:%H")

    val result = Git.getInstance().runCommand(handler)
    return if (result.success()) result.output.joinToString("\n").trim() else null
}
