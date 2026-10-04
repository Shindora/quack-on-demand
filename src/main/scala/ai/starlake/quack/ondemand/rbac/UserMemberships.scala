package ai.starlake.quack.ondemand.rbac

/** What a user create attaches. Deliberately has no default at the call site: every creation path
  * states whether it applies the built-in defaults or leaves membership to an IdP.
  */
enum UserMemberships:
  /** REST, CLI, MCP, SQL dialect. A tenant user ends with at least one role and one group: `None`
    * means the built-in default (`qod_all_tables` / `qod_all_pools`), `Some(Nil)` is refused. For a
    * superuser both must be `None` (RBAC does not apply).
    */
  case Requested(roles: Option[List[String]], groups: Option[List[String]])

  /** SCIM: the IdP pushes group membership afterwards; nothing is added, nothing is required. */
  case IdpManaged
