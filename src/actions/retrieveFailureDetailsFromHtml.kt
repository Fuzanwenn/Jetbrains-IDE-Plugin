package com.example.jetbrainsplugin.actions

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File

fun extractFailureDetailsFromHtmlReport(reportPath: String): String {
    val file = File(reportPath)
    val doc: Document = Jsoup.parse(file, "UTF-8")

    val failures = StringBuilder("Test Failure Details:\n")

    // Extract the list of failed test functions
    val failedTestElements = doc.select("ul.linkList a")

    failedTestElements.forEach { testElement ->
        // Extract function name from the summary page
        val functionName = testElement.text()

        // Get the link to the test case page and separate the file path and fragment
        val testLink = testElement.attr("href")
        val fragmentIndex = testLink.indexOf("#")
        val cleanPath = if (fragmentIndex != -1) testLink.substring(0, fragmentIndex) else testLink
        val fragment = if (fragmentIndex != -1) testLink.substring(fragmentIndex) else ""

        // Load the test page HTML
        val testPageFile = File(file.parentFile, cleanPath).absolutePath
        val testPageDoc = Jsoup.parse(File(testPageFile), "UTF-8")

        // Extract class name
        val className = testPageDoc.select("h1").text()

        // Extract stack trace or error message
        val fullErrorDetails = testPageDoc.select("pre").firstOrNull()?.text() ?: "No stack trace available"
        val limitedStackTrace = fullErrorDetails.lines().take(6).joinToString("\n")

        if (className != functionName) {
            // Append the extracted information
            failures.append("Class: $className\n")
            failures.append("Method: $functionName\n")
            failures.append("Error:\n$limitedStackTrace\n\n")
        }

    }

    return failures.toString()
}
