package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.api.{UserCreateRequest, UserHandlers}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.rbac.UserMemberships
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{
  BuiltinRbac,
  ControlPlaneStore,
  LiquibaseRunner,
  PostgresControlPlaneStore,
  UserStore
}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

class UserMembershipsSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodum")

  /** Fails the membership transaction, so the compensation path runs. */
  private final class FailingAttachStore(inner: PostgresControlPlaneStore)
      extends ControlPlaneStore:
    export inner.{addUserMemberships as _, *}
    def addUserMemberships(userId: String, roleIds: List[String], groupIds: List[String]): Unit =
      throw new RuntimeException("boom")

  private def withSup(
      test: (PoolSupervisor, PostgresControlPlaneStore, UserStore) => Unit,
      failAttach: Boolean = false
  ): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodum_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store     = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val userStore = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val supStore  = if failAttach then new FailingAttachStore(store) else store
      val sup       = new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, supStore)
      sup.restore()
      sup.createTenant(Tenant(id = "acme")).unsafeRunSync()
      try test(sup, store, userStore)
      finally
        userStore.close()
        store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def create(
      sup: PoolSupervisor,
      users: UserStore,
      tenant: Option[String],
      m: UserMemberships
  ) = sup.createUser(tenant, "bob", "pw", "user", users, m).unsafeRunSync()

  private def names(store: PostgresControlPlaneStore, uid: String): (Set[String], Set[String]) =
    (
      store.listDirectRolesForUser(uid).flatMap(store.getRole).map(_.name).toSet,
      store.listGroupsForUser(uid).flatMap(store.getGroup).map(_.name).toSet
    )

  "createUser" should "default omitted lists to qod_all_tables and qod_all_pools" in withSup {
    (sup, store, users) =>
      val u = create(sup, users, Some("acme"), UserMemberships.Requested(None, None)).toOption.get
      names(store, u.id) shouldBe ((Set(BuiltinRbac.AllTables), Set(BuiltinRbac.AllPools)))
  }

  it should "attach exactly the named roles and groups" in withSup { (sup, store, users) =>
    sup.createRole("acme", "analyst").unsafeRunSync()
    val u = create(
      sup,
      users,
      Some("acme"),
      UserMemberships.Requested(
        Some(List("analyst", BuiltinRbac.NoTables)),
        Some(List(BuiltinRbac.NoPools))
      )
    ).toOption.get
    names(store, u.id) shouldBe ((Set("analyst", BuiltinRbac.NoTables), Set(BuiltinRbac.NoPools)))
  }

  it should "refuse an empty list with roles_required / groups_required" in withSup {
    (sup, _, users) =>
      create(sup, users, Some("acme"), UserMemberships.Requested(Some(Nil), None)) shouldBe
        Left(
          SupervisorError.InvalidMembership(
            "roles_required",
            "a tenant user needs at least one role"
          )
        )
      create(sup, users, Some("acme"), UserMemberships.Requested(None, Some(Nil))) shouldBe
        Left(
          SupervisorError
            .InvalidMembership("groups_required", "a tenant user needs at least one group")
        )
  }

  it should "refuse an unknown name and leave no user row behind" in withSup {
    (sup, store, users) =>
      val out =
        create(sup, users, Some("acme"), UserMemberships.Requested(Some(List("nope")), None))
      out.left.toOption.get match
        case SupervisorError.InvalidMembership(code, _) => code shouldBe "unknown_role"
        case other                                      => fail(other.toString)
      store.findUser(Some("acme"), "bob") shouldBe None
      create(
        sup,
        users,
        Some("acme"),
        UserMemberships.Requested(None, Some(List("nope")))
      ).left.toOption.get
        .asInstanceOf[SupervisorError.InvalidMembership]
        .code shouldBe "unknown_group"
      store.findUser(Some("acme"), "bob") shouldBe None
  }

  it should "refuse roles or groups on a superuser" in withSup { (sup, _, users) =>
    create(sup, users, None, UserMemberships.Requested(Some(List("x")), None)).left.toOption.get
      .asInstanceOf[SupervisorError.InvalidMembership]
      .code shouldBe "memberships_not_applicable"
    create(sup, users, None, UserMemberships.Requested(None, Some(List("x")))).left.toOption.get
      .asInstanceOf[SupervisorError.InvalidMembership]
      .code shouldBe "memberships_not_applicable"
    create(sup, users, None, UserMemberships.Requested(None, None)).isRight shouldBe true
  }

  it should "never widen an existing user's access with the defaults on a re-create upsert" in
    withSup { (sup, store, users) =>
      val first = create(
        sup,
        users,
        Some("acme"),
        UserMemberships.Requested(Some(List(BuiltinRbac.NoTables)), Some(List(BuiltinRbac.NoPools)))
      ).toOption.get
      val again =
        create(sup, users, Some("acme"), UserMemberships.Requested(None, None)).toOption.get
      again.id shouldBe first.id
      names(store, first.id) shouldBe ((Set(BuiltinRbac.NoTables), Set(BuiltinRbac.NoPools)))
    }

  it should "not resolve an omitted default for an existing user (no spurious unknown_*)" in
    withSup { (sup, store, users) =>
      sup.createRole("acme", "analyst").unsafeRunSync()
      val first = create(
        sup,
        users,
        Some("acme"),
        UserMemberships.Requested(Some(List("analyst")), Some(List(BuiltinRbac.NoPools)))
      ).toOption.get
      // A tenant missing a built-in (store-level delete bypasses the protection).
      store.deleteRole(store.findRole("acme", BuiltinRbac.AllTables).get.id)
      create(sup, users, Some("acme"), UserMemberships.Requested(None, None)).isRight shouldBe true
      names(store, first.id) shouldBe ((Set("analyst"), Set(BuiltinRbac.NoPools)))
    }

  it should "add nothing for an IdP-managed (SCIM) create" in withSup { (sup, store, users) =>
    val u = create(sup, users, Some("acme"), UserMemberships.IdpManaged).toOption.get
    names(store, u.id) shouldBe ((Set.empty, Set.empty))
  }

  it should "delete the just-inserted user row when attaching fails" in withSup(
    (sup, store, users) =>
      val out = create(sup, users, Some("acme"), UserMemberships.Requested(None, None))
      out.isLeft shouldBe true
      out.left.toOption.get shouldBe a[SupervisorError.Internal]
      store.findUser(Some("acme"), "bob") shouldBe None
    ,
    failAttach = true
  )

  "REST user/create" should "answer 500 with a generic message when attaching fails" in withSup(
    (sup, store, users) =>
      val scopeOf: String => Option[SessionScope] = _ => None
      val res                                     = new UserHandlers(sup, users)
        .createUser(UserCreateRequest(Some("acme"), "bob", "pw"), None)(scopeOf)
        .unsafeRunSync()
      val (status, body) = res.left.toOption.get
      status.code shouldBe 500
      body.error shouldBe "internal"
      body.message should not include "boom"
      store.findUser(Some("acme"), "bob") shouldBe None
    ,
    failAttach = true
  )
