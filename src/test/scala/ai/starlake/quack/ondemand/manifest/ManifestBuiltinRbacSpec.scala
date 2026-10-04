package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.state.{BuiltinRbac, InMemoryControlPlaneStore}
import io.circe.yaml.v12.parser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** The importer writes the store directly (no supervisor guard), so it is the one path that must
  * seed built-ins for manifest-created tenants, heal existing ones, accept references to built-ins
  * the manifest never declares, refuse altered or invented `qod_` objects, and never write a
  * built-in entry. Export omits built-ins but keeps user references to them by name.
  */
class ManifestBuiltinRbacSpec extends AnyFlatSpec with Matchers:

  private object ManifestTestKit:
    def importOk(store: InMemoryControlPlaneStore, m: ConfigManifest): Unit =
      ManifestImporter.validate(m, store) shouldBe Right(())
      ManifestImporter.apply(m, store, requireEncryption = false) shouldBe Right(())

    def exportOf(store: InMemoryControlPlaneStore): ConfigManifest =
      ManifestExporter.build(store, Instant.parse("2026-10-04T00:00:00Z"), "test", "test")

  private def manifest(
      tenants: List[ManifestTenant] = List(ManifestTenant(name = "acme")),
      roles: List[ManifestRole] = Nil,
      groups: List[ManifestGroup] = Nil,
      users: List[ManifestUser] = Nil
  ): ConfigManifest =
    ConfigManifest(
      ConfigManifest.ApiVersion,
      ConfigManifest.Kind,
      Instant.parse("2026-10-04T00:00:00Z"),
      ExportedFrom("test", "test"),
      tenants,
      roles,
      groups,
      users
    )

  private def bob(roles: List[String], groups: List[String]): ManifestUser =
    ManifestUser(
      tenant = Some("acme"),
      username = "bob",
      password = Some("pw"),
      roles = roles,
      groups = groups
    )

  private def builtinNames(store: InMemoryControlPlaneStore, tenant: String): Set[String] =
    store.listRoles(tenant).filter(_.builtin).map(_.name).toSet ++
      store.listGroups(tenant).filter(_.builtin).map(_.name).toSet

  private val AllBuiltins = BuiltinRbac.RoleNames ++ BuiltinRbac.GroupNames

  "import of a new tenant" should "seed the built-ins" in {
    val store = new InMemoryControlPlaneStore()
    ManifestTestKit.importOk(store, manifest())
    store.listRoles("acme").filter(_.builtin).map(_.name).toSet shouldBe BuiltinRbac.RoleNames
    store.listGroups("acme").filter(_.builtin).map(_.name).toSet shouldBe BuiltinRbac.GroupNames
    val allTables = store.findRole("acme", BuiltinRbac.AllTables).get
    store.listRolePermissions(allTables.id).map(p => (p.tableName, p.verb)) shouldBe
      List(("*", "ALL"))
  }

  "import of an existing tenant" should "heal missing built-ins" in {
    val store = new InMemoryControlPlaneStore()
    store.upsertTenant(Tenant(id = "acme", displayName = "acme"))
    builtinNames(store, "acme") shouldBe empty
    ManifestTestKit.importOk(store, manifest())
    builtinNames(store, "acme") shouldBe AllBuiltins
  }

  it should "be idempotent across re-imports" in {
    val store = new InMemoryControlPlaneStore()
    ManifestTestKit.importOk(store, manifest())
    val ids = store.listRoles("acme").map(_.id).toSet
    ManifestTestKit.importOk(store, manifest())
    store.listRoles("acme").map(_.id).toSet shouldBe ids
    store.listGroups("acme").count(_.builtin) shouldBe 2
  }

  "a user" should "reference built-ins without the manifest declaring them" in {
    val store = new InMemoryControlPlaneStore()
    val m     = manifest(users = List(bob(List(BuiltinRbac.AllTables), List(BuiltinRbac.AllPools))))
    ManifestTestKit.importOk(store, m)
    val uid = store.findUser(Some("acme"), "bob").get.id
    store.listDirectRolesForUser(uid) shouldBe
      List(store.findRole("acme", BuiltinRbac.AllTables).get.id)
    store.listGroupsForUser(uid) shouldBe
      List(store.findGroup("acme", BuiltinRbac.AllPools).get.id)
  }

  it should "reference built-ins of a tenant that exists only in the store" in {
    val store = new InMemoryControlPlaneStore()
    // A tenant created before built-ins existed and absent from the manifest's tenants list: the
    // import must still heal it before resolving the user's references.
    store.upsertTenant(Tenant(id = "acme", displayName = "acme"))
    val m = manifest(
      tenants = Nil,
      users = List(bob(List(BuiltinRbac.NoTables), List(BuiltinRbac.NoPools)))
    )
    ManifestTestKit.importOk(store, m)
    val uid = store.findUser(Some("acme"), "bob").get.id
    store.listDirectRolesForUser(uid) shouldBe
      List(store.findRole("acme", BuiltinRbac.NoTables).get.id)
    store.listGroupsForUser(uid) shouldBe
      List(store.findGroup("acme", BuiltinRbac.NoPools).get.id)
  }

  "a group" should "reference a built-in role without the manifest declaring it" in {
    val store = new InMemoryControlPlaneStore()
    val m     = manifest(groups =
      List(ManifestGroup("acme", "analysts", roles = List(BuiltinRbac.AllTables)))
    )
    ManifestTestKit.importOk(store, m)
    val gid = store.findGroup("acme", "analysts").get.id
    store.listRolesForGroup(gid) shouldBe List(store.findRole("acme", BuiltinRbac.AllTables).get.id)
  }

  "a superuser" should "not see built-in names as defined" in {
    val store = new InMemoryControlPlaneStore()
    val m     = manifest(users =
      List(
        ManifestUser(
          tenant = None,
          username = "root",
          password = Some("pw"),
          kind = "admin",
          roles = List(BuiltinRbac.AllTables)
        )
      )
    )
    ManifestImporter.validate(m, store).isLeft shouldBe true
  }

  "a manifest" should "refuse a modified built-in role" in {
    val store = new InMemoryControlPlaneStore()
    val m     = manifest(roles =
      List(
        ManifestRole(
          "acme",
          BuiltinRbac.NoTables,
          permissions = List(ManifestTablePermission("*", "s", "t", "RO"))
        )
      )
    )
    val res = ManifestImporter.validate(m, store)
    res.isLeft shouldBe true
    res.left.toOption.get.mkString should include(BuiltinRbac.NoTables)
  }

  it should "refuse a built-in role carrying a policy" in {
    val store = new InMemoryControlPlaneStore()
    val m     = manifest(roles =
      List(
        ManifestRole(
          "acme",
          BuiltinRbac.AllTables,
          permissions = List(ManifestTablePermission("*", "*", "*", "ALL")),
          rowPolicies = List(ManifestRoleRowPolicy(schema = "s", table = "t", predicateSql = "1=1"))
        )
      )
    )
    ManifestImporter.validate(m, store).isLeft shouldBe true
  }

  it should "accept a built-in role in its exact shape and never write it" in {
    val store = new InMemoryControlPlaneStore()
    val m     = manifest(
      roles = List(
        ManifestRole(
          "acme",
          BuiltinRbac.AllTables,
          description = Some("overwritten?"),
          permissions = List(ManifestTablePermission("*", "*", "*", "all"))
        ),
        ManifestRole("acme", BuiltinRbac.NoTables)
      ),
      groups = List(
        ManifestGroup("acme", BuiltinRbac.AllPools),
        ManifestGroup("acme", BuiltinRbac.NoPools)
      )
    )
    ManifestTestKit.importOk(store, m)
    val role = store.findRole("acme", BuiltinRbac.AllTables).get
    role.builtin shouldBe true
    role.description shouldBe Some("Built-in: ALL on every table")
    store.listRoles("acme").size shouldBe 2
    store.listGroups("acme").size shouldBe 2
    store.listRolePermissions(role.id).size shouldBe 1
  }

  it should "refuse a built-in group carrying roles" in {
    val store = new InMemoryControlPlaneStore()
    val m     = manifest(
      roles = List(ManifestRole("acme", "reader")),
      groups = List(ManifestGroup("acme", BuiltinRbac.AllPools, roles = List("reader")))
    )
    ManifestImporter.validate(m, store).isLeft shouldBe true
  }

  it should "refuse any other reserved name" in {
    val store = new InMemoryControlPlaneStore()
    ManifestImporter
      .validate(manifest(groups = List(ManifestGroup("acme", "qod_mine"))), store)
      .isLeft shouldBe true
    ManifestImporter
      .validate(manifest(roles = List(ManifestRole("acme", "QOD_ALL_TABLES"))), store)
      .isLeft shouldBe true
  }

  "export" should "omit built-ins but keep user references to them" in {
    val store = new InMemoryControlPlaneStore()
    ManifestTestKit.importOk(
      store,
      manifest(
        roles = List(ManifestRole("acme", "reader")),
        users = List(bob(List(BuiltinRbac.AllTables), List(BuiltinRbac.AllPools)))
      )
    )
    val out = ManifestTestKit.exportOf(store)
    out.roles.map(_.name) shouldBe List("reader")
    out.groups shouldBe empty
    out.users.find(_.username == "bob").get.roles shouldBe List(BuiltinRbac.AllTables)
    out.users.find(_.username == "bob").get.groups shouldBe List(BuiltinRbac.AllPools)
  }

  it should "round-trip into a fresh store with the same memberships" in {
    val src = new InMemoryControlPlaneStore()
    ManifestTestKit.importOk(
      src,
      manifest(users = List(bob(List(BuiltinRbac.AllTables), List(BuiltinRbac.AllPools))))
    )
    val dst = new InMemoryControlPlaneStore()
    ManifestTestKit.importOk(dst, ManifestTestKit.exportOf(src))
    val uid = dst.findUser(Some("acme"), "bob").get.id
    dst.listDirectRolesForUser(uid) shouldBe
      List(dst.findRole("acme", BuiltinRbac.AllTables).get.id)
    builtinNames(dst, "acme") shouldBe AllBuiltins
  }

  "the bundled demo manifests" should "leave every demo tenant with the four built-ins" in
    List("bootstrap-demo.yaml", "bootstrap-demo-minimal.yaml").foreach { res =>
      val body =
        scala.io.Source.fromResource(res).mkString
      val m =
        parser.parse(body).flatMap(_.as[ConfigManifest]).fold(e => fail(s"$res: $e"), identity)
      val store = new InMemoryControlPlaneStore()
      ManifestTestKit.importOk(store, m)
      m.tenants.foreach { t =>
        withClue(s"$res tenant ${t.name}: ") {
          builtinNames(store, t.name) shouldBe AllBuiltins
        }
      }
    }
