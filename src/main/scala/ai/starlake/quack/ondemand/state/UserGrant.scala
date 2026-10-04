package ai.starlake.quack.ondemand.state

/** One row from `qodstate_user` viewed as a management-plane grant.
  *
  * `tenant = None` is a superuser grant (matches the partial index `qodstate_user_admin_unique`).
  * Non-empty `tenant` is a tenant-scoped grant. `kind` is the account kind in `qodstate_user.kind`
  * (`admin` | `user`); only `equalsIgnoreCase("admin")` carries management privileges.
  */
final case class UserGrant(tenant: Option[String], kind: String)
