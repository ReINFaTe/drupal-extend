package com.github.nvelychenko.drupalextend.type

import com.github.nvelychenko.drupalextend.extensions.getValue
import com.github.nvelychenko.drupalextend.extensions.isSuperInterfaceOf
import com.github.nvelychenko.drupalextend.index.ContentEntityIndex
import com.github.nvelychenko.drupalextend.patterns.Patterns
import com.github.nvelychenko.drupalextend.project.drupalExtendSettings
import com.github.nvelychenko.drupalextend.type.EntityStorageTypeProvider.Util.SPLITER_KEY
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.childrenOfType
import com.intellij.util.indexing.FileBasedIndex
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.*
import com.jetbrains.php.lang.psi.resolve.types.PhpType
import com.jetbrains.php.lang.psi.resolve.types.PhpTypeProvider4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream


/**
 * $entity_storage = \Drupal::entityTypeManager()->getStorage('node');
 *       ↑
 */
class EntityStorageTypeProvider : PhpTypeProvider4 {

    private val entityTypeManagerInterface = "\\Drupal\\Core\\Entity\\EntityTypeManagerInterface"

    private val fileBasedIndex: FileBasedIndex by lazy {
        FileBasedIndex.getInstance()
    }

    override fun getKey(): Char {
        return Util.KEY
    }

    override fun getType(methodReference: PsiElement): PhpType? {
        val project = methodReference.project
        if (
            !project.drupalExtendSettings.isEnabled
            || DumbService.getInstance(project).isDumb
            || !Patterns.METHOD_WITH_FIRST_STRING_PARAMETER.accepts(methodReference)
        ) {
            return null
        }

        methodReference as MethodReference

        if (methodReference.name != "getStorage") {
            return null
        }

        val parameterList = methodReference.childrenOfType<ParameterList>().first()

        val firstParameter = parameterList.getParameter(0) as StringLiteralExpression

        if (firstParameter.contents.isEmpty()) return null

        val signature = compressString(methodReference.signature).replace('|', SPLITER_KEY)
        return PhpType().add("#$key${signature}${Util.SPLIT_KEY}${firstParameter.contents}")
    }

    private fun compressString(str: String): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        GZIPOutputStream(byteArrayOutputStream).bufferedWriter(Charsets.UTF_8).use { it.write(str) }
        return Base64.getEncoder().encodeToString(byteArrayOutputStream.toByteArray())
    }

    private fun decompressString(compressedStr: String): String {
        val bytes = Base64.getDecoder().decode(compressedStr)
        return GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    override fun complete(expression: String?, project: Project?): PhpType? {
        if (project == null || expression == null || !expression.contains(Util.SPLIT_KEY)) return null

        val signature = expression.replace("#${key}", "")
        val parts = signature.split(Util.SPLIT_KEY)

        if (parts.size != 2) {
            return null
        }

        val (originalSignature, entityTypeId) = parts

        val entityType = fileBasedIndex.getValue(ContentEntityIndex.KEY, entityTypeId, project) ?: return null

        val phpIndex = PhpIndex.getInstance(project)
        val namedCollection = mutableListOf<PhpNamedElement>()
        for (partialSignature in decompressString(originalSignature.replace(SPLITER_KEY, '|')).split('|')) {
            namedCollection.addAll(phpIndex.getBySignature(partialSignature))
        }

        val methods = namedCollection.filterIsInstance<Method>()

        if (methods.isEmpty()) return null

        val entityTypeManagerInterface =
            phpIndex.getInterfacesByFQN(entityTypeManagerInterface).takeIf { it.isNotEmpty() }?.first() ?: return null

        val entityStorageClass = phpIndex.getClassesByFQN(entityType.storageHandler)
            .takeIf { it.isNotEmpty() }
            ?.first()
            ?: return null

        val type = PhpType()
        methods.forEach { method ->
            val clazz = method.containingClass ?: return@forEach
            if (clazz.fqn == entityTypeManagerInterface.fqn || clazz.isSuperInterfaceOf(entityTypeManagerInterface)) {
                entityStorageClass.implementsList.referenceElements.map {
                    it.fqn
                }.forEach(type::add)
            }
        }

        return type
    }

    override fun getBySignature(
        expression: String, visited: Set<String?>?, depth: Int, project: Project?
    ): Collection<PhpNamedElement>? {
        return null
    }

    object Util {
        const val SPLIT_KEY = '\u3333'
        const val KEY = '\u3334'
        const val SPLITER_KEY = '\u3336'
    }


}