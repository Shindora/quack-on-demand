package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.TelemetryConfig
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class StdoutTelemetrySinkSpec extends AnyFlatSpec with Matchers:

  private val ts = Instant.parse("2026-10-08T00:00:00Z")

  private val audit = AuditEvent(
    ts,
    "data-denial",
    "alice",
    "tenant",
    Some("acme"),
    "sql.denied",
    Some("sales.orders"),
    "denied",
    "flightsql",
    Map("verb" -> "Read")
  )

  private def stmt(i: Int) =
    StatementEvent(ts, "alice", "acme", "p1", "n1", s"SELECT $i\nFROM t", 3L, None, "ok", None)

  private def sink() =
    val lines = scala.collection.mutable.ListBuffer.empty[String]
    val inner = new RecordingTelemetryStore
    (new StdoutTelemetrySink(inner, lines += _), inner, lines)

  "StdoutTelemetrySink" should "write one tagged JSON line per audit event and forward it to the store" in {
    val (s, inner, lines) = sink()
    s.appendAudit(List(audit))

    lines should have size 1
    val json = parse(lines.head).toOption.get.hcursor
    json.get[String]("qodEvent") shouldBe Right("audit")
    json.get[String]("actor") shouldBe Right("alice")
    json.get[String]("outcome") shouldBe Right("denied")
    json.downField("detail").get[String]("verb") shouldBe Right("Read")
    json.downField("patId").succeeded shouldBe false
    inner.events.toList shouldBe List(audit)
  }

  it should "receive exactly one single-line event per statement drained by the journal" in {
    val (s, inner, lines) = sink()
    val journal           = new EventJournal(s)
    (1 to 3).foreach(i => journal.offerStatement(stmt(i)))
    journal.drainNow()

    lines should have size 3
    lines.foreach(_ should not include "\n")
    lines.map(l => parse(l).toOption.get.hcursor.get[String]("sql").toOption.get) shouldBe
      (1 to 3).map(i => s"SELECT $i\nFROM t")
    lines.map(l => parse(l).toOption.get.hcursor.get[String]("qodEvent")).distinct shouldBe
      List(Right("statement"))
    inner.searchStatements(StatementQuery()) should have size 3
  }

  it should "emit the line even when the store append fails" in {
    val (s, inner, lines) = sink()
    inner.failNext = true
    a[RuntimeException] should be thrownBy s.appendAudit(List(audit))
    lines should have size 1
  }

  "TelemetryConfig.auditSink" should "refuse an unknown sink and a sink without a store" in {
    TelemetryConfig(auditSink = "stdout").auditSink shouldBe "stdout"
    an[IllegalArgumentException] should be thrownBy TelemetryConfig(auditSink = "kafka")
    an[IllegalArgumentException] should be thrownBy
      TelemetryConfig(store = "none", auditSink = "stdout")
  }
