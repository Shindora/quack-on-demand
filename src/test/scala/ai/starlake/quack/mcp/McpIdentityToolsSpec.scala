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
      * only) needs the hash seeded here first, or `updateUserPassword` refuses with "no stored
      * password hash".
      */
    def seedPasswordHash(tenant: Option[String], username: String): Unit =
      store.upsertUserWithHash(tenant, username, "seed-hash", "user")
      ()

  "create_tenant" should "create a tenant as static key and echo it in list_tenants" in {
    val f       = new Fixture
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
    val f   = new Fixture
    val out = f.call("create_tenant", adminPat(), "id" -> Json.fromString("evil"))
    out.isLeft shouldBe true
    out.left.toOption.get should include("superuser_required")
  }

  "delete_tenant" should "delete an empty tenant" in {
    val f   = new Fixture
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("acme"))
    val out =
      f.call("delete_tenant", McpPrincipal.StaticKey, "name" -> Json.fromString("acme"))
    out.isRight shouldBe true
  }

  "set_tenant_disabled" should "flip the disabled flag" in {
    val f   = new Fixture
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
    val f       = new Fixture
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
    val f   = new Fixture
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
    val f   = new Fixture
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
    val f       = new Fixture
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
    val f       = new Fixture
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
    val f       = new Fixture
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("acme"))
    val created = f.call(
      "create_user",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString("acme"),
      "username" -> Json.fromString("bob"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    val id  = f.idOf(created)
    val out =
      f.call("user_effective_permissions", McpPrincipal.StaticKey, "id" -> Json.fromString(id))
    out.isRight shouldBe true
    out.toOption.get.hcursor.downField("user").get[String]("username").toOption.get shouldBe "bob"
  }

  "create_group and create_role" should "create in a tenant and list back" in {
    val f = new Fixture
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("acme"))
    val g = f.call(
      "create_group",
      McpPrincipal.StaticKey,
      "tenant" -> Json.fromString("acme"),
      "name"   -> Json.fromString("analysts")
    )
    g.isRight shouldBe true
    val r = f.call(
      "create_role",
      McpPrincipal.StaticKey,
      "tenant" -> Json.fromString("acme"),
      "name"   -> Json.fromString("reader")
    )
    r.isRight shouldBe true
    f.call("list_groups", McpPrincipal.StaticKey, "tenant" -> Json.fromString("acme"))
      .toOption
      .get
      .hcursor
      .downField("groups")
      .values
      .get
      .size shouldBe 1
    // create_tenant seeds a built-in "admin" role (PoolSupervisor.createTenant), so the tenant
    // already has 1 role before "reader" is created here.
    f.call("list_roles", McpPrincipal.StaticKey, "tenant" -> Json.fromString("acme"))
      .toOption
      .get
      .hcursor
      .downField("roles")
      .values
      .get
      .size shouldBe 2
  }

  "list_roles" should "infer the tenant for a tenant-scoped PAT" in {
    val f = new Fixture
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("acme"))
    f.call(
      "create_role",
      McpPrincipal.StaticKey,
      "tenant" -> Json.fromString("acme"),
      "name"   -> Json.fromString("reader")
    )
    val out = f.call("list_roles", adminPat()) // no tenant arg
    out.isRight shouldBe true
    // create_tenant's built-in "admin" role plus the "reader" role created above.
    out.toOption.get.hcursor.downField("roles").values.get.size shouldBe 2
  }

  "add_membership" should "attach a user to a role and reflect in effective permissions" in {
    val f = new Fixture
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("acme"))
    val u = f.idOf(
      f.call(
        "create_user",
        McpPrincipal.StaticKey,
        "tenant"   -> Json.fromString("acme"),
        "username" -> Json.fromString("bob"),
        "password" -> Json.fromString("s3cret-s3cret")
      )
    )
    val r = f.idOf(
      f.call(
        "create_role",
        McpPrincipal.StaticKey,
        "tenant" -> Json.fromString("acme"),
        "name"   -> Json.fromString("reader")
      )
    )
    val add = f.call(
      "add_membership",
      McpPrincipal.StaticKey,
      "kind"    -> Json.fromString("user_role"),
      "user_id" -> Json.fromString(u),
      "role_id" -> Json.fromString(r)
    )
    add.isRight shouldBe true
    val eff =
      f.call("user_effective_permissions", McpPrincipal.StaticKey, "id" -> Json.fromString(u))
    eff.toOption.get.hcursor.downField("roles").values.get.size shouldBe 1
  }

  it should "reject an unknown kind" in {
    val f   = new Fixture
    val out = f.call(
      "add_membership",
      McpPrincipal.StaticKey,
      "kind"    -> Json.fromString("user_planet"),
      "user_id" -> Json.fromString("u"),
      "role_id" -> Json.fromString("r")
    )
    out.isLeft shouldBe true
    out.left.toOption.get should include("kind")
  }

  "remove_membership" should "detach a group role and list_group_role_memberships shows it" in {
    val f = new Fixture
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("acme"))
    val g = f.idOf(
      f.call(
        "create_group",
        McpPrincipal.StaticKey,
        "tenant" -> Json.fromString("acme"),
        "name"   -> Json.fromString("analysts")
      )
    )
    val r = f.idOf(
      f.call(
        "create_role",
        McpPrincipal.StaticKey,
        "tenant" -> Json.fromString("acme"),
        "name"   -> Json.fromString("reader")
      )
    )
    f.call(
      "add_membership",
      McpPrincipal.StaticKey,
      "kind"     -> Json.fromString("group_role"),
      "group_id" -> Json.fromString(g),
      "role_id"  -> Json.fromString(r)
    ).isRight shouldBe true
    f.call(
      "list_group_role_memberships",
      McpPrincipal.StaticKey,
      "group_id" -> Json.fromString(g)
    ).toOption
      .get
      .hcursor
      .downField("roles")
      .values
      .get
      .size shouldBe 1
    f.call(
      "remove_membership",
      McpPrincipal.StaticKey,
      "kind"     -> Json.fromString("group_role"),
      "group_id" -> Json.fromString(g),
      "role_id"  -> Json.fromString(r)
    ).isRight shouldBe true
    f.call(
      "list_group_role_memberships",
      McpPrincipal.StaticKey,
      "group_id" -> Json.fromString(g)
    ).toOption
      .get
      .hcursor
      .downField("roles")
      .values
      .get
      .size shouldBe 0
  }

  "delete_group and delete_role" should "delete by id" in {
    val f = new Fixture
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("acme"))
    val g = f.idOf(
      f.call(
        "create_group",
        McpPrincipal.StaticKey,
        "tenant" -> Json.fromString("acme"),
        "name"   -> Json.fromString("analysts")
      )
    )
    val r = f.idOf(
      f.call(
        "create_role",
        McpPrincipal.StaticKey,
        "tenant" -> Json.fromString("acme"),
        "name"   -> Json.fromString("reader")
      )
    )
    f.call("delete_group", McpPrincipal.StaticKey, "id" -> Json.fromString(g)).isRight shouldBe true
    f.call("delete_role", McpPrincipal.StaticKey, "id" -> Json.fromString(r)).isRight shouldBe true
  }

  "tenant scoping" should "hide other tenants' users from a tenant-scoped PAT" in {
    val f = new Fixture
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("acme"))
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("umbrella"))
    f.call(
      "create_user",
      McpPrincipal.StaticKey,
      "tenant"   -> Json.fromString("umbrella"),
      "username" -> Json.fromString("eve"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    val listed = f.call(
      "list_users",
      adminPat(), // scoped to acme
      "tenant" -> Json.fromString("umbrella")
    )
    listed.isRight shouldBe true
    listed.toOption.get.hcursor.downField("users").values.get.size shouldBe 0
  }

  it should "refuse a tenant-scoped PAT updating another tenant's user" in {
    val f   = new Fixture
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("acme"))
    f.call("create_tenant", McpPrincipal.StaticKey, "id" -> Json.fromString("umbrella"))
    val eve = f.idOf(
      f.call(
        "create_user",
        McpPrincipal.StaticKey,
        "tenant"   -> Json.fromString("umbrella"),
        "username" -> Json.fromString("eve"),
        "password" -> Json.fromString("s3cret-s3cret")
      )
    )
    val out = f.call(
      "update_user",
      adminPat(), // scoped to acme
      "id"      -> Json.fromString(eve),
      "enabled" -> Json.False
    )
    out.isLeft shouldBe true
  }
