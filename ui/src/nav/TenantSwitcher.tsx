import { useLocation, useNavigate } from 'react-router-dom';
import { ALL, switchTenantPath, type Scope } from './scope';
import { usePrincipal } from './TenantScope';
import { useTenants } from './TenantsContext';

const ALL_VALUE = '__all';
const MANAGE_VALUE = '__manage';
const NEW_VALUE = '__new';

/** Superusers pick any tenant or All tenants; a tenant admin sees their tenant, fixed. Shows the
  * display name, navigates by slug. */
export default function TenantSwitcher({ scope, collapsed, onExpand }: {
  scope: Scope;
  collapsed: boolean;
  onExpand: () => void;
}) {
  const { superuser } = usePrincipal();
  const { tenants, error } = useTenants();
  const location = useLocation();
  const navigate = useNavigate();
  const current = scope.kind === 'all' ? ALL_VALUE : scope.tenant;
  const label = scope.kind === 'all'
    ? 'All tenants'
    : tenants?.find(t => t.id === scope.tenant)?.displayName ?? scope.tenant;

  if (collapsed) {
    return (
      <button type="button" className="switcher-initial" title={`Tenant: ${label}`} onClick={onExpand}>
        {scope.kind === 'all' ? '*' : label.charAt(0).toUpperCase()}
      </button>
    );
  }
  if (!superuser) {
    return <div className="switcher"><span className="switcher-label">Tenant</span><strong>{label}</strong></div>;
  }

  function onChange(value: string) {
    if (value === MANAGE_VALUE) navigate('/tenants');
    else if (value === NEW_VALUE) navigate('/tenants?new=1');
    else navigate(switchTenantPath(location.pathname, value === ALL_VALUE ? ALL : { kind: 'tenant', tenant: value }));
  }

  return (
    <label className="switcher">
      <span className="switcher-label">Tenant</span>
      <select value={current} onChange={e => onChange(e.target.value)} aria-label="Switch tenant">
        <option value={ALL_VALUE}>All tenants</option>
        {(tenants ?? []).map(t => <option key={t.id} value={t.id}>{t.displayName}</option>)}
        <option disabled>----------</option>
        <option value={MANAGE_VALUE}>Manage tenants...</option>
        <option value={NEW_VALUE}>New tenant</option>
      </select>
      {error && <span className="switcher-error" title={error}>Could not load tenants</span>}
    </label>
  );
}
