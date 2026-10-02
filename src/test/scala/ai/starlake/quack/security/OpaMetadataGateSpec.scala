package ai.starlake.quack.security

import ai.starlake.quack.boot.BootFactories
import ai.starlake.quack.edge.adapter.TestArrow
import ai.starlake.quack.edge.config.AclConfig
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.apache.arrow.flight.{FlightRuntimeException, FlightStatusCode}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** System-catalog reads on an `opa` tenant, through the production validator AND the production
  * filter mount ([[BootFactories.metadataFilterRewriter]]), over the real FlightSQL wire.
  *
  * The implicit metadata admit is only safe while the filter that narrows those rows is mounted. On
  * the default `acl.enabled=false` the filter is not mounted, so an `opa` tenant's
  * `information_schema` / `duckdb_tables()` read must reach OPA like any other table read instead
  * of being admitted unfiltered with no decision at all.
  */
class OpaMetadataGateSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  import OpaEdgeFixtures.*

  private val wm                 = new WireMockServer(options().dynamicPort())
  override def beforeAll(): Unit = wm.start()
  override def afterAll(): Unit  = wm.stop()

  private val bob = headers(SecurityFixtures.BobUsername, SecurityFixtures.BobPassword)

  private val MetadataReads =
    List("SELECT * FROM information_schema.tables", "SELECT * FROM duckdb_tables()")

  private def withBoot[A](aclEnabled: Boolean)(
      body: (FlightEdgeHarness.Harness, ConcurrentLinkedQueue[String]) => A
  ): A =
    val fix    = seed(s"http://localhost:${wm.port()}")
    val aclCfg = AclConfig(enabled = aclEnabled, dialect = "duckdb", filteredMetadata = true)
    val seen   = new ConcurrentLinkedQueue[String]()
    val opa    = authorizer(ttlSec = 0)
    val h      = FlightEdgeHarness.boot(
      fix.store,
      wiring = FlightEdgeHarness.RouterWiring(
        validator = validator(opa, aclEnabled = aclEnabled, filteredMetadata = true),
        opa = Some(opa),
        nodeQuery = sql =>
          seen.add(sql)
          TestArrow.okResponse()
        ,
        metadataFilterRewriter = BootFactories.metadataFilterRewriter(aclCfg),
        spawnNodes = true
      )
    )
    try body(h, seen)
    finally h.shutdown()

  private def denyStatements(): Unit =
    wm.resetAll()
    stubConnect(wm, allow)
    stubStatement(wm, deny("no metadata for you"))

  "an opa tenant on acl.enabled=false" should
    "deny information_schema (through OPA) and duckdb_tables() reads under a deny-all OPA" in:
      denyStatements()
      withBoot(aclEnabled = false) { (h, seen) =>
        def denied(sql: String): String =
          withClue(sql) {
            val e = intercept[FlightRuntimeException](query(h, bob, sql))
            e.status().code() shouldBe FlightStatusCode.UNAUTHORIZED
            e.getMessage
          }
        // A gated table read: OPA is asked, and its denial is what the client sees.
        denied(MetadataReads.head) should include("no metadata for you")
        wm.verify(1, statementCalls)
        // Without the implicit admit, a catalog table function is an unresolvable construct,
        // refused fail-closed before OPA (OPA cannot judge what the parser could not resolve).
        denied(MetadataReads(1)) should include("duckdb_tables()")
        // Nothing reached the node.
        seen.asScala.filter(_.contains("tables")) shouldBe empty
      }

  "an opa tenant on acl.enabled=true + filteredMetadata=true" should
    "admit the metadata read without OPA and forward it narrowed by the filter" in:
      denyStatements()
      withBoot(aclEnabled = true) { (h, seen) =>
        query(h, bob, "SELECT * FROM information_schema.tables")
        wm.verify(0, statementCalls)
        val forwarded = seen.asScala.toList.filter(_.contains("information_schema"))
        forwarded should not be empty
        // The filter replaced the bare reference with a derived table narrowed to the system
        // rows (bob holds no grant).
        forwarded.foreach { sql =>
          sql should include(
            "FROM information_schema.tables WHERE (table_schema IN ('information_schema', 'pg_catalog'))"
          )
        }
      }
