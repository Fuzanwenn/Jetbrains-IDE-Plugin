package com.example.jetbrainsplugin.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipProjectAction : AnAction("Zip Project") {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val projectBaseDir = project.baseDir
        val zipFilePath = "${projectBaseDir.path}.zip"

        // Run the zipping process with progress indication
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            zipDirectory(projectBaseDir, zipFilePath)
        }, "Zipping Project", true, project)

        Messages.showInfoMessage("Project zipped successfully to: $zipFilePath", "Zipping Complete")
    }

    private fun zipDirectory(folder: VirtualFile, zipPath: String) {
        ZipOutputStream(FileOutputStream(zipPath)).use { zipOut ->
            zipFile(folder, folder, zipOut)
        }
    }

    private fun zipFile(rootDir: VirtualFile, fileToZip: VirtualFile, zipOut: ZipOutputStream) {
        if (fileToZip.isDirectory) {
            val children = fileToZip.children
            if (children.isEmpty()) {
                val zipEntry = ZipEntry(getZipEntryName(rootDir, fileToZip) + "/")
                zipOut.putNextEntry(zipEntry)
                zipOut.closeEntry()
            } else {
                for (child in children) {
                    zipFile(rootDir, child, zipOut)
                }
            }
            return
        }
        val zipEntry = ZipEntry(getZipEntryName(rootDir, fileToZip))
        zipOut.putNextEntry(zipEntry)
        fileToZip.inputStream.use { input ->
            input.copyTo(zipOut)
        }
        zipOut.closeEntry()
    }

    private fun getZipEntryName(rootDir: VirtualFile, fileToZip: VirtualFile): String {
        val rootPath = rootDir.path
        val filePath = fileToZip.path
        return filePath.substring(rootPath.length + 1)
    }
}
