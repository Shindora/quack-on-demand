package ai.starlake.quack.edge.opa

import ai.starlake.acl.parser.TableAccess
import io.circe.{Json, JsonObject}
import io.circe.parser.{parse => parseJson}

enum Decision:
  case Allow(decisionId: Option[String])
  case Deny(reason: String, denied: Set[TableAccess], decisionId: Option[String])

  /** Transport failure (timeout, refused, non-2xx). Never cached; surfaces as UNAVAILABLE. */
  case Error(cause: String)

/** Fail-closed parsing of an OPA `/v1/data` response: only a literal `true` at `result.allow`
  * allows. See the decision-parsing table in the design spec.
  */
object OpaDecision:

  val ReservedKeys: Set[String] = Set("row_filter", "masks")

  def sanitize(reason: String): String =
    reason.filterNot(c => Character.isISOControl(c)).take(256)

  private def policyError(msg: String, id: Option[String], acc: Set[TableAccess]): Decision =
    Decision.Deny(s"policy error: $msg", acc, id)

  def parse(body: String, accesses: Set[TableAccess]): Decision =
    parseJson(body).toOption.flatMap(_.asObject) match
      case None       => policyError("OPA response is not a JSON object", None, accesses)
      case Some(root) =>
        val id = root("decision_id").flatMap(_.asString)
        root("result").flatMap(_.asObject) match
          case None =>
            policyError(
              "undefined decision (no rule at the policy path, or result is not an object)",
              id,
              accesses
            )
          case Some(res) if ReservedKeys.exists(res.contains) =>
            policyError("row_filter/masks are not supported in this QoD version", id, accesses)
          case Some(res) =>
            res("allow") match
              case Some(Json.True)  => Decision.Allow(id)
              case Some(Json.False) =>
                val reason = res("reason").flatMap(_.asString).map(sanitize).filter(_.nonEmpty)
                Decision.Deny(reason.getOrElse("denied by policy"), named(res, accesses), id)
              case _ => policyError("allow must be the boolean true or false", id, accesses)

  /** `denied` is advisory: entries matching an input access narrow the error message; anything else
    * is ignored. An empty match names every access.
    */
  private def named(res: JsonObject, accesses: Set[TableAccess]): Set[TableAccess] =
    val keys = res("denied")
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
      .flatMap { j =>
        val c = j.hcursor
        for
          cat <- c.get[String]("catalog").toOption
          sch <- c.get[String]("schema").toOption
          tab <- c.get[String]("table").toOption
          vb  <- c.get[String]("verb").toOption
        yield (cat.toLowerCase, sch.toLowerCase, tab.toLowerCase, vb.toLowerCase)
      }
      .toSet
    val hit = accesses.filter(a =>
      keys.contains((a.table.database, a.table.schema, a.table.table, OpaInput.verbName(a.verb)))
    )
    if hit.isEmpty then accesses else hit
