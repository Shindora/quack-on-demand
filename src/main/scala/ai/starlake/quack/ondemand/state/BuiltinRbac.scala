package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.Names

import java.util.Locale

/** The four protected objects every tenant carries. Roles grant tables, groups grant pools; the
  * built-in groups never carry roles. The `qod_` prefix is reserved for them on every create path.
  */
object BuiltinRbac:
  val NoTables: String  = "qod_no_tables"
  val AllTables: String = "qod_all_tables"
  val NoPools: String   = "qod_no_pools"
  val AllPools: String  = "qod_all_pools"

  val RoleNames: Set[String]  = Set(NoTables, AllTables)
  val GroupNames: Set[String] = Set(NoPools, AllPools)

  /** Applied when a tenant-user create omits the list (never when it sends an empty one). */
  val DefaultRoles: List[String]  = List(AllTables)
  val DefaultGroups: List[String] = List(AllPools)

  val ReservedPrefix: String = "qod_"

  def isReserved(name: String): Boolean =
    name.toLowerCase(Locale.ROOT).startsWith(ReservedPrefix)

  /** The name a user-made row colliding with a built-in name is renamed to on backfill: the first
    * of `<name>_renamed`, `<name>_renamed_2`, `<name>_renamed_3`, ... that `taken` reports free.
    */
  def renamedName(name: String, taken: String => Boolean): String =
    Iterator
      .from(1)
      .map(i => if i == 1 then s"${name}_renamed" else s"${name}_renamed_$i")
      .find(n => !taken(n))
      .get

  /** `changed` is set by `ControlPlaneStore.ensureBuiltins` when it wrote anything (a rename, an
    * insert, a restored permission or grant), so a caller can tell peers to refresh.
    */
  final case class Rows(
      roles: List[RbacRole],
      groups: List[RbacGroup],
      permissions: List[RolePermission],
      poolGrants: List[PoolPermission],
      changed: Boolean = false
  )

  /** Fresh rows (new ids) for one tenant. */
  def rowsFor(tenantId: String): Rows =
    val noTables = RbacRole(
      Names.newSurrogateId("r"),
      tenantId,
      NoTables,
      Some("Built-in: no table access"),
      builtin = true
    )
    val allTables = RbacRole(
      Names.newSurrogateId("r"),
      tenantId,
      AllTables,
      Some("Built-in: ALL on every table"),
      builtin = true
    )
    val noPools = RbacGroup(
      Names.newSurrogateId("g"),
      tenantId,
      NoPools,
      Some("Built-in: no pool access"),
      builtin = true
    )
    val allPools = RbacGroup(
      Names.newSurrogateId("g"),
      tenantId,
      AllPools,
      Some("Built-in: access to every pool"),
      builtin = true
    )
    Rows(
      roles = List(noTables, allTables),
      groups = List(noPools, allPools),
      permissions = List(
        RolePermission(
          Names.newSurrogateId("rp"),
          allTables.id,
          RolePermission.Wildcard,
          RolePermission.Wildcard,
          RolePermission.Wildcard,
          "ALL"
        )
      ),
      poolGrants = List(
        PoolPermission(Names.newSurrogateId("pp"), tenantId, None, None, Some(allPools.id))
      )
    )
