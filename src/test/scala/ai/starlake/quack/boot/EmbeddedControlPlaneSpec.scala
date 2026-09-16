package ai.starlake.quack.boot

import ai.starlake.quack.{EmbeddedPostgresConfig, ManagerConfig}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.nio.file.Files
import java.sql.DriverManager

class EmbeddedControlPlaneSpec extends AnyFlatSpec with Matchers:
  import ai.starlake.quack.Main.given

  /** A free port picked once per test, so two specs in the same fork cannot collide on the
    * production default of 25432.
    */
  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  "EmbeddedControlPlane" should "keep its data across a stop and restart" in:
    val dir  = Files.createTempDirectory("qod-embedded-spec")
    val port = freePort()
    val cfg  = EmbeddedPostgresConfig(enabled = true, port = port, dataDir = dir.toString)

    val first = EmbeddedControlPlane.start(cfg)
    try
      first.ensureDatabase("qod")
      val conn = DriverManager.getConnection(
        s"jdbc:postgresql://${first.host}:${first.port}/qod",
        first.user,
        first.password
      )
      try
        val st = conn.createStatement()
        try
          st.executeUpdate("CREATE TABLE persisted (id int)")
          st.executeUpdate("INSERT INTO persisted VALUES (42)")
        finally st.close()
      finally conn.close()
    finally first.stop()

    val second = EmbeddedControlPlane.start(cfg)
    try
      val conn = DriverManager.getConnection(
        s"jdbc:postgresql://${second.host}:${second.port}/qod",
        second.user,
        second.password
      )
      try
        val rs = conn.createStatement().executeQuery("SELECT id FROM persisted")
        rs.next() shouldBe true
        rs.getInt("id") shouldBe 42
      finally conn.close()
    finally second.stop()

  it should "be idempotent in ensureDatabase" in:
    val dir = Files.createTempDirectory("qod-embedded-idem")
    val cp  = EmbeddedControlPlane.start(
      EmbeddedPostgresConfig(enabled = true, port = freePort(), dataDir = dir.toString)
    )
    try
      cp.ensureDatabase("qod")
      noException should be thrownBy cp.ensureDatabase("qod")
    finally cp.stop()

  it should "project only the five Postgres coordinates onto ManagerConfig" in:
    val dir = Files.createTempDirectory("qod-embedded-coords")
    val cp  = EmbeddedControlPlane.start(
      EmbeddedPostgresConfig(enabled = true, port = freePort(), dataDir = dir.toString)
    )
    try
      val base    = ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]
      val applied = EmbeddedControlPlane.applyCoordinates(base, cp)
      applied.defaultMetastore.pgHost shouldBe cp.host
      applied.defaultMetastore.pgPort shouldBe cp.port.toString
      applied.defaultMetastore.pgUser shouldBe cp.user
      applied.defaultMetastore.pgPassword shouldBe cp.password
      applied.defaultMetastore.dbName shouldBe base.defaultMetastore.dbName
      // Everything else is untouched: this is the demo-posture guardrail in assertion form.
      applied.defaultMetastore.dataPath shouldBe base.defaultMetastore.dataPath
      applied.defaultMetastore.schemaName shouldBe base.defaultMetastore.schemaName
      applied.apiKey shouldBe base.apiKey
      applied.runtimeType shouldBe base.runtimeType
      applied.admin shouldBe base.admin
      applied.auth shouldBe base.auth
    finally cp.stop()

  it should "default the data dir to the platform user-data dir when unconfigured" in:
    EmbeddedControlPlane.resolveDataDir("").toString should endWith("pg")

  it should "honor an explicit data dir" in:
    EmbeddedControlPlane.resolveDataDir("/tmp/custom-qod").toString shouldBe "/tmp/custom-qod"
