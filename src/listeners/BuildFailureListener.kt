package com.example.jetbrainsplugin.listeners

import com.example.jetbrainsplugin.ACRToolWindowFactory
import com.example.jetbrainsplugin.actions.extractFailureDetailsFromHtmlReport
import com.intellij.build.BuildProgressListener
import com.intellij.build.BuildViewManager
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FailureResult
import com.intellij.build.events.FinishBuildEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

class BuildFailureListener(
    private val project: Project,
    private val onFailure: (String) -> Unit
) {

    companion object {
        private val logger = Logger.getInstance(ACRToolWindowFactory::class.java)
    }
    
    private val failureMessagesBuffer = StringBuilder()
    private val uniqueMessages = mutableSetOf<String>()
    private var buildFinishedCalled = false
    private var disposable: Disposable? = null
    var ignoreFailures = false

    private val reportLinkRegex = Regex("file://[^\\s]*\\.html")

    // Clean and normalize failure messages.
    private fun cleanFailureMessage(message: String): String {
        var cleanedMessage = message.replace(Regex("<.*?>"), "") // Strip XML tags.

        cleanedMessage = cleanedMessage.split("\n")
            .map { it.trim() } // Trim each line
            .filter { line ->
                line.isNotBlank()
            }
            .joinToString("\n")

        return cleanedMessage
    }

    // Normalize message to make it comparable (hashing for uniqueness).
    private fun normalizeMessage(message: String): String {
        return message
            .replace(Regex("\\s+"), " ")  // Replace multiple spaces with a single space
            .trim()
            .lowercase() // Make case-insensitive comparisons
    }

    // Extract the report link from the raw build message.
    public fun extractReportLink(message: String): String? {
        val matchResult = reportLinkRegex.find(message)
        val link = matchResult?.value
        logger.info("Extracted link: $link") // Debug print
        return link
    }

    // Trigger the callback with the collected failure messages.
    private fun triggerOnFailureIfNecessary() {
        if (buildFinishedCalled && !ignoreFailures) {
            // Iterate through the unique messages and add them to the buffer.
            if ("> task :test failed" in uniqueMessages) {
                for (message in uniqueMessages) {
                    val reportLink = extractReportLink(message)
                    if (reportLink != null) {
                        logger.info("Extracted test report link: $reportLink")
                        try {
                            val filePath = reportLink.removePrefix("file://")
                            val failureDetails = extractFailureDetailsFromHtmlReport(filePath)
                            failureMessagesBuffer.append("\n$failureDetails\n")
                        } catch (e: Exception) {
                            failureMessagesBuffer.append("\nFailed to load HTML report: ${e.message}\n")
                        }
                        break
                    }
                }
            } else {
                for (message in uniqueMessages) {
                    failureMessagesBuffer.append(message).append("\n") // Add each message with a newline.
                }
            }

            // Call the failure handler with the collected messages.
            onFailure(failureMessagesBuffer.toString())

            // Clear the buffer and reset state.
            failureMessagesBuffer.clear()
            uniqueMessages.clear()
            buildFinishedCalled = false
        }
    }

    fun startListeningForBuildFailures() {
        disposable = Disposable { }
        ignoreFailures = false

        val buildViewManager = project.getService(BuildViewManager::class.java)

        val buildProgressListener = object : BuildProgressListener {
            override fun onEvent(buildId: Any, event: BuildEvent) {
                if (ignoreFailures) return

                val message = event.message
                logger.info("Raw build message: $message") // Debugging line
                val cleanedMessage = cleanFailureMessage(message)
                val normalizedMessage = normalizeMessage(cleanedMessage) // Normalize for uniqueness
                logger.info("Normalized message: $normalizedMessage")

                if (uniqueMessages.add(normalizedMessage)) {
                    logger.info("Added unique message: $normalizedMessage")
                } else {
                    logger.info("Duplicate message ignored: $normalizedMessage")
                }

                if (event is FinishBuildEvent && event.result is FailureResult) {
                    logger.info("Build has failed")
                    buildFinishedCalled = true
                    triggerOnFailureIfNecessary()
                }
            }
        }

        buildViewManager.addListener(buildProgressListener, disposable!!)
    }

    fun stopListeningForBuildFailures() {
        disposable?.dispose()
        ignoreFailures = true
        disposable = null
    }
}
