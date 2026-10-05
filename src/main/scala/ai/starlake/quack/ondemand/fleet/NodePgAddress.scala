package ai.starlake.quack.ondemand.fleet

/** The managed Postgres as fleet servers reach it. The manager may dial the metastore by a name
  * only it resolves (a compose service name); `QOD_FLEET_NODE_PG_HOST` / `_PORT` replace that
  * address inside fleet assignments only. A database on its own Postgres is never touched.
  */
object NodePgAddress:
  def rewrite(
      metastore: Map[String, String],
      defaultPgHost: String,
      defaultPgPort: String,
      nodePgHost: String,
      nodePgPort: String
  ): Map[String, String] =
    val managed = defaultPgHost.nonEmpty && metastore.get("pgHost").contains(defaultPgHost)
    if !managed then metastore
    else
      val withHost =
        if nodePgHost.nonEmpty then metastore.updated("pgHost", nodePgHost) else metastore
      if nodePgPort.nonEmpty && metastore.get("pgPort").contains(defaultPgPort) then
        withHost.updated("pgPort", nodePgPort)
      else withHost
