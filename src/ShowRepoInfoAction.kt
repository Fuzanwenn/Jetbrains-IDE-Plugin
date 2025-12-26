package com.example.jetbrainsplugin

import com.example.jetbrainsplugin.actions.getCurrentRepoInfo
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class ShowRepoInfoAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        // Retrieve the repository information
        val (repoUrl, latestCommit) = getCurrentRepoInfo(project)

        // Display the information in a dialog
        Messages.showMessageDialog(
                project,
                "Repository URL: $repoUrl\nLatest Commit Hash: $latestCommit",
                "Repository Info",
                Messages.getInformationIcon()
        )
    }
}
