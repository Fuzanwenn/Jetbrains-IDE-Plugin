package com.example.jetbrainsplugin.parser.gumtree

import com.example.jetbrainsplugin.ACRToolWindowFactory
import com.github.gumtreediff.gen.jdt.JdtTreeGenerator
import com.github.gumtreediff.tree.Tree
import com.github.gumtreediff.tree.TreeContext
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.StringReader

class Parser {
    /**
     * Parses a String representation of code into an AST.
     * If the code is blank, it returns null.
     *
     * Uses a StringReader to wrap the code and passes it to JdtTreeGenerator.generate(Reader).
     */

    companion object {
        private val logger = Logger.getInstance(Parser::class.java)
    }
    
    fun parseString(code: String): Tree? {
        if (code.isBlank()) {
            logger.error("⚠️ Skipping AST parsing: Code is empty!")
            return null
        }
        return try {
            val reader = StringReader(code)
            val treeContext: TreeContext = JdtTreeGenerator().generate(reader)
            treeContext.root
        } catch (e: Exception) {
            logger.error("❌ Error parsing AST from string: ${e.message}")
            null
        }
    }

    /**
     * Parses a File into an AST.
     * Checks whether the file exists and is non-empty before parsing.
     *
     * Uses a BufferedReader (from file.bufferedReader()) and passes it to JdtTreeGenerator.generate(Reader).
     */
    fun parseFile(file: File): Tree? {
        if (!file.exists() || file.readText().isBlank()) {
            logger.error("⚠️ Skipping AST parsing: File ${file.path} does not exist or is empty!")
            return null
        }
        return try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                val treeContext: TreeContext = JdtTreeGenerator().generate(reader)
                treeContext.root
            }
        } catch (e: Exception) {
            logger.error("❌ Error parsing AST from file ${file.path}: ${e.message}")
            null
        }
    }
}
