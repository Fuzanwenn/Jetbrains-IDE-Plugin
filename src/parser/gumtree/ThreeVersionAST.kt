package com.example.jetbrainsplugin.parser.gumtree

import com.example.jetbrainsplugin.actions.git.getBaselineCode
import com.example.jetbrainsplugin.actions.git.getFileAtCommit
import com.example.jetbrainsplugin.actions.git.getLatestCommit
import com.example.jetbrainsplugin.parser.applyDiffToBaseline
import com.github.gumtreediff.tree.Tree
import com.intellij.openapi.project.Project
import java.io.File

class ThreeVersionAST(private val project: Project) {
    private val baselineCommit = getLatestCommit(project)

    fun getBaseAST(file: File): Tree? {
        val baselineCode = baselineCommit?.let { getBaselineCode(file, it, project) }
        val parser = Parser()
        return baselineCode?.let { parser.parseString(it) }
    }

    fun getModifiedAST(file: File): Tree? {
        val parser = Parser()
        return parser.parseFile(file)
    }

    fun getPatchedAST(file: File, diffText: String): Tree? {
        val baseFile = baselineCommit?.let { getFileAtCommit(project, it, file) }
        val patchedFile = baseFile?.let { applyDiffToBaseline(it, diffText) }
        val parser = Parser()
        return patchedFile?.let { parser.parseFile(it) }
    }

}
