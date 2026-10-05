package ai.starlake.quack.cli

import ai.starlake.quack.{EmbeddedPostgresConfig, ManagerConfig}
import ai.starlake.quack.Main.given
import ai.starlake.quack.boot.EmbeddedControlPlane
import ai.starlake.quack.ondemand.state.UserStore
import pureconfig.ConfigSource

import java.io.{BufferedReader, InputStream, InputStreamReader, PrintStream}
import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

/** `java -jar qod.jar admin reset-password <username> [--must-change]`, password on the first stdin
  * line. Break-glass recovery of a superuser password straight in the control-plane database, so it
  * works with the manager down and every login path blocked. Authority = reaching that database
  * with the configured credentials. Exit 0 updated, 1 no such superuser / usage / control plane not
  * initialized, 2 control plane unreachable.
  */
object AdminResetCli:

  private val Usage = "usage: admin reset-password <username> [--must-change]"

  def run(args: List[String], in: InputStream, out: PrintStream, err: PrintStream): Int =
    runWith(args, in, out, err, loadConfig, EmbeddedControlPlane.attachOrStart)

  private def loadConfig(): ManagerConfig =
    ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]

  private[cli] def runWith(
      args: List[String],
      in: InputStream,
      out: PrintStream,
      err: PrintStream,
      loadCfg: () => ManagerConfig,
      attach: EmbeddedPostgresConfig => Either[String, EmbeddedControlPlane.Handle]
  ): Int =
    val mustChange = args.contains("--must-change")
    args.filterNot(_ == "--must-change") match
      case username :: Nil =>
        val reader   = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
        val password = Option(reader.readLine()).getOrElse("")
        if password.isEmpty then
          err.println("error: empty password on stdin")
          1
        else resetConfigured(username, password, mustChange, out, err, loadCfg, attach)
      case _ =>
        err.println(Usage)
        1

  /** The update itself, on an open store. Exit code per the object doc. */
  def reset(store: UserStore, username: String, password: String, mustChange: Boolean): Int =
    store.resetSuperuserPassword(username, password, mustChange) match
      case Some(_) => 0
      case None    => 1

  private def sqlCause(t: Throwable): Option[java.sql.SQLException] =
    Iterator
      .iterate(t)(_.getCause)
      .takeWhile(_ != null)
      .collectFirst { case e: java.sql.SQLException => e }

  private def resetConfigured(
      username: String,
      password: String,
      mustChange: Boolean,
      out: PrintStream,
      err: PrintStream,
      loadCfg: () => ManagerConfig,
      attach: EmbeddedPostgresConfig => Either[String, EmbeddedControlPlane.Handle]
  ): Int =
    def unreachable(e: Throwable): Int =
      err.println(s"error: control plane unreachable: ${e.getMessage}")
      2
    try
      val mgrCfg                                                    = loadCfg()
      val coords: Either[String, (Map[String, String], () => Unit)] =
        if !mgrCfg.embeddedPostgres.enabled then Right((mgrCfg.defaultMetastore.asMap, () => ()))
        else
          attach(mgrCfg.embeddedPostgres).map { h =>
            val meta = mgrCfg.defaultMetastore.asMap ++ Map(
              "pgHost"     -> h.host,
              "pgPort"     -> h.port.toString,
              "pgUser"     -> h.user,
              "pgPassword" -> h.password
            )
            (meta, h.stop)
          }
      coords match
        case Left(msg) =>
          err.println(s"error: $msg")
          1
        case Right((meta, release)) =>
          try
            val store = UserStore.fromDefaultMetastore(meta, mgrCfg.auth.lockout)
            try
              reset(store, username, password, mustChange) match
                case 0 =>
                  out.println(
                    s"password reset for superuser '$username'. Existing sessions are not revoked."
                  )
                  0
                case code =>
                  err.println(s"error: no superuser named '$username'")
                  code
            finally store.close()
          catch
            case NonFatal(e)
                if sqlCause(e).exists(x => x.getSQLState == "42P01" || x.getSQLState == "3D000") =>
              err.println("error: control plane not initialized; start the manager once first")
              1
            case NonFatal(e) => unreachable(e)
          finally release()
    catch case NonFatal(e) => unreachable(e)
