package ai.starlake.quack.ondemand.telemetry

import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*
import io.circe.{Encoder, Json}

import java.time.Instant

/** telemetry.auditSink = stdout: tees every audit and statement event to stdout as one JSON line,
  * then hands it to the wrapped store. Wrapping the store catches both write paths (the synchronous
  * [[AuditRecorder]] and the async [[EventJournal]]). The line goes out BEFORE the store append so
  * a Postgres outage drops the row, not the log line. `qodEvent` (`audit` | `statement`) sets these
  * lines apart from regular log output for a log shipper to route. Statement SQL is emitted as
  * captured (capped at 500 chars upstream); literal redaction is not applied.
  */
final class StdoutTelemetrySink(inner: TelemetryStore, out: String => Unit = println)
    extends TelemetryStore:
  import StdoutTelemetrySink.{line, given}

  def enabled: Boolean = inner.enabled

  def appendAudit(events: List[AuditEvent]): Unit =
    events.foreach(e => out(line("audit", e.asJson)))
    inner.appendAudit(events)

  def appendStatements(events: List[StatementEvent]): Unit =
    events.foreach(e => out(line("statement", e.asJson)))
    inner.appendStatements(events)

  def listAudit(q: AuditQuery): List[AuditRow]                = inner.listAudit(q)
  def purgeAudit(olderThan: Instant): Int                     = inner.purgeAudit(olderThan)
  def searchStatements(q: StatementQuery): List[StatementRow] = inner.searchStatements(q)
  def purgeStatements(olderThan: Instant): Int                = inner.purgeStatements(olderThan)
  def rollupWatermark(): Option[Instant]                      = inner.rollupWatermark()
  def recomputeRollups(fromExclusive: Option[Instant], toInclusive: Instant): Unit =
    inner.recomputeRollups(fromExclusive, toInclusive)
  def advanceRollupWatermark(to: Instant): Unit                  = inner.advanceRollupWatermark(to)
  def queryRollups(q: RollupQuery): List[RollupBucket]           = inner.queryRollups(q)
  def purgeRollups(granularity: String, olderThan: Instant): Int =
    inner.purgeRollups(granularity, olderThan)
  def queryUsage(q: UsageQuery): UsageResult = inner.queryUsage(q)
  override def close(): Unit                 = inner.close()

object StdoutTelemetrySink:
  private given Encoder[AuditEvent]     = deriveEncoder
  private given Encoder[StatementEvent] = deriveEncoder

  private def line(kind: String, body: Json): String =
    body.mapObject(("qodEvent" -> Json.fromString(kind)) +: _).dropNullValues.noSpaces
