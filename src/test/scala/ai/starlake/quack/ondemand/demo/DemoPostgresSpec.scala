package ai.starlake.quack.ondemand.demo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.sql.DriverManager

class DemoPostgresSpec extends AnyFlatSpec with Matchers:

  "DemoPostgres" should "start, create a database, accept a connection, and stop" in {
    val dir = Files.createTempDirectory("demo-pg-spec")
    val pg  = DemoPostgres.start(dir)
    try
      pg.coords.host shouldBe "localhost"
      pg.coords.port should be > 0
      pg.createDatabase("qod_demo_probe")
      pg.createDatabase("qod_demo_probe") // idempotent second call must not throw
      val url  = s"jdbc:postgresql://${pg.coords.host}:${pg.coords.port}/qod_demo_probe"
      val conn = DriverManager.getConnection(url, pg.coords.user, pg.coords.password)
      try conn.isValid(2) shouldBe true
      finally conn.close()
    finally pg.stop()
  }

  // Regression: a demo run killed before teardown leaves `pg/pgdata` populated, and `initdb` then
  // refuses the non-empty directory -- reported only as zonky's opaque `IllegalStateException:
  // Process [...initdb...] failed`. `DemoHome.create` clears the owned subdirs so the next run
  // starts anyway.
  it should "start against a home left dirty by a previous run" in {
    val base  = Files.createTempDirectory("demo-pg-stale")
    val root  = base.resolve("qod-demo")
    val stale = root.resolve("pg").resolve("pgdata")
    Files.createDirectories(stale)
    Files.writeString(stale.resolve("PG_VERSION"), "16")

    val home = DemoHome.create(Some(root.toString))
    val pg   = DemoPostgres.start(home.pgDir)
    try pg.coords.port should be > 0
    finally
      pg.stop()
      home.deleteRecursively()
  }

  it should "point a failed start at the log level that reveals initdb's output" in {
    val cause   = new IllegalStateException("Process [/tmp/PG/bin/initdb, -A, trust] failed")
    val wrapped =
      DemoPostgres.startFailure(cause, java.nio.file.Paths.get("/tmp/qod-demo/pg"))
    wrapped.getMessage should include("/tmp/qod-demo/pg")
    wrapped.getMessage should include("QOD_LOG_LEVEL=INFO")
    wrapped.getMessage should include("initdb")
    wrapped.getCause shouldBe cause
  }
