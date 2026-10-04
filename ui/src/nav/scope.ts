/** The tenant context the sidebar and every scoped page work in. It lives in the URL:
  * `/t/<slug>/...` for one tenant, `/all/...` for every tenant (superusers only). */
export type Scope = { kind: 'all' } | { kind: 'tenant'; tenant: string };

export const ALL: Scope = { kind: 'all' };

/** Sections (first segment after the scope prefix) that only make sense for one tenant. */
const TENANT_ONLY = new Set([
  'databases', 'catalog', 'pools', 'maintenance', 'branches',
  'auth-provider', 'access-control', 'groups', 'roles',
]);

export function scopePrefix(scope: Scope): string {
  return scope.kind === 'all' ? '/all' : `/t/${encodeURIComponent(scope.tenant)}`;
}

export function sameScope(a: Scope, b: Scope): boolean {
  if (a.kind === 'all' || b.kind === 'all') return a.kind === b.kind;
  return a.tenant === b.tenant;
}

export interface ScopedPath {
  scope: Scope;
  /** The rest of the path after the prefix, still URL-encoded (`pools/tpch/bi`). */
  section: string;
}

function decodeSegment(s: string): string {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
}

/** Splits `/t/acme/pools/x` into its scope and section; null for paths outside any scope. */
export function parseScope(pathname: string): ScopedPath | null {
  const segs = pathname.split('/').filter(s => s !== '');
  if (segs[0] === 'all') return { scope: ALL, section: segs.slice(1).join('/') };
  if (segs[0] === 't' && segs.length >= 2) {
    return {
      scope: { kind: 'tenant', tenant: decodeSegment(segs[1]) },
      section: segs.slice(2).join('/'),
    };
  }
  return null;
}

export function sectionNeedsTenant(section: string): boolean {
  return TENANT_ONLY.has(section.split('/')[0]);
}

/** The list-level root of a section: detail routes (`pools/db/p`) drop to their list (`pools`),
  * audit keeps its sub-page (`audit/statements`), an empty section is the dashboard. */
export function sectionRoot(section: string): string {
  const segs = section.split('/').filter(s => s !== '');
  if (segs.length === 0) return 'dashboard';
  return segs[0] === 'audit' ? segs.slice(0, 2).join('/') : segs[0];
}

/** Where the tenant switcher goes: same section under `next`, list level only (a detail route
  * names a db or pool of the previous tenant), dashboard when `next` cannot show the section or
  * the current page is outside any scope. */
export function switchTenantPath(pathname: string, next: Scope): string {
  const prefix = scopePrefix(next);
  const parsed = parseScope(pathname);
  if (!parsed) return `${prefix}/dashboard`;
  const root = sectionRoot(parsed.section);
  if (next.kind === 'all' && sectionNeedsTenant(root)) return `${prefix}/dashboard`;
  return `${prefix}/${root}`;
}

export interface Principal {
  superuser: boolean;
  /** Tenant slug the session is bound to; null for a superuser. */
  ownTenant: string | null;
  /** Tenant slugs a non-superuser may switch among (whoami's manageableTenants); ignored for
    * superusers. `ownTenant` is always allowed, listed here or not. */
  allowedTenants: string[];
}

export function defaultScope(p: Principal): Scope {
  if (p.superuser) return ALL;
  const tenant = p.ownTenant ?? p.allowedTenants[0] ?? null;
  return tenant ? { kind: 'tenant', tenant } : ALL;
}

/** Null when `p` may use `scope`, else the scope to redirect to. Usability only: the server
  * already answers 403 tenant_forbidden for another tenant's resources. */
export function scopeRedirect(scope: Scope, p: Principal): Scope | null {
  if (p.superuser) return null;
  if (scope.kind === 'tenant'
    && (scope.tenant === p.ownTenant || p.allowedTenants.includes(scope.tenant))) {
    return null;
  }
  const def = defaultScope(p);
  // A principal with no tenant at all defaults to ALL; do not bounce ALL onto itself.
  return sameScope(scope, def) ? null : def;
}
