package io.joern.kotlin2cpg.ast

import io.joern.kotlin2cpg.Constants
import io.joern.kotlin2cpg.types.TypeConstants
import io.joern.x2cpg.Ast
import io.joern.x2cpg.ValidationMode
import io.shiftleft.codepropertygraph.generated.ControlStructureTypes
import io.shiftleft.codepropertygraph.generated.DispatchTypes
import io.shiftleft.codepropertygraph.generated.Operators
import io.shiftleft.codepropertygraph.generated.nodes.NewLocal
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtContainerNodeForControlStructureBody
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiUtil
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtWhenConditionWithExpression
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression

import scala.jdk.CollectionConverters.*

trait AstForStatementsCreator(implicit withSchemaValidation: ValidationMode) {
  this: AstCreator =>

  def astForFor(expr: KtForExpression, annotations: Seq[KtAnnotationEntry] = Seq()): Ast = {
    val outAst =
      if (expr.getDestructuringDeclaration != null) astForForWithDestructuringLHS(expr)
      else astForForWithSimpleVarLHS(expr)
    outAst.withChildren(annotations.map(astForAnnotationEntry))
  }

  // e.g. lowering:
  // for `for ((d1, d2) in l) { <statements> }`
  // BLOCK
  //     LOCAL iterator
  //     loweringOf{iterator = l.iterator()}
  //     CONTROL_STRUCTURE (while)
  //         --AST[order.1]--> loweringOf{iterator.hasNext()}
  //         --AST[order.2]--> BLOCK
  //                            |-> LOCAL d1
  //                            |-> LOCAL d2
  //                            |-> LOCAL tmp
  //                            |-> loweringOf{tmp = iterator.next()}
  //                            |-> loweringOf{d1 = tmp.component1()}
  //                            |-> loweringOf{d2 = tmp.component2()}
  //                            |-> <statements>
  //
  private def astForForWithDestructuringLHS(expr: KtForExpression): Ast = {
    val loopRangeText         = expr.getLoopRange.getText
    val iteratorName          = s"${Constants.IteratorPrefix}${iteratorKeyPool.next}"
    val localForIterator      = localNode(expr, iteratorName, iteratorName, TypeConstants.Any)
    val iteratorAssignmentLhs = identifierNode(expr, iteratorName, iteratorName, TypeConstants.Any)
    val iteratorLocalAst      = Ast(localForIterator).withRefEdge(iteratorAssignmentLhs, localForIterator)

    // TODO: maybe use a different method here, one which does not translate `kotlin.collections.List` to `java.util.List`
    val loopRangeExprTypeFullName = registerType(exprTypeFullName(expr.getLoopRange).getOrElse(TypeConstants.Any))
    val iteratorAssignmentRhsIdentifier = identifierNode(expr, loopRangeText, loopRangeText, loopRangeExprTypeFullName)
      .argumentIndex(0)
    val iteratorAssignmentRhs = callNode(
      expr.getLoopRange,
      s"$loopRangeText.${Constants.GetIteratorMethodName}()",
      Constants.GetIteratorMethodName,
      s"$loopRangeExprTypeFullName.${Constants.GetIteratorMethodName}:${Constants.JavaUtilIterator}()",
      DispatchTypes.DYNAMIC_DISPATCH,
      Some(s"${Constants.JavaUtilIterator}()"),
      Some(Constants.JavaUtilIterator)
    )

    val iteratorAssignmentRhsAst =
      callAst(iteratorAssignmentRhs, Seq(), Option(Ast(iteratorAssignmentRhsIdentifier)))

    val iteratorAssignment =
      operatorCallNode(expr, s"$iteratorName = ${iteratorAssignmentRhs.code}", Operators.assignment, None)
    val iteratorAssignmentAst = callAst(iteratorAssignment, List(Ast(iteratorAssignmentLhs), iteratorAssignmentRhsAst))

    val controlStructure = controlStructureNode(expr, ControlStructureTypes.WHILE, expr.getText)
    val conditionIdentifier =
      identifierNode(expr, loopRangeText, loopRangeText, loopRangeExprTypeFullName).argumentIndex(0)

    val hasNextFullName =
      s"${Constants.CollectionsIteratorName}.${Constants.HasNextIteratorMethodName}:${TypeConstants.JavaLangBoolean}()"
    val controlStructureCondition = callNode(
      expr.getLoopRange,
      s"$iteratorName.${Constants.HasNextIteratorMethodName}()",
      Constants.HasNextIteratorMethodName,
      hasNextFullName,
      DispatchTypes.DYNAMIC_DISPATCH,
      Some(s"${TypeConstants.JavaLangBoolean}()"),
      Some(TypeConstants.JavaLangBoolean)
    ).argumentIndex(0)
    val controlStructureConditionAst =
      callAst(controlStructureCondition, List(), Option(Ast(conditionIdentifier)))

    val destructuringDeclEntries   = expr.getDestructuringDeclaration.getEntries
    val localsForDestructuringVars = localsForDestructuringEntries(expr.getDestructuringDeclaration)

    val tmpName     = s"${Constants.TmpLocalPrefix}${tmpKeyPool.next}"
    val localForTmp = localNode(expr, tmpName, tmpName, TypeConstants.Any)
    scope.addToScope(localForTmp.name, localForTmp)
    val localForTmpAst = Ast(localForTmp)

    val tmpIdentifier             = identifierNode(expr, tmpName, tmpName, TypeConstants.Any)
    val tmpIdentifierAst          = Ast(tmpIdentifier).withRefEdge(tmpIdentifier, localForTmp)
    val iteratorNextIdentifier    = identifierNode(expr, iteratorName, iteratorName, TypeConstants.Any).argumentIndex(0)
    val iteratorNextIdentifierAst = Ast(iteratorNextIdentifier).withRefEdge(iteratorNextIdentifier, localForIterator)

    val iteratorNextCall = callNode(
      expr.getLoopRange,
      s"${iteratorNextIdentifier.code}.${Constants.NextIteratorMethodName}()",
      Constants.NextIteratorMethodName,
      s"${Constants.CollectionsIteratorName}.${Constants.NextIteratorMethodName}:${TypeConstants.JavaLangObject}()",
      DispatchTypes.DYNAMIC_DISPATCH,
      Some(s"${TypeConstants.JavaLangObject}()"),
      Some(TypeConstants.JavaLangObject)
    )

    val iteratorNextCallAst =
      callAst(iteratorNextCall, Seq(), Option(iteratorNextIdentifierAst))
    val tmpParameterNextAssignment =
      operatorCallNode(expr, s"$tmpName = ${iteratorNextCall.code}", Operators.assignment, None)
    val tmpParameterNextAssignmentAst = callAst(tmpParameterNextAssignment, List(tmpIdentifierAst, iteratorNextCallAst))

    val assignmentsForEntries =
      destructuringDeclEntries.asScala.filterNot(_.getText == Constants.UnusedDestructuringEntryText).zipWithIndex.map {
        case (entry, idx) =>
          val rhsBaseAst =
            astWithRefEdgeMaybe(
              localForTmp.name,
              identifierNode(entry, localForTmp.name, localForTmp.name, localForTmp.typeFullName)
                .argumentIndex(0)
            )
          assignmentAstForDestructuringEntry(entry, rhsBaseAst, idx + 1)
      }

    val stmtAsts             = astsForExpression(expr.getBody, None)
    val controlStructureBody = blockNode(expr.getBody, "", "")
    val controlStructureBodyAst = blockAst(
      controlStructureBody,
      localsForDestructuringVars.toList ++
        List(localForTmpAst, tmpParameterNextAssignmentAst) ++
        assignmentsForEntries ++
        stmtAsts
    )

    val _controlStructureAst =
      controlStructureAst(controlStructure, Some(controlStructureConditionAst), Seq(controlStructureBodyAst))
    blockAst(
      blockNode(expr, Constants.CodeForLoweredForBlock, ""),
      List(iteratorLocalAst, iteratorAssignmentAst, _controlStructureAst)
    )
  }

  // e.g. lowering:
  // for `for (one in l) { <statements> }`
  // BLOCK
  //     LOCAL iterator
  //     loweringOf{iterator = l.iterator()}
  //     CONTROL_STRUCTURE (while)
  //         --AST[order.1]--> loweringOf{iterator.hasNext()}
  //         --AST[order.2]--> BLOCK
  //                            |-> LOCAL one
  //                            |-> loweringOf{one = iterator.next()}
  //                            |-> <statements>
  //
  private def astForForWithSimpleVarLHS(expr: KtForExpression): Ast = {
    val loopRangeText         = expr.getLoopRange.getText
    val iteratorName          = s"${Constants.IteratorPrefix}${iteratorKeyPool.next}"
    val iteratorLocal         = localNode(expr, iteratorName, iteratorName, TypeConstants.Any)
    val iteratorAssignmentLhs = identifierNode(expr, iteratorName, iteratorName, TypeConstants.Any)
    val iteratorLocalAst      = Ast(iteratorLocal).withRefEdge(iteratorAssignmentLhs, iteratorLocal)

    val loopRangeExprTypeFullName = registerType(exprTypeFullName(expr.getLoopRange).getOrElse(TypeConstants.Any))

    val iteratorAssignmentRhsIdentifier = identifierNode(expr, loopRangeText, loopRangeText, loopRangeExprTypeFullName)
      .argumentIndex(0)
    val iteratorAssignmentRhs = callNode(
      expr.getLoopRange,
      s"$loopRangeText.${Constants.GetIteratorMethodName}()",
      Constants.GetIteratorMethodName,
      s"$loopRangeExprTypeFullName.${Constants.GetIteratorMethodName}:${Constants.JavaUtilIterator}()",
      DispatchTypes.DYNAMIC_DISPATCH,
      Some(s"${Constants.JavaUtilIterator}()"),
      Some(Constants.JavaUtilIterator)
    )

    val iteratorAssignmentRhsAst =
      callAst(iteratorAssignmentRhs, Seq(), Option(Ast(iteratorAssignmentRhsIdentifier)))
    val iteratorAssignment =
      operatorCallNode(expr, s"$iteratorName = ${iteratorAssignmentRhs.code}", Operators.assignment, None)

    val iteratorAssignmentAst = callAst(iteratorAssignment, List(Ast(iteratorAssignmentLhs), iteratorAssignmentRhsAst))
    val controlStructure      = controlStructureNode(expr, ControlStructureTypes.WHILE, expr.getText)

    val conditionIdentifier =
      identifierNode(expr, loopRangeText, loopRangeText, loopRangeExprTypeFullName).argumentIndex(0)

    val hasNextFullName =
      s"${Constants.CollectionsIteratorName}.${Constants.HasNextIteratorMethodName}:${TypeConstants.JavaLangBoolean}()"
    val controlStructureCondition = callNode(
      expr.getLoopRange,
      s"$iteratorName.${Constants.HasNextIteratorMethodName}()",
      Constants.HasNextIteratorMethodName,
      hasNextFullName,
      DispatchTypes.DYNAMIC_DISPATCH,
      Some(s"${TypeConstants.JavaLangBoolean}()"),
      Some(TypeConstants.JavaLangBoolean)
    ).argumentIndex(0)
    val controlStructureConditionAst =
      callAst(controlStructureCondition, List(), Option(Ast(conditionIdentifier)))

    val loopParameterTypeFullName = registerType(
      bindingUtils
        .getVariableDesc(expr.getLoopParameter)
        .flatMap(desc => nameRenderer.typeFullName(desc.getType))
        .getOrElse(TypeConstants.Any)
    )
    val loopParameterName  = expr.getLoopParameter.getText
    val loopParameterLocal = localNode(expr, loopParameterName, loopParameterName, loopParameterTypeFullName)
    scope.addToScope(loopParameterName, loopParameterLocal)

    val loopParameterIdentifier = identifierNode(expr, loopParameterName, loopParameterName, TypeConstants.Any)
    val loopParameterAst        = Ast(loopParameterLocal).withRefEdge(loopParameterIdentifier, loopParameterLocal)

    val iteratorNextIdentifier    = identifierNode(expr, iteratorName, iteratorName, TypeConstants.Any).argumentIndex(0)
    val iteratorNextIdentifierAst = Ast(iteratorNextIdentifier).withRefEdge(iteratorNextIdentifier, iteratorLocal)

    val iteratorNextCall = callNode(
      expr.getLoopParameter,
      s"$iteratorName.${Constants.NextIteratorMethodName}()",
      Constants.NextIteratorMethodName,
      s"${Constants.CollectionsIteratorName}.${Constants.NextIteratorMethodName}:${TypeConstants.JavaLangObject}()",
      DispatchTypes.DYNAMIC_DISPATCH,
      Some(s"${TypeConstants.JavaLangObject}()"),
      Some(TypeConstants.JavaLangObject)
    )
    val iteratorNextCallAst =
      callAst(iteratorNextCall, Seq(), Option(iteratorNextIdentifierAst))
    val loopParameterNextAssignment =
      operatorCallNode(expr, s"$loopParameterName = ${iteratorNextCall.code}", Operators.assignment, None)
    val loopParameterNextAssignmentAst =
      callAst(loopParameterNextAssignment, List(Ast(loopParameterIdentifier), iteratorNextCallAst))

    val stmtAsts             = astsForExpression(expr.getBody, Some(3))
    val controlStructureBody = blockNode(expr.getBody, "", "")
    val controlStructureBodyAst =
      blockAst(controlStructureBody, List(loopParameterAst, loopParameterNextAssignmentAst) ++ stmtAsts)

    val _controlStructureAst =
      controlStructureAst(controlStructure, Some(controlStructureConditionAst), Seq(controlStructureBodyAst))
    blockAst(
      blockNode(expr, Constants.CodeForLoweredForBlock, ""),
      List(iteratorLocalAst, iteratorAssignmentAst, _controlStructureAst)
    )
  }

  def astForIf(
    expr: KtIfExpression,
    argIdx: Option[Int],
    argNameMaybe: Option[String],
    annotations: Seq[KtAnnotationEntry] = Seq()
  ): Ast = {
    val isChildOfControlStructureBody = expr.getParent.isInstanceOf[KtContainerNodeForControlStructureBody]
    if (KtPsiUtil.isStatement(expr) && !isChildOfControlStructureBody) astForIfAsControlStructure(expr, annotations)
    else astForIfAsExpression(expr, argIdx, argNameMaybe, annotations)
  }

  private def astForIfAsControlStructure(expr: KtIfExpression, annotations: Seq[KtAnnotationEntry] = Seq()): Ast = {
    val conditionAst = astsForExpression(expr.getCondition, None).headOption
    val thenAsts     = astsForExpression(expr.getThen, None)
    val elseAsts     = Option(expr.getElse).toSeq.flatMap(astsForExpression(_, None))

    val node = controlStructureNode(expr, ControlStructureTypes.IF, expr.getText)
    controlStructureAst(node, conditionAst, List(thenAsts ++ elseAsts).flatten)
      .withChildren(annotations.map(astForAnnotationEntry))
  }

  def astForIfAsExpression(
    expr: KtIfExpression,
    argIdx: Option[Int],
    argNameMaybe: Option[String],
    annotations: Seq[KtAnnotationEntry] = Seq()
  ): Ast = {
    val conditionAsts = astsForExpression(expr.getCondition, None)
    val thenAsts      = astsForExpression(expr.getThen, None)
    val elseAsts      = Option(expr.getElse).toSeq.flatMap(astsForExpression(_, None))

    val allAsts = (conditionAsts ++ thenAsts ++ elseAsts).toList
    if (allAsts.nonEmpty) {
      val returnTypeFullName = registerType(exprTypeFullName(expr).getOrElse(TypeConstants.Any))
      val node =
        operatorCallNode(expr, expr.getText, Operators.conditional, Option(returnTypeFullName))
      callAst(withArgumentIndex(node, argIdx).argumentName(argNameMaybe), allAsts)
        .withChildren(annotations.map(astForAnnotationEntry))
    } else {
      logger.warn("Could not create ASTs for condition-then-else of conditional.")
      astForUnknown(expr, argIdx, argNameMaybe)
    }
  }

  def astForWhile(expr: KtWhileExpression, annotations: Seq[KtAnnotationEntry] = Seq()): Ast = {
    val conditionAst = astsForExpression(expr.getCondition, None).headOption
    val stmtAsts     = astsForExpression(expr.getBody, None)
    val code         = Option(expr.getText)
    val lineNumber   = line(expr)
    val columnNumber = column(expr)

    whileAst(conditionAst, stmtAsts, code, lineNumber, columnNumber)
      .withChildren(annotations.map(astForAnnotationEntry))
  }

  def astForDoWhile(expr: KtDoWhileExpression, annotations: Seq[KtAnnotationEntry] = Seq()): Ast = {
    val conditionAst = astsForExpression(expr.getCondition, None).headOption
    val stmtAsts     = astsForExpression(expr.getBody, None)
    val code         = Option(expr.getText)
    val lineNumber   = line(expr)
    val columnNumber = column(expr)

    doWhileAst(conditionAst, stmtAsts, code, lineNumber, columnNumber)
      .withChildren(annotations.map(astForAnnotationEntry))
  }

  private def astForWhenAsStatement(expr: KtWhenExpression, argIdx: Option[Int]): Ast = {
    val (astForSubject, finalAstForSubject) = Option(expr.getSubjectExpression) match {
      case Some(subjectExpression) =>
        val astForSubject = astsForExpression(subjectExpression, Some(1)).headOption.getOrElse(Ast())
        val finalAstForSubject = expr.getSubjectExpression match {
          case p: KtProperty =>
            val block = blockNode(p, "", "").argumentIndex(1)
            blockAst(block, List(astForSubject))
          case _ => astForSubject
        }
        (astForSubject, finalAstForSubject)
      case _ =>
        logger.warn(s"Subject Expression empty in this file `$relativizedPath`.")
        (Ast(), Ast())
    }

    val astsForEntries =
      withIndex(expr.getEntries.asScala.toList) { (e, idx) =>
        astsForWhenEntry(e, idx)
      }.flatten

    val switchBlockNode =
      blockNode(expr, expr.getEntries.asScala.map(_.getText).mkString("\n"), TypeConstants.Any)
    val astForBlock = blockAst(switchBlockNode, astsForEntries.toList)
    val codeForSwitch = Option(expr.getSubjectExpression)
      .map(_.getText)
      .map { text => s"${Constants.WhenKeyword}($text)" }
      .getOrElse(Constants.WhenKeyword)
    val switchNode = controlStructureNode(expr, ControlStructureTypes.SWITCH, codeForSwitch)
    val ast        = Ast(withArgumentIndex(switchNode, argIdx)).withChildren(List(astForSubject, astForBlock))
    // TODO: rewrite this as well
    finalAstForSubject.root match {
      case Some(root) => ast.withConditionEdge(switchNode, root)
      case None       => ast
    }
  }

  def astForWhenAsExpression(expr: KtWhenExpression, argIdx: Option[Int], argNameMaybe: Option[String]): Ast = {
    val callNode =
      withArgumentIndex(operatorCallNode(expr, "<operator>.when", "<operator>.when", None), argIdx)
        .argumentName(argNameMaybe)

    val subjectExpressionAsts = Option(expr.getSubjectExpression) match {
      case Some(subjectExpression) => astsForExpression(subjectExpression, None)
      case _ =>
        logger.warn(s"Subject Expression empty in this file `$relativizedPath`.")
        Seq.empty
    }
    val subjectBlock    = blockNode(expr.getSubjectExpression, "", "")
    val subjectBlockAst = blockAst(subjectBlock, subjectExpressionAsts.toList)

    val argAsts = expr.getEntries.asScala.toList.map { e =>
      val block = blockNode(e, "", "")
      val conditionAsts =
        e.getConditions
          .flatMap(_.getChildren)
          .collect { case e: KtExpression => e }
          .map(astsForExpression(_, None))
          .toList
          .flatten
      val bodyAsts = astsForExpression(e.getExpression, None)
      blockAst(block, conditionAsts ++ bodyAsts)
    }
    callAst(callNode, List(subjectBlockAst) ++ argAsts)
  }

  private def astForNoArgWhen(expr: KtWhenExpression): Ast = {
    assert(expr.getSubjectExpression == null)

    val typeFullName = registerType(exprTypeFullName(expr).getOrElse(TypeConstants.Any))
    var elseAst: Ast = Ast() // Initialize this as `Ast()` instead of `null`, as there is no guarantee of else block

    // In reverse order than expr.getEntries since that is the order
    // we need for nested Operators.conditional construction.
    expr.getEntries.asScala.reverse.foreach { entry =>
      entry.getConditions.headOption match {
        // The other KtWhenCondition implementations are not generated
        // we have smoke tests for those.
        case Some(cond: KtWhenConditionWithExpression) =>
          val condAst = astsForExpression(cond.getExpression, None).head

          val entryExpr    = entry.getExpression
          val entryExprAst = astsForExpression(entryExpr, None).head

          val callNode =
            operatorCallNode(cond, Operators.conditional, Operators.conditional, Some(typeFullName))

          val newElseAst = callAst(callNode, Seq(condAst, entryExprAst, elseAst))
          elseAst = newElseAst
        case Some(cond) =>
          logger.debug(
            s"Creating empty AST node for unknown condition expression `${cond.getClass}` with text `${cond.getText}`."
          )
          Seq(Ast(unknownNode(expr, Option(expr).map(_.getText).getOrElse(Constants.CodePropUndefinedValue))))
        case None =>
          // This is the 'else' branch of 'when'.
          // and thus first in reverse order, if exists
          elseAst = astsForExpression(entry.getExpression, None).head
      }
    }
    elseAst
  }

  def astForWhen(
    expr: KtWhenExpression,
    argIdx: Option[Int],
    argNameMaybe: Option[String],
    annotations: Seq[KtAnnotationEntry] = Seq()
  ): Ast = {
    val outAst =
      if (expr.getSubjectExpression != null) {
        typeInfoProvider.usedAsExpression(expr) match {
          case Some(true) => astForWhenAsExpression(expr, argIdx, argNameMaybe)
          case _          => astForWhenAsStatement(expr, argIdx)
        }
      } else {
        astForNoArgWhen(expr)
      }
    outAst.withChildren(annotations.map(astForAnnotationEntry))
  }

  private def astsForWhenEntry(entry: KtWhenEntry, argIdx: Int): Seq[Ast] = {
    // TODO: get all conditions with entry.getConditions()
    val name =
      if (entry.getElseKeyword == null) Constants.DefaultCaseNode
      else s"${Constants.CaseNodePrefix}$argIdx"
    val jumpNode = jumpTargetNode(entry, name, entry.getText, Some(Constants.CaseNodeParserTypeName))
      .argumentIndex(argIdx)
    val exprNode = astsForExpression(entry.getExpression, Some(argIdx + 1)).headOption.getOrElse(Ast())
    Seq(Ast(jumpNode), exprNode)
  }

  private def astForTryAsStatement(expr: KtTryExpression): Ast = {
    val tryAst = astsForExpression(expr.getTryBlock, None).headOption.getOrElse(Ast())
    val clauseAsts = expr.getCatchClauses.asScala.toSeq.map { catchClause =>
      val catchNode    = controlStructureNode(catchClause, ControlStructureTypes.CATCH, catchClause.getText)
      val childrenAsts = astsForExpression(catchClause.getCatchBody, None)
      Ast(catchNode).withChildren(childrenAsts)
    }
    val finallyAst = Option(expr.getFinallyBlock)
      .map(_.getFinalExpression)
      .map { finallyBlock =>
        val finallyNode  = controlStructureNode(finallyBlock, ControlStructureTypes.FINALLY, finallyBlock.getText)
        val childrenAsts = astsForExpression(finallyBlock, None)
        Ast(finallyNode).withChildren(childrenAsts)
      }
    val tryNode = controlStructureNode(expr, ControlStructureTypes.TRY, expr.getText)
    tryCatchAst(tryNode, tryAst, clauseAsts, finallyAst)
  }

  private def astForTryAsExpression(
    expr: KtTryExpression,
    argIdx: Option[Int],
    argNameMaybe: Option[String],
    annotations: Seq[KtAnnotationEntry] = Seq()
  ): Ast = {
    val typeFullName = registerType(
      // TODO: remove the `last`
      exprTypeFullName(expr.getTryBlock.getStatements.asScala.last).getOrElse(TypeConstants.Any)
    )
    val tryBlockAst = astsForExpression(expr.getTryBlock, None).headOption.getOrElse(Ast())
    val clauseAsts = expr.getCatchClauses.asScala.toSeq.flatMap { entry =>
      astsForExpression(entry.getCatchBody, None)
    }
    val node =
      operatorCallNode(expr, expr.getText, Operators.tryCatch, Option(typeFullName))
        .argumentName(argNameMaybe)

    callAst(withArgumentIndex(node, argIdx), List(tryBlockAst) ++ clauseAsts)
      .withChildren(annotations.map(astForAnnotationEntry))
  }

  // TODO: handle parameters passed to the clauses
  def astForTry(
    expr: KtTryExpression,
    argIdx: Option[Int],
    argNameMaybe: Option[String],
    annotations: Seq[KtAnnotationEntry] = Seq()
  ): Ast = {
    if (KtPsiUtil.isStatement(expr)) astForTryAsStatement(expr)
    else astForTryAsExpression(expr, argIdx, argNameMaybe, annotations)
  }

  def astForBreak(expr: KtBreakExpression): Ast = {
    val node = controlStructureNode(expr, ControlStructureTypes.BREAK, expr.getText)
    Ast(node)
  }

  def astForContinue(expr: KtContinueExpression): Ast = {
    val node = controlStructureNode(expr, ControlStructureTypes.CONTINUE, expr.getText)
    Ast(node)
  }

  def astForBlock(
    expr: KtBlockExpression,
    argIdxMaybe: Option[Int],
    argNameMaybe: Option[String],
    pushToScope: Boolean = true,
    localsForCaptures: List[NewLocal] = List(),
    implicitReturnAroundLastStatement: Boolean = false,
    preStatements: Option[Seq[Ast]] = None
  ): Ast = {
    val typeFullName = registerType(exprTypeFullName(expr).getOrElse(TypeConstants.Any))
    val node =
      withArgumentIndex(
        blockNode(expr, expr.getStatements.asScala.map(_.getText).mkString("\n"), typeFullName),
        argIdxMaybe
      )
        .argumentName(argNameMaybe)
    if (pushToScope) scope.pushNewScope(node)
    val statements = expr.getStatements.asScala.toSeq.filter { stmt =>
      !stmt.isInstanceOf[KtNamedFunction] && !stmt.isInstanceOf[KtClassOrObject]
    }
    val declarations = expr.getStatements.asScala.toSeq.collect {
      case fn: KtNamedFunction         => fn
      case classOrObj: KtClassOrObject => classOrObj
    }
    val declarationAsts          = declarations.flatMap(astsForDeclaration)
    val allStatementsButLast     = statements.dropRight(1)
    val allStatementsButLastAsts = allStatementsButLast.flatMap(astsForExpression(_, None))

    val lastStatementAstWithTail =
      if (implicitReturnAroundLastStatement && statements.nonEmpty) {
        val _returnNode          = returnNode(statements.last, Constants.RetCode)
        val astsForLastStatement = astsForExpression(statements.last, Some(1))
        if (astsForLastStatement.isEmpty)
          (Seq(), None)
        else
          (
            astsForLastStatement.dropRight(1),
            Some(returnAst(_returnNode, Seq(astsForLastStatement.lastOption.getOrElse(Ast()))))
          )
      } else if (statements.nonEmpty) {
        val astsForLastStatement = astsForExpression(statements.last, None)
        if (astsForLastStatement.isEmpty)
          (Seq(), None)
        else
          (astsForLastStatement.dropRight(1), Some(astsForLastStatement.lastOption.getOrElse(Ast())))
      } else (Seq(), None)

    if (pushToScope) scope.popScope()
    val childrenAsts = localsForCaptures.map(Ast(_)) ++
      preStatements.getOrElse(Seq()) ++
      declarationAsts ++
      allStatementsButLastAsts ++
      lastStatementAstWithTail._1 ++
      lastStatementAstWithTail._2.map(Seq(_)).getOrElse(Seq())
    blockAst(node, childrenAsts)
  }
}
