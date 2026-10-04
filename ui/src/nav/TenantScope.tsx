import { createContext, useContext } from 'react';
import { Link, Navigate, Outlet, useLocation, useParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import {
  ALL, parseScope, scopePrefix, scopeRedirect, sectionNeedsTenant, type Principal, type Scope,
} from './scope';
import { useTenants } from './TenantsContext';

interface TenantScopeValue {
  scope: Scope;
  /** The scope's tenant slug, or null under All tenants. */
  scopedTenant: string | null;
}

const Ctx = createContext<TenantScopeValue | null>(null);

export function useTenantScope(): TenantScopeValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('useTenantScope outside a scoped route');
  return v;
}

/** `!authEnabled` is the no-auth dev mode: the synthetic anonymous user is a superuser. */
export function usePrincipal(): Principal {
  const { authEnabled, tenant } = useAuth();
  return { superuser: !authEnabled || tenant === null, ownTenant: tenant };
}

/** Layout route for `/t/:tenant/*` and `/all/*`: resolves the scope, pins tenant admins to their
  * tenant, and stops tenant-only sections under All tenants with a pick-a-tenant card. */
export function ScopedLayout({ all }: { all?: boolean }) {
  const params = useParams<{ tenant: string }>();
  const location = useLocation();
  const principal = usePrincipal();
  const { tenants } = useTenants();
  const scope: Scope = all ? ALL : { kind: 'tenant', tenant: params.tenant ?? '' };
  const section = parseScope(location.pathname)?.section ?? '';

  const redirect = scopeRedirect(scope, principal);
  if (redirect) {
    return <Navigate replace to={`${scopePrefix(redirect)}/${section || 'dashboard'}${location.search}`} />;
  }
  if (scope.kind === 'tenant' && tenants && !tenants.some(t => t.id === scope.tenant)) {
    return (
      <div className="card">
        <div className="card-title">Tenant not found</div>
        <p className="subtle">
          No tenant <code>{scope.tenant}</code>. <Link to="/tenants">Manage tenants</Link>
        </p>
      </div>
    );
  }
  if (scope.kind === 'all' && sectionNeedsTenant(section)) {
    return (
      <div className="card">
        <div className="card-title">Pick a tenant</div>
        <p className="subtle">This page shows one tenant at a time. Choose one in the tenant switcher.</p>
      </div>
    );
  }
  return (
    <Ctx.Provider value={{ scope, scopedTenant: scope.kind === 'tenant' ? scope.tenant : null }}>
      <Outlet />
    </Ctx.Provider>
  );
}
