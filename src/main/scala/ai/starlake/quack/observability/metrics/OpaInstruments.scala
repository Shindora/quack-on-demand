package ai.starlake.quack.observability.metrics

import io.micrometer.core.instrument.{MeterRegistry, Timer}
import java.time.Duration

/** `qod_opa_requests_total{tenant,kind,outcome}` (outcome = allow | deny | error | cache) and
  * `qod_opa_request_seconds{tenant,kind}` (network calls only).
  */
final class OpaInstruments(registry: MeterRegistry):
  private val timers = new java.util.concurrent.ConcurrentHashMap[(String, String), Timer]()

  /** Counter only, no timer sample. Used for outcomes that never reached the network (e.g. no OPA
    * URL configured for the tenant), so `qod_opa_request_seconds` stays a network-only histogram.
    */
  def count(tenant: String, kind: String, outcome: String): Unit =
    registry
      .counter("qod_opa_requests_total", "tenant", tenant, "kind", kind, "outcome", outcome)
      .increment()

  def record(tenant: String, kind: String, outcome: String, durationNanos: Long): Unit =
    count(tenant, kind, outcome)
    if outcome != "cache" then
      timers
        .computeIfAbsent(
          (tenant, kind),
          _ =>
            Timer
              .builder("qod_opa_request_seconds")
              .tag("tenant", tenant)
              .tag("kind", kind)
              .publishPercentileHistogram()
              .register(registry)
        )
        .record(Duration.ofNanos(durationNanos))

object OpaInstruments:
  val noop = new OpaInstruments(
    new io.micrometer.core.instrument.composite.CompositeMeterRegistry()
  )
