// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.script.configuration

import com.intellij.ide.actions.ShowSettingsUtilImpl
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotificationProvider.*
import com.intellij.ui.EditorNotifications
import com.intellij.ui.HyperlinkLabel
import org.jetbrains.annotations.Nls
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.core.script.ScriptDefinitionsManager
import org.jetbrains.kotlin.idea.core.script.StandardIdeScriptDefinition
import org.jetbrains.kotlin.idea.core.script.settings.KotlinScriptingSettings
import org.jetbrains.kotlin.parsing.KotlinParserDefinition
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.resolve.KotlinScriptDefinitionFromAnnotatedTemplate
import org.jetbrains.kotlin.scripting.resolve.KtFileScriptSource
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import java.util.function.Function
import javax.swing.JComponent

class MultipleScriptDefinitionsChecker : EditorNotificationProvider {

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?> {
        if (!FileTypeRegistry.getInstance().isFileOfType(file, KotlinFileType.INSTANCE)) return CONST_NULL

        val ktFile = PsiManager.getInstance(project).findFile(file).safeAs<KtFile>()?.takeIf(KtFile::isScript) ?: return CONST_NULL

        if (KotlinScriptingSettings.getInstance(project).suppressDefinitionsCheck ||
            !ScriptDefinitionsManager.getInstance(project).isReady()) return CONST_NULL

        val allApplicableDefinitions = ScriptDefinitionsManager.getInstance(project)
            .getAllDefinitions()
            .filter {
                it.asLegacyOrNull<StandardIdeScriptDefinition>() == null && it.isScript(KtFileScriptSource(ktFile)) &&
                        KotlinScriptingSettings.getInstance(project).isScriptDefinitionEnabled(it)
            }
            .toList()
        if (allApplicableDefinitions.size < 2 || areDefinitionsForGradleKts(allApplicableDefinitions)) return CONST_NULL

        return Function { fileEditor: FileEditor ->
            createNotification(
                fileEditor,
                ktFile,
                allApplicableDefinitions
            )
        }
    }

    private fun areDefinitionsForGradleKts(allApplicableDefinitions: List<ScriptDefinition>): Boolean {
        return allApplicableDefinitions.all {
            val pattern = it.asLegacyOrNull<KotlinScriptDefinitionFromAnnotatedTemplate>()?.scriptFilePattern?.pattern
            pattern == ".*\\.gradle\\.kts" || pattern == "^(settings|.+\\.settings)\\.gradle\\.kts\$" || pattern == ".+\\.init\\.gradle\\.kts"
        }
    }

    private fun createNotification(fileEditor: FileEditor, psiFile: KtFile, defs: List<ScriptDefinition>): EditorNotificationPanel =
        EditorNotificationPanel(fileEditor).apply {
            text = KotlinBundle.message("script.text.multiple.script.definitions.are.applicable.for.this.script", defs.first().name)
            createComponentActionLabel(
                KotlinBundle.message("script.action.text.show.all")
            ) { label ->
                val list = JBPopupFactory.getInstance().createListPopup(
                    object : BaseListPopupStep<ScriptDefinition>(null, defs) {
                        override fun getTextFor(value: ScriptDefinition): String {
                            @NlsSafe
                            val text = value.asLegacyOrNull<KotlinScriptDefinitionFromAnnotatedTemplate>()?.let {
                                it.name + " (${it.scriptFilePattern})"
                            } ?: value.asLegacyOrNull<StandardIdeScriptDefinition>()?.let {
                                it.name + " (${KotlinParserDefinition.STD_SCRIPT_EXT})"
                            } ?: (value.name + " (${value.fileExtension})")
                            return text
                        }
                    }
                )
                list.showUnderneathOf(label)
            }

            createComponentActionLabel(KotlinBundle.message("script.action.text.ignore")) {
                KotlinScriptingSettings.getInstance(psiFile.project).suppressDefinitionsCheck = true
                EditorNotifications.getInstance(psiFile.project).updateAllNotifications()
            }

            createComponentActionLabel(KotlinBundle.message("script.action.text.open.settings")) {
                ShowSettingsUtilImpl.showSettingsDialog(psiFile.project, KotlinScriptingSettingsConfigurable.ID, "")
            }
        }

    private fun EditorNotificationPanel.createComponentActionLabel(@Nls labelText: String, callback: (HyperlinkLabel) -> Unit) {
        val label: Ref<HyperlinkLabel> = Ref.create()
        label.set(createActionLabel(labelText) {
            callback(label.get())
        })
    }
}
