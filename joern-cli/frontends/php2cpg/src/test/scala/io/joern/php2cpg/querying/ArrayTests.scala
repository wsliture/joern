package io.joern.php2cpg.querying

import io.joern.php2cpg.testfixtures.PhpCode2CpgFixture
import io.shiftleft.codepropertygraph.generated.Operators
import io.shiftleft.codepropertygraph.generated.nodes.{Block, Call, Identifier, Literal, Local}
import io.shiftleft.semanticcpg.language.*

class ArrayTests extends PhpCode2CpgFixture {
  "array accesses with variable keys should be represented as index accesses" in {
    val cpg = code("""<?php
        |$array[$key];
        |""".stripMargin)

    inside(cpg.call.l) { case List(indexAccess) =>
      indexAccess.name shouldBe Operators.indexAccess
      indexAccess.code shouldBe "$array[$key]"
      indexAccess.lineNumber shouldBe Some(2)

      inside(indexAccess.argument.l) { case List(array: Identifier, key: Identifier) =>
        array.name shouldBe "array"
        array.code shouldBe "$array"
        array.lineNumber shouldBe Some(2)

        key.name shouldBe "key"
        key.code shouldBe "$key"
        key.lineNumber shouldBe Some(2)
      }
    }
  }

  "array accesses with literal keys should be represented as index accesses" in {
    val cpg = code("""<?php
        |$array[0];
        |""".stripMargin)

    inside(cpg.call.l) { case List(indexAccess) =>
      indexAccess.name shouldBe Operators.indexAccess
      indexAccess.code shouldBe "$array[0]"
      indexAccess.lineNumber shouldBe Some(2)

      inside(indexAccess.argument.l) { case List(array: Identifier, key: Literal) =>
        array.name shouldBe "array"
        array.code shouldBe "$array"
        array.lineNumber shouldBe Some(2)

        key.code shouldBe "0"
        key.lineNumber shouldBe Some(2)
      }
    }
  }

  "assignments using the empty array dimension fetch syntax should be rewritten as array_push" in {
    val cpg = code("""<?php
        |function foo($val) {
        |  $xs[] = $val;
        |}
        |""".stripMargin)

    inside(cpg.method.name("foo").body.astChildren.l) { case List(xsLocal: Local, arrayPush: Call) =>
      xsLocal.name shouldBe "xs"
      xsLocal.lineNumber shouldBe Some(3)

      arrayPush.name shouldBe "array_push"
      arrayPush.code shouldBe "$xs[] = $val"
    }
  }

  "associative array definitions should be lowered with the correct assignments" in {
    val cpg = code("""<?php
        |array(
        |  "A" => 1,
        |  "B" => 2
        |);
        |""".stripMargin)

    inside(cpg.method.internal.body.astChildren.l) { case List(tmpLocal: Local, arrayBlock: Block) =>
      tmpLocal.name shouldBe "Test0.php:<global>@tmp-0"
      tmpLocal.code shouldBe "$Test0.php:<global>@tmp-0"

      inside(arrayBlock.astChildren.l) {
        case List(initAssign: Call, aAssign: Call, bAssign: Call, tmpIdent: Identifier) =>
          initAssign.code shouldBe "$Test0.php:<global>@tmp-0 = array()"
          initAssign.lineNumber shouldBe Some(2)

          aAssign.code shouldBe "$Test0.php:<global>@tmp-0[\"A\"] = 1"
          aAssign.lineNumber shouldBe Some(3)

          bAssign.code shouldBe "$Test0.php:<global>@tmp-0[\"B\"] = 2"
          bAssign.lineNumber shouldBe Some(4)

          tmpIdent.name shouldBe "Test0.php:<global>@tmp-0"
          tmpIdent.code shouldBe "$Test0.php:<global>@tmp-0"
          tmpIdent._localViaRefOut should contain(tmpLocal)
      }
    }
  }

  "non-associative array definitions should be lowered with the correct index accesses and assignments" in {
    val cpg = code("""<?php
        |array(
        |  "A",
        |  "B"
        |);
        |""".stripMargin)

    inside(cpg.method.internal.body.astChildren.l) { case List(tmpLocal: Local, arrayBlock: Block) =>
      tmpLocal.name shouldBe "Test0.php:<global>@tmp-0"
      tmpLocal.code shouldBe "$Test0.php:<global>@tmp-0"

      inside(arrayBlock.astChildren.l) {
        case List(initAssign: Call, aAssign: Call, bAssign: Call, tmpIdent: Identifier) =>
          initAssign.code shouldBe "$Test0.php:<global>@tmp-0 = array()"
          initAssign.lineNumber shouldBe Some(2)

          aAssign.code shouldBe "$Test0.php:<global>@tmp-0[0] = \"A\""
          aAssign.lineNumber shouldBe Some(3)

          bAssign.code shouldBe "$Test0.php:<global>@tmp-0[1] = \"B\""
          bAssign.lineNumber shouldBe Some(4)

          tmpIdent.name shouldBe "Test0.php:<global>@tmp-0"
          tmpIdent.code shouldBe "$Test0.php:<global>@tmp-0"
          tmpIdent._localViaRefOut should contain(tmpLocal)
      }
    }
  }

  "arrays with int-compatible indices should have them treated as ints" in {
    val cpg = code("""<?php
        |array(
        |  "2" => "A"
        |);
        |""".stripMargin)

    inside(cpg.method.internal.body.astChildren.l) { case List(tmpLocal: Local, arrayBlock: Block) =>
      tmpLocal.name shouldBe "Test0.php:<global>@tmp-0"
      tmpLocal.code shouldBe "$Test0.php:<global>@tmp-0"

      inside(arrayBlock.astChildren.l) { case List(initAssign: Call, assign: Call, tmpIdent: Identifier) =>
        initAssign.code shouldBe "$Test0.php:<global>@tmp-0 = array()"
        initAssign.lineNumber shouldBe Some(2)

        assign.code shouldBe "$Test0.php:<global>@tmp-0[2] = \"A\""
        inside(assign.argument.collectAll[Call].argument.l) { case List(array: Identifier, index: Literal) =>
          array.name shouldBe "Test0.php:<global>@tmp-0"
          array.code shouldBe "$Test0.php:<global>@tmp-0"

          index.code shouldBe "2"
          index.typeFullName shouldBe "int"
        }

        tmpIdent.name shouldBe "Test0.php:<global>@tmp-0"
        tmpIdent.code shouldBe "$Test0.php:<global>@tmp-0"
        tmpIdent._localViaRefOut should contain(tmpLocal)
      }
    }
  }

  "mixed associative array definitions should be represented with correct keys" in {
    val cpg = code("""<?php
        |array(
        |  "A" => "B",
        |  "C",
        |  4 => "D",
        |  "E",
        |  "10" => "F",
        |  "G",
        |  8 => "H",
        |);
        |""".stripMargin)

    inside(cpg.method.internal.body.astChildren.l) { case List(tmpLocal: Local, arrayBlock: Block) =>
      tmpLocal.name shouldBe "Test0.php:<global>@tmp-0"
      tmpLocal.code shouldBe "$Test0.php:<global>@tmp-0"

      inside(arrayBlock.astChildren.l) {
        case List(
              initAssign: Call,
              aAssign: Call,
              cAssign: Call,
              fourAssign: Call,
              eAssign: Call,
              tenAssign: Call,
              gAssign: Call,
              eightAssign: Call,
              tmpIdent: Identifier
            ) =>
          initAssign.code shouldBe "$Test0.php:<global>@tmp-0 = array()"
          initAssign.lineNumber shouldBe Some(2)

          aAssign.code shouldBe "$Test0.php:<global>@tmp-0[\"A\"] = \"B\""
          cAssign.code shouldBe "$Test0.php:<global>@tmp-0[0] = \"C\""
          fourAssign.code shouldBe "$Test0.php:<global>@tmp-0[4] = \"D\""
          eAssign.code shouldBe "$Test0.php:<global>@tmp-0[5] = \"E\""
          tenAssign.code shouldBe "$Test0.php:<global>@tmp-0[10] = \"F\""
          gAssign.code shouldBe "$Test0.php:<global>@tmp-0[11] = \"G\""
          eightAssign.code shouldBe "$Test0.php:<global>@tmp-0[8] = \"H\""

          tmpIdent.name shouldBe "Test0.php:<global>@tmp-0"
          tmpIdent.code shouldBe "$Test0.php:<global>@tmp-0"
          tmpIdent._localViaRefOut should contain(tmpLocal)
      }
    }
  }
}
