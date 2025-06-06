package io.joern.kotlin2cpg.ast

import io.joern.kotlin2cpg.Constants
import io.joern.kotlin2cpg.types.TypeConstants
import io.joern.x2cpg.Ast
import io.joern.x2cpg.Defines
import io.joern.x2cpg.ValidationMode
import io.shiftleft.codepropertygraph.generated.DispatchTypes
import io.shiftleft.codepropertygraph.generated.Operators
import io.shiftleft.codepropertygraph.generated.nodes.NewAnnotation
import io.shiftleft.codepropertygraph.generated.nodes.NewAnnotationLiteral
import io.shiftleft.codepropertygraph.generated.nodes.NewImport
import io.shiftleft.codepropertygraph.generated.nodes.NewLocal
import io.shiftleft.codepropertygraph.generated.nodes.NewMember
import io.shiftleft.codepropertygraph.generated.nodes.NewMethodParameterIn
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.types.structure.NamespaceTraversal
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.ValueDescriptor
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.types.error.ErrorType

import scala.annotation.unused
import scala.jdk.CollectionConverters.*
import scala.util.Try

trait AstForPrimitivesCreator(implicit withSchemaValidation: ValidationMode) {
  this: AstCreator =>

  def astForLiteral(
    expr: KtConstantExpression,
    argIdx: Option[Int],
    argName: Option[String],
    annotations: Seq[KtAnnotationEntry] = Seq()
  ): Ast = {
    val typeFullName   = registerType(exprTypeFullName(expr).getOrElse(TypeConstants.Any))
    val node           = literalNode(expr, expr.getText, typeFullName)
    val annotationAsts = annotations.map(astForAnnotationEntry)
    Ast(withArgumentName(withArgumentIndex(node, argIdx), argName)).withChildren(annotationAsts)
  }

  def astForStringTemplate(
    expr: KtStringTemplateExpression,
    argIdx: Option[Int],
    argName: Option[String],
    annotations: Seq[KtAnnotationEntry] = Seq()
  ): Ast = {
    val typeFullName = registerType(exprTypeFullName(expr).getOrElse(TypeConstants.Any))
    val outAst =
      if (expr.hasInterpolation) {
        val args = expr.getEntries.filter(_.getExpression != null).zipWithIndex.map { case (entry, idx) =>
          val entryTypeFullName = registerType(exprTypeFullName(entry.getExpression).getOrElse(TypeConstants.Any))
          val valueCallNode = operatorCallNode(
            entry.getExpression,
            entry.getExpression.getText,
            Operators.formattedValue,
            Option(entryTypeFullName)
          )
          val valueArgs = astsForExpression(entry.getExpression, Some(idx + 1))
          callAst(valueCallNode, valueArgs.toList)
        }
        val node =
          operatorCallNode(expr, expr.getText, Operators.formatString, Option(typeFullName))
        callAst(withArgumentName(withArgumentIndex(node, argIdx), argName), args.toIndexedSeq.toList)
      } else {
        val node = literalNode(expr, expr.getText, typeFullName)
        Ast(withArgumentName(withArgumentIndex(node, argIdx), argName))
      }
    outAst.withChildren(annotations.map(astForAnnotationEntry))
  }

  def astForNameReference(
    expr: KtNameReferenceExpression,
    argIdx: Option[Int],
    argName: Option[String],
    annotations: Seq[KtAnnotationEntry] = Seq()
  ): Ast = {
    val isReferencingMember = scope.lookupVariable(expr.getIdentifier.getText) match {
      case Some(_: NewMember) => true
      case _                  => false
    }
    val isUsedAsImplicitThis = typeInfoProvider.usedAsImplicitThis(expr)
    val outAst =
      if (typeInfoProvider.isReferenceToClass(expr)) astForNameReferenceToType(expr, argIdx)
      else if (isReferencingMember || isUsedAsImplicitThis) astForNameReferenceToMember(expr, argIdx)
      else astForNonSpecialNameReference(expr, argIdx, argName)
    outAst.withChildren(annotations.map(astForAnnotationEntry))
  }

  private def astForNameReferenceToType(expr: KtNameReferenceExpression, argIdx: Option[Int]): Ast = {
    val declDesc =
      bindingUtils.getDeclDesc(expr).collect { case classifierDesc: ClassifierDescriptor => classifierDesc }
    val typeFullName = registerType(declDesc.flatMap(nameRenderer.descFullName).getOrElse(TypeConstants.Any))
    val referencesCompanionObject = typeInfoProvider.isRefToCompanionObject(expr)
    if (referencesCompanionObject) {
      val argAsts = List(
        // TODO: change this to a TYPE_REF node as soon as the closed source data-flow engine supports it
        identifierNode(expr, expr.getIdentifier.getText, expr.getIdentifier.getText, typeFullName),
        fieldIdentifierNode(expr, Constants.CompanionObjectMemberName, Constants.CompanionObjectMemberName)
      ).map(Ast(_))
      val node = operatorCallNode(expr, expr.getText, Operators.fieldAccess, Option(typeFullName))
      callAst(withArgumentIndex(node, argIdx), argAsts)
    } else {
      val node = typeRefNode(expr.getIdentifier, expr.getIdentifier.getText, typeFullName)
      Ast(withArgumentIndex(node, argIdx))
    }
  }

  private def astForNameReferenceToMember(expr: KtNameReferenceExpression, argIdx: Option[Int]): Ast = {
    val declDesc = bindingUtils.getDeclDesc(expr).collect { case propDesc: PropertyDescriptor => propDesc }
    val typeFullName = declDesc
      .flatMap(desc => nameRenderer.typeFullName(desc.getType))
      .getOrElse(TypeConstants.Any)
    registerType(typeFullName)

    val baseTypeFullName = declDesc
      .flatMap(desc => nameRenderer.typeFullName(desc.getDispatchReceiverParameter.getType))
      .getOrElse(TypeConstants.Any)
    registerType(baseTypeFullName)

    val thisNode             = identifierNode(expr, Constants.ThisName, Constants.ThisName, baseTypeFullName)
    val thisAst              = astWithRefEdgeMaybe(Constants.ThisName, thisNode)
    val _fieldIdentifierNode = fieldIdentifierNode(expr, expr.getReferencedName, expr.getReferencedName)
    val node = operatorCallNode(
      expr,
      s"${Constants.ThisName}.${expr.getReferencedName}",
      Operators.fieldAccess,
      Option(typeFullName)
    )
    callAst(withArgumentIndex(node, argIdx), List(thisAst, Ast(_fieldIdentifierNode)))
  }

  private def astForNonSpecialNameReference(
    expr: KtNameReferenceExpression,
    argIdx: Option[Int],
    argName: Option[String] = None
  ): Ast = {
    val declDesc = bindingUtils.getDeclDesc(expr).collect { case valueDesc: ValueDescriptor => valueDesc }
    val typeFullName = declDesc
      .flatMap(desc => nameRenderer.typeFullName(desc.getType))
      .orElse {
        val typeFromScopeMaybe = scope.lookupVariable(expr.getIdentifier.getText) match {
          case Some(n: NewLocal)             => Some(n.typeFullName)
          case Some(n: NewMethodParameterIn) => Some(n.typeFullName)
          case _                             => None
        }
        typeFromScopeMaybe
      }
      .getOrElse(TypeConstants.Any)

    val name = expr.getIdentifier.getText
    val node =
      withArgumentName(withArgumentIndex(identifierNode(expr, name, name, typeFullName), argIdx), argName)
    astWithRefEdgeMaybe(name, node)
  }

  def astForSuperExpression(
    expr: KtSuperExpression,
    argIdx: Option[Int],
    argName: Option[String],
    annotations: Seq[KtAnnotationEntry] = Seq()
  ): Ast = {
    val typeFullName = registerType(exprTypeFullName(expr).getOrElse(TypeConstants.Any))
    val node = withArgumentName(
      withArgumentIndex(identifierNode(expr, expr.getText, expr.getText, typeFullName), argIdx),
      argName
    )
    astWithRefEdgeMaybe(expr.getText, node)
      .withChildren(annotations.map(astForAnnotationEntry))
  }

  def astForThisExpression(
    expr: KtThisExpression,
    argIdx: Option[Int],
    argName: Option[String],
    annotations: Seq[KtAnnotationEntry] = Seq()
  ): Ast = {
    val typeFullName = registerType(exprTypeFullName(expr).getOrElse(TypeConstants.Any))
    val node = withArgumentName(
      withArgumentIndex(identifierNode(expr, expr.getText, expr.getText, typeFullName), argIdx),
      argName
    )
    astWithRefEdgeMaybe(expr.getText, node)
      .withChildren(annotations.map(astForAnnotationEntry))
  }

  def astForClassLiteral(
    expr: KtClassLiteralExpression,
    argIdx: Option[Int],
    argName: Option[String],
    annotations: Seq[KtAnnotationEntry] = Seq()
  ): Ast = {
    val typeFullName = registerType(exprTypeFullName(expr).getOrElse(TypeConstants.JavaLangObject))
    val fullName     = "<operator>.class"
    val signature    = s"$typeFullName()"
    val node = callNode(
      expr,
      expr.getText,
      fullName,
      fullName,
      DispatchTypes.STATIC_DISPATCH,
      Some(signature),
      Some(typeFullName)
    )
    Ast(withArgumentName(withArgumentIndex(node, argIdx), argName))
      .withChildren(annotations.map(astForAnnotationEntry))
  }

  def astForImportDirective(directive: KtImportDirective): Ast = {
    val importedAs = Try(directive.getImportedName.getIdentifier).toOption
    val isWildcard = importedAs.contains(Constants.WildcardImportName) || directive.getImportedName == null
    val node =
      NewImport()
        .isWildcard(isWildcard)
        .isExplicit(true)
        .importedAs(importedAs)
        .importedEntity(directive.getImportPath.getPathStr)
        .code(s"${Constants.ImportKeyword} ${directive.getImportPath.getPathStr}")
        .lineNumber(line(directive))
        .columnNumber(column(directive))
    Ast(node)
  }

  @unused
  def astForPackageDeclaration(originNode: PsiElement, packageName: String): Ast = {
    val node =
      if (packageName == Constants.Root)
        namespaceBlockNode(
          originNode,
          NamespaceTraversal.globalNamespaceName,
          NamespaceTraversal.globalNamespaceName,
          relativizedPath
        )
      else {
        val name = packageName.split("\\.").lastOption.getOrElse("")
        namespaceBlockNode(originNode, name, packageName, relativizedPath)
      }
    Ast(node)
  }

  def astForAnnotationEntry(entry: KtAnnotationEntry): Ast = {
    val typeFullName = nameRenderer
      .typeFullName(bindingUtils.getAnnotationDesc(entry).getType)
      .orElse(fullNameByImportPath(entry.getTypeReference, entry.getContainingKtFile))
      .getOrElse(s"${Defines.UnresolvedNamespace}.${entry.getShortName.toString}")
    registerType(typeFullName)

    val node =
      NewAnnotation()
        .code(entry.getText)
        .name(entry.getShortName.toString)
        .lineNumber(line(entry))
        .columnNumber(column(entry))
        .fullName(typeFullName)

    val children =
      entry.getValueArguments.asScala.flatMap { varg =>
        varg.getArgumentExpression match {
          case ste: KtStringTemplateExpression if ste.getEntries.length == 1 =>
            val node = NewAnnotationLiteral().code(ste.getText)
            Some(Ast(node))
          case ce: KtConstantExpression =>
            val node = NewAnnotationLiteral().code(ce.getText)
            Some(Ast(node))
          case _ => None
        }
      }.toList
    annotationAst(node, children)
  }

  def astForTypeAlias(typeAlias: KtTypeAlias): Ast = {
    val typeAliasDesc = bindingUtils.getTypeAliasDesc(typeAlias)
    val aliasedType = typeAliasDesc.getExpandedType match {
      case _: ErrorType =>
        None
      case nonErrorType =>
        Some(nonErrorType)
    }

    val node = typeDeclNode(
      typeAlias,
      typeAlias.getName,
      registerType(nameRenderer.descFullName(typeAliasDesc).getOrElse(TypeConstants.Any)),
      relativizedPath,
      Seq(),
      Option(registerType(aliasedType.flatMap(nameRenderer.typeFullName).getOrElse(TypeConstants.Any)))
    )
    Ast(node)
  }

  def astForTypeReference(expr: KtTypeReference, argIdx: Option[Int], argName: Option[String]): Ast = {
    val typeFullName = registerType(
      bindingUtils.getTypeRefType(expr).flatMap(nameRenderer.typeFullName).getOrElse(TypeConstants.Any)
    )
    val node = typeRefNode(expr, expr.getText, typeFullName)
    Ast(withArgumentName(withArgumentIndex(node, argIdx), argName))
  }
}
