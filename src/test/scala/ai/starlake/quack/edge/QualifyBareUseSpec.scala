package ai.starlake.quack.edge

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A bare one-part `USE x` must be qualified with the tenant-db name: the node session's current
  * catalog is the transient memory db, so the raw statement fails with "No catalog + schema named x
  * found" even though `<db>.x` exists, and clients (starlake, BI tools) do not know the physical db
  * name. Qualified, catalog-switching, and non-USE statements pass through untouched.
  */
class QualifyBareUseSpec extends AnyFlatSpec with Matchers:

  private val db = Some("saleh_default")

  "qualifyBareUse" should "qualify a bare one-part USE with the tenant db" in {
    FlightSqlRouter.qualifyBareUse(db, "USE star1") shouldBe "USE saleh_default.star1"
    FlightSqlRouter.qualifyBareUse(db, "use audit;") shouldBe "USE saleh_default.audit"
    FlightSqlRouter.qualifyBareUse(db, "  USE  star1  ") shouldBe "USE saleh_default.star1"
  }

  it should "preserve quoting on the schema name" in {
    FlightSqlRouter.qualifyBareUse(db, "USE \"My Schema\"") shouldBe
      "USE saleh_default.\"My Schema\""
  }

  it should "leave two-part USE untouched" in {
    val sql = "USE saleh_default.star1"
    FlightSqlRouter.qualifyBareUse(db, sql) shouldBe sql
  }

  it should "leave catalog switching untouched" in {
    FlightSqlRouter.qualifyBareUse(db, "USE saleh_default") shouldBe "USE saleh_default"
    FlightSqlRouter.qualifyBareUse(db, "USE SALEH_DEFAULT") shouldBe "USE SALEH_DEFAULT"
    FlightSqlRouter.qualifyBareUse(db, "USE memory") shouldBe "USE memory"
  }

  it should "pass through when the pool has no db name" in {
    FlightSqlRouter.qualifyBareUse(None, "USE star1") shouldBe "USE star1"
  }

  it should "not touch statements that merely start with use-like text" in {
    val sql = "USE star1 EXTRA"
    FlightSqlRouter.qualifyBareUse(db, sql) shouldBe sql
  }
