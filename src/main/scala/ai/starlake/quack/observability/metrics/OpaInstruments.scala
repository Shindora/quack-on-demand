package ai.starlake.quack.observability.metrics

import io.micrometer.core.instrument.{MeterRegistry, Timer}
import java.time.Duration

/** `qod_opa_requests_total{tenant,kind,outcome}` (outcome = allow | deny | error | cache) and
  * `qod_opa_request_seconds{tenant,kind}` (network calls only).
  */
final class OpaInstruments(registry: MeterRegistry):
  private val timers = new java.util.concurrent.ConcurrentHashMap[(String, String), Timer]()

  def record(tenant: String, kind: String, outcome: String, durationNanos: Long): Unit =
    registry
      .counter("qod_opa_requests_total", "tenant", tenant, "kind", kind, "outcome", outcome)
      .increment()
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
