package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.PatPrincipal
import sttp.model.StatusCode

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Resolves a REST handler's `apiKey` seam (the `Endpoints.authToken` input: `X-API-Key` header,
  * else the `qod_session` cookie) into the [[ExecCaller]] a handler hands the routed executor. It
  * is the one place the catalog preview / data-diff / undrop / restore handlers learn who is
  * running their statement, so it mirrors the credentials `ManagerServer.apiKeyGuard` admits:
  *
  *   - the configured static key (constant-time compare, like `SessionScope.failClosed`): the
  *     synthetic superuser, unrestricted;
  *   - a session JWT: its username, unrestricted (a login session carries no token restriction);
  *   - a personal access token: its owner's username, the PAT's own `TokenRestriction` and its id,
  *     the same shape `McpDataTools.callerFor` builds, so per-table ACL, column masking, row
  *     filtering and the token's pools / maxRows / branchOnly all apply;
  *   - any other token (unknown, expired, revoked, a session that died between the guard and the
  *     handler): 401 `unauthorized`. Never the superuser: the executor must not be called.
  *
  * `None` is the static key. Over REST the guard never lets a credential-less request through, and
  * the MCP layer curries `None` for its own static-key principal (`McpPrincipal.rawToken`), which
  * is exactly how every handler gate ([[TenantScopeCheck]], [[TenantDbGate]]) already reads it. A
  * token string is never mistaken for `None`: an unresolvable one is refused.
  */
final class RestCaller(
    staticKey: Option[String],
    sessionOf: String => Option[SessionTokenStore.Session],
    patOf: String => Option[PatPrincipal]
):
  private val staticBytes =
    staticKey.filter(_.nonEmpty).map(_.getBytes(StandardCharsets.UTF_8))

  /** Constant-time match against the configured static key; an empty key never matches. */
  def isStaticKey(token: String): Boolean =
    staticBytes.exists(k => MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), k))

  def apply(
      connectionId: String,
      apiKey: Option[String]
  ): Either[(StatusCode, ErrorResponse), ExecCaller] =
    apiKey match
      case None                      => Right(RestCaller.superuser(connectionId))
      case Some(t) if isStaticKey(t) => Right(RestCaller.superuser(connectionId))
      case Some(t)                   =>
        sessionOf(t) match
          case Some(s) => Right(ExecCaller.unrestricted(connectionId, s.profile.username))
          case None    =>
            patOf(t) match
              case Some(p) =>
                Right(ExecCaller(connectionId, p.user.username, p.restriction, Some(p.patId)))
              case None => Left(RestCaller.Unauthorized)

object RestCaller:

  /** A resolver that knows no credential but the static key (`None`): every token is refused. */
  val staticOnly: RestCaller = new RestCaller(None, _ => None, _ => None)

  def apply(
      staticKey: Option[String] = None,
      sessionOf: String => Option[SessionTokenStore.Session] = _ => None,
      patOf: String => Option[PatPrincipal] = _ => None
  ): RestCaller = new RestCaller(staticKey, sessionOf, patOf)

  val Unauthorized: (StatusCode, ErrorResponse) =
    StatusCode.Unauthorized -> ErrorResponse(
      "unauthorized",
      "the credential does not resolve to a live session, token or the static key"
    )

  private def superuser(connectionId: String): ExecCaller =
    ExecCaller.system(connectionId)
