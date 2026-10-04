package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** Existing tenants (created before built-ins existed, or by a path that bypassed seeding) get the
  * built-ins on boot; a pristine legacy `admin` role folds into qod_all_tables; anything else is
  * left alone; running twice changes nothing.
  */
class BuiltinRbacBackfillSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodbib")

  private def withStore(test: PostgresControlPlaneStore => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodbib_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(store)
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  /** A tenant as an old manager left it: the row plus a legacy `admin` role (*.*.* ALL). */
  private def legacyTenant(store: ControlPlaneStore, id: String): RbacRole =
    store.upsertTenant(Tenant(id = id))
    val admin = RbacRole(s"r-$id-admin", id, "admin", Some("legacy"))
    store.upsertRole(admin)
    store.insertRolePermission(RolePermission(s"rp-$id-admin", admin.id, "*", "*", "*", "ALL"))
    admin

  "run" should "seed the four built-ins into a tenant that has none" in withStore { store =>
    store.upsertTenant(Tenant(id = "acme"))
    BuiltinRbacBackfill.run(store)
    store.listRoles("acme").filter(_.builtin).map(_.name).toSet shouldBe BuiltinRbac.RoleNames
    store.listGroups("acme").filter(_.builtin).map(_.name).toSet shouldBe BuiltinRbac.GroupNames
    val allPools = store.findGroup("acme", BuiltinRbac.AllPools).get
    store.listPoolPermissionsForGroup(allPools.id).map(_.poolId) shouldBe List(None)
    val allTables = store.findRole("acme", BuiltinRbac.AllTables).get
    store
      .listRolePermissions(allTables.id)
      .map(p => (p.catalogName, p.schemaName, p.tableName, p.verb)) shouldBe
      List(("*", "*", "*", "ALL"))
    // Exact descriptions, not "" (nullable binding of the BuiltinRbac values).
    allTables.description shouldBe Some("Built-in: ALL on every table")
    allPools.description shouldBe Some("Built-in: access to every pool")
  }

  it should "return the rows as stored, preserving existing ids" in withStore { store =>
    store.upsertTenant(Tenant(id = "acme"))
    val first  = store.ensureBuiltins("acme")
    val second = store.ensureBuiltins("acme")
    second.roles.map(_.id).toSet shouldBe first.roles.map(_.id).toSet
    second.groups.map(_.id).toSet shouldBe first.groups.map(_.id).toSet
    second.permissions.map(_.id) shouldBe first.permissions.map(_.id)
    second.poolGrants.map(_.id) shouldBe first.poolGrants.map(_.id)
    first.roles.size shouldBe 2
    first.groups.size shouldBe 2
    first.permissions.size shouldBe 1
    first.poolGrants.size shouldBe 1
  }

  it should "restore a missing qod_all_tables permission and qod_all_pools grant" in withStore {
    store =>
      store.upsertTenant(Tenant(id = "acme"))
      val rows = store.ensureBuiltins("acme")
      rows.permissions.foreach(p => store.deleteRolePermission(p.id))
      rows.poolGrants.foreach(p => store.deletePoolPermission(p.id))
      val healed = store.ensureBuiltins("acme")
      healed.permissions.size shouldBe 1
      healed.poolGrants.size shouldBe 1
      healed.poolGrants.head.poolId shouldBe None
  }

  it should "fold a pristine admin role into qod_all_tables, moving user and group edges" in
    withStore { store =>
      val admin = legacyTenant(store, "acme")
      val uid   = store.upsertUserWithHash(Some("acme"), "bob", "x", "user")
      store.addUserRole(uid, admin.id)
      store.upsertGroup(RbacGroup("g-ops", "acme", "ops"))
      store.addGroupRole("g-ops", admin.id)
      BuiltinRbacBackfill.run(store)
      val all = store.findRole("acme", BuiltinRbac.AllTables).get
      store.findRole("acme", "admin") shouldBe None
      store.listDirectRolesForUser(uid) shouldBe List(all.id)
      store.listRolesForGroup("g-ops") shouldBe List(all.id)
    }

  it should "keep an admin role that was customized" in withStore { store =>
    val admin = legacyTenant(store, "acme")
    store.insertRolePermission(RolePermission("rp-extra", admin.id, "c", "s", "t", "RO"))
    BuiltinRbacBackfill.run(store)
    store.findRole("acme", "admin").map(_.builtin) shouldBe Some(false)
  }

  it should "keep an admin role that carries a column policy" in withStore { store =>
    val admin = legacyTenant(store, "acme")
    store.insertColumnPolicy(
      RoleColumnPolicy("cp-1", admin.id, "c", "s", "t", "col", "mask", Some("'***'"))
    )
    BuiltinRbacBackfill.run(store)
    store.findRole("acme", "admin").map(_.builtin) shouldBe Some(false)
  }

  it should "rename a user-made role or group that collides with a built-in name" in withStore {
    store =>
      store.upsertTenant(Tenant(id = "acme"))
      store.upsertRole(RbacRole("r-mine", "acme", BuiltinRbac.AllTables))
      store.upsertGroup(RbacGroup("g-mine", "acme", BuiltinRbac.NoPools))
      BuiltinRbacBackfill.run(store)
      store.getRole("r-mine").map(_.name) shouldBe Some(s"${BuiltinRbac.AllTables}_renamed")
      store.getGroup("g-mine").map(_.name) shouldBe Some(s"${BuiltinRbac.NoPools}_renamed")
      store.findRole("acme", BuiltinRbac.AllTables).map(_.builtin) shouldBe Some(true)
  }

  it should "be idempotent" in withStore { store =>
    legacyTenant(store, "acme")
    BuiltinRbacBackfill.run(store)
    val roles1  = store.listRoles("acme").map(r => r.id -> r.name).toSet
    val groups1 = store.listGroups("acme").map(g => g.id -> g.name).toSet
    val perms1  = store.listRolePermissions(store.findRole("acme", BuiltinRbac.AllTables).get.id)
    BuiltinRbacBackfill.run(store)
    store.listRoles("acme").map(r => r.id -> r.name).toSet shouldBe roles1
    store.listGroups("acme").map(g => g.id -> g.name).toSet shouldBe groups1
    store
      .listRolePermissions(store.findRole("acme", BuiltinRbac.AllTables).get.id)
      .map(_.id) shouldBe
      perms1.map(_.id)
    val allPools = store.findGroup("acme", BuiltinRbac.AllPools).get
    store.listPoolPermissionsForGroup(allPools.id).size shouldBe 1
  }

  it should "leave a tenant created with built-ins unchanged" in withStore { store =>
    val rows = BuiltinRbac.rowsFor("acme")
    store.createTenantWithBuiltins(Tenant(id = "acme"), rows)
    val stored = store.ensureBuiltins("acme")
    stored.roles.map(_.id).toSet shouldBe rows.roles.map(_.id).toSet
    stored.groups.map(_.id).toSet shouldBe rows.groups.map(_.id).toSet
    stored.permissions.map(_.id) shouldBe rows.permissions.map(_.id)
    stored.poolGrants.map(_.id) shouldBe rows.poolGrants.map(_.id)
  }

  // ---------------- In-memory parity (supervisor tests run on it) ----------------

  "InMemoryControlPlaneStore" should "seed, fold and stay idempotent like Postgres" in {
    val store = new InMemoryControlPlaneStore
    val admin = legacyTenant(store, "acme")
    store.upsertRole(RbacRole("r-mine", "acme", BuiltinRbac.NoTables))
    val uid = store.upsertUserWithHash(Some("acme"), "bob", "x", "user")
    store.addUserRole(uid, admin.id)
    store.upsertGroup(RbacGroup("g-ops", "acme", "ops"))
    store.addGroupRole("g-ops", admin.id)
    BuiltinRbacBackfill.run(store)
    val all = store.findRole("acme", BuiltinRbac.AllTables).get
    all.builtin shouldBe true
    store.findRole("acme", "admin") shouldBe None
    store.getRole("r-mine").map(_.name) shouldBe Some(s"${BuiltinRbac.NoTables}_renamed")
    store.listDirectRolesForUser(uid) shouldBe List(all.id)
    store.listRolesForGroup("g-ops") shouldBe List(all.id)
    val before = store.ensureBuiltins("acme")
    BuiltinRbacBackfill.run(store)
    val after = store.ensureBuiltins("acme")
    after.roles.map(_.id).toSet shouldBe before.roles.map(_.id).toSet
    after.groups.map(_.id).toSet shouldBe before.groups.map(_.id).toSet
    after.permissions.map(_.id) shouldBe before.permissions.map(_.id)
    after.poolGrants.map(_.id) shouldBe before.poolGrants.map(_.id)
  }

  it should "keep a customized admin role" in {
    val store = new InMemoryControlPlaneStore
    val admin = legacyTenant(store, "acme")
    store.insertRolePermission(RolePermission("rp-extra", admin.id, "c", "s", "t", "RO"))
    BuiltinRbacBackfill.run(store)
    store.findRole("acme", "admin").map(_.builtin) shouldBe Some(false)
  }

  it should "preserve the builtin flag across a role or group upsert, like Postgres" in {
    val store = new InMemoryControlPlaneStore
    store.upsertTenant(Tenant(id = "acme"))
    val rows = store.ensureBuiltins("acme")
    val role = rows.roles.head
    val grp  = rows.groups.head
    // ManifestImporter re-upserts without the flag.
    store.upsertRole(role.copy(description = Some("x"), builtin = false))
    store.upsertGroup(grp.copy(description = Some("y"), builtin = false))
    store.getRole(role.id).map(r => (r.description, r.builtin)) shouldBe Some((Some("x"), true))
    store.getGroup(grp.id).map(g => (g.description, g.builtin)) shouldBe Some((Some("y"), true))
    // A fresh id never gets the flag through an upsert either.
    store.upsertRole(RbacRole("r-new", "acme", "new", builtin = true))
    store.getRole("r-new").map(_.builtin) shouldBe Some(false)
  }
