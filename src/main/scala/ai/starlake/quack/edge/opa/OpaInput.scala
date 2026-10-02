package ai.starlake.quack.edge.opa

import ai.starlake.acl.parser.{TableAccess, Verb}
import io.circe.Json

/** Addressed pool. `parentPools` is non-empty only for a branch pool (`__br_<id8>`) and lists the
  * parent tenant-db's pool names, so a policy can mirror "a branch inherits any parent pool".
  */
final case class OpaTarget(
    tenant: String,
    tenantDb: String,
    pool: String,
    parentPools: List[String]
)

final case class OpaUser(
    name: String,
    roles: List[String],
    groups: List[String],
    claims: Map[String, String]
)

/** Builds the `{"input": ...}` documents of the OPA contract. Pure; golden-tested in OpaInputSpec:
  * any change here is a breaking change for every customer policy.
  */
object OpaInput:

  def verbName(v: Verb): String = v match
    case Verb.Read  => "read"
    case Verb.Write => "write"
    case Verb.Ddl   => "ddl"

  def sorted(accesses: Set[TableAccess]): List[TableAccess] =
    accesses.toList.sortBy(a => (a.table.canonical, verbName(a.verb)))

  private def accessJson(a: TableAccess): Json = Json.obj(
    "catalog" -> Json.fromString(a.table.database),
    "schema"  -> Json.fromString(a.table.schema),
    "table"   -> Json.fromString(a.table.table),
    "verb"    -> Json.fromString(verbName(a.verb))
  )

  private def common(kind: String, t: OpaTarget, u: OpaUser, edge: String): List[(String, Json)] =
    List(
      "kind"       -> Json.fromString(kind),
      "tenant"     -> Json.fromString(t.tenant),
      "database"   -> Json.fromString(t.tenantDb),
      "pool"       -> Json.fromString(t.pool),
      "parentPool" -> (if t.parentPools.isEmpty then Json.Null
                       else Json.arr(t.parentPools.sorted.map(Json.fromString)*)),
      "user" -> Json.obj(
        "name"   -> Json.fromString(u.name),
        "roles"  -> Json.arr(u.roles.sorted.map(Json.fromString)*),
        "groups" -> Json.arr(u.groups.sorted.map(Json.fromString)*),
        "claims" -> Json.obj(u.claims.toList.sortBy(_._1).map((k, v) => k -> Json.fromString(v))*)
      ),
      "client" -> Json.obj("edge" -> Json.fromString(edge))
    )

  def connect(target: OpaTarget, user: OpaUser, edge: String): Json =
    Json.obj("input" -> Json.obj(common("connect", target, user, edge)*))

  def statement(
      target: OpaTarget,
      user: OpaUser,
      edge: String,
      statementClass: String,
      accesses: Set[TableAccess],
      text: Option[String]
  ): Json =
    val stmt = Json.obj(
      (("class" -> Json.fromString(statementClass)) ::
        text.map(s => "text" -> Json.fromString(s)).toList)*
    )
    Json.obj(
      "input" -> Json.obj(
        (common("statement", target, user, edge) ++ List(
          "statement" -> stmt,
          "accesses"  -> Json.arr(sorted(accesses).map(accessJson)*)
        ))*
      )
    )
