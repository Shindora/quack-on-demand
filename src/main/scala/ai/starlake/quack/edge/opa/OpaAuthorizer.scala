package ai.starlake.quack.edge.opa

import ai.starlake.acl.parser.TableAccess
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.observability.metrics.OpaInstruments
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json

/** Composes input building, the decision cache, the HTTP client and metrics. One instance per
  * manager; tenant settings are read from the `Tenant` passed on every call, never cached here.
  */
final class OpaAuthorizer(
    cfg: OpaConfig,
    client: OpaClient,
    cache: OpaDecisionCache,
    metrics: OpaInstruments
) extends LazyLogging:

  def mode(tenant: Tenant): String = tenant.acl.effectiveMode(cfg.defaultMode)

  def connect(
      tenant: Tenant,
      target: OpaTarget,
      user: OpaUser,
      userId: String,
      edge: String
  ): Decision =
    run(tenant, "connect", OpaInput.connect(target, user, edge), Set.empty, target, user, userId)

  def statement(
      tenant: Tenant,
      target: OpaTarget,
      user: OpaUser,
      userId: String,
      edge: String,
      statementClass: String,
      accesses: Set[TableAccess],
      text: String
  ): Decision =
    val input = OpaInput.statement(
      target,
      user,
      edge,
      statementClass,
      accesses,
      Option.when(tenant.acl.sendStatementText)(text)
    )
    run(tenant, "statement", input, accesses, target, user, userId)

  /** Admin dry-run: same transport and parsing, never cached, never counted. */
  def dryRun(tenant: Tenant, input: Json, rule: String, accesses: Set[TableAccess]): Decision =
    tenant.acl.effectiveUrl(cfg.url) match
      case None      => Decision.Error(s"no OPA URL configured for tenant '${tenant.id}'")
      case Some(url) =>
        client.query(
          OpaClient.endpoint(url, tenant.acl.effectivePolicyPath, rule),
          tenant.acl.opaToken,
          input,
          accesses
        )

  private def fingerprint(tenant: Tenant, url: String): String =
    val tokenFp = tenant.acl.opaToken
      .map(t => java.util.Arrays.hashCode(t.getBytes("UTF-8")).toString)
      .getOrElse("")
    s"$url|${tenant.acl.effectivePolicyPath}|$tokenFp|${tenant.acl.sendStatementText}"

  private def run(
      tenant: Tenant,
      kind: String,
      input: Json,
      accesses: Set[TableAccess],
      target: OpaTarget,
      user: OpaUser,
      userId: String
  ): Decision =
    tenant.acl.effectiveUrl(cfg.url) match
      case None =>
        metrics.record(tenant.id, kind, "error", 0L)
        Decision.Error(s"no OPA URL configured for tenant '${tenant.id}'")
      case Some(url) =>
        val key = OpaCacheKey(
          tenantId = tenant.id,
          settings = fingerprint(tenant, url),
          userId = userId,
          roles = user.roles.sorted,
          groups = user.groups.sorted,
          claims = user.claims.toList.sorted,
          pool = s"${target.tenant}/${target.tenantDb}/${target.pool}",
          kind = kind,
          accesses =
            OpaInput.sorted(accesses).map(a => s"${a.table.canonical}:${OpaInput.verbName(a.verb)}")
        )
        val started         = System.nanoTime()
        val (decision, hit) = cache.getOrCompute(key) {
          client.query(
            OpaClient.endpoint(url, tenant.acl.effectivePolicyPath, kind),
            tenant.acl.opaToken,
            input,
            accesses
          )
        }
        val outcome =
          if hit then "cache"
          else
            decision match
              case _: Decision.Allow => "allow"
              case _: Decision.Deny  => "deny"
              case _: Decision.Error => "error"
        metrics.record(tenant.id, kind, outcome, System.nanoTime() - started)
        decision match
          case Decision.Error(cause) =>
            logger.warn(s"OPA $kind error for tenant=${tenant.id}: $cause")
          case d => logger.debug(s"OPA $kind tenant=${tenant.id} user=${user.name} -> $d")
        decision
