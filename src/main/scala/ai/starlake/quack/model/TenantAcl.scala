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

  def isOpa(managerDefault: String): Boolean = effectiveMode(managerDefault) == TenantAcl.Opa

  def effectiveUrl(managerUrl: String): Option[String] =
    opaUrl.orElse(Option(managerUrl).map(_.trim).filter(_.nonEmpty))

  def effectivePolicyPath: String = opaPolicyPath.getOrElse(TenantAcl.DefaultPolicyPath)

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
