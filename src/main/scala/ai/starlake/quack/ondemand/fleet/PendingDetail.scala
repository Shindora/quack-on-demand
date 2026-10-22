package ai.starlake.quack.ondemand.fleet

import ai.starlake.quack.ondemand.state.FleetServerRow

/** The sentence behind a pending fleet slot: what every server is doing and the next command. Pure:
  * the caller passes the server listing and its liveness classifier.
  */
object PendingDetail:
  private val MaxNames = 5
  // Display order, and the order a server's state is decided in (first match wins).
  private val States = List("pending approval", "drained", "unreachable", "busy", "free")

  def describe(
      reason: String,
      rows: List[FleetServerRow],
      liveness: FleetServerRow => ServerLiveness,
      requiredMemoryBytes: Option[Long]
  ): String =
    val largestFree = rows
      .filter(r => stateOf(r, liveness) == "free" && r.memoryBytes.isDefined)
      .maxByOption(_.memoryBytes.get)
    (reason, requiredMemoryBytes, largestFree) match
      case ("none_fits", Some(need), Some(big)) =>
        s"needs ${gib(need)}; the largest free server (${big.name}) reports " +
          s"${gib(big.memoryBytes.get)}: add a server with more memory or lower the pool's " +
          "memory request"
      case _ => noneFree(rows, liveness)

  private def stateOf(r: FleetServerRow, liveness: FleetServerRow => ServerLiveness): String =
    if !r.approved then "pending approval"
    else if r.unschedulable then "drained"
    else if liveness(r) != ServerLiveness.Reachable then "unreachable"
    else if r.assignedNodeId.isDefined then "busy"
    else "free" // taken by a concurrent claim between the miss and this listing

  private def noneFree(rows: List[FleetServerRow], liveness: FleetServerRow => ServerLiveness) =
    if rows.isEmpty then "no server has joined the fleet: run qod fleet join on a server"
    else
      val byState = rows.groupBy(stateOf(_, liveness))
      val parts   = States.flatMap(s => byState.get(s).map(rs => s"${rs.size} $s (${names(rs)})"))
      val steps   = States.flatMap(s => byState.get(s).flatMap(rs => step(s, rs.map(_.name).min)))
      val plural  = if rows.size == 1 then "" else "s"
      s"${rows.size} server$plural: ${parts.mkString(", ")}: " +
        (steps :+ "join another server").mkString(", or ")

  private def step(state: String, name: String): Option[String] = state match
    case "pending approval" => Some(s"qod fleet approve $name")
    case "drained"          => Some(s"qod fleet undrain $name")
    case "unreachable"      => Some(s"check qod fleet join on $name")
    case _                  => None

  private def names(rs: List[FleetServerRow]): String =
    val sorted = rs.map(_.name).sorted
    val shown  = sorted.take(MaxNames).mkString(", ")
    if sorted.size > MaxNames then s"$shown, +${sorted.size - MaxNames} more" else shown

  private def gib(bytes: Long): String = f"${bytes.toDouble / (1L << 30)}%.1f GiB"
