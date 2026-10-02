package ai.starlake.quack.edge.sql

import ai.starlake.quack.model.PoolKey
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TenantRoutingValidatorSpec extends AnyFlatSpec with Matchers:

  private val qod: StatementValidator = _ => Denied("qod")
  private val opa: StatementValidator = _ => Denied("opa")
  private val v                       = new TenantRoutingValidator(qod, opa, _ == "acme")

  private def ctx(tenant: Option[String]) =
    ValidationContext("u", "d", "SELECT 1", "p", poolKey = tenant.map(t => PoolKey(t, "db", "bi")))

  "TenantRoutingValidator" should "send opa tenants to the OPA arm and others to QoD" in:
    v.validate(ctx(Some("acme"))) shouldBe Denied("opa")
    v.validate(ctx(Some("globex"))) shouldBe Denied("qod")

  it should "use the QoD arm when no pool is bound" in:
    v.validate(ctx(None)) shouldBe Denied("qod")
