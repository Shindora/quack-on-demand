package ai.starlake.quack.ondemand.demo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.sql.DriverManager
import scala.jdk.CollectionConverters.*

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

  // Regression: zonky resolves its Postgres binaries strictly from the classpath, and its only
  // emulation fallback covers Darwin/aarch64 (Rosetta) and Windows on ARM. Linux arm64 has none, so
  // with the amd64-only transitive set `qod start --demo` died there on zonky's
  // `IllegalStateException: Missing embedded postgres binaries`. Every platform is pinned, not just
  // the one that broke: a dropped artifact fails only on the machine nobody develops on, and on
  // Apple Silicon the Rosetta fallback hides it behind a WARN the default QOD_LOG_LEVEL=ERROR
  // swallows. `getResources` mirrors zonky's own lookup, which rejects two binaries for one
  // platform as `Duplicate embedded postgres binaries`, so the count pins both directions.
  private val demoPgBinaries = List(
    "postgres-linux-x86_64.txz",
    "postgres-linux-arm_64.txz",
    "postgres-darwin-x86_64.txz",
    "postgres-darwin-arm_64.txz",
    "postgres-windows-x86_64.txz"
  )

  it should "carry exactly one embedded Postgres binary for every supported platform" in {
    val loader = classOf[DemoPostgres].getClassLoader
    demoPgBinaries.foreach: name =>
      withClue(s"$name: "):
        java.util.Collections.list(loader.getResources(name)).size shouldBe 1
  }

  // The arm64 artifacts carry an explicit version pin while the amd64 ones arrive transitively with
  // `embedded-postgres`, so a future bump of that could hand arm users a different Postgres major
  // than x86 users of the same jar -- silently, since each platform reads only its own binary. On
  // the test classpath every binary resolves out of its own versioned jar, so one distinct version
  // is the invariant. An assembled classpath merges them all into one jar with no version in the
  // path and yields none, which is not a failure.
  it should "resolve every embedded Postgres binary from a single binaries version" in {
    val loader   = classOf[DemoPostgres].getClassLoader
    val stamped  = raw"embedded-postgres-binaries-[a-z0-9-]+-(\d+\.\d+\.\d+)\.jar".r
    val versions = demoPgBinaries
      .flatMap: name =>
        loader
          .getResources(name)
          .asScala
          .flatMap(url => stamped.findFirstMatchIn(url.toString).map(_.group(1)))
      .distinct
    versions.size should be <= 1
  }
