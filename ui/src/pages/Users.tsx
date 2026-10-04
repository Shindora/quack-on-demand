import { useState } from 'react';
import GroupSection from '../components/GroupSection';
import RoleSection from '../components/RoleSection';
import UserSection from '../components/UserSection';
import { usePrincipal, useTenantScope } from '../nav/TenantScope';
import { useTenants } from '../nav/TenantsContext';

/** Users page. Under a tenant scope it lists that tenant's users; under All tenants it lists
  * every user, including superusers (tenant IS NULL), with a superusers-only toggle. The backend
  * has no "superusers only" listUsers filter, so UserSection narrows on the client. */
export default function UsersPage() {
  const { scopedTenant } = useTenantScope();
  const { superuser } = usePrincipal();
  const tenants = useTenants().tenants ?? [];
  const [superusersOnly, setSuperusersOnly] = useState(false);
  const onlySupers = scopedTenant == null && superusersOnly;
  return (
    <>
      <h1>Users</h1>
      {scopedTenant == null && superuser && (
        <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <input
            type="checkbox"
            checked={superusersOnly}
            onChange={e => setSuperusersOnly(e.target.checked)}
            style={{ width: 'auto' }}
          />
          Superusers only
        </label>
      )}
      <UserSection tenant={scopedTenant} tenants={tenants} superusersOnly={onlySupers} />
    </>
  );
}

export function GroupsPage() {
  const { scopedTenant } = useTenantScope();
  return <><h1>Groups</h1><GroupSection tenant={scopedTenant} /></>;
}

export function RolesPage() {
  const { scopedTenant } = useTenantScope();
  return <><h1>Roles</h1><RoleSection tenant={scopedTenant} /></>;
}
