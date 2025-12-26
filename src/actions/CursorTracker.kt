package com.example.jetbrainsplugin.actions

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.project.Project
import java.util.LinkedList

class CursorTracker(private val project: Project) {
    private val cursorHistory = LinkedList<String>()

    init {
        val editorFactory = EditorFactory.getInstance()

        // Add listener to track caret movements
        editorFactory.eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                val editor = event.editor
                val file = editor.virtualFile ?: return
                val position = "File: ${file.name}, Line: ${event.newPosition.line + 1}, Column: ${event.newPosition.column + 1}"
                trackCursorMove(position)
            }
        }, project)
    }

    private fun trackCursorMove(position: String) {
        if (cursorHistory.size >= 10) {
            cursorHistory.removeFirst() // Maintain only last 10 moves
        }
        cursorHistory.add(position)
    }

    fun getCursorHistory(): List<String> {
        return cursorHistory.toList()
    }
}
