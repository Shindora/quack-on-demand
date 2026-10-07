package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.TokenRestriction

/** Who is running a statement through the routed executor.
  *
  * This is a value type rather than three loose parameters so that adding a call site forces an
  * explicit decision about the restriction. The alternative considered was pinning an already
  * attenuated EffectiveSet on a connection context, which needs no signature change but makes the
  * security property rest on remembering to attenuate first. Here, omitting it does not compile.
  *
  * `patId` is set for a PAT bearer by [[RestCaller]] (the catalog preview, data-diff, undrop and
  * restore REST endpoints) and by `McpDataTools.callerFor` (MCP). The routed executor
  * (`Main.routedExecutor`) threads it into `FlightSqlRouter.execute`, so the statement-history and
  * audit rows and the `ActiveStatementRegistry` entry carry it: that is what lets a revoke of the
  * token kill the statement. System and session callers carry `None`.
  *
  * `system` is the ONLY privilege signal: a system caller gets the synthetic superuser EffectiveSet
  * in the routed executor (no ACL, no CLS/RLS, never attenuated). It is set only by
  * [[ExecCaller.system]] at trusted internal sites (the static key, the restore dry run, the branch
  * change counter). `identity` is a label for logs and history; user names are not reserved (a
  * tenant may have a user called "superuser"), so nothing may decide privilege from it.
  */
final case class ExecCaller(
    connectionId: String,
    identity: String,
    restriction: TokenRestriction,
    patId: Option[String] = None,
    system: Boolean = false
):
  /** The row cap actually applied: the server cap, the token's cap and the request's, smallest
    * wins. A token can lower the cap and can never raise it.
    */
  def effectiveMaxRows(serverCap: Int, requested: Int): Int =
    (List(serverCap, requested) ++ restriction.maxRows.toList).min.max(1)

object ExecCaller:

  /** A trusted internal caller: the static key or a system leg. Labelled with
    * [[CatalogPreviewHandlers.SuperuserIdentity]] for logs; the privilege is the flag.
    */
  def system(connectionId: String): ExecCaller =
    ExecCaller(
      connectionId,
      CatalogPreviewHandlers.SuperuserIdentity,
      TokenRestriction.Unrestricted,
      system = true
    )

  def unrestricted(connectionId: String, identity: String): ExecCaller =
    ExecCaller(connectionId, identity, TokenRestriction.Unrestricted)
