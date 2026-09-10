package ai.starlake.quack.mcp

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  GroupHandlers,
  MembershipHandlers,
  RoleHandlers,
  TenantHandlers,
  UserHandlers
}
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser, UserStore}
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class McpIdentityToolsSpec extends AnyFlatSpec with Matchers:

  private val Tenant0  = "acme"
  private val patToken = "qod_pat_alice"

  private def adminPat(tenant: String = Tenant0): McpPrincipal =
    new McpPrincipal.Pat(
      PatPrincipal(
        user = RbacUser(id = "u1", tenant = Some(tenant), username = "alice", role = "admin"),
        patId = "pat-1",
        scope = SessionScope(superuser = false, manageableTenants = Set(tenant)),
        isAdmin = true,
        restriction = TokenRestriction.Unrestricted
      ),
      patToken
    )

  private def makeDuckDbUserStore(): UserStore =
    Class.forName("org.duckdb.DuckDBDriver")
    val tmpFile = java.nio.file.Files.createTempFile("qod-mcp-identity-test-users", ".duckdb")
    tmpFile.toFile.delete()
    tmpFile.toFile.deleteOnExit()
    val jdbcUrl = s"jdbc:duckdb:${tmpFile.toAbsolutePath}"
    val c       = java.sql.DriverManager.getConnection(jdbcUrl)
    try
      c.createStatement()
        .execute(
          // Mirrors the real qodstate_user schema (Liquibase 0003 + 0006 + 0022 + 0028 + 0029 +
          // 0030): UserUpsert's ON CONFLICT clause unconditionally touches enabled,
          // must_change_password, email, failed_attempts and locked_at, so all must exist even
          // though this fixture never runs Liquibase against DuckDB.
          """CREATE TABLE IF NOT EXISTS qodstate_user (
          |  id                    TEXT PRIMARY KEY,
          |  tenant                TEXT,
          |  username              TEXT NOT NULL,
          |  password_hash         TEXT NOT NULL,
          |  role                  TEXT NOT NULL DEFAULT 'user',
          |  enabled               BOOLEAN NOT NULL DEFAULT true,
          |  must_change_password  BOOLEAN NOT NULL DEFAULT false,
          |  email                 TEXT,
          |  failed_attempts       INT NOT NULL DEFAULT 0,
          |  locked_at             TIMESTAMPTZ,
          |  created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          |  updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
          |)""".stripMargin
        )
    finally c.close()
    new UserStore(jdbcUrl, "", "")

  private final class Fixture:
    val store   = new InMemoryControlPlaneStore()
    val backend = ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend.noop()
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, store)
    sup.restore()

    val scopeOf: String => Option[SessionScope] =
      t =>
        if t == patToken then
          Some(SessionScope(superuser = false, manageableTenants = Set(Tenant0)))
        else None

    val userStore   = makeDuckDbUserStore()
    val users       = new UserHandlers(sup, userStore)
    val tenants     = new TenantHandlers(sup)
    val groups      = new GroupHandlers(sup, users)
    val roles       = new RoleHandlers(sup, users)
    val memberships = new MembershipHandlers(sup, users)

    val tools = new McpIdentityTools(tenants, users, groups, roles, memberships, scopeOf)

    def call(name: String, principal: McpPrincipal, args: (String, Json)*): Either[String, Json] =
      val tool = tools.tools.find(_.name == name).getOrElse(fail(s"tool $name not defined"))
      tool.adminOnly shouldBe true
      tool.run(principal, JsonObject(args*)).unsafeRunSync()

    def idOf(out: Either[String, Json]): String =
      out.toOption.get.hcursor.get[String]("id").toOption.get

    /** In production, `store` and `userStore` are two Scala wrappers over the SAME physical
      * `qodstate_user` table, so a password hash written via `userStore.upsertUser` (createUser's
      * path) is immediately visible to `store.getPasswordHash` (updateUserPassword's rewrite
      * guard). This fixture deliberately decouples them (in-memory store vs. a real DuckDB
      * UserStore) for speed, so any update that doesn't rotate the password (role/email/enabled-
      * only) needs the hash seeded here first, or `updateUserPassword` refuses with
      * "no stored password hash".
      */
    def seedPasswordHash(tenant: Option[String], username: String): Unit =
      store.upsertUserWithHash(tenant, username, "seed-hash", "user")
      ()

  "create_tenant" should "create a tenant as static key and echo it in list_tenants" in {
    val f = new Fixture
    val created = f.call(
      "create_tenant",
      McpPrincipal.StaticKey,
      "id"           -> Json.fromString("acme"),
      "display_name" -> Json.fromString("Acme Corporation")
    )
    created.isRight shouldBe true
    val listed = f.call("list_tenants", McpPrincipal.StaticKey)
    listed.toOption.get.hcursor.downField("tenants").values.get.size shouldBe 1
  }

  it should "refuse a tenant-scoped admin PAT (superuser_required)" in {
    val f = new Fixture
    val out = f.call("create_tenant", adminPat(), "id" -> Json.fromString("evil"))
    out.isLeft shouldBe true
    out.left.toOption.get should include("superuser_required")
  }

  "delete_tenant" should "delete an empty tenant" in {
    val f = new Fixture
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("acme"))
    val out =
      f.call("delete_tenant", McpPrincipal.StaticKey, "name" -> Json.fromString("acme"))
    out.isRight shouldBe true
  }

  "set_tenant_disabled" should "flip the disabled flag" in {
    val f = new Fixture
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("acme"))
    val out = f.call(
      "set_tenant_disabled",
      McpPrincipal.StaticKey,
      "name"     -> Json.fromString("acme"),
      "disabled" -> Json.True
    )
    out.isRight shouldBe true
    out.toOption.get.hcursor.get[Boolean]("disabled").toOption.get shouldBe true
  }

  "create_user" should "create a tenant user and list it" in {
    val f = new Fixture
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("acme"))
    val created = f.call(
      "create_user",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString("acme"),
      "username" -> Json.fromString("bob"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    created.isRight shouldBe true
    val listed = f.call(
      "list_users",
      McpPrincipal.StaticKey,
      "tenant" -> Json.fromString("acme")
    )
    listed.toOption.get.hcursor.downField("users").values.get.size shouldBe 1
  }

  it should "let a PAT create a user in its own tenant without a tenant arg... via explicit tenant" in {
    val f = new Fixture
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("acme"))
    val out = f.call(
      "create_user",
      adminPat(),
      "tenant"   -> Json.fromString("acme"),
      "username" -> Json.fromString("carol"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    out.isRight shouldBe true
  }

  it should "refuse a PAT creating a superuser (no tenant arg)" in {
    val f = new Fixture
    val out = f.call(
      "create_user",
      adminPat(),
      "username" -> Json.fromString("root2"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    out.isLeft shouldBe true
    out.left.toOption.get should include("tenant_forbidden")
  }

  "update_user" should "disable a user by id" in {
    val f = new Fixture
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("acme"))
    val created = f.call(
      "create_user",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString("acme"),
      "username" -> Json.fromString("bob"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    val id = f.idOf(created)
    f.seedPasswordHash(Some("acme"), "bob")
    val out = f.call(
      "update_user",
      McpPrincipal.StaticKey,
      "id"      -> Json.fromString(id),
      "enabled" -> Json.False
    )
    out.isRight shouldBe true
    out.toOption.get.hcursor.get[Boolean]("enabled").toOption.get shouldBe false
  }

  "delete_user" should "delete a user and surface handler guard errors verbatim" in {
    val f = new Fixture
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("acme"))
    val created = f.call(
      "create_user",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString("acme"),
      "username" -> Json.fromString("bob"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    val id  = f.idOf(created)
    val out = f.call("delete_user", McpPrincipal.StaticKey, "id" -> Json.fromString(id))
    out.isRight shouldBe true
    val missing = f.call("delete_user", McpPrincipal.StaticKey, "id" -> Json.fromString(id))
    missing.isLeft shouldBe true
    missing.left.toOption.get should include("not_found")
  }

  "user_effective_permissions" should "return the effective set for a user" in {
    val f = new Fixture
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("acme"))
    val created = f.call(
      "create_user",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString("acme"),
      "username" -> Json.fromString("bob"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    val id = f.idOf(created)
    val out =
      f.call("user_effective_permissions", McpPrincipal.StaticKey, "id" -> Json.fromString(id))
    out.isRight shouldBe true
    out.toOption.get.hcursor.downField("user").get[String]("username").toOption.get shouldBe "bob"
  }
