package ai.starlake.quack.ondemand.demo

/** The `qod demo` startup banner. Scaled to the minimal demo's story: row + column security on a
  * governed catalog, with the admin UI and Flight SQL credentials of every seeded identity
  * (bootstrap-demo-minimal.yaml) and a link to the client connection-string documentation.
  */
object DemoBanner:

  /** Render `header` + `rows` as a Unicode box table, every line prefixed with `indent`. */
  private def table(header: Seq[String], rows: Seq[Seq[String]], indent: String): String =
    val widths = header.indices.map(i => (header(i) +: rows.map(_(i))).map(_.length).max)
    def border(left: String, mid: String, right: String) =
      widths.map(w => "─" * (w + 2)).mkString(left, mid, right)
    def line(cells: Seq[String]) =
      cells.zip(widths).map((c, w) => s" ${c.padTo(w, ' ')} ").mkString("│", "│", "│")
    (border("┌", "┬", "┐") +: line(header) +: border("├", "┼", "┤")
      +: rows.map(line) :+ border("└", "┴", "┘"))
      .map(indent + _)
      .mkString("\n")

  def render(restPort: Int, flightPort: Int, dataPath: String, rows: String): String =
    val adminUiTable = table(
      header = Seq("Tenant", "User", "Password", "Access"),
      rows = Seq(
        Seq("(blank)", "root", "demo-root", "superuser console"),
        Seq("(blank)", "admin", "admin", "superuser console"),
        Seq("acme", "acme-admin", "demo-acme-admin", "acme-scoped view"),
        Seq("acme", "alice", "demo-alice", "acme-scoped view")
      ),
      indent = "    "
    )
    val flightSqlTable = table(
      header = Seq("User", "Password", "Access", "Notes"),
      rows = Seq(
        Seq("alice", "demo-alice", "analyst", "c_phone masked, BUILDING rows only"),
        Seq("acme-admin", "demo-acme-admin", "everything in acme", "unmasked"),
        Seq("root", "demo-root", "superuser", "add superuser=true"),
        Seq("admin", "admin", "superuser", "add superuser=true")
      ),
      indent = "    "
    )
    s"""|
        |===================================================================
        | QoD demo ready  (self-signed TLS, open REST, ephemeral catalog)
        |===================================================================
        |
        |  DuckLake: $dataPath (tenant acme, $rows TPC-H rows)
        |
        |  Admin UI: http://localhost:$restPort/ui/
        |
        |$adminUiTable
        |
        |
        |  Flight SQL/ODBC/ADBC/JDBC/Quack Client connection strings: ${ai.starlake.quack.Banner.ClientsDocUrl}
        |
        |  -> tenant=acme
        |  -> pool=bi
        |$flightSqlTable
        |
        |""".stripMargin

  /** Poll `host:port` with short connect attempts until it accepts a TCP connection or `timeoutMs`
    * elapses. Used to hold the banner back until the manager is actually up, so it prints last and
    * is not buried under boot logs.
    */
  def awaitPort(host: String, port: Int, timeoutMs: Long): Boolean =
    val deadline = System.nanoTime() + timeoutMs * 1000000L
    var up       = false
    while !up && System.nanoTime() < deadline do
      val socket = new java.net.Socket()
      try
        socket.connect(new java.net.InetSocketAddress(host, port), 250)
        up = true
      catch case _: Exception => Thread.sleep(200)
      finally
        try socket.close()
        catch case _: Exception => ()
    up
