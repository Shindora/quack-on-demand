package ai.starlake.quack.model

import java.net.URI
import scala.util.Try

/** Per-tenant data-access authorization settings. `mode` None means the manager default
  * (`QOD_ACL_MODE`). In `opa` mode the tenant's OPA is authoritative for pool access and
  * table-level statement decisions; QoD's grant tables are ignored for that tenant. `opaToken` is a
  * secret: it never leaves the control plane (responses expose only whether it is set).
  */
final case class TenantAcl(
    mode: Option[String] = None,
    opaUrl: Option[String] = None,
    opaPolicyPath: Option[String] = None,
    opaToken: Option[String] = None,
    sendStatementText: Boolean = false
):
  def effectiveMode(managerDefault: String): String = mode.getOrElse(managerDefault)

  /** Fail closed: anything other than exactly `qod` (e.g. a stray `OPA` written by direct SQL)
    * routes to OPA, which refuses when no OPA is configured, rather than to QoD grants.
    */
  def isOpa(managerDefault: String): Boolean = effectiveMode(managerDefault) != TenantAcl.Qod

  def effectiveUrl(managerUrl: String): Option[String] =
    opaUrl.orElse(Option(managerUrl).map(_.trim).filter(_.nonEmpty))

  def effectivePolicyPath: String = opaPolicyPath.getOrElse(TenantAcl.DefaultPolicyPath)

  /** Redact `opaToken` so no `s"$tenant"` / `s"$acl"` log line (or exception message built from
    * one) can ever print the secret. Case-class equality, `hashCode` and `copy` are untouched --
    * Scala only synthesizes those from the constructor fields, never from `toString`.
    */
  override def toString: String =
    s"TenantAcl(mode=$mode,opaUrl=$opaUrl,opaPolicyPath=$opaPolicyPath,opaToken=${opaToken
        .map(_ => FederatedSecret.RedactedMarker)},sendStatementText=$sendStatementText)"

object TenantAcl:
  val Qod                       = "qod"
  val Opa                       = "opa"
  val ValidModes: Set[String]   = Set(Qod, Opa)
  val DefaultPolicyPath: String = "qod/authz"

  private val PolicyPathRe = "^[A-Za-z0-9_]+(/[A-Za-z0-9_]+)*$".r

  /** Slash-separated identifiers only: the path is spliced into `/v1/data/<path>/<rule>`. */
  def validPolicyPath(p: String): Boolean = PolicyPathRe.matches(p)

  def validUrl(u: String): Boolean =
    Try(URI(u)).toOption.exists { uri =>
      val scheme = Option(uri.getScheme).map(_.toLowerCase)
      (scheme.contains("http") || scheme.contains("https")) && Option(uri.getHost).nonEmpty
    }

/** A partial update of [[TenantAcl]]: an omitted field (None) keeps the stored value, `Some("")`
  * clears it (mode `""` = the manager default, opaToken `""` = no token).
  */
final case class TenantAclPatch(
    mode: Option[String] = None,
    opaUrl: Option[String] = None,
    opaPolicyPath: Option[String] = None,
    opaToken: Option[String] = None,
    sendStatementText: Option[Boolean] = None
)
