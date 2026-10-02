package ai.starlake.quack.edge.opa

import ai.starlake.acl.model.TableRef
import ai.starlake.acl.parser.{TableAccess, Verb}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OpaDecisionSpec extends AnyFlatSpec with Matchers:

  private val orders   = TableAccess(TableRef("tpch", "main", "orders"), Verb.Read)
  private val lineitem = TableAccess(TableRef("tpch", "main", "lineitem"), Verb.Write)
  private val acc      = Set(orders, lineitem)

  private def isDeny(d: Decision) = d match
    case _: Decision.Deny => true
    case _                => false

  "OpaDecision.parse" should "allow only on a literal true" in:
    OpaDecision.parse("""{"result":{"allow":true}}""", acc) shouldBe Decision.Allow(None)

  it should "carry decision_id" in:
    OpaDecision.parse("""{"decision_id":"d-1","result":{"allow":true}}""", acc) shouldBe
      Decision.Allow(Some("d-1"))

  it should "deny on allow=false with reason and the named accesses" in:
    OpaDecision.parse(
      """{"result":{"allow":false,"reason":"no writes",
        |"denied":[{"catalog":"TPCH","schema":"main","table":"lineitem","verb":"write"}]}}""".stripMargin,
      acc
    ) shouldBe Decision.Deny("no writes", Set(lineitem), None)

  it should "name every access when denied is absent, and ignore unknown denied entries" in:
    OpaDecision.parse("""{"result":{"allow":false}}""", acc) shouldBe
      Decision.Deny("denied by policy", acc, None)
    OpaDecision.parse(
      """{"result":{"allow":false,"denied":[{"catalog":"x","schema":"y","table":"z","verb":"read"}]}}""",
      acc
    ) shouldBe Decision.Deny("denied by policy", acc, None)

  it should "deny every non-boolean or missing allow" in:
    Seq(
      """{"result":{"allow":"true"}}""",
      """{"result":{"allow":1}}""",
      """{"result":{"allow":null}}""",
      """{"result":{}}""",
      """{"result":true}""",
      """{}""",
      """not json"""
    ).foreach(b => withClue(b)(isDeny(OpaDecision.parse(b, acc)) shouldBe true))

  it should "deny when the v2-reserved row_filter or masks are present" in:
    Seq(
      """{"result":{"allow":true,"row_filter":"region = 'EU'"}}""",
      """{"result":{"allow":true,"masks":{}}}""",
      """{"result":{"allow":true,"row_filter":null}}"""
    ).foreach { b =>
      withClue(b) {
        OpaDecision.parse(b, acc) match
          case Decision.Deny(r, _, _) => r should include("row_filter/masks")
          case other                  => fail(s"expected Deny, got $other")
      }
    }

  "OpaDecision.sanitize" should "strip control characters and cap at 256 chars" in:
    OpaDecision.sanitize("a\nb\u0007c") shouldBe "abc"
    OpaDecision.sanitize("x" * 300).length shouldBe 256
