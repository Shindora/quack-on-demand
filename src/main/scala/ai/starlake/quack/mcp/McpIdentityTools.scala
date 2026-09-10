package ai.starlake.quack.mcp

import ai.starlake.quack.ondemand.api.{
  GroupHandlers,
  MembershipHandlers,
  RoleHandlers,
  SetTenantAuthRequest,
  SetTenantDisabledRequest,
  TenantHandlers,
  TenantOpRequest,
  TenantRequest,
  UserCreateRequest,
  UserDeleteRequest,
  UserHandlers,
  UserUpdateRequest
}
import ai.starlake.quack.ondemand.api.Dtos.given
import ai.starlake.quack.ondemand.auth.SessionScope
import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.syntax._

/** The MCP identity tier: tenants, users, groups, roles, memberships. Full-surface per spec
  * 2026-09-10 (supersedes the 2026-08-18 deny-list): every tool delegates to the SAME REST
  * handlers the admin UI uses, with the principal's raw bearer as `apiKey`, so superuser gates,
  * tenant-scope checks, self/floor guards, and the audit trail behave identically to REST.
  */
final class McpIdentityTools(
    tenants: TenantHandlers,
    users: UserHandlers,
    groups: GroupHandlers,
    roles: RoleHandlers,
    memberships: MembershipHandlers,
    scopeOf: String => Option[SessionScope]
):

  import McpToolArgs._

  def tools: List[McpToolDef] = List(
    listTenantsTool,
    createTenantTool,
    deleteTenantTool,
    setTenantAuthTool,
    setTenantDisabledTool,
    listUsersTool,
    createUserTool,
    updateUserTool,
    deleteUserTool,
    userEffectivePermissionsTool
  )

  private def keyOf(principal: McpPrincipal): Option[String] = principal.rawToken

  // ---------- tenants ----------

  private val listTenantsTool = McpToolDef(
    name = "list_tenants",
    description = "List tenants visible to the caller (superusers see all; a tenant-scoped " +
      "credential sees only its own).",
    inputSchema = objectSchema(required = Nil),
    adminOnly = true,
    run = (principal, _) =>
      tenants.listTenants(keyOf(principal))(scopeOf).map(res => bridge(res).map(_.asJson))
  )

  private val createTenantTool = McpToolDef(
    name = "create_tenant",
    description = "Create a tenant. Superuser credentials only. `id` is the lowercase slug key " +
      "used in URLs and scope checks (e.g. 'acme').",
    inputSchema = objectSchema(
      required = List("id"),
      props = "id" -> strProp("Tenant slug id (lowercase identifier starting with a letter)."),
      "display_name"  -> strProp("Human label; defaults to the id."),
      "auth_provider" -> strProp("One of db|keycloak|google|azure|aws (default db)."),
      "auth_config"   -> objProp("Provider config object; e.g. {\"issuer\": \"...\"}.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "id") match
        case Left(err) => IO.pure(Left(err))
        case Right(id) =>
          tenants
            .createTenant(
              TenantRequest(
                id = id,
                // The handler requires a non-empty display name; default to the id itself,
                // matching TenantRequest's own doc comment ("Defaults to id when empty").
                displayName = str(args, "display_name").getOrElse(id),
                authProvider = str(args, "auth_provider").getOrElse("db"),
                authConfig = mapArg(args, "auth_config")
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val deleteTenantTool = McpToolDef(
    name = "delete_tenant",
    description = "Delete a tenant. Refused while the tenant still has pools (has_pools).",
    inputSchema = objectSchema(
      required = List("name"),
      props = "name" -> strProp("Tenant id.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "name") match
        case Left(err)   => IO.pure(Left(err))
        case Right(name) =>
          tenants
            .deleteTenant(TenantOpRequest(name), keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_ => Json.obj("deleted" -> Json.fromString(name))))
  )

  private val setTenantAuthTool = McpToolDef(
    name = "set_tenant_auth",
    description = "Set a tenant's auth provider and provider config.",
    inputSchema = objectSchema(
      required = List("name", "auth_provider"),
      props = "name" -> strProp("Tenant id."),
      "auth_provider" -> strProp("One of db|keycloak|google|azure|aws."),
      "auth_config"   -> objProp("Provider config object.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        name     <- required(args, "name")
        provider <- required(args, "auth_provider")
      yield (name, provider)) match
        case Left(err) => IO.pure(Left(err))
        case Right((name, provider)) =>
          tenants
            .setTenantAuth(
              SetTenantAuthRequest(name, provider, mapArg(args, "auth_config")),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val setTenantDisabledTool = McpToolDef(
    name = "set_tenant_disabled",
    description = "Disable (true) or re-enable (false) a tenant.",
    inputSchema = objectSchema(
      required = List("name", "disabled"),
      props = "name" -> strProp("Tenant id."),
      "disabled" -> boolProp("true to disable, false to enable.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        name     <- required(args, "name")
        disabled <- bool(args, "disabled").toRight("the 'disabled' argument is required")
      yield (name, disabled)) match
        case Left(err) => IO.pure(Left(err))
        case Right((name, disabled)) =>
          tenants
            .setTenantDisabled(SetTenantDisabledRequest(name, disabled), keyOf(principal))(
              scopeOf
            )
            .map(res => bridge(res).map(_.asJson))
  )

  // ---------- users ----------

  private val listUsersTool = McpToolDef(
    name = "list_users",
    description = "List users. Superusers may filter by tenant or omit it for all; " +
      "tenant-scoped credentials see only their own tenant.",
    inputSchema = objectSchema(
      required = Nil,
      props = tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      users
        .listUsers(str(args, "tenant"), keyOf(principal))(scopeOf)
        .map(res => bridge(res).map(_.asJson))
  )

  private val createUserTool = McpToolDef(
    name = "create_user",
    description = "Create a user in a tenant. Omitting 'tenant' creates a SUPERUSER, which " +
      "only a superuser credential may do.",
    inputSchema = objectSchema(
      required = List("username", "password"),
      props = "username" -> strProp("Login name."),
      "password" -> strProp("Initial password."),
      "role"     -> strProp("user or admin (default user)."),
      "email"    -> strProp("Optional contact email."),
      "must_change_password" -> boolProp(
        "Mark the password temporary: login refused until changed."
      ),
      tenantProp
    ),
    adminOnly = true,
    run = (principal, args) =>
      (for
        username <- required(args, "username")
        password <- required(args, "password")
      yield (username, password)) match
        case Left(err) => IO.pure(Left(err))
        case Right((username, password)) =>
          users
            .createUser(
              UserCreateRequest(
                tenant = str(args, "tenant"),
                username = username,
                password = password,
                role = str(args, "role").getOrElse("user"),
                mustChangePassword = bool(args, "must_change_password").getOrElse(false),
                email = str(args, "email")
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val updateUserTool = McpToolDef(
    name = "update_user",
    description = "Update a user by id: rotate password, change role/email, enable/disable. " +
      "Omitted fields stay unchanged; empty email clears it. Cannot lock yourself or the " +
      "last enabled superuser.",
    inputSchema = objectSchema(
      required = List("id"),
      props = "id" -> strProp("User id."),
      "password" -> strProp("New password (omit = no rotation)."),
      "role"     -> strProp("user or admin."),
      "email"    -> strProp("New email; empty string clears it."),
      "must_change_password" -> boolProp("Mark the password temporary."),
      "enabled"              -> boolProp("false locks the account, true unlocks.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "id") match
        case Left(err) => IO.pure(Left(err))
        case Right(id) =>
          users
            .updateUser(
              UserUpdateRequest(
                id = id,
                password = str(args, "password"),
                role = str(args, "role"),
                mustChangePassword = bool(args, "must_change_password"),
                email = args("email").flatMap(_.asString), // preserve "" = clear
                enabled = bool(args, "enabled")
              ),
              keyOf(principal)
            )(scopeOf)
            .map(res => bridge(res).map(_.asJson))
  )

  private val deleteUserTool = McpToolDef(
    name = "delete_user",
    description = "Delete a user by id. Cannot delete yourself or the last enabled superuser.",
    inputSchema = objectSchema(
      required = List("id"),
      props = "id" -> strProp("User id.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "id") match
        case Left(err) => IO.pure(Left(err))
        case Right(id) =>
          users
            .deleteUser(UserDeleteRequest(id), keyOf(principal))(scopeOf)
            .map(res => bridge(res).map(_ => Json.obj("deleted" -> Json.fromString(id))))
  )

  private val userEffectivePermissionsTool = McpToolDef(
    name = "user_effective_permissions",
    description = "Everything a user can do: direct role, roles via groups, pool grants, and " +
      "effective table permissions.",
    inputSchema = objectSchema(
      required = List("id"),
      props = "id" -> strProp("User id.")
    ),
    adminOnly = true,
    run = (principal, args) =>
      required(args, "id") match
        case Left(err) => IO.pure(Left(err))
        case Right(id) =>
          users.effective(id, keyOf(principal))(scopeOf).map(res => bridge(res).map(_.asJson))
  )
