package ai.starlake.quack.ondemand.demo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class DemoHomeSpec extends AnyFlatSpec with Matchers:

  "DemoHome.create" should "make the home + subdirs and clean up" in {
    val base = Files.createTempDirectory("demo-home-spec")
    val home = DemoHome.create(Some(base.resolve("qod-demo").toString))
    Files.isDirectory(home.root) shouldBe true
    Files.isDirectory(home.pgDir) shouldBe true
    Files.isDirectory(home.dataPath) shouldBe true
    // `nativeDir` is still created for shape/symmetry, but (final-review Fix 1) DuckDB's JNI
    // native never actually unpacks there: `Files.createTempFile` resolves against the JVM's
    // startup snapshot of `java.io.tmpdir`, which a runtime `System.setProperty` cannot redirect,
    // so there is nothing left to assert a "pin" for here -- see the NOTE in DemoHome.scala.
    Files.isDirectory(home.nativeDir) shouldBe true

    home.deleteRecursively()
    Files.exists(home.root) shouldBe false
  }

  // A demo run killed before its teardown (SIGKILL, machine sleep, OOM) leaves a populated
  // `pg/pgdata` behind. The next run's `initdb` then refuses with "directory exists but is not
  // empty", surfacing only as zonky's opaque `IllegalStateException: Process [...initdb...]
  // failed`. `create` therefore starts every run from an empty tree.
  it should "clear the owned subdirs left behind by a crashed run" in {
    val base        = Files.createTempDirectory("demo-home-stale")
    val root        = base.resolve("qod-demo")
    val stalePgData = root.resolve("pg").resolve("pgdata")
    Files.createDirectories(stalePgData)
    Files.writeString(stalePgData.resolve("PG_VERSION"), "16")
    Files.createDirectories(root.resolve("ducklake"))
    Files.writeString(root.resolve("ducklake").resolve("leftover.parquet"), "x")
    // Anything the demo does not own is left alone -- `QOD_DEMO_HOME` may point at a directory
    // the caller also uses for other things, so the wipe is scoped to the three owned subdirs.
    Files.writeString(root.resolve("keep.txt"), "keep")

    val home = DemoHome.create(Some(root.toString))

    Files.exists(stalePgData) shouldBe false
    Files.exists(root.resolve("ducklake").resolve("leftover.parquet")) shouldBe false
    Files.isDirectory(home.pgDir) shouldBe true
    Files.isDirectory(home.dataPath) shouldBe true
    Files.exists(root.resolve("keep.txt")) shouldBe true

    home.deleteRecursively()
  }

  // The clean above is destructive, so it must not fire while another demo still holds the home.
  it should "refuse to clean a home whose postgres is still running" in {
    val base   = Files.createTempDirectory("demo-home-live")
    val root   = base.resolve("qod-demo")
    val pgData = root.resolve("pg").resolve("pgdata")
    Files.createDirectories(pgData)
    // This JVM stands in for the live postmaster: what the guard tests is pid liveness.
    Files.writeString(pgData.resolve("postmaster.pid"), s"${ProcessHandle.current().pid()}\n/tmp\n")

    val ex = intercept[RuntimeException](DemoHome.create(Some(root.toString)))
    ex.getMessage should include("a demo is already running")
    Files.exists(pgData.resolve("postmaster.pid")) shouldBe true // not wiped
  }

  it should "clean past a postmaster.pid whose process is gone" in {
    val base   = Files.createTempDirectory("demo-home-deadpid")
    val root   = base.resolve("qod-demo")
    val pgData = root.resolve("pg").resolve("pgdata")
    Files.createDirectories(pgData)
    Files.writeString(pgData.resolve("postmaster.pid"), "4194304\n/tmp\n") // above any live pid

    val home = DemoHome.create(Some(root.toString))

    Files.exists(pgData.resolve("postmaster.pid")) shouldBe false
    home.deleteRecursively()
  }
