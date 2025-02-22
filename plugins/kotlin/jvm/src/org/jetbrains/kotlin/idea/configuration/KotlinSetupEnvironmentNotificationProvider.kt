// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.configuration

import com.intellij.ide.JavaUiBundle
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectBundle
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ui.configuration.ProjectSettingsService
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotificationProvider.CONST_NULL
import com.intellij.ui.EditorNotifications
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.KotlinJvmBundle
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.configuration.ui.KotlinConfigurationCheckerService
import org.jetbrains.kotlin.idea.project.TargetPlatformDetector
import org.jetbrains.kotlin.idea.util.application.runWriteAction
import org.jetbrains.kotlin.idea.versions.SuppressNotificationState
import org.jetbrains.kotlin.idea.versions.UnsupportedAbiVersionNotificationPanelProvider
import org.jetbrains.kotlin.idea.versions.createComponentActionLabel
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.psi.KtFile
import java.util.function.Function
import javax.swing.JComponent

// Code is partially copied from com.intellij.codeInsight.daemon.impl.SetupSDKNotificationProvider
class KotlinSetupEnvironmentNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?> {
        if (file.extension != KotlinFileType.EXTENSION && !FileTypeRegistry.getInstance().isFileOfType(file, KotlinFileType.INSTANCE)) {
            return CONST_NULL
        }

        val psiFile = PsiManager.getInstance(project).findFile(file) as? KtFile ?: return CONST_NULL
        if (psiFile.language !== KotlinLanguage.INSTANCE) {
            return CONST_NULL
        }

        val module = ModuleUtilCore.findModuleForPsiElement(psiFile) ?: return CONST_NULL
        if (!ModuleRootManager.getInstance(module).fileIndex.isInSourceContent(file)) {
            return CONST_NULL
        }

        if (ModuleRootManager.getInstance(module).sdk == null &&
            TargetPlatformDetector.getPlatform(psiFile).isJvm()
        ) {
            return createSetupSdkPanel(project, psiFile)
        }

        val configurationChecker = KotlinConfigurationCheckerService.getInstance(module.project)

        if (!configurationChecker.isSyncing &&
            isNotConfiguredNotificationRequired(module.toModuleGroup()) &&
            !hasAnyKotlinRuntimeInScope(module) &&
            UnsupportedAbiVersionNotificationPanelProvider.collectBadRoots(module).isEmpty()
        ) {
            return createKotlinNotConfiguredPanel(module)
        }

        return CONST_NULL
    }

    companion object {
        private fun createSetupSdkPanel(project: Project, file: PsiFile): Function<in FileEditor, out JComponent?> =
            Function { fileEditor: FileEditor ->
                EditorNotificationPanel(fileEditor).apply {
                    text = JavaUiBundle.message("project.sdk.not.defined")
                    createActionLabel(ProjectBundle.message("project.sdk.setup")) {
                        ProjectSettingsService.getInstance(project).chooseAndSetSdk() ?: return@createActionLabel

                        runWriteAction {
                            val module = ModuleUtilCore.findModuleForPsiElement(file)
                            if (module != null) {
                                ModuleRootModificationUtil.setSdkInherited(module)
                            }
                        }
                    }
                }
            }

        private fun createKotlinNotConfiguredPanel(module: Module): Function<in FileEditor, out JComponent?> =
            Function { fileEditor: FileEditor ->
                EditorNotificationPanel(fileEditor).apply {
                text = KotlinJvmBundle.message("kotlin.not.configured")
                val configurators = getAbleToRunConfigurators(module).toList()
                if (configurators.isNotEmpty()) {
                    createComponentActionLabel(KotlinJvmBundle.message("action.text.configure")) { label ->
                        val singleConfigurator = configurators.singleOrNull()
                        if (singleConfigurator != null) {
                            singleConfigurator.apply(module.project)
                        } else {
                            val configuratorsPopup = createConfiguratorsPopup(module.project, configurators)
                            configuratorsPopup.showUnderneathOf(label)
                        }
                    }

                    createComponentActionLabel(KotlinJvmBundle.message("action.text.ignore")) {
                        SuppressNotificationState.suppressKotlinNotConfigured(module)
                        EditorNotifications.getInstance(module.project).updateAllNotifications()
                    }
                }
            }
        }

        private fun KotlinProjectConfigurator.apply(project: Project) {
            configure(project, emptyList())
            EditorNotifications.getInstance(project).updateAllNotifications()
            checkHideNonConfiguredNotifications(project)
        }

        fun createConfiguratorsPopup(project: Project, configurators: List<KotlinProjectConfigurator>): ListPopup {
            val step = object : BaseListPopupStep<KotlinProjectConfigurator>(
                KotlinJvmBundle.message("title.choose.configurator"),
                configurators
            ) {
                override fun getTextFor(value: KotlinProjectConfigurator?) = value?.presentableText ?: "<none>"

                override fun onChosen(selectedValue: KotlinProjectConfigurator?, finalChoice: Boolean): PopupStep<*>? {
                    return doFinalStep {
                        selectedValue?.apply(project)
                    }
                }
            }
            return JBPopupFactory.getInstance().createListPopup(step)
        }
    }
}
