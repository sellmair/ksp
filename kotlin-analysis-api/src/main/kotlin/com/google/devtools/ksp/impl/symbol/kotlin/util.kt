/*
 * Copyright 2022 Google LLC
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.google.devtools.ksp.impl.symbol.kotlin

import com.google.devtools.ksp.ExceptionMessage
import com.google.devtools.ksp.getDocString
import com.google.devtools.ksp.impl.KSPCoreEnvironment
import com.google.devtools.ksp.impl.ResolverAAImpl
import com.google.devtools.ksp.memoized
import com.google.devtools.ksp.symbol.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.KtStarTypeProjection
import org.jetbrains.kotlin.analysis.api.KtTypeArgumentWithVariance
import org.jetbrains.kotlin.analysis.api.KtTypeProjection
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.*
import org.jetbrains.kotlin.analysis.api.fir.evaluate.FirAnnotationValueConverter
import org.jetbrains.kotlin.analysis.api.fir.symbols.KtFirValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.lifetime.KtAlwaysAccessibleLifetimeToken
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtSymbolWithMembers
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.java.JavaTypeParameterStack
import org.jetbrains.kotlin.fir.java.toFirExpression
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.load.java.structure.JavaAnnotationArgument
import org.jetbrains.kotlin.load.java.structure.impl.JavaClassImpl
import org.jetbrains.kotlin.load.java.structure.impl.classFiles.BinaryClassSignatureParser
import org.jetbrains.kotlin.load.java.structure.impl.classFiles.BinaryJavaAnnotationVisitor
import org.jetbrains.kotlin.load.java.structure.impl.classFiles.ClassifierResolutionContext
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.utils.KotlinExceptionWithAttachments
import org.jetbrains.org.objectweb.asm.AnnotationVisitor
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes

internal val ktSymbolOriginToOrigin = mapOf(
    KtSymbolOrigin.JAVA to Origin.JAVA,
    KtSymbolOrigin.SOURCE to Origin.KOTLIN,
    KtSymbolOrigin.SAM_CONSTRUCTOR to Origin.SYNTHETIC,
    KtSymbolOrigin.SOURCE_MEMBER_GENERATED to Origin.SYNTHETIC,
    KtSymbolOrigin.DELEGATED to Origin.SYNTHETIC,
    KtSymbolOrigin.PROPERTY_BACKING_FIELD to Origin.KOTLIN,
    KtSymbolOrigin.JAVA_SYNTHETIC_PROPERTY to Origin.SYNTHETIC,
    KtSymbolOrigin.INTERSECTION_OVERRIDE to Origin.KOTLIN,
    // TODO: distinguish between kotlin library and java library.
    KtSymbolOrigin.LIBRARY to Origin.KOTLIN_LIB,
    KtSymbolOrigin.SUBSTITUTION_OVERRIDE to Origin.JAVA_LIB
)

internal fun mapAAOrigin(ktSymbol: KtSymbol): Origin {
    val symbolOrigin = ktSymbolOriginToOrigin[ktSymbol.origin]
        ?: throw IllegalStateException("unhandled origin ${ktSymbol.origin.name}")
    return if (symbolOrigin == Origin.JAVA && ktSymbol.psi?.containingFile?.fileType?.isBinary == true) {
        Origin.JAVA_LIB
    } else {
        symbolOrigin
    }
}

internal fun KtAnnotationApplicationWithArgumentsInfo.render(): String {
    return buildString {
        append("@")
        if (this@render.useSiteTarget != null) {
            append(this@render.useSiteTarget!!.renderName + ":")
        }
        append(this@render.classId!!.shortClassName.asString())
        this@render.arguments.forEach {
            append(it.expression.render())
        }
    }
}

internal fun KtAnnotationValue.render(): String {
    return when (this) {
        is KtAnnotationApplicationValue -> annotationValue.render()
        is KtArrayAnnotationValue -> values.joinToString(",", "{", "}") { it.render() }
        is KtConstantAnnotationValue -> constantValue.renderAsKotlinConstant()
        is KtEnumEntryAnnotationValue -> callableId.toString()
        is KtKClassAnnotationValue.KtErrorClassAnnotationValue -> "<Error class>"
        is KtKClassAnnotationValue.KtLocalKClassAnnotationValue -> "$ktClass::class"
        is KtKClassAnnotationValue.KtNonLocalKClassAnnotationValue -> "$classId::class"
        is KtUnsupportedAnnotationValue -> throw IllegalStateException("Unsupported annotation value: $this")
    }
}

internal fun KtType.render(inFunctionType: Boolean = false): String {
    return buildString {
        annotations.forEach {
            append("[${it.render()}] ")
        }
        append(
            when (this@render) {
                is KtNonErrorClassType -> buildString {
                    val symbol = this@render.classifierSymbol()
                    if (symbol is KtTypeAliasSymbol) {
                        if (!inFunctionType) {
                            append("[typealias ${symbol.name.asString()}]")
                        } else {
                            append(this@render.toAbbreviatedType().render(inFunctionType))
                        }
                    } else {
                        append(classSymbol.name?.asString())
                        if (typeArguments().isNotEmpty()) {
                            typeArguments().joinToString(separator = ", ", prefix = "<", postfix = ">") {
                                when (it) {
                                    is KtStarTypeProjection -> "*"
                                    is KtTypeArgumentWithVariance ->
                                        "${it.variance}" +
                                            (if (it.variance != Variance.INVARIANT) " " else "") +
                                            it.type.render(this@render is KtFunctionalType)
                                }
                            }.also { append(it) }
                        }
                    }
                }
                is KtClassErrorType, is KtTypeErrorType -> "<ERROR TYPE>"
                is KtCapturedType -> asStringForDebugging()
                is KtDefinitelyNotNullType -> original.render(inFunctionType) + " & Any"
                is KtDynamicType -> "<dynamic type>"
                is KtFlexibleType -> "(${lowerBound.render(inFunctionType)}..${upperBound.render(inFunctionType)})"
                is KtIntegerLiteralType -> "ILT: $value"
                is KtIntersectionType ->
                    this@render.conjuncts
                        .joinToString(separator = " & ", prefix = "(", postfix = ")") { it.render(inFunctionType) }
                is KtTypeParameterType -> asStringForDebugging()
            } + if (nullability == KtTypeNullability.NULLABLE) "?" else ""
        )
    }
}

internal fun KtType.toClassifierReference(parent: KSTypeReference?): KSReferenceElement? {
    return when (val ktType = this) {
        is KtFunctionalType -> KSCallableReferenceImpl.getCached(ktType, parent)
        is KtDynamicType -> KSDynamicReferenceImpl.getCached(parent!!)
        is KtUsualClassType -> KSClassifierReferenceImpl.getCached(ktType, ktType.qualifiers.size - 1, parent)
        is KtFlexibleType -> ktType.lowerBound.toClassifierReference(parent)
        is KtErrorType -> null
        is KtTypeParameterType -> KSClassifierParameterImpl.getCached(ktType, parent)
        is KtDefinitelyNotNullType -> KSDefNonNullReferenceImpl.getCached(ktType, parent)
        else -> throw IllegalStateException("Unexpected type element ${ktType.javaClass}, $ExceptionMessage")
    }
}

internal fun KSTypeArgument.toKtTypeProjection(): KtTypeProjection {
    val variance = when (this.variance) {
        com.google.devtools.ksp.symbol.Variance.INVARIANT -> Variance.INVARIANT
        com.google.devtools.ksp.symbol.Variance.COVARIANT -> Variance.OUT_VARIANCE
        com.google.devtools.ksp.symbol.Variance.CONTRAVARIANT -> Variance.IN_VARIANCE
        else -> null
    }
    val argType = (this.type?.resolve() as? KSTypeImpl)?.type
    // TODO: maybe make a singleton of alwaysAccessibleLifetimeToken?
    val alwaysAccessibleLifetimeToken = KtAlwaysAccessibleLifetimeToken(ResolverAAImpl.ktModule.project!!)
    return if (argType == null || variance == null) {
        KtStarTypeProjection(alwaysAccessibleLifetimeToken)
    } else {
        KtTypeArgumentWithVariance(argType, variance, alwaysAccessibleLifetimeToken)
    }
}

internal fun PsiElement?.toLocation(): Location {
    if (this == null) {
        return NonExistLocation
    }
    val file = this.containingFile
    val document = KSPCoreEnvironment.instance.psiDocumentManager.getDocument(file) ?: return NonExistLocation
    return FileLocation(file.virtualFile.path, document.getLineNumber(this.textOffset) + 1)
}

internal fun KtSymbol.toContainingFile(): KSFile? {
    return when (val psi = this.psi) {
        is KtElement -> analyze {
            KSFileImpl.getCached(psi.containingKtFile.getFileSymbol())
        }
        is PsiElement -> KSFileJavaImpl.getCached(psi.containingFile as PsiJavaFile)
        else -> null
    }
}

internal fun KtSymbol.toDocString(): String? = this.psi?.getDocString()

internal inline fun <R> analyze(crossinline action: KtAnalysisSession.() -> R): R {
    return analyze(ResolverAAImpl.ktModule, action)
}

internal fun KtSymbolWithMembers.declarations(): Sequence<KSDeclaration> {
    return analyze {
        this@declarations.let {
            it.getDeclaredMemberScope().getAllSymbols() + it.getStaticMemberScope().getAllSymbols()
        }.distinct().map { symbol ->
            when (symbol) {
                is KtNamedClassOrObjectSymbol -> KSClassDeclarationImpl.getCached(symbol)
                is KtFunctionLikeSymbol -> KSFunctionDeclarationImpl.getCached(symbol)
                is KtPropertySymbol -> KSPropertyDeclarationImpl.getCached(symbol)
                is KtEnumEntrySymbol -> KSClassDeclarationEnumEntryImpl.getCached(symbol)
                is KtJavaFieldSymbol -> KSPropertyDeclarationJavaImpl.getCached(symbol)
                else -> throw IllegalStateException()
            }
        }.memoized()
    }
}

internal fun KtSymbolWithMembers.getAllProperties(): Sequence<KSPropertyDeclaration> {
    return analyze {
        this@getAllProperties.getMemberScope().getCallableSymbols()
            .filter {
                it.isVisibleInClass(this@getAllProperties as KtClassOrObjectSymbol) ||
                    it.getContainingSymbol() == this@getAllProperties
            }
            .mapNotNull { callableSymbol ->
                when (callableSymbol) {
                    is KtPropertySymbol -> KSPropertyDeclarationImpl.getCached(callableSymbol)
                    is KtJavaFieldSymbol -> KSPropertyDeclarationJavaImpl.getCached(callableSymbol)
                    else -> null
                }
            }
    }
}

internal fun KtSymbolWithMembers.getAllFunctions(): Sequence<KSFunctionDeclaration> {
    return analyze {
        this@getAllFunctions.getMemberScope().let { it.getCallableSymbols() + it.getConstructors() }
            .filter {
                it.isVisibleInClass(this@getAllFunctions as KtClassOrObjectSymbol) ||
                    it.getContainingSymbol() == this@getAllFunctions
            }
            .mapNotNull { callableSymbol ->
                // TODO: replace with single safe cast if no more implementations of KSFunctionDeclaration is added.
                when (callableSymbol) {
                    is KtFunctionLikeSymbol -> KSFunctionDeclarationImpl.getCached(callableSymbol)
                    else -> null
                }
            }
    }
}

internal fun KtAnnotated.annotations(parent: KSNode? = null): Sequence<KSAnnotation> {
    return this.annotations.asSequence().map { KSAnnotationImpl.getCached(it, parent) }
}

internal fun KtSymbol.getContainingKSSymbol(): KSDeclaration? {
    return analyze {
        when (val containingSymbol = this@getContainingKSSymbol.getContainingSymbol()) {
            is KtNamedClassOrObjectSymbol -> KSClassDeclarationImpl.getCached(containingSymbol)
            is KtFunctionLikeSymbol -> KSFunctionDeclarationImpl.getCached(containingSymbol)
            is KtPropertySymbol -> KSPropertyDeclarationImpl.getCached(containingSymbol)
            else -> null
        }
    }
}

internal fun KtSymbol.toKSDeclaration(): KSDeclaration? = this.toKSNode() as? KSDeclaration

internal fun KtSymbol.toKSNode(): KSNode {
    return when (this) {
        is KtPropertySymbol -> KSPropertyDeclarationImpl.getCached(this)
        is KtNamedClassOrObjectSymbol -> KSClassDeclarationImpl.getCached(this)
        is KtFunctionLikeSymbol -> KSFunctionDeclarationImpl.getCached(this)
        is KtTypeAliasSymbol -> KSTypeAliasImpl.getCached(this)
        is KtJavaFieldSymbol -> KSPropertyDeclarationJavaImpl.getCached(this)
        is KtFileSymbol -> KSFileImpl.getCached(this)
        is KtEnumEntrySymbol -> KSClassDeclarationEnumEntryImpl.getCached(this)
        is KtTypeParameterSymbol -> KSTypeParameterImpl.getCached(this)
        is KtLocalVariableSymbol -> KSPropertyDeclarationLocalVariableImpl.getCached(this)
        else -> throw IllegalStateException("Unexpected class for KtSymbol: ${this.javaClass}")
    }
}

internal fun ClassId.toKtClassSymbol(): KtClassOrObjectSymbol? {
    return analyze {
        if (this@toKtClassSymbol.isLocal) {
            this@toKtClassSymbol.outerClassId?.toKtClassSymbol()?.getDeclaredMemberScope()?.getClassifierSymbols {
                it.asString() == this@toKtClassSymbol.shortClassName.asString()
            }?.singleOrNull() as? KtClassOrObjectSymbol
        } else {
            getClassOrObjectSymbolByClassId(this@toKtClassSymbol)
        }
    }
}

internal fun KtType.classifierSymbol(): KtClassifierSymbol? {
    return try {
        when (this) {
            is KtTypeParameterType -> this.symbol
            // TODO: upstream is not exposing enough information for captured types.
            is KtCapturedType -> TODO("fix in upstream")
            is KtClassErrorType, is KtTypeErrorType -> null
            is KtFunctionalType -> classSymbol
            is KtUsualClassType -> classSymbol
            is KtDefinitelyNotNullType -> original.classifierSymbol()
            is KtDynamicType -> null
            // flexible types have 2 bounds, using lower bound for a safer approximation.
            // TODO: find a case where lower bound is not appropriate.
            is KtFlexibleType -> lowerBound.classifierSymbol()
            is KtIntegerLiteralType -> null
            // TODO: KSP does not support intersection type.
            is KtIntersectionType -> null
        }
    } catch (e: KotlinExceptionWithAttachments) {
        // The implementation for getting symbols from a type throws an excpetion
        // when it can't find the corresponding class symbol fot the given class ID.
        null
    }
}

internal fun KtType.typeArguments(): List<KtTypeProjection> {
    return (this as? KtNonErrorClassType)?.qualifiers?.reversed()?.flatMap { it.typeArguments } ?: emptyList()
}

internal fun KSAnnotated.findAnnotationFromUseSiteTarget(): Sequence<KSAnnotation> {
    return when (this) {
        is KSPropertyGetter -> (this.receiver as? AbstractKSDeclarationImpl)?.let {
            it.originalAnnotations.asSequence().filter { it.useSiteTarget == AnnotationUseSiteTarget.GET }
        }
        is KSPropertySetter -> (this.receiver as? AbstractKSDeclarationImpl)?.let {
            it.originalAnnotations.asSequence().filter { it.useSiteTarget == AnnotationUseSiteTarget.SET }
        }
        is KSValueParameter -> {
            var parent = this.parent
            // TODO: eliminate annotationsFromParents to make this fully sequence.
            val annotationsFromParents = mutableListOf<KSAnnotation>()
            (parent as? KSPropertyAccessorImpl)?.let {
                annotationsFromParents.addAll(
                    it.originalAnnotations.asSequence()
                        .filter { it.useSiteTarget == AnnotationUseSiteTarget.SETPARAM }
                )
                parent = (parent as KSPropertyAccessorImpl).receiver
            }
            (parent as? KSPropertyDeclarationImpl)?.let {
                annotationsFromParents.addAll(
                    it.originalAnnotations.asSequence()
                        .filter { it.useSiteTarget == AnnotationUseSiteTarget.SETPARAM }
                )
            }
            annotationsFromParents.asSequence()
        }
        else -> emptySequence()
    } ?: emptySequence()
}

internal fun org.jetbrains.kotlin.descriptors.Visibility.toModifier(): Modifier {
    return when (this) {
        Visibilities.Public -> Modifier.PUBLIC
        Visibilities.Private -> Modifier.PRIVATE
        Visibilities.Internal -> Modifier.INTERNAL
        Visibilities.Protected -> Modifier.PROTECTED
        else -> Modifier.PUBLIC
    }
}

internal fun Modality.toModifier(): Modifier {
    return when (this) {
        Modality.FINAL -> Modifier.FINAL
        Modality.ABSTRACT -> Modifier.ABSTRACT
        Modality.OPEN -> Modifier.OPEN
        Modality.SEALED -> Modifier.SEALED
    }
}

internal inline fun <reified T : KSNode> KSNode.findParentOfType(): KSNode? {
    var result = parent
    while (!(result == null || result is T)) {
        result = result.parent
    }
    return result
}

@OptIn(SymbolInternals::class)
internal fun KtValueParameterSymbol.getDefaultValue(): KtAnnotationValue? {
    return this.psi.let {
        when (it) {
            is KtParameter -> analyze {
                it.defaultValue?.evaluateAsAnnotationValue()
            }
            null -> {
                val fileManager = ResolverAAImpl.instance.javaFileManager
                val parentClass = this.getContainingKSSymbol()!!.findParentOfType<KSClassDeclaration>()
                val classId = (parentClass as KSClassDeclarationImpl).ktClassOrObjectSymbol.classIdIfNonLocal!!
                val file = analyze {
                    (fileManager.findClass(classId, analysisScope) as JavaClassImpl).virtualFile!!.contentsToByteArray()
                }
                var defaultValue: JavaAnnotationArgument? = null
                ClassReader(file).accept(
                    object : ClassVisitor(Opcodes.API_VERSION) {
                        override fun visitMethod(
                            access: Int,
                            name: String?,
                            desc: String?,
                            signature: String?,
                            exceptions: Array<out String>?
                        ): MethodVisitor {
                            return if (name == this@getDefaultValue.name.asString()) {
                                object : MethodVisitor(Opcodes.API_VERSION) {
                                    override fun visitAnnotationDefault(): AnnotationVisitor =
                                        BinaryJavaAnnotationVisitor(
                                            ClassifierResolutionContext { null },
                                            BinaryClassSignatureParser()
                                        ) {
                                            defaultValue = it
                                        }
                                }
                            } else {
                                object : MethodVisitor(Opcodes.API_VERSION) {}
                            }
                        }
                    },
                    ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
                )
                (this as? KtFirValueParameterSymbol)?.let {
                    val firSession = it.firSymbol.fir.moduleData.session
                    val expectedTypeRef = it.firSymbol.fir.returnTypeRef
                    val expression = defaultValue
                        ?.toFirExpression(firSession, JavaTypeParameterStack.EMPTY, expectedTypeRef)
                    expression?.let {
                        FirAnnotationValueConverter.toConstantValue(expression, firSession)
                    }
                }
            }
            else -> throw IllegalStateException("Unhandled default value type ${it.javaClass}")
        }
    }
}
