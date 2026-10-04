package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Pool, RoleDistribution, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{
  BuiltinRbac,
  InMemoryControlPlaneStore,
  RoleColumnPolicy,
  RoleRowPolicy
}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BuiltinRbacProtectionSpec extends AnyFlatSpec with Matchers:

  private final case class Fx(
      sup: PoolSupervisor,
      store: InMemoryControlPlaneStore,
      allTables: String,
      noTables: String,
      allPools: String,
      noPools: String,
      userId: String
  )

  private def fx(): Fx =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    sup.restore()
    sup.createTenant(Tenant(id = "acme")).unsafeRunSync()
    store.upsertTenantDb(
      TenantDb("td-acme", "acme", "acme_db", TenantDbKind.InMemory, Map.empty, "")
    )
    store.upsertPool(
      Pool(
        id = "p-acme",
        tenantId = "acme",
        tenantDbId = "td-acme",
        name = "bi",
        size = 1,
        distribution = RoleDistribution(0, 0, 1)
      )
    )
    sup.restore()
    val uid = store.upsertUserWithHash(Some("acme"), "bob", "x", "user")
    Fx(
      sup,
      store,
      store.findRole("acme", BuiltinRbac.AllTables).get.id,
      store.findRole("acme", BuiltinRbac.NoTables).get.id,
      store.findGroup("acme", BuiltinRbac.AllPools).get.id,
      store.findGroup("acme", BuiltinRbac.NoPools).get.id,
      uid
    )

  private def protectedErr[A](io: cats.effect.IO[Either[SupervisorError, A]]): Unit =
    io.unsafeRunSync().left.toOption.get shouldBe a[SupervisorError.BuiltinProtected]

  "built-in roles" should "refuse delete" in {
    val f = fx(); protectedErr(f.sup.deleteRole(f.allTables))
  }
  it should "refuse permission grant and revoke" in {
    val f = fx()
    protectedErr(f.sup.grantRolePermission(f.noTables, "*", "*", "*", "RO"))
    val pid = f.store.listRolePermissions(f.allTables).head.id
    protectedErr(f.sup.revokeRolePermission(pid))
    f.store.listRolePermissions(f.allTables).map(_.id) shouldBe List(pid)
  }
  it should "refuse column and row policies" in {
    val f = fx()
    protectedErr(f.sup.createColumnPolicy(f.allTables, "c", "s", "t", "col", "deny", None))
    protectedErr(f.sup.createRowPolicy(f.allTables, "c", "s", "t", "1 = 1"))
  }
  it should "refuse update and delete of a column or row policy planted on a built-in role" in {
    val f  = fx()
    val cp = f.store.insertColumnPolicy(
      RoleColumnPolicy("cp-x", f.allTables, "c", "s", "t", "col", "deny", None)
    )
    val rp = f.store.insertRowPolicy(RoleRowPolicy("rp-x", f.allTables, "c", "s", "t", "1 = 1"))
    protectedErr(f.sup.updateColumnPolicy(cp.id, "deny", None))
    protectedErr(f.sup.deleteColumnPolicy(cp.id))
    protectedErr(f.sup.updateRowPolicy(rp.id, "2 = 2"))
    protectedErr(f.sup.deleteRowPolicy(rp.id))
  }
  it should "still 404 a missing id" in {
    val f = fx()
    f.sup.deleteRole("nope").unsafeRunSync().left.toOption.get shouldBe
      a[SupervisorError.NotFound]
    f.sup.deleteGroup("nope").unsafeRunSync().left.toOption.get shouldBe
      a[SupervisorError.NotFound]
  }

  "built-in groups" should "refuse delete" in {
    val f = fx(); protectedErr(f.sup.deleteGroup(f.allPools))
  }
  it should "refuse role bindings both ways" in {
    val f = fx()
    protectedErr(f.sup.addGroupRole(f.noPools, f.allTables))
    protectedErr(f.sup.removeGroupRole(f.allPools, f.allTables))
  }
  it should "refuse pool grant and revoke" in {
    val f = fx()
    protectedErr(f.sup.grantPoolPermission("acme", Some("p-acme"), None, Some(f.noPools)))
    val ppId = f.store.listPoolPermissionsForGroup(f.allPools).head.id
    protectedErr(f.sup.revokePoolPermission(ppId))
  }

  "memberships" should "stay editable on built-ins" in {
    val f = fx()
    f.sup.addUserRole(f.userId, f.allTables).unsafeRunSync().isRight shouldBe true
    f.sup.removeUserRole(f.userId, f.allTables).unsafeRunSync().isRight shouldBe true
    f.sup.addUserGroup(f.userId, f.allPools).unsafeRunSync().isRight shouldBe true
    f.sup.removeUserGroup(f.userId, f.allPools).unsafeRunSync().isRight shouldBe true
  }
  it should "allow binding a built-in role to a custom group" in {
    val f = fx()
    val g = f.sup.createGroup("acme", "ops").unsafeRunSync().toOption.get
    f.sup.addGroupRole(g.id, f.allTables).unsafeRunSync().isRight shouldBe true
  }

  "the qod_ prefix" should "be reserved on role and group create" in {
    val f = fx()
    f.sup.createRole("acme", "qod_mine").unsafeRunSync().left.toOption.get shouldBe
      a[SupervisorError.ReservedName]
    f.sup.createGroup("acme", "QOD_mine").unsafeRunSync().left.toOption.get shouldBe
      a[SupervisorError.ReservedName]
  }

  "the built-in guards" should "read the store, not a resolver that has not refreshed" in {
    // An HA replica whose resolver predates a peer's createTenant: the built-ins exist in the
    // store but were never loaded into this supervisor's resolver (no restore after insert).
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    sup.restore()
    store.upsertTenant(Tenant(id = "peer"))
    store.ensureBuiltins("peer")
    val allTables = store.findRole("peer", BuiltinRbac.AllTables).get.id
    val allPools  = store.findGroup("peer", BuiltinRbac.AllPools).get.id
    val permId    = store.listRolePermissions(allTables).head.id
    val ppId      = store.listPoolPermissionsForGroup(allPools).head.id
    protectedErr(sup.revokeRolePermission(permId))
    protectedErr(sup.revokePoolPermission(ppId))
    store.listRolePermissions(allTables).map(_.id) shouldBe List(permId)
    store.listPoolPermissionsForGroup(allPools).map(_.id) shouldBe List(ppId)
  }
