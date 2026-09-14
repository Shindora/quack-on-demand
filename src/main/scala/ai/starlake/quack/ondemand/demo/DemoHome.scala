package ai.starlake.quack.ondemand.demo

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Ephemeral root for a `qod demo` run. Everything a demo touches lives under `root` so teardown is
  * a single recursive delete.
  */
final case class DemoHome(root: Path, pgDir: Path, dataPath: Path, nativeDir: Path):

  /** Best-effort recursive delete (deepest-first). Teardown must not throw on a partially-created
    * or locked tree.
    */
  def deleteRecursively(): Unit = DemoHome.wipe(root)

object DemoHome:

  // NOTE (final-review Fix 1): a prior revision of this object set a
  // `duckdb.jni.tmpdir` system property here, intending to pin where the DuckDB JNI native gets
  // unpacked (the design doc's "Guardrail 2"). That property is never read by anything -- the
  // DuckDB JDBC driver (`org.duckdb.DuckDBNative.unpackAndLoad`) always unpacks via
  // `Files.createTempFile("libduckdb_java", ".so")`, which resolves against the JVM's
  // `jdk.internal.util.StaticProperty` snapshot of `java.io.tmpdir` taken at VM start -- not the
  // live `System.getProperty`/`System.setProperty` value. Verified empirically (Java 21): calling
  // `System.setProperty("java.io.tmpdir", ...)` at any point *after* the JVM has started, however
  // early in `main`, has zero effect on where `Files.createTempFile` lands; only a `-D` flag passed
  // at JVM launch does. The demo has no way to pass JVM flags to its own already-running process, so
  // this pin is not achievable in-process. Rather than keep an assertion on a property nothing
  // reads, the property-set was removed here; the native `.so` extracts to the ambient
  // `java.io.tmpdir` and self-cleans via `File.deleteOnExit()` inside DuckDB's own unpack code. See
  // `docs/superpowers/specs/2026-07-16-qod-demo-self-contained-design.md` Guardrail 2 for the
  // corrected claim. `nativeDir` is kept as a real, created-and-deleted subdir of the demo home for
  // shape/symmetry with `pgDir`/`dataPath`, even though nothing writes into it today.

  private def defaultRoot: Path =
    val tmp = sys.env.getOrElse("TMPDIR", System.getProperty("java.io.tmpdir"))
    Paths.get(tmp, "qod-demo")

  /** Create the demo home (from `explicit`, else `QOD_DEMO_HOME`, else `${TMPDIR}/qod-demo`) and
    * its subdirs.
    */
  def create(explicit: Option[String]): DemoHome =
    val root = explicit
      .orElse(sys.env.get("QOD_DEMO_HOME"))
      .map(Paths.get(_))
      .getOrElse(defaultRoot)
    val pgDir     = root.resolve("pg")
    val dataPath  = root.resolve("ducklake")
    val nativeDir = root.resolve("native")
    // The clean below is destructive, so refuse it while a previous demo is still LIVE on this
    // home -- two concurrent `qod start --demo` share `${TMPDIR}/qod-demo` by default. Postgres's
    // own `postmaster.pid` names the process holding the data directory; a dead pid is exactly the
    // crashed run the clean exists for, so it falls through.
    livePostmaster(pgDir).foreach(pid =>
      sys.error(
        s"a demo is already running on $root (postgres pid $pid) - stop it first, " +
          "or give this run its own QOD_DEMO_HOME"
      )
    )
    // Teardown normally empties the home, but a run killed before it (SIGKILL, machine sleep, OOM)
    // leaves `pg/pgdata` populated -- and the next run's `initdb` then refuses with `directory
    // "..." exists but is not empty`, which zonky reports only as the opaque
    // `IllegalStateException: Process [...initdb...] failed` (its stderr goes to an INFO logger the
    // default QOD_LOG_LEVEL=ERROR swallows). So every run starts from an empty tree. The clean is
    // scoped to the three subdirs the demo owns, never `root` itself: `QOD_DEMO_HOME` is
    // caller-supplied and may point at a directory holding other things.
    List(pgDir, dataPath, nativeDir).foreach(wipe)
    // `wipe` is best-effort by design (teardown must not throw). Here a silent failure would land
    // right back on the unreadable initdb error, so check the one path that matters and say what
    // to remove.
    val stalePgData = pgDir.resolve("pgdata")
    if Files.exists(stalePgData) then
      sys.error(
        s"demo home $root still holds a previous run's Postgres data directory at $stalePgData " +
          "and it could not be removed - delete it by hand, or point QOD_DEMO_HOME elsewhere"
      )
    List(root, pgDir, dataPath, nativeDir).foreach(Files.createDirectories(_))
    DemoHome(root, pgDir, dataPath, nativeDir)

  /** The pid from `pgdata/postmaster.pid` when that process is still alive, else `None` (no file,
    * unreadable, unparseable, or a pid that has since died).
    */
  private def livePostmaster(pgDir: Path): Option[Long] =
    val pidFile = pgDir.resolve("pgdata").resolve("postmaster.pid")
    if !Files.exists(pidFile) then None
    else
      scala.util
        .Try(Files.readString(pidFile).linesIterator.next().trim.toLong)
        .toOption
        .filter(pid => ProcessHandle.of(pid).filter(_.isAlive).isPresent)

  /** Best-effort recursive delete of one subtree, deepest-first. Never throws: it runs both on the
    * teardown path (which must not mask the real failure) and on the pre-flight clean above.
    */
  private def wipe(dir: Path): Unit =
    if Files.exists(dir) then
      Files
        .walk(dir)
        .sorted(java.util.Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(p =>
          try Files.deleteIfExists(p)
          catch { case _: Throwable => () }
        )
