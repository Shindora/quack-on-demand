package ai.starlake.quack

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BannerSpec extends AnyFlatSpec with Matchers:

  private val meta = Map(
    "pgHost"     -> "localhost",
    "pgPort"     -> "5432",
    "pgUser"     -> "postgres",
    "pgPassword" -> "azizam",
    "dbName"     -> "qod"
  )

  "jdbcControlPlaneUrl" should "render host, port, and database" in {
    Banner.jdbcControlPlaneUrl(meta) shouldBe "jdbc:postgresql://localhost:5432/qod"
  }

  "postgresPreflight" should "refuse clearly when nothing listens on the port" in {
    val dead = meta.updated("pgPort", "1") // reserved port, nothing listens
    val out  = Banner.postgresPreflight(dead, timeoutSec = 2)
    out.isLeft shouldBe true
    val msg = out.left.getOrElse("")
    msg should include("Postgres is NOT reachable")
    msg should include("jdbc:postgresql://localhost:1/qod")
    msg should include("pgHost/pgPort/pgUser/pgPassword/dbName")
  }

  it should "create the control-plane database when the server is up but the database is missing" in {
    import ai.starlake.quack.ondemand.state.testkit.TestPostgres
    TestPostgres.ensureReachable()
    val name = s"qod_cpb_banner_test_${System.nanoTime()}"
    val m    = Map(
      "pgHost"     -> TestPostgres.pgHost,
      "pgPort"     -> TestPostgres.pgPort.toString,
      "pgUser"     -> TestPostgres.pgUser,
      "pgPassword" -> TestPostgres.pgPass,
      "dbName"     -> name
    )
    try
      Banner.postgresPreflight(m) shouldBe Right(())
      // The database must now exist: a second probe succeeds without creating anything.
      Banner.postgresPreflight(m) shouldBe Right(())
    finally scala.util.Try(TestPostgres.dropDatabase(name))
  }

  "startup" should "render the endpoints with TLS on, 0.0.0.0 mapped, and link the client docs" in {
    val b =
      Banner.startup(meta, "0.0.0.0", 20900, "0.0.0.0", 31338, tlsEnabled = true, aclEnabled = true)
    b should include("http://localhost:20900/ui")
    b should include("SQL ACL       : ENABLED")
    b should include("grpc+tls://localhost:31338")
    b should include("Client connection strings: https://docs.starlake.ai/qod/connecting/clients")
    (b should not).include("jdbc:arrow-flight-sql")
    (b should not).include("Arrow Flight SQL ODBC Driver")
    (b should not).include("adbc_driver_flightsql")
  }

  it should "render plain grpc when TLS is off" in {
    val b =
      Banner.startup(meta, "myhost", 20900, "myhost", 31338, tlsEnabled = false, aclEnabled = false)
    b should include("grpc://myhost:31338")
    b should include("SQL ACL       : DISABLED (every statement admitted; set QOD_ACL_ENABLED=true")
    b should include(
      "ACL MODE      : qod (default for tenants that set none; 0 tenant(s) in opa mode)"
    )
  }

  it should "not imply nothing is enforced when the ACL is off but a tenant is in opa mode" in {
    val b = Banner.startup(
      meta,
      "myhost",
      20900,
      "myhost",
      31338,
      tlsEnabled = false,
      aclEnabled = false,
      aclMode = "qod",
      opaTenants = 2
    )
    b should include("SQL ACL       : DISABLED for qod tenants")
    b should include("opa tenants enforced by OPA")
    b should include(
      "ACL MODE      : qod (default for tenants that set none; 2 tenant(s) in opa mode)"
    )
    (b should not).include("SQL ACL       : DISABLED (every statement admitted")
  }

  it should "name opa as the default mode when QOD_ACL_MODE=opa" in {
    val b = Banner.startup(
      meta,
      "myhost",
      20900,
      "myhost",
      31338,
      tlsEnabled = false,
      aclEnabled = false,
      aclMode = "opa",
      opaTenants = 0
    )
    b should include("opa tenants enforced by OPA")
    b should include(
      "ACL MODE      : opa (default for tenants that set none; 0 tenant(s) in opa mode)"
    )
  }

  it should "keep the plain ENABLED line with the mode line when the ACL is on" in {
    val b = Banner.startup(
      meta,
      "myhost",
      20900,
      "myhost",
      31338,
      tlsEnabled = false,
      aclEnabled = true,
      aclMode = "qod",
      opaTenants = 1
    )
    b should include("SQL ACL       : ENABLED (grants, column and row policies enforced)")
    b should include("1 tenant(s) in opa mode")
  }

  it should "name the qod CLI config file only when launched through the CLI" in {
    val withCli = Banner.startup(
      meta,
      "myhost",
      20900,
      "myhost",
      31338,
      tlsEnabled = false,
      aclEnabled = true,
      cliConfigFile = Some("/home/u/.config/qod/config.toml")
    )
    withCli should include(
      "qod config    : /home/u/.config/qod/config.toml\n   control plane : "
    )
    val bare =
      Banner.startup(meta, "myhost", 20900, "myhost", 31338, tlsEnabled = false, aclEnabled = true)
    (bare should not).include("qod config")
  }
