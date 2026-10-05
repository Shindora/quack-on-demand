package ai.starlake.quack.cli

import ai.starlake.quack.ManagerConfig
import ai.starlake.quack.Main.given
import ai.starlake.quack.boot.EmbeddedControlPlane
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, UserStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import at.favre.lib.crypto.bcrypt.BCrypt
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.nio.file.Files
import java.sql.DriverManager
import scala.util.Try

class AdminResetCliSpec extends AnyFlatSpec with Matchers:

  private def withFreshDb(test: String => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodreset_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      test(url)
    finally Try(TestPostgres.dropDatabase(dbName))

  private def column(url: String, sql: String): String =
    val c = DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      val rs = c.createStatement().executeQuery(sql)
      rs.next()
      rs.getString(1)
    finally c.close()

  "reset" should "rehash, clear lockout and honor --must-change" in withFreshDb { url =>
    val store = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      store.upsertUser(None, "root", "old-pw", "admin")
      val c = DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        c.createStatement()
          .executeUpdate(
            "UPDATE qodstate_user SET failed_attempts = 7, locked_at = NOW() WHERE username = 'root'"
          )
      finally c.close()
      AdminResetCli.reset(store, "root", "new-pw", mustChange = true) shouldBe 0
      val hash = column(url, "SELECT password_hash FROM qodstate_user WHERE username = 'root'")
      BCrypt.verifyer().verify("new-pw".toCharArray, hash).verified shouldBe true
      column(url, "SELECT failed_attempts FROM qodstate_user WHERE username = 'root'") shouldBe "0"
      column(
        url,
        "SELECT locked_at IS NULL FROM qodstate_user WHERE username = 'root'"
      ) shouldBe "t"
      column(
        url,
        "SELECT must_change_password FROM qodstate_user WHERE username = 'root'"
      ) shouldBe "t"
    finally store.close()
  }

  it should "refuse an unknown name and a tenant user with exit 1" in withFreshDb { url =>
    val store = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      store.upsertUser(Some("acme"), "alice", "pw", "user")
      AdminResetCli.reset(store, "nobody", "x", mustChange = false) shouldBe 1
      AdminResetCli.reset(store, "alice", "x", mustChange = false) shouldBe 1
    finally store.close()
  }

  "run" should "refuse a missing username and an empty stdin password" in {
    val err                                    = new ByteArrayOutputStream()
    def run(args: List[String], stdin: String) =
      AdminResetCli.run(
        args,
        new ByteArrayInputStream(stdin.getBytes("UTF-8")),
        new PrintStream(new ByteArrayOutputStream()),
        new PrintStream(err)
      )
    run(Nil, "pw\n") shouldBe 1
    err.toString should include("usage: admin reset-password <username> [--must-change]")
    run(List("root"), "\n") shouldBe 1
    err.toString should include("empty password")
  }

  "postmasterPort" should "read the port from line 4 of postmaster.pid" in {
    val dir = Files.createTempDirectory("pgdata")
    EmbeddedControlPlane.postmasterPort(dir) shouldBe None
    Files.writeString(dir.resolve("postmaster.pid"), "123\n/data\n1700000000\n25432\n/tmp\n")
    EmbeddedControlPlane.postmasterPort(dir) shouldBe Some(25432)
  }

  "attachOrStart" should "refuse a data dir with no pgdata" in {
    val root = Files.createTempDirectory("qodpg")
    val cfg  = ai.starlake.quack.EmbeddedPostgresConfig(enabled = true, dataDir = root.toString)
    EmbeddedControlPlane.attachOrStart(cfg).left.toOption.get should include(
      "nothing to reset; run qod serve first"
    )
  }

  "runWith" should "exit 2 when config loading or the embedded start throws" in {
    val err = new ByteArrayOutputStream()
    def go(
        load: () => ManagerConfig,
        attach: ai.starlake.quack.EmbeddedPostgresConfig => Either[
          String,
          EmbeddedControlPlane.Handle
        ]
    ) =
      AdminResetCli.runWith(
        List("root"),
        new ByteArrayInputStream("pw\n".getBytes("UTF-8")),
        new PrintStream(new ByteArrayOutputStream()),
        new PrintStream(err),
        load,
        attach
      )
    go(() => throw new IllegalStateException("bad conf"), _ => Left("unused")) shouldBe 2
    err.toString should include("error: control plane unreachable: bad conf")
    val cfg = pureconfig.ConfigSource.default
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]
    val embedded = cfg.copy(embeddedPostgres = cfg.embeddedPostgres.copy(enabled = true))
    go(() => embedded, _ => throw new IllegalStateException("start boom")) shouldBe 2
    err.toString should include("error: control plane unreachable: start boom")
    go(() => embedded, _ => Left("nothing to reset; run qod serve first")) shouldBe 1
  }
