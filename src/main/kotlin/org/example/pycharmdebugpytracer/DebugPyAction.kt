package com.example.debugpyplugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import javax.swing.JOptionPane

class DebugPyAction : AnAction("Run DebugPy") {
    override fun actionPerformed(e: AnActionEvent) {
        JOptionPane.showMessageDialog(null, "DebugPy Run Clicked!")
    }
}