package com.example.jetbrainsplugin.parser

import com.example.jetbrainsplugin.actions.LLMClient
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.visibilityModifier

private val logger = com.intellij.openapi.diagnostic.Logger.getInstance("ReferenceFinder")

object ReferenceFinder {

    /**
     * Finds references to an element (method, field, or class) using PSI traversal.
     * In addition to collecting reference occurrences (with line numbers and context information),
     * this function appends the full class content at the end.
     */
    fun findReferences(file: VirtualFile, elementName: String, project: Project): List<String> {
        return ApplicationManager.getApplication().runReadAction<List<String>> {
            val referencesList = mutableListOf<String>()

            if (!file.isValid) {
                logger.debug("⚠️ Skipping invalid file: ${file.path}")
                return@runReadAction emptyList()
            }

            // Obtain the PSI file for the given VirtualFile.
            val psiFile = PsiManager.getInstance(project).findFile(file)
            if (psiFile == null) {
                logger.debug("❌ PSI file not found for ${file.path}")
                return@runReadAction emptyList()
            }

            // Get the corresponding Document to compute line numbers.
            val document = FileDocumentManager.getInstance().getDocument(file)
            if (document == null) {
                logger.debug("❌ Unable to get document for ${file.path}")
                return@runReadAction emptyList()
            }

            // Traverse PSI elements to locate references matching the element name.
            psiFile.accept(object : PsiRecursiveElementVisitor() {
                override fun visitElement(element: PsiElement) {
                    if (element.text == elementName) {
                        val offset = element.textOffset
                        val lineNumber = document.getLineNumber(offset) + 1 // Document lines are 0-based.
                        var context = ""

                        // For Java: Check if the element is inside a method.
                        val psiMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
                        if (psiMethod != null) {
                            context = "in function '${psiMethod.name}'"
                        } else {
                            // Check if it's in a Java field.
                            val psiField = PsiTreeUtil.getParentOfType(element, PsiField::class.java)
                            if (psiField != null) {
                                val modifiers = psiField.modifierList?.text ?: ""
                                val className = psiField.containingClass?.name ?: "unknown class"
                                context = "in ${modifiers.trim()} field '${psiField.name}' of class '$className'"
                            } else {
                                // For Kotlin: check for a named function.
                                val ktFunction = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java)
                                if (ktFunction != null) {
                                    context = "in function '${ktFunction.name}'"
                                } else {
                                    // For Kotlin: check for a property.
                                    val ktProperty = PsiTreeUtil.getParentOfType(element, KtProperty::class.java)
                                    if (ktProperty != null) {
                                        val visibility = ktProperty.visibilityModifier()?.text ?: "default"
                                        val ktClass = PsiTreeUtil.getParentOfType(ktProperty, KtClass::class.java)
                                        val className = ktClass?.name ?: "unknown class"
                                        context = "in $visibility field '${ktProperty.name}' of class '$className'"
                                    } else {
                                        context = "at top-level or unknown context"
                                    }
                                }
                            }
                        }
                        referencesList.add("Line $lineNumber $context: ${element.text}")
                    }
                    super.visitElement(element)
                }
            })

            referencesList
        }
    }


    fun findClassFiles(className: String, project: Project): List<VirtualFile> {
        val projectBasePath = project.basePath ?: return emptyList()
        val projectDir = LocalFileSystem.getInstance().findFileByPath(projectBasePath) ?: return emptyList()

        val matchingFiles = mutableListOf<VirtualFile>()

        ApplicationManager.getApplication().runReadAction {
            if (!projectDir.isValid) {
                logger.debug("⚠️ Project directory is invalid: $projectBasePath")
                return@runReadAction
            }
            collectJavaKtFiles(projectDir, matchingFiles, className)
        }

        return matchingFiles
    }


    /**
     * Recursively searches for Java/Kotlin files matching the given class name.
     */
    private fun collectJavaKtFiles(dir: VirtualFile, matchingFiles: MutableList<VirtualFile>, className: String) {
        for (file in dir.children) {
            if (file.isDirectory) {
                collectJavaKtFiles(file, matchingFiles, className)
            } else if (file.extension == "java" || file.extension == "kt") {
                if (file.nameWithoutExtension == className) {
                    matchingFiles.add(file)
                }
            }
        }
    }
}


fun constructReferenceString(issueDescription: String, project: Project): String? {
    val gptClient = LLMClient()

    val (elementName, className) = gptClient.extractElementAndClass(issueDescription)

    if (elementName != null && className != null) {
        logger.debug("✅ Extracted Element: $elementName")
        logger.debug("✅ Extracted Class: $className")

        // Find class file(s) in the project
        val classFiles = ReferenceFinder.findClassFiles(className, project)
        if (classFiles.isEmpty()) {
            logger.debug("❌ Class `$className` not found in the project.")
            return null
        }

        val allReferences = mutableListOf<String>()

        for (file in classFiles) {
            if (!file.isValid) {
                logger.debug("⚠️ Skipping invalid file: ${file.name}")
                continue
            }

            logger.debug("🔍 Searching for references in ${file.name}...")

            // Find references and store AST for later tracking
            val references = ReferenceFinder.findReferences(file, elementName, project)
            allReferences.addAll(references)
        }

        val referencesString = if (allReferences.isNotEmpty()) {
            val header = "**References for `$elementName` in class `$className`:**\n"
            val referencesList = allReferences.joinToString("\n") { "$it" }
            "$header\n$referencesList"
        } else {
            logger.debug("❌ No references found for `$elementName` in class `$className`.")
            null
        }

        logger.debug(referencesString)
        return referencesString
    } else {
        logger.debug("❌ Failed to extract element and class from description.")
        return null
    }
}


