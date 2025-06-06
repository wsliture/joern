package io.joern.csharpsrc2cpg.querying.ast

import io.joern.csharpsrc2cpg.testfixtures.CSharpCode2CpgFixture
import io.shiftleft.codepropertygraph.generated.ModifierTypes
import io.shiftleft.codepropertygraph.generated.nodes.Return
import io.shiftleft.semanticcpg.language.*

class MethodTests extends CSharpCode2CpgFixture {

  "a basic class declaration with method" should {
    val cpg = code(basicBoilerplate(), "Program.cs")

    "generate a method node with type decl parent" in {
      val x = cpg.method.nameExact("Main").head
      x.fullName should startWith("HelloWorld.Program.Main:System.Void")
      x.fullName shouldBe "HelloWorld.Program.Main:System.Void(System.String[])"
      x.signature shouldBe "System.Void(System.String[])"
      x.filename shouldBe "Program.cs"
      x.code shouldBe
        """static void Main(string[] args)
          |    {
          |      Console.WriteLine("Hello, world!");
          |    }""".stripMargin
      x.typeDecl match
        case Some(typeDecl) => typeDecl.name shouldBe "Program"
        case None           => fail("No TYPE_DECL parent found!")
    }

    "generate a method node with the correct modifiers" in {
      val List(x, y) = cpg.method.nameExact("Main").modifier.l: @unchecked
      x.modifierType shouldBe ModifierTypes.INTERNAL
      y.modifierType shouldBe ModifierTypes.STATIC
    }

    "generate a method node with a parameter" in {
      val List(x) = cpg.method.nameExact("Main").parameter.l: @unchecked
      x.name shouldBe "args"
    }

    "generate a method node with a block" in {
      cpg.method.nameExact("Main").body.l should not be empty
    }
  }

  "basic method with a return statement" should {
    val cpg = code("""
        |using System;
        |namespace HelloWorld
        |{
        |  class Program
        |  {
        |    static void Main(string[] args) {}
        |    
        |    public int getInt(int foo) {
        |       return foo;
        |    }
        |  }
        |}
        |""".stripMargin)

    "have correct method properties" in {
      inside(cpg.method("getInt").l) {
        case methodNode :: Nil =>
          methodNode.name shouldBe "getInt"
          methodNode.fullName shouldBe "HelloWorld.Program.getInt:System.Int32(System.Int32)"
          methodNode.code should startWith("public int getInt(int foo)")
          methodNode.signature shouldBe "System.Int32(System.Int32)"
          methodNode.isExternal shouldBe false

          methodNode.order shouldBe 3
          methodNode.filename shouldBe "Test0.cs"
          methodNode.lineNumber shouldBe Option(8)
          methodNode.lineNumberEnd shouldBe Option(10)
        case _ => fail("No method with name `getInt` found.")
      }
    }

    "have correct return information" in {
      val List(methodReturnNode) = cpg.method.name("getInt").methodReturn.l
      methodReturnNode.typeFullName shouldBe "System.Int32"
    }
  }

  "empty public abstract method" should {
    val cpg = code("""
        |abstract class C
        |{
        | public abstract void DoStuff();
        |}
        |""".stripMargin)

    "have correct modifiers" in {
      cpg.method.nameExact("DoStuff").modifier.modifierType.sorted.l shouldBe List(
        ModifierTypes.ABSTRACT,
        ModifierTypes.PUBLIC
      )
    }
  }

  "empty protected abstract method" should {
    val cpg = code("""
        |abstract class C
        |{
        |  protected abstract void DoStuff();
        |}""".stripMargin)

    "have correct modifiers" in {
      cpg.method.nameExact("DoStuff").modifier.modifierType.sorted.l shouldBe List(
        ModifierTypes.ABSTRACT,
        ModifierTypes.PROTECTED
      )
    }
  }

  "standalone method declaration inside a top-level method" should {
    val cpg = code("""
        |int MyMain()
        |{
        |   int MySubMethod() {return 1;}
        |}
        |""".stripMargin)

    "have correct properties for the nested method" in {
      inside(cpg.method.nameExact("MySubMethod").l) {
        case sub :: Nil =>
          sub.fullName shouldBe "Test0_cs_Program.<Main>$.MyMain.MySubMethod:System.Int32()"
          sub.signature shouldBe "System.Int32()"
          sub.modifier.modifierType.sorted.l shouldBe List(ModifierTypes.INTERNAL)
          sub.methodReturn.typeFullName shouldBe "System.Int32"
          sub.parentBlock.method.l shouldBe cpg.method.fullNameExact("Test0_cs_Program.<Main>$.MyMain:System.Int32()").l
        case xs => fail(s"Expected single MySubMethod METHOD, but got $xs")
      }
    }

    "have correct body for the nested method" in {
      inside(cpg.method.nameExact("MySubMethod").block.astChildren.l) {
        case (ret: Return) :: Nil => ret.code shouldBe "return 1;"
        case xs                   => fail(s"Expected single RETURN node, but got $xs")
      }
    }
  }

  "standalone method declaration inside a class method" should {
    val cpg = code("""
        |class MyClass
        |{
        |   int MyMain()
        |   {
        |     int MySubMethod() {return 1;}
        |   }
        |}
        |""".stripMargin)

    "have correct properties for the nested method" in {
      inside(cpg.method.nameExact("MySubMethod").l) {
        case sub :: Nil =>
          sub.fullName shouldBe "MyClass.MyMain.MySubMethod:System.Int32()"
          sub.signature shouldBe "System.Int32()"
          sub.modifier.modifierType.sorted.l shouldBe List(ModifierTypes.INTERNAL)
          sub.methodReturn.typeFullName shouldBe "System.Int32"
          sub.parentBlock.method.l shouldBe cpg.method.fullNameExact("MyClass.MyMain:System.Int32()").l
        case xs => fail(s"Expected single MySubMethod METHOD, but got $xs")
      }
    }

    "have correct body for the nested method" in {
      inside(cpg.method.nameExact("MySubMethod").block.astChildren.l) {
        case (ret: Return) :: Nil => ret.code shouldBe "return 1;"
        case xs                   => fail(s"Expected single RETURN node, but got $xs")
      }
    }
  }
}
