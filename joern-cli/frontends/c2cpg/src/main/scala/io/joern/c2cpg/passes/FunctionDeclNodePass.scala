package io.joern.c2cpg.passes

import io.joern.c2cpg.astcreation.CGlobal
import io.joern.c2cpg.Config
import io.joern.x2cpg.Ast
import io.joern.x2cpg.Defines
import io.joern.x2cpg.ValidationMode
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.NewBlock
import io.shiftleft.codepropertygraph.generated.nodes.NewMethod
import io.shiftleft.codepropertygraph.generated.nodes.NewMethodParameterIn
import io.shiftleft.codepropertygraph.generated.nodes.NewMethodReturn
import io.shiftleft.codepropertygraph.generated.EvaluationStrategies
import io.shiftleft.codepropertygraph.generated.nodes.NewBinding
import io.shiftleft.codepropertygraph.generated.nodes.NewTypeDecl
import io.shiftleft.codepropertygraph.generated.EdgeTypes
import io.shiftleft.codepropertygraph.generated.NodeTypes
import io.shiftleft.codepropertygraph.generated.nodes.Method
import io.shiftleft.codepropertygraph.generated.nodes.NewModifier
import io.shiftleft.codepropertygraph.generated.nodes.TypeDecl
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.types.structure.NamespaceTraversal
import org.apache.commons.lang3.StringUtils

import scala.collection.immutable.Map

object FunctionDeclNodePass {

  final case class MethodInfo(
    name: String,
    code: String,
    fileName: String,
    returnType: String,
    astParentType: String,
    astParentFullName: String,
    lineNumber: Option[Int],
    columnNumber: Option[Int],
    lineNumberEnd: Option[Int],
    columnNumberEnd: Option[Int],
    signature: String,
    offset: Option[(Int, Int)],
    parameter: Seq[ParameterInfo],
    modifier: Seq[String]
  )

  final class ParameterInfo(
    val name: String,
    var code: String,
    val index: Int,
    var isVariadic: Boolean,
    val evaluationStrategy: String,
    val lineNumber: Option[Int],
    val columnNumber: Option[Int],
    val typeFullName: String
  )

}

class FunctionDeclNodePass(cpg: Cpg, methodDeclarations: Map[String, FunctionDeclNodePass.MethodInfo], config: Config)
    extends CpgPass(cpg) {

  import FunctionDeclNodePass.*

  private implicit val schemaValidation: ValidationMode = config.schemaValidation

  override def run(dstGraph: DiffGraphBuilder): Unit = {
    stubMethods(dstGraph)
    createMissingCppBindings(dstGraph)
  }

  private def nameAndSignature(method: Method): String = s".${method.name}:${method.signature}"

  private def parentTypeDeclForMethod(method: Method): Option[TypeDecl] = {
    val typeDeclFullName = method.fullName.substring(0, method.fullName.indexOf(nameAndSignature(method)))
    cpg.typeDecl.fullNameExact(typeDeclFullName).headOption
  }

  private def isCppMethod(method: Method): Boolean = {
    !method.name.startsWith(io.joern.x2cpg.Defines.ClosurePrefix) &&
    method.signature.nonEmpty &&
    method.fullName.endsWith(nameAndSignature(method))
  }

  private def createMissingCppBindings(dstGraph: DiffGraphBuilder): Unit = {
    for {
      method <- cpg.method
        .nameNot(NamespaceTraversal.globalNamespaceName)
        .where(_.parameter.index(0))
        .not(_.isPrivate)
        .filter(isCppMethod)
      parentTypeDecl <- parentTypeDeclForMethod(method)
      if !parentTypeDecl.bindsOut.refOut.contains(method)
    } {
      // create new binding at proper typeDecl
      val functionBinding = NewBinding().name(method.name).methodFullName(method.fullName).signature(method.signature)
      dstGraph.addNode(functionBinding)
      dstGraph.addEdge(parentTypeDecl, functionBinding, EdgeTypes.BINDS)
      dstGraph.addEdge(functionBinding, method, EdgeTypes.REF)
    }
  }

  private def stubMethods(dstGraph: DiffGraphBuilder): Unit = {
    methodDeclarations.foreach { case (fullName, methodNodeInfo) =>
      val methodNode_    = methodNode(fullName, methodNodeInfo)
      val parameterNodes = methodNodeInfo.parameter.map(p => Ast(parameterInNode(p)))
      val stubAst =
        methodStubAst(
          methodNode_,
          parameterNodes,
          methodReturnNode(methodNodeInfo.returnType, methodNodeInfo.lineNumber, methodNodeInfo.columnNumber),
          methodNodeInfo.modifier.map(m => Ast(NewModifier().modifierType(m)))
        )
      val typeDeclAst = createFunctionTypeAndTypeDecl(
        methodNodeInfo,
        methodNode_,
        methodNodeInfo.name,
        fullName,
        methodNodeInfo.signature,
        dstGraph
      )
      val ast = stubAst.merge(typeDeclAst)
      Ast.storeInDiffGraph(ast, dstGraph)
    }
  }

  private def methodNode(fullName: String, methodNodeInfo: MethodInfo): NewMethod = {
    val node_ =
      NewMethod()
        .name(StringUtils.normalizeSpace(methodNodeInfo.name))
        .code(methodNodeInfo.code)
        .fullName(StringUtils.normalizeSpace(fullName))
        .filename(methodNodeInfo.fileName)
        .astParentType(methodNodeInfo.astParentType)
        .astParentFullName(methodNodeInfo.astParentFullName)
        .isExternal(false)
        .lineNumber(methodNodeInfo.lineNumber)
        .columnNumber(methodNodeInfo.columnNumber)
        .lineNumberEnd(methodNodeInfo.lineNumberEnd)
        .columnNumberEnd(methodNodeInfo.columnNumberEnd)
        .signature(StringUtils.normalizeSpace(methodNodeInfo.signature))
    methodNodeInfo.offset.foreach { case (offset, offsetEnd) =>
      node_.offset(offset).offsetEnd(offsetEnd)
    }
    node_
  }

  private def parameterInNode(parameterNodeInfo: ParameterInfo): NewMethodParameterIn = {
    NewMethodParameterIn()
      .name(parameterNodeInfo.name)
      .code(parameterNodeInfo.code)
      .index(parameterNodeInfo.index)
      .order(parameterNodeInfo.index)
      .isVariadic(parameterNodeInfo.isVariadic)
      .evaluationStrategy(parameterNodeInfo.evaluationStrategy)
      .lineNumber(parameterNodeInfo.lineNumber)
      .columnNumber(parameterNodeInfo.columnNumber)
      .typeFullName(parameterNodeInfo.typeFullName)
  }

  private def methodReturnNode(typeFullName: String, line: Option[Int], column: Option[Int]): NewMethodReturn =
    NewMethodReturn()
      .typeFullName(typeFullName)
      .code("RET")
      .evaluationStrategy(EvaluationStrategies.BY_VALUE)
      .lineNumber(line)
      .columnNumber(column)

  private def methodStubAst(
    method: NewMethod,
    parameters: Seq[Ast],
    methodReturn: NewMethodReturn,
    modifier: Seq[Ast]
  ): Ast =
    Ast(method)
      .withChildren(parameters)
      .withChild(Ast(NewBlock().typeFullName(Defines.Any)))
      .withChildren(modifier)
      .withChild(Ast(methodReturn))

  private def createFunctionTypeAndTypeDecl(
    methodInfo: MethodInfo,
    method: NewMethod,
    methodName: String,
    methodFullName: String,
    signature: String,
    dstGraph: DiffGraphBuilder
  ): Ast = {
    val normalizedName     = StringUtils.normalizeSpace(methodName)
    val normalizedFullName = StringUtils.normalizeSpace(methodFullName)

    if (methodInfo.astParentType == NodeTypes.TYPE_DECL) {
      val parentTypeDecl = cpg.typeDecl.nameExact(methodInfo.astParentFullName).headOption
      parentTypeDecl
        .map { typeDecl =>
          val functionBinding =
            NewBinding().name(normalizedName).methodFullName(normalizedFullName).signature(signature)
          dstGraph.addEdge(typeDecl, functionBinding, EdgeTypes.BINDS)
          Ast(functionBinding).withRefEdge(functionBinding, method)
        }
        .getOrElse(Ast())
    } else {
      val typeDecl = typeDeclNode(
        normalizedName,
        normalizedFullName,
        method.filename,
        normalizedName,
        methodInfo.astParentType,
        methodInfo.astParentFullName,
        methodInfo.lineNumber,
        methodInfo.columnNumber,
        methodInfo.offset
      )
      Ast.storeInDiffGraph(Ast(typeDecl), dstGraph)
      method.astParentFullName = typeDecl.fullName
      method.astParentType = typeDecl.label
      val functionBinding = NewBinding().name(normalizedName).methodFullName(normalizedFullName).signature(signature)
      Ast(functionBinding).withBindsEdge(typeDecl, functionBinding).withRefEdge(functionBinding, method)
    }
  }

  private def typeDeclNode(
    name: String,
    fullName: String,
    filename: String,
    code: String,
    astParentType: String,
    astParentFullName: String,
    line: Option[Int],
    column: Option[Int],
    offset: Option[(Int, Int)]
  ): NewTypeDecl = {
    val node_ = NewTypeDecl()
      .name(name)
      .fullName(fullName)
      .code(code)
      .isExternal(false)
      .filename(filename)
      .astParentType(astParentType)
      .astParentFullName(astParentFullName)
      .lineNumber(line)
      .columnNumber(column)
    offset.foreach { case (offset, offsetEnd) =>
      node_.offset(offset).offsetEnd(offsetEnd)
    }
    node_
  }

}
