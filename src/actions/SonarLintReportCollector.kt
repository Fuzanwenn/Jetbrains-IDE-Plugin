package com.example.jetbrainsplugin.actions

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.sonarsource.sonarlint.core.analysis.AnalysisEngine
import org.sonarsource.sonarlint.core.analysis.api.*
import org.sonarsource.sonarlint.core.analysis.command.AnalyzeCommand
import org.sonarsource.sonarlint.core.commons.api.SonarLanguage
import org.sonarsource.sonarlint.core.commons.api.progress.ClientProgressMonitor
import org.sonarsource.sonarlint.core.commons.log.LogOutput
import org.sonarsource.sonarlint.core.commons.log.SonarLintLogger
import org.sonarsource.sonarlint.core.commons.progress.ProgressMonitor
import org.sonarsource.sonarlint.core.plugin.commons.PluginsLoader
import org.sonarsource.sonarlint.core.plugin.commons.PluginsLoader.Configuration
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.function.Consumer
import kotlin.io.path.relativeTo

private val logger = com.intellij.openapi.diagnostic.Logger.getInstance("SonarLintReport")

class PluginConfig private constructor() {
    companion object {
        fun getPluginDirectory(): String {
            // Check the local path in your build output directory
            val sonarlintHome = System.getenv("SONARLINT_HOME").trim() ?: throw IllegalStateException(
                "SONARLINT_HOME environment variable is not set. Please set SONARLINT_HOME to your sonarlint jar installation path."
            )
            return sonarlintHome
        }

        fun getJdkHome(): String {
            return System.getenv("JDK_HOME") ?: throw IllegalStateException(
                "JDK_HOME environment variable is not set. Please set JDK_HOME to your Java installation path."
            )
        }

        fun getJavaLibsPath(): String {
            val jdkHome = getJdkHome()
            val javaLibPath = Paths.get(jdkHome, "lib", "jrt-fs.jar")

            if (!Files.exists(javaLibPath)) {
                throw IllegalStateException("Java libraries not found at expected path: $javaLibPath. Check your JDK installation.")
            }
            return javaLibPath.toAbsolutePath().toString()
        }
    }
}


class CustomClientInputFile(private val file: File, private val projectDir: String?) : ClientInputFile {
    override fun getPath(): String = file.absolutePath
    override fun isTest(): Boolean = true
    override fun getCharset(): Charset? = Charset.forName("UTF-8")
    override fun <G> getClientObject(): G = throw UnsupportedOperationException("Not implemented")
    override fun inputStream(): InputStream = file.inputStream()
    override fun contents(): String = file.readText()

    override fun relativePath(): String {
        return projectDir?.let {
            try {
                file.toPath().relativeTo(Paths.get(it)).toString()
            } catch (e: IllegalArgumentException) {
                file.absolutePath
            }
        } ?: file.absolutePath
    }

    override fun uri(): URI = file.toURI()
}



class SimpleClientProgressMonitor : ClientProgressMonitor {
    override fun isCanceled(): Boolean {
        logger.info("Checking if the analysis is canceled")
        return false
    }
}


class ResourceLoader {
    fun loadResourcePath(resource: String): Path {
        // Try loading the resource from the classpath
        val resourceUrl = javaClass.classLoader.getResource(resource)

        if (resourceUrl != null) {
            try {
                return Paths.get(resourceUrl.toURI())
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("Error converting URL to URI for resource: $resource", e)
            }
        }

        // Fallback to a path relative to the plugin directory
        val fallbackPath = Paths.get("src", "main", "resources", resource)

        if (Files.exists(fallbackPath)) {
            logger.info("Fallback path: $fallbackPath")
            return fallbackPath.toAbsolutePath()
        }

        // If both classpath and fallback paths fail
        throw IllegalArgumentException("Resource not found: $resource")
    }
}


// Main analysis function
fun analyzeFileWithSonarLint(
    filePath: String,
    projectDir: String?,
    project: Project,
    onComplete: CompletableDeferred<Unit>? = null
): String = runBlocking {
    if (!File(filePath).exists()) {
        logger.info("File $filePath does not exist.")
        onComplete?.complete(Unit)
        return@runBlocking "File does not exist."
    }

    val workingDirectory = Paths.get(projectDir, "sonarlint_work_dir")
    if (!Files.exists(workingDirectory)) {
        Files.createDirectories(workingDirectory)
    }

    logger.info("Working directory: $workingDirectory")

    val sourceDirectory = Paths.get(projectDir, "src").toFile()
    val allSourceFiles: List<File>
    allSourceFiles = sourceDirectory.walkTopDown().filter { it.extension == "java" || it.extension == "py"}.toList()

    val issueDescriptions = StringBuilder()

    val pluginPath = PluginConfig.getPluginDirectory()

    logger.info("pluginPath: ${pluginPath}")

    val pluginPaths: Set<Path> = setOf(
        Paths.get(pluginPath),
    )

    // Step 2: Define enabled languages (Java in this case)
    val enabledLanguages: Set<SonarLanguage> = setOf(SonarLanguage.JAVA, SonarLanguage.PYTHON)

    // Step 3: Set up the log output for SonarLint
    val logOutput = object : LogOutput {
        override fun log(message: String, level: LogOutput.Level) {
            logger.info("[${level.name}] $message")
        }
    }
    SonarLintLogger.setTarget(logOutput)  // Explicitly configure the log output here

    // Step 4: Create Configuration for PluginsLoader
    val configuration = Configuration(
        pluginPaths,
        enabledLanguages,
        false,  // Disable dataflow bug detection
        Optional.empty() // No node version required
    )

    // Step 5: Load plugins using PluginsLoader
    val pluginsLoadResult = PluginsLoader().load(configuration)

    // Step 6: Extract loaded plugins
    val loadedPlugins = pluginsLoadResult.loadedPlugins
    loadedPlugins.pluginInstancesByKeys.forEach { (key, plugin) ->
        logger.info("Loaded plugin: $key - ${plugin::class.java.name}")
    }

    val testLibraries = mutableListOf<String>()
    val modules = ModuleManager.getInstance(project).modules

    for (module in modules) {
        val classpathRoots: Array<VirtualFile> = OrderEnumerator.orderEntries(module)
            .withoutSdk()
            .withoutModuleSourceEntries()
            .getClassesRoots()

        classpathRoots.forEach { root ->
            val rootPath = root.path
            val file = File(rootPath)
            if (file.exists() && (file.isDirectory || file.extension == "jar")) {
                testLibraries.add(rootPath)
            } else {
                logger.info("Skipping invalid or non-existent path: $rootPath")
            }
        }
    }

    val allActiveRulesJava = getAllActiveRulesForLanguage("java")
    val allActiveRulesPython = getAllActiveRulesForLanguage("python")

    // Dynamic resolution of source paths and binaries
    val javaSourceDirectories = findJavaSourceDirectories(projectDir)
    val javaBinariesPath = findJavaBinariesDirectory(projectDir)
    val javaTestBinariesPath = findJavaTestBinariesDirectory(projectDir)

    val pythonSourceDirectories = findPythonSourceDirectories(projectDir)
    val pythonLibrariesPath = findPythonLibrariesDirectory(projectDir) ?: ""
    val pythonTestPath = findPythonTestDirectory(projectDir) ?: ""

    logger.info("python source directory: $pythonSourceDirectories")
    logger.info("python lib path: $pythonLibrariesPath")
    logger.info("python test path: $pythonTestPath")

    val javaLibsPath = PluginConfig.getJavaLibsPath()

    // Step 7: Run the analysis for each file
    for (sourceFile in allSourceFiles) {
        val clientInputFile = CustomClientInputFile(sourceFile, projectDir)
        val activeRules = if (sourceFile.extension == "java") allActiveRulesJava else allActiveRulesPython

        // Prepend class path or file path for each file
        val classPath = clientInputFile.relativePath()
        val fileIssueDescriptions = StringBuilder()
//        issueDescriptions.append("Issues found in class: $classPath\n")
        val analysisConfig: AnalysisConfiguration
        // Create the analysis configuration for this specific file
        if (sourceFile.extension == "java") {
            analysisConfig = AnalysisConfiguration.builder()
                .setBaseDir(workingDirectory)
                .addInputFile(clientInputFile)
                .addActiveRules(activeRules)
                .putExtraProperty("sonar.lint.projectVersion", "1.0")
                .putExtraProperty("sonar.lint.file.suffixes", ".java")
                .putExtraProperty("sonar.java.libraries", javaLibsPath)
                .putExtraProperty("sonar.java.test.libraries", javaLibsPath)
                .putExtraProperty("sonar.java.binaries", javaBinariesPath ?: "")
                .putExtraProperty("sonar.java.test.binaries", javaTestBinariesPath ?: "")
                .putExtraProperty("sonar.java.jdkHome", PluginConfig.getJdkHome())
                .putExtraProperty("sonar.java.source", "17")
                .putExtraProperty("sonar.java.target", "17")
                .putExtraProperty("sonar.working.directory", workingDirectory.resolve("sonar_work_dir").toString())
                .putExtraProperty("sonar.sources", javaSourceDirectories.joinToString(","))
                .putExtraProperty("sonar.verbose", "true")
                .putExtraProperty("sonar.log.level", "DEBUG")
                .build()
        } else {
            logger.info("Client input file metadata: ${clientInputFile.relativePath()} with charset ${clientInputFile.getCharset()}")
            logger.info("binaries: ${pythonSourceDirectories.firstOrNull()}")
            logger.info("sonar.sources: ${pythonSourceDirectories.joinToString(",")}")
            logger.info("sonar.python.libraries: ${pythonLibrariesPath}")
            logger.info("sonar.python.test.libraries: $pythonTestPath")
            analysisConfig = AnalysisConfiguration.builder()
                .setBaseDir(workingDirectory)
                .addInputFile(clientInputFile)
                .addActiveRules(activeRules)
                .putExtraProperty("sonar.lint.projectVersion", "1.0")
                .putExtraProperty("sonar.lint.file.suffixes", ".py")
                .putExtraProperty("sonar.python.file.suffixes", ".py")
                .putExtraProperty("sonar.python.version", "3.11")
                .putExtraProperty("sonar.python.forceParsing", "true")
                .putExtraProperty("sonar.python.indexAllFiles", "true")
                .putExtraProperty("sonar.python.cache", workingDirectory.resolve("python_cache").toString())
                .putExtraProperty("sonar.working.directory", workingDirectory.resolve("sonar_work_dir").toString())
                .putExtraProperty("sonar.verbose", "true")
                .putExtraProperty("sonar.log.level", "DEBUG")
                .build()

            logger.info("Active rules count for Python: ${activeRules.size}")
        }


        // Step 8: Create the analysis engine with the loaded plugins
        val analysisEngine = AnalysisEngine(
            AnalysisEngineConfiguration.builder()
                .setWorkDir(workingDirectory)
                .build(),
            loadedPlugins,
            logOutput
        )

        val capturedIssues = mutableListOf<Issue>()

        val issueListener = Consumer<Issue> { issue ->
            capturedIssues.add(issue)
            val primaryMessage = issue.getMessage()
            val ruleKey = issue.getRuleKey()
            val line = issue.getTextRange()?.startLine ?: "N/A"

            // Retrieve suggested fixes if available
            val quickFixes = issue.quickFixes()
            val fixesDescription = if (quickFixes.isNotEmpty()) {
                quickFixes.joinToString("\n") { fix ->
                    val editDescriptions = fix.inputFileEdits().joinToString("\n") { edit ->
                        val targetFile = edit.target().relativePath()  // Get file path from ClientInputFile
                        val textEdits = edit.textEdits().joinToString("\n") { textEdit ->
                            val range = textEdit.range()
                            val newText = textEdit.newText()
                            "Edit in $targetFile - " +
                                    "From line ${range.getStartLine()}, offset ${range.getStartLineOffset()} " +
                                    "to line ${range.getEndLine()}, offset ${range.getEndLineOffset()}, " +
                                    "replacement text: \"$newText\""
                        }
                        textEdits  // Combine all text edits for this file edit
                    }
                    "- Suggested fix: ${fix.message()}\n$editDescriptions"
                }
            } else {
                "No suggested fixes available"
            }

            // Log the issue and suggested fixes
            logOutput.log("Issue detected: $primaryMessage at line $line, rule: $ruleKey", LogOutput.Level.INFO)
            logOutput.log("Suggested fixes:\n$fixesDescription", LogOutput.Level.INFO)

            // Append the issue details and suggested fixes to the output
            fileIssueDescriptions.append("Issue detected: $primaryMessage at line $line, rule: $ruleKey\n")
                .append("Suggested fixes: $fixesDescription\n\n")        }

        val analyzeCommand = AnalyzeCommand(null, analysisConfig, issueListener, logOutput)

        val clientProgressMonitor = SimpleClientProgressMonitor()
        val progressMonitor = ProgressMonitor(clientProgressMonitor)

        val analysisComplete = CompletableDeferred<Unit>()

        // Run the analysis and wait for completion
        try {
            analysisEngine.post(analyzeCommand, progressMonitor).thenAccept { analysisResults: AnalysisResults ->
                if (analysisResults.rawIssues.isNotEmpty()) {
                    analysisResults.rawIssues.forEach { issue ->
                        logger.info("Issue found: ${issue.primaryMessage} at line ${issue.textRange}, rule: ${issue.ruleKey}")
                    }
                } else {
                    logOutput.log("No issues found, possibly a configuration problem.", LogOutput.Level.WARN)
                }
                analysisComplete.complete(Unit)
            }.exceptionally { throwable ->
                logOutput.log("Error during analysis: ${throwable.message}", LogOutput.Level.ERROR)
                throwable.printStackTrace()
                analysisComplete.complete(Unit)
                null
            }
        } catch (e: Exception) {
            logOutput.log("Exception in SonarLint analysis: ${e.message}", LogOutput.Level.ERROR)
            e.printStackTrace()
            analysisComplete.complete(Unit)
        }

        analysisComplete.await()
        if (fileIssueDescriptions.isNotEmpty()) {
            issueDescriptions.append("Issues found in class: $classPath\n")
            issueDescriptions.append(fileIssueDescriptions.toString())
        }
    }

    onComplete?.complete(Unit)
    return@runBlocking issueDescriptions.toString()
}


fun getAllActiveRulesForLanguage(languageKey: String): Collection<ActiveRule> {
    // Simulate getting all active rules for the specific language (e.g., "java")
    val ruleKeys = (100..9999).map { "$languageKey:S$it" }  // Create rule keys in the format "java:S1000" to "java:S9999"
    return ruleKeys.map { ActiveRule(it, languageKey) }
}

fun findPythonSourceDirectories(projectDir: String?): List<String> {
    if (projectDir == null) return listOf()

    val sourcePaths = mutableListOf<String>()
    // Common directories for Python source files
    val potentialDirs = listOf("src", "lib")  // Replace 'project_name' with your project's name or other relevant directory

    potentialDirs.forEach { dir ->
        val fullPath = Paths.get(projectDir, dir)
        if (Files.exists(fullPath)) {
            sourcePaths.add(fullPath.toString())
        }
    }

    return sourcePaths
}

fun findPythonLibrariesDirectory(projectDir: String?): String? {
    if (projectDir == null) return null

    // Common directories for Python libraries, including virtual environments
    val libraryPaths = listOf("venv", "env/lib/site-packages", "lib/site-packages", "lib", "venv/Lib", "env/Lib")
    for (path in libraryPaths) {
        val fullPath = Paths.get(projectDir, path)
        if (Files.exists(fullPath)) {
            return fullPath.toString()
        }
    }
    return null
}

fun findPythonTestDirectory(projectDir: String?): String? {
    if (projectDir == null) return null

    // Common test directories in Python projects
    val testPaths = listOf("tests", "test", "src/tests", "src/test")
    for (path in testPaths) {
        val fullPath = Paths.get(projectDir, path)
        if (Files.exists(fullPath)) {
            return fullPath.toString()
        }
    }
    return null
}

fun findJavaSourceDirectories(projectDir: String?): List<String> {
    if (projectDir == null) return listOf()

    val sourcePaths = mutableListOf<String>()
    val potentialDirs = listOf("src/main/java", "src/generated/java", "src/main/kotlin")

    potentialDirs.forEach { dir ->
        val fullPath = Paths.get(projectDir, dir)
        if (Files.exists(fullPath)) {
            sourcePaths.add(fullPath.toString())
        }
    }

    return sourcePaths
}

fun findJavaBinariesDirectory(projectDir: String?): String? {
    if (projectDir == null) return null

    val binaryPaths = listOf("build/classes/java/main", "out/production/classes", "bin/classes")
    for (path in binaryPaths) {
        val fullPath = Paths.get(projectDir, path)
        if (Files.exists(fullPath)) {
            return fullPath.toString()
        }
    }
    return null
}

fun findJavaTestBinariesDirectory(projectDir: String?): String? {
    if (projectDir == null) return null

    val testBinaryPaths = listOf("build/classes/java/test", "out/test/classes", "bin/test-classes")
    for (path in testBinaryPaths) {
        val fullPath = Paths.get(projectDir, path)
        if (Files.exists(fullPath)) {
            return fullPath.toString()
        }
    }
    return null
}
