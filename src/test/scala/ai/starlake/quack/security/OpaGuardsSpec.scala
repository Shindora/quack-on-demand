package ai.starlake.quack.security

import ai.starlake.quack.edge.cls.{ColumnCatalog, ColumnPolicyRewriter}
import ai.starlake.quack.edge.policy.ProtectedWriteGuard
import ai.starlake.quack.model.{Pool, RoleDistribution, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.state.{RbacRole, RoleColumnPolicy}
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.apache.arrow.flight.{FlightRuntimeException, FlightStatusCode}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** OPA decides table access only: an OPA that allows everything must not switch off the edge's
  * other guards. Over the real FlightSQL wire, with the production validator and the production
  * lockdown wiring (`sup.effectiveLockdown`, `sup.duckLakeBuckets()`), `bob` (opa tenant, no grant)
  * holds a QoD role `masked` that carries a REAL column policy masking `orders.email`.
  */
class OpaGuardsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  import OpaEdgeFixtures.*

  private val wm                 = new WireMockServer(options().dynamicPort())
  override def beforeAll(): Unit = wm.start()
  override def afterAll(): Unit  = wm.stop()

  private val MaskedRoleId = "r-masked01"
  private val LakeBucket   = "lakebucket"

  /** One catalog shared by the SELECT-path rewriter and the write-path guard, as in production. */
  private val catalog =
    new ColumnCatalog.MapCatalog(Map(("memory", "main", "orders") -> List("id", "email")))

  private def withBoot[A](lockdown: Boolean)(body: FlightEdgeHarness.Harness => A): A =
    val fix = seed(s"http://localhost:${wm.port()}")
    val s   = fix.store
    s.upsertRole(
      RbacRole(id = MaskedRoleId, tenantId = SecurityFixtures.TenantId, name = "masked")
    )
    s.insertColumnPolicy(
      RoleColumnPolicy(
        id = "cp-orders-email",
        roleId = MaskedRoleId,
        catalogName = "*",
        schemaName = "main",
        tableName = "orders",
        columnName = "email",
        action = RoleColumnPolicy.ActionMask,
        transformSql = Some("'***'")
      )
    )
    s.addUserRole(fix.bobUserId, MaskedRoleId)
    // A DuckLake tenant-db whose data lives in `lakebucket`: the bucket sup.duckLakeBuckets()
    // reports and the lockdown screen denies.
    s.upsertTenantDb(
      TenantDb(
        id = "td-lake0001",
        tenantId = SecurityFixtures.TenantId,
        name = "acme_lake",
        kind = TenantDbKind.DuckLake,
        metastore = Map.empty,
        dataPath = s"s3://$LakeBucket/acme_lake/"
      )
    )
    if lockdown then
      s.upsertPool(
        Pool(
          id = SecurityFixtures.PoolId,
          tenantId = SecurityFixtures.TenantId,
          tenantDbId = SecurityFixtures.TenantDbId,
          name = SecurityFixtures.PoolName,
          size = 1,
          distribution = RoleDistribution(writeonly = 0, readonly = 0, dual = 1),
          maxConcurrentPerNode = 0,
          disabled = false,
          lockdown = Some(true)
        )
      )
    val opa = authorizer()
    val h   = FlightEdgeHarness.boot(
      s,
      wiring = FlightEdgeHarness.RouterWiring(
        validator = validator(opa),
        opa = Some(opa),
        nodeQuery = duckNode(OrdersSetup),
        columnPolicyRewriter = new ColumnPolicyRewriter(catalog, enabled = true),
        protectedWriteGuard =
          new ProtectedWriteGuard(catalog, clsEnabled = true, rlsEnabled = true),
        lockdownFor = sup => sup.effectiveLockdown,
        deniedBuckets = sup => () => sup.duckLakeBuckets(),
        spawnNodes = true
      )
    )
    try body(h)
    finally h.shutdown()

  private val bob = headers(SecurityFixtures.BobUsername, SecurityFixtures.BobPassword)

  private def allowEverything(): Unit =
    wm.resetAll()
    stubConnect(wm, allow)
    stubStatement(wm, allow)

  "an allow-everything OPA" should "not lift lockdown: a DuckLake bucket read is denied" in:
    allowEverything()
    withBoot(lockdown = true) { h =>
      h.supervisor.duckLakeBuckets() should contain(LakeBucket)
      val e = intercept[FlightRuntimeException](
        query(h, bob, s"SELECT * FROM read_parquet('s3://$LakeBucket/acme_lake/x.parquet')")
      )
      e.status().code() shouldBe FlightStatusCode.UNAUTHORIZED
      e.getMessage should include("lockdown")
      e.getMessage should include(LakeBucket)
      // The lockdown screen runs before the validator: OPA was never asked about the statement.
      wm.verify(0, statementCalls)
    }

  it should "not lift column masking: a role's mask policy masks the column in the result" in:
    allowEverything()
    withBoot(lockdown = false) { h =>
      val rows = query(h, bob, "SELECT id, email FROM orders")
      rows.map(_("id")) shouldBe List("1", "2")
      rows.map(_("email")) shouldBe List("***", "***")
      wm.verify(1, statementCalls)
    }

  it should "not lift the protected-write guard: a CTAS laundering the masked column is refused" in:
    allowEverything()
    withBoot(lockdown = false) { h =>
      val e = intercept[FlightRuntimeException](
        query(h, bob, "CREATE TABLE main.scratch AS SELECT email FROM orders")
      )
      e.status().code() shouldBe FlightStatusCode.UNAUTHORIZED
      e.getMessage should include("cannot be read into a write statement")
      // OPA allowed the statement; the denial is the guard's.
      wm.verify(1, statementCalls)
      wm.verify(
        statementCalls.withRequestBody(matchingJsonPath("$.input.statement.class", equalTo("DDL")))
      )
    }
