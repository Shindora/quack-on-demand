package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.manifest.ConfigManifest
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** Liquibase 0045 renames qodstate_user.role to kind; the default auth queries and the store read
  * the new column; a manifest still carrying the old `role` user key is refused, never silently
  * demoted to the default kind.
  */
class UserKindMigrationSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodkind")

  private def withDb(test: (String, PostgresControlPlaneStore, UserStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodkind_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store     = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val userStore = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(url, store, userStore)
      finally
        userStore.close()
        store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  "0045" should "leave a kind column and no role column on qodstate_user" in withDb { (url, _, _) =>
    val c = java.sql.DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      val rs = c
        .createStatement()
        .executeQuery(
          "SELECT column_name FROM information_schema.columns WHERE table_name = 'qodstate_user'"
        )
      val cols = Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toSet
      cols should contain("kind")
      cols should not contain "role"
    finally c.close()
  }

  "the store" should "round-trip kind" in withDb { (_, store, _) =>
    val id = store.upsertUserWithHash(None, "root", "x", "admin")
    store.getUserById(id).map(_.kind) shouldBe Some("admin")
  }

  "ManifestUser decoding" should "refuse the legacy role key" in {
    val json =
      """{"apiVersion":"quack-on-demand/v1","kind":"ConfigManifest",
        |"exportedAt":"2026-10-04T00:00:00Z","exportedFrom":{"managerVersion":"v","hostname":"h"},
        |"users":[{"tenant":"acme","username":"bob","role":"admin"}]}""".stripMargin
    val out = io.circe.parser.decode[ConfigManifest](json)
    out.isLeft shouldBe true
    out.left.toOption.get.getMessage should include("renamed to `kind`")
  }

  it should "decode kind" in {
    val json =
      """{"apiVersion":"quack-on-demand/v1","kind":"ConfigManifest",
        |"exportedAt":"2026-10-04T00:00:00Z","exportedFrom":{"managerVersion":"v","hostname":"h"},
        |"users":[{"tenant":"acme","username":"bob","kind":"admin"}]}""".stripMargin
    io.circe.parser.decode[ConfigManifest](json).map(_.users.head.kind) shouldBe Right("admin")
  }
