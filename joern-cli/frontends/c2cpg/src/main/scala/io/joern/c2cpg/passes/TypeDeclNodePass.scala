package io.joern.c2cpg.passes

import io.joern.x2cpg.AstNodeBuilder
import io.joern.c2cpg.astcreation.Defines
import io.joern.c2cpg.Config
import io.shiftleft.codepropertygraph.generated.{Cpg, EdgeTypes, NodeTypes}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.types.structure.NamespaceTraversal
import io.shiftleft.semanticcpg.language.*
import io.joern.x2cpg.passes.frontend.MetaDataPass
import io.joern.x2cpg.{Ast, ValidationMode}

class TypeDeclNodePass(cpg: Cpg, config: Config) extends CpgPass(cpg) {

  private val filename: String                          = "<includes>"
  private val globalName: String                        = NamespaceTraversal.globalNamespaceName
  private val fullName: String                          = MetaDataPass.getGlobalNamespaceBlockFullName(Option(filename))
  private val typeDeclFullNames: Set[String]            = cpg.typeDecl.fullName.toSetImmutable
  private implicit val schemaValidation: ValidationMode = config.schemaValidation

  override def run(dstGraph: DiffGraphBuilder): Unit = {
    var hadMissingTypeDecl = false
    cpg.typ.filter(typeNeedsTypeDeclStub).foreach { t =>
      val newTypeDecl = NewTypeDecl()
        .name(t.name)
        .fullName(t.typeDeclFullName)
        .code(t.name)
        .isExternal(true)
        .filename(filename)
        .astParentType(NodeTypes.NAMESPACE_BLOCK)
        .astParentFullName(fullName)
      dstGraph.addNode(newTypeDecl)
      createOperatorBinding(newTypeDecl, dstGraph)
      hadMissingTypeDecl = true
    }
    if (hadMissingTypeDecl) Ast.storeInDiffGraph(createGlobalAst(), dstGraph)
  }

  private def createOperatorBinding(typeDecl: NewTypeDecl, dstGraph: DiffGraphBuilder): Unit = {
    if (typeDecl.fullName.startsWith("<operator") && typeDecl.fullName != Defines.OperatorUnknown) {
      val functionBinding = NewBinding().name("").methodFullName(typeDecl.fullName).signature("")
      dstGraph.addNode(functionBinding)
      dstGraph.addEdge(typeDecl, functionBinding, EdgeTypes.BINDS)
    }
  }

  private def createGlobalAst(): Ast = {
    val includesFile = NewFile().name(filename)
    val namespaceBlock = NewNamespaceBlock()
      .name(globalName)
      .fullName(fullName)
      .filename(filename)
    val fakeGlobalIncludesMethod =
      NewMethod()
        .name(globalName)
        .code(globalName)
        .fullName(fullName)
        .filename(filename)
        .lineNumber(1)
        .astParentType(NodeTypes.NAMESPACE_BLOCK)
        .astParentFullName(fullName)
    val blockNode    = NewBlock().typeFullName(Defines.Any)
    val methodReturn = AstNodeBuilder.methodReturnNodeWithExplicitPositionInfo(Defines.Any, lineNumber = Option(1))
    Ast(includesFile).withChild(
      Ast(namespaceBlock)
        .withChild(Ast(fakeGlobalIncludesMethod).withChild(Ast(blockNode)).withChild(Ast(methodReturn)))
    )
  }

  private def typeNeedsTypeDeclStub(t: Type): Boolean = {
    !typeDeclFullNames.contains(t.typeDeclFullName)
  }

}
