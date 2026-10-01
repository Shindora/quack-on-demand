package ai.starlake.quack.security

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.opa.OpaInput
import ai.starlake.quack.edge.sql.{Denied, StatementValidator, ValidationContext}
import ai.starlake.quack.model.{PoolKey, StatementKind, Tenant, TenantAcl}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.route.StatementClassifier
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters.*

/** Parser parity: for every statement of the corpus, the `accesses` an opa tenant's OPA receives
  * equal the `unauthorized` set QoD's own [[PostgresAclValidator]] reports for a principal with
  * zero grants, under the same [[ValidationContext]]. Both arms are reached through the production
  * `TenantRoutingValidator` ([[BootFactories.aclValidator]]): the tenant is first in opa mode, then
  * flipped to qod mode, and the same contexts are validated again.
  */
class OpaParserParitySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  import OpaEdgeFixtures.*

  private val wm                 = new WireMockServer(options().dynamicPort())
  override def beforeAll(): Unit = wm.start()
  override def afterAll(): Unit  = wm.stop()

  private val poolKey =
    PoolKey(SecurityFixtures.TenantId, SecurityFixtures.TenantDbName, SecurityFixtures.PoolName)

  /** Session catalogs the pool's nodes attach: the session catalog and one attached catalog. */
  private val Attached = Set("memory", "ext")

  private val corpus: List[(String, String)] = List(
    "join" -> "SELECT o.id FROM orders o JOIN customers c ON o.id = c.id JOIN items i ON i.id = o.id",
    "CTE shadowing a table name" ->
      "WITH orders AS (SELECT 1 AS id) SELECT * FROM orders JOIN customers c ON c.id = orders.id",
    "INSERT ... SELECT" -> "INSERT INTO archive SELECT * FROM orders",
    "MERGE"             ->
      ("MERGE INTO target t USING src s ON t.id = s.id " +
        "WHEN MATCHED THEN UPDATE SET v = s.v WHEN NOT MATCHED THEN INSERT VALUES (s.id, s.v)"),
    "FROM shorthand"                   -> "FROM orders",
    "attached-catalog three-part name" -> "SELECT * FROM ext.main.t",
    "UPDATE ... FROM"                  ->
      "UPDATE orders SET email = c.email FROM customers c WHERE orders.id = c.id"
  )

  private def classOf(sql: String): String = StatementClassifier.default.classify(sql) match
    case StatementKind.Ddl => "DDL"
    case StatementKind.Dml => "WRITE"
    case _                 => "READ"

  private def ctx(sql: String, eff: EffectiveSet): ValidationContext =
    ValidationContext(
      username = SecurityFixtures.BobUsername,
      database = poolKey.toString,
      statement = sql,
      peer = "parity",
      defaultDatabase = Some("memory"),
      defaultSchema = Some("main"),
      effectiveSet = Some(eff),
      attachedCatalogs = Attached,
      poolKey = Some(poolKey),
      edge = "flightsql",
      statementClass = classOf(sql)
    )

  type Access = (String, String, String, String)

  /** The accesses of the last OPA statement request, or None when OPA was not asked. */
  private def lastOpaAccesses(): Option[Set[Access]] =
    wm.getAllServeEvents.asScala.toList
      .filter(_.getRequest.getUrl == StatementPath)
      .headOption // newest first
      .map { ev =>
        val json = parse(ev.getRequest.getBodyAsString).toOption.get
        json.hcursor
          .downField("input")
          .downField("accesses")
          .focus
          .flatMap(_.asArray)
          .getOrElse(Vector.empty)
          .map { a =>
            val c = a.hcursor
            (
              c.get[String]("catalog").toOption.get,
              c.get[String]("schema").toOption.get,
              c.get[String]("table").toOption.get,
              c.get[String]("verb").toOption.get
            )
          }
          .toSet
      }

  "OPA's accesses" should "equal QoD's unauthorized set for a zero-grant principal" in:
    val fix = seed(s"http://localhost:${wm.port()}")
    val sup = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, fix.store)
    sup.restore()
    val eff = sup.effectiveSetForUser(fix.bobUserId).get
    eff.permissions shouldBe empty
    val v: StatementValidator = validator(authorizer(ttlSec = 0))(sup)

    // 1. Tenant in opa mode: record what OPA receives (it denies, which does not matter here).
    val opaSide: Map[String, Option[Set[Access]]] = corpus.map { (name, sql) =>
      wm.resetAll()
      stubStatement(wm, deny("parity"))
      v.validate(ctx(sql, eff))
      name -> lastOpaAccesses()
    }.toMap

    // 2. Same tenant flipped to qod mode: the QoD arm's unauthorized set for the same contexts.
    fix.store.upsertTenant(
      Tenant(
        id = SecurityFixtures.TenantId,
        displayName = SecurityFixtures.TenantName,
        authProvider = "db",
        acl = TenantAcl(Some("qod"))
      )
    )
    sup.restore()
    wm.resetAll()
    val qodSide: Map[String, Set[Access]] = corpus.map { (name, sql) =>
      name -> (v.validate(ctx(sql, eff)) match
        case Denied(_, unauthorized, _) =>
          unauthorized.map(a =>
            (a.table.database, a.table.schema, a.table.table, OpaInput.verbName(a.verb))
          )
        case other => fail(s"$name: QoD arm did not deny a zero-grant principal: $other"))
    }.toMap
    wm.getAllServeEvents.asScala shouldBe empty

    // Every statement of the corpus parses without a Refuse, so OPA is asked about each one (a
    // refused statement would be denied by both arms before any OPA call, with nothing to compare).
    corpus.foreach { (name, _) =>
      withClue(s"$name: ") {
        val opaAccesses = opaSide(name).getOrElse(fail("OPA was never asked (parser refused?)"))
        opaAccesses should not be empty
        opaAccesses shouldBe qodSide(name)
        info(s"$name -> ${opaAccesses.toList.sorted.mkString(", ")}")
      }
    }
