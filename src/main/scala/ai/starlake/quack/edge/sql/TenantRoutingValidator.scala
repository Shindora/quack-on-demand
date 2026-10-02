package ai.starlake.quack.edge.sql

/** Per-tenant arm selection. `opa` is consulted whenever the session's tenant is in OPA mode,
  * independent of `quack-flightsql.acl.enabled` (the caller passes `allowAll` as `qod` when the
  * flag is off): a tenant that configured Rego is never silently unenforced.
  */
final class TenantRoutingValidator(
    qod: StatementValidator,
    opa: StatementValidator,
    isOpaTenant: String => Boolean
) extends StatementValidator:
  override def validate(context: ValidationContext): ValidationResult =
    context.poolKey match
      case Some(k) if isOpaTenant(k.tenant) => opa.validate(context)
      case _                                => qod.validate(context)
