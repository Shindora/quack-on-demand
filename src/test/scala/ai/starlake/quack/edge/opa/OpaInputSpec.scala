package ai.starlake.quack.edge.opa

import ai.starlake.acl.model.TableRef
import ai.starlake.acl.parser.{TableAccess, Verb}
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OpaInputSpec extends AnyFlatSpec with Matchers:

  private val target = OpaTarget("acme", "tpch", "bi", Nil)
  private val user   = OpaUser("alice", List("analyst"), List("emea"), Map("dept" -> "fin"))

  "OpaInput.connect" should "match the golden document" in:
    OpaInput.connect(target, user, "flightsql") shouldBe parse(
      """{"input":{"kind":"connect","tenant":"acme","database":"tpch","pool":"bi",
        |"parentPool":null,
        |"user":{"name":"alice","roles":["analyst"],"groups":["emea"],"claims":{"dept":"fin"}},
        |"client":{"edge":"flightsql"}}}""".stripMargin
    ).toOption.get

  it should "list parent pools for a branch pool" in:
    val doc =
      OpaInput.connect(target.copy(pool = "__br_ab12cd34", parentPools = List("bi")), user, "quack")
    doc.hcursor.downField("input").downField("parentPool").as[List[String]] shouldBe Right(
      List("bi")
    )

  "OpaInput.statement" should "sort accesses, lowercase verbs and omit the text by default" in:
    val acc = Set(
      TableAccess(TableRef("tpch", "main", "orders"), Verb.Read),
      TableAccess(TableRef("tpch", "main", "lineitem"), Verb.Write)
    )
    OpaInput.statement(target, user, "mcp", "WRITE", acc, None) shouldBe parse(
      """{"input":{"kind":"statement","tenant":"acme","database":"tpch","pool":"bi",
        |"parentPool":null,
        |"user":{"name":"alice","roles":["analyst"],"groups":["emea"],"claims":{"dept":"fin"}},
        |"client":{"edge":"mcp"},
        |"statement":{"class":"WRITE"},
        |"accesses":[
        | {"catalog":"tpch","schema":"main","table":"lineitem","verb":"write"},
        | {"catalog":"tpch","schema":"main","table":"orders","verb":"read"}]}}""".stripMargin
    ).toOption.get

  it should "carry the statement text only when given" in:
    val doc = OpaInput.statement(target, user, "flightsql", "READ", Set.empty, Some("SELECT 1"))
    doc.hcursor.downField("input").downField("statement").downField("text").as[String] shouldBe
      Right("SELECT 1")
