package com.github.nvelychenko.drupalextend.intention

import com.github.nvelychenko.drupalextend.extensions.isInstanceOf
import com.intellij.codeInsight.intention.BaseElementAtCaretIntentionAction
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.childrenOfType
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.PhpCodeUtil
import com.jetbrains.php.lang.actions.generation.PhpDocCreationOption
import com.jetbrains.php.lang.inspections.quickfix.PhpImportClassQuickFix
import com.jetbrains.php.lang.psi.PhpPsiElementFactory
import com.jetbrains.php.lang.psi.elements.*
import com.jetbrains.php.lang.psi.elements.impl.MethodImpl
import fr.adrienbrault.idea.symfony2plugin.stubs.ServiceIndexUtil

/**
 * Implements an intention action to replace a ternary statement with if-then-else.
 */
class AddDependencyInjectionIntention : BaseElementAtCaretIntentionAction(), IntentionAction {
    override fun getFamilyName(): String {
        return "Drupal injection"
    }

    override fun getText(): String {
        return "Drupal injection"
    }

    override fun isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean {
        if (element.parent !is PhpClass) return false;
        return true;
    }

    override fun invoke(project: Project, editor: Editor, element: PsiElement) {
        val clazz = element.parent as PhpClass
        addConstructIfNone(project, clazz)
        if (ServiceIndexUtil.findServiceDefinitions(clazz).isEmpty()) {
            addInterfaceIfNone(project, clazz)
            addCreateIfNone(project, clazz)
        }
    }

    private fun addInterfaceIfNone(project: Project, clazz: PhpClass) {
        if (clazz.implementsList.childrenOfType<ClassReference>().firstOrNull { it.fqn == "\\Drupal\\Core\\DependencyInjection\\ContainerInjectionInterface" } != null) return
        val injectionInterface = PhpIndex.getInstance(project).getInterfacesByFQN("\\Drupal\\Core\\DependencyInjection\\ContainerInjectionInterface").firstOrNull()
                ?: return
        if (clazz.isInstanceOf(injectionInterface)) return
        if (clazz.implementsList.children.isEmpty()) {
            clazz.implementsList.replace(
                    PhpPsiElementFactory.createImplementsList(project, "\\Drupal\\Core\\DependencyInjection\\ContainerInjectionInterface")
            )
        } else {
            clazz.implementsList.replace(
                    PhpPsiElementFactory.createPhpPsiFromText(
                            project, ImplementsList::class.java,
                            "class A ${clazz.implementsList.text}, \\Drupal\\Core\\DependencyInjection\\ContainerInjectionInterface {}")
            )
        }
    }

    private fun addConstructIfNone(project: Project, clazz: PhpClass) {
        val constructor = clazz.ownConstructor
        if (constructor != null) return
        val firstMethod = clazz.childrenOfType<MethodImpl>().firstOrNull()
        val newConstructor = if (clazz.constructor != null) {
            PhpCodeUtil.createOverridingMethodText(clazz, clazz.constructor, PhpDocCreationOption.INHERIT)
        } else {
            "public function __construct() {}"
        }
        if (firstMethod != null) {
            clazz.addBefore(
                    PhpPsiElementFactory.createMethod(project, newConstructor),
                    firstMethod
            )
        } else {
            clazz.addBefore(
                    PhpPsiElementFactory.createMethod(project, newConstructor),
                    clazz.lastChild
            )
        }
    }

    private fun addCreateIfNone(project: Project, clazz: PhpClass) {
        val methods = clazz.childrenOfType<MethodImpl>()
        val create = methods.find {
            it.name == "create"
        }
        if (create != null) return
        val construct = methods.find {
            it.name == "__construct"
        }
        val createImpl = clazz.findMethodByName("create") ?: return
        val createImplText = createImpl.text?.trimEnd(';')
        val newCreate = PhpPsiElementFactory.createMethod(project, "$createImplText{}")
        val newCreatePsi = if (construct != null) {
            clazz.addAfter(
                    newCreate,
                    construct
            )
        } else {
            clazz.addBefore(
                    newCreate,
                    clazz.lastChild
            )
        }

        var index = 0;
        createImpl.parameters.forEach {
            if (it.children.firstOrNull()?.children?.firstOrNull() !is PhpReference) return@forEach
            val ref = (it.children.firstOrNull()?.children?.firstOrNull() as? PhpReference)?.resolveGlobal(false)?.firstOrNull()
            val crRef = (newCreatePsi as Method).parameters[index].children.firstOrNull()?.children?.firstOrNull() as? PhpReference
            if (ref == null || crRef == null) return@forEach
            PhpImportClassQuickFix.INSTANCE.applyFix(project, crRef, ref)
            index++
        }
    }

}