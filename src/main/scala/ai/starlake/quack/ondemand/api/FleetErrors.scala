package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.fleet.ServerLiveness
import ai.starlake.quack.ondemand.state.FleetServerRow
import sttp.model.StatusCode

/** Every fleet error body. The sentence (`message`), its facts (`details`) and the suggested fixes
  * (`actions`) are built together here so they cannot drift apart, and every client (CLI, UI, curl,
  * a worker's log) gets the same guidance. `actions` are alternatives: a fix that takes several
  * steps is ONE action whose `note` names the steps after its command. `details("server")` names
  * the fleet server an action `where = "server"` runs on. `retryable = false` marks a refusal no
  * wait can fix: `qod fleet join` exits on one before its first successful heartbeat.
  */
object FleetErrors:
  type Err = (StatusCode, ErrorResponse)

  private def onManager(command: String, note: String = ""): ErrorAction =
    ErrorAction("manager", command, Option(note).filter(_.nonEmpty))

  private def onServer(command: String, note: String = ""): ErrorAction =
    ErrorAction("server", command, Option(note).filter(_.nonEmpty))

  private def err(
      status: StatusCode,
      code: String,
      message: String,
      details: Map[String, String],
      actions: List[ErrorAction],
      retryable: Boolean
  ): Err =
    (
      status,
      ErrorResponse(
        code,
        message,
        Some(details.filter(_._2.nonEmpty)).filter(_.nonEmpty),
        Some(actions).filter(_.nonEmpty),
        Some(retryable)
      )
    )

  private val upgradeQod = onServer(
    "uv tool upgrade qod (or pip install -U qod)",
    "this server's qod speaks another heartbeat version than the manager"
  )

  /** Not a fleet manager: every fleet endpoint, heartbeat included. */
  val disabled: Err = err(
    StatusCode.BadRequest,
    "fleet_disabled",
    "this manager does not run fleet servers (runtimeType is not fleet)",
    Map.empty,
    List(
      onManager("restart the manager with QOD_RUNTIME_TYPE=fleet and QOD_FLEET_JOIN_TOKEN=<token>")
    ),
    retryable = false
  )

  /** The join token never appears in the body, neither the manager's nor the presented one. */
  val unauthorized: Err = err(
    StatusCode.Unauthorized,
    "fleet_unauthorized",
    "invalid or missing X-Fleet-Token: this server's join token does not match the manager's",
    Map.empty,
    List(
      onServer(
        "export QOD_FLEET_JOIN_TOKEN=<the manager's QOD_FLEET_JOIN_TOKEN>",
        "then re-run qod fleet join"
      )
    ),
    retryable = false
  )

  /** The cause (SQL, constraint names, hosts) stays in the manager log, never in the body. */
  val backendError: Err = err(
    StatusCode.BadGateway,
    "backend_error",
    FleetHandlers.StoreError,
    Map.empty,
    List(
      onManager(
        "read the manager log for the 'fleet: ... failed' line",
        "the server retries on its own"
      )
    ),
    retryable = true
  )

  def invalidNodeState(state: String): Err = err(
    StatusCode.BadRequest,
    "invalid_node_state",
    s"unknown node state '$state'; expected one of none, starting, running, failed, stopped",
    Map("state" -> state),
    List(upgradeQod),
    retryable = false
  )

  def invalidStartedAt(value: String): Err = err(
    StatusCode.BadRequest,
    "invalid_started_at",
    s"node.startedAt '$value' is not an ISO-8601 instant",
    Map("startedAt" -> value),
    List(upgradeQod),
    retryable = false
  )

  /** `row` is the registered server, read after the refusal (which writes nothing). */
  def addressChangeRefused(name: String, reported: String, row: Option[FleetServerRow]): Err =
    val registered = row.fold("another address")(r => s"${r.advertiseHost}:${r.nodePort}")
    val drained    = row.exists(_.unschedulable)
    val held       = row.flatMap(_.assignedNodeId)
    val state      = (if drained then "drained" else "not drained") + ", " +
      held.fold("holds no node")(n => s"holds $n")
    err(
      StatusCode.Conflict,
      "address_change_refused",
      s"server '$name' is registered at $registered; this heartbeat reports $reported ($state)",
      Map(
        "server"       -> name,
        "registered"   -> registered,
        "reported"     -> reported,
        "drained"      -> drained.toString,
        "assignedNode" -> held.getOrElse("")
      ),
      List(
        onServer(
          "qod fleet join ... --name <another-name>",
          "if this is a different machine than the registered one"
        ),
        onManager(
          s"qod fleet drain $name",
          "then re-run qod fleet join here if it exited; outside QOD_FLEET_AUTO_APPROVE, " +
            s"qod fleet approve $name once it shows as pending; then qod fleet undrain $name"
        ),
        onManager(
          s"qod fleet remove $name",
          "forgets the server; drain it first if its old qod fleet join still runs"
        )
      ),
      retryable = false
    )

  def sourceChangeRefused(
      name: String,
      source: Option[String],
      row: Option[FleetServerRow],
      autoApprove: String
  ): Err =
    val bound = row.flatMap(_.approvedSource)
    err(
      StatusCode.Conflict,
      "source_change_refused",
      s"server '$name' is approved from ${bound.getOrElse("an unknown address")}; this " +
        s"heartbeat comes from ${source.getOrElse("an unknown address")}, outside " +
        "QOD_FLEET_AUTO_APPROVE",
      Map(
        "server"         -> name,
        "approvedSource" -> bound.getOrElse(""),
        "source"         -> source.getOrElse(""),
        "autoApprove"    -> autoApprove
      ),
      List(
        onManager(
          s"qod fleet drain $name",
          "to move it here: re-run qod fleet join here if it exited, " +
            s"qod fleet approve $name once it shows as pending, then qod fleet undrain $name"
        ),
        onManager(
          s"add ${source.getOrElse("this server's address")} to QOD_FLEET_AUTO_APPROVE",
          "then restart the manager"
        ),
        onServer(
          "qod fleet join ... --name <another-name>",
          "if this is another machine than the approved one"
        )
      ),
      retryable = false
    )

  def approvalUnbound(name: String, source: Option[String], autoApprove: String): Err =
    err(
      StatusCode.Conflict,
      "approval_unbound",
      s"server '$name' was approved before its source address was recorded, and this " +
        s"heartbeat comes from ${source.getOrElse("an unknown address")}, outside " +
        "QOD_FLEET_AUTO_APPROVE",
      Map("server" -> name, "source" -> source.getOrElse(""), "autoApprove" -> autoApprove),
      List(
        onManager(
          s"qod fleet drain $name",
          "then re-run qod fleet join here if it exited, " +
            s"qod fleet approve $name once it shows as pending, then qod fleet undrain $name"
        ),
        onManager(
          s"add ${source.getOrElse("this server's address")} to QOD_FLEET_AUTO_APPROVE",
          "then restart the manager"
        )
      ),
      retryable = false
    )

  def notFound(name: String): Err = err(
    StatusCode.NotFound,
    "not_found",
    s"no such server '$name'",
    Map("server" -> name),
    List(onManager("qod fleet servers", "lists the joined servers and their names")),
    retryable = false
  )

  /** Refused remove: an approved, reachable, schedulable server; its join would re-register it. */
  def serverActive(row: FleetServerRow, liveness: ServerLiveness): Err =
    val live = FleetHandlers.livenessString(liveness)
    val held = row.assignedNodeId.fold("holds no node")(n => s"holds $n")
    err(
      StatusCode.Conflict,
      "server_active",
      s"server '${row.name}' is active ($live, schedulable, $held, last heartbeat " +
        s"${row.silentSeconds}s ago): its qod fleet join would re-register it",
      Map(
        "server"           -> row.name,
        "liveness"         -> live,
        "drained"          -> "false",
        "assignedNode"     -> row.assignedNodeId.getOrElse(""),
        "lastHeartbeatSec" -> row.silentSeconds.toString
      ),
      List(
        onManager(
          s"qod fleet drain ${row.name}",
          s"then stop qod fleet join on ${row.name}, then qod fleet remove ${row.name}"
        )
      ),
      retryable = false
    )

  def sourceUnknown(row: FleetServerRow, trustedProxies: String): Err = err(
    StatusCode.Conflict,
    "source_unknown",
    s"server '${row.name}' has no known source address yet (last heartbeat " +
      s"${row.silentSeconds}s ago); approve it after its next heartbeat",
    Map(
      "server"           -> row.name,
      "lastHeartbeatSec" -> row.silentSeconds.toString,
      "trustedProxies"   -> trustedProxies
    ),
    List(
      onManager(s"qod fleet approve ${row.name}", "again, after its next heartbeat"),
      onManager(
        "set QOD_FLEET_TRUSTED_PROXIES to the proxy's address",
        "when the manager sits behind a proxy that sends X-Forwarded-For"
      )
    ),
    retryable = true
  )
