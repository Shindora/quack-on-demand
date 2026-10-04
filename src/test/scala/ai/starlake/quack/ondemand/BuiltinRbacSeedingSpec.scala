package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{BuiltinRbac, InMemoryControlPlaneStore}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BuiltinRbacSeedingSpec extends AnyFlatSpec with Matchers:

  private def fresh(): (PoolSupervisor, InMemoryControlPlaneStore) =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    sup.restore()
    (sup, store)

  "createTenant" should "seed exactly the four built-ins and no admin role" in {
    val (sup, store) = fresh()
    sup.createTenant(Tenant(id = "acme")).unsafeRunSync().isRight shouldBe true
    store.listRoles("acme").map(r => r.name -> r.builtin).toSet shouldBe
      Set(BuiltinRbac.NoTables -> true, BuiltinRbac.AllTables -> true)
    store.listGroups("acme").map(g => g.name -> g.builtin).toSet shouldBe
      Set(BuiltinRbac.NoPools -> true, BuiltinRbac.AllPools -> true)
  }

  it should "give qod_all_tables one *.*.* ALL permission and qod_no_tables none" in {
    val (sup, store) = fresh()
    sup.createTenant(Tenant(id = "acme")).unsafeRunSync()
    val all  = store.findRole("acme", BuiltinRbac.AllTables).get
    val none = store.findRole("acme", BuiltinRbac.NoTables).get
    store
      .listRolePermissions(all.id)
      .map(p => (p.catalogName, p.schemaName, p.tableName, p.verb)) shouldBe List(
      ("*", "*", "*", "ALL")
    )
    store.listRolePermissions(none.id) shouldBe Nil
  }

  it should "give qod_all_pools one tenant-wide pool grant and qod_no_pools none, no role bindings" in {
    val (sup, store) = fresh()
    sup.createTenant(Tenant(id = "acme")).unsafeRunSync()
    val all  = store.findGroup("acme", BuiltinRbac.AllPools).get
    val none = store.findGroup("acme", BuiltinRbac.NoPools).get
    store.listPoolPermissionsForGroup(all.id).map(p => (p.tenantId, p.poolId)) shouldBe
      List(("acme", None))
    store.listPoolPermissionsForGroup(none.id) shouldBe Nil
    store.listRolesForGroup(all.id) shouldBe Nil
    store.listRolesForGroup(none.id) shouldBe Nil
  }

  "BuiltinRbac.isReserved" should "match the qod_ prefix case-insensitively" in {
    BuiltinRbac.isReserved("qod_x") shouldBe true
    BuiltinRbac.isReserved("QOD_x") shouldBe true
    BuiltinRbac.isReserved("analyst") shouldBe false
  }
