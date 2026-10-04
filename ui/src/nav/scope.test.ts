import { describe, expect, it } from 'vitest';
import {
  ALL, defaultScope, parseScope, scopePrefix, scopeRedirect, sectionNeedsTenant,
  sectionRoot, switchTenantPath,
} from './scope';

const acme = { kind: 'tenant', tenant: 'acme' } as const;
const globex = { kind: 'tenant', tenant: 'globex' } as const;

describe('scopePrefix', () => {
  it('renders all and tenant prefixes, encoding the slug', () => {
    expect(scopePrefix(ALL)).toBe('/all');
    expect(scopePrefix(acme)).toBe('/t/acme');
    expect(scopePrefix({ kind: 'tenant', tenant: 'a b' })).toBe('/t/a%20b');
  });
});

describe('parseScope', () => {
  it('parses tenant and all prefixes', () => {
    expect(parseScope('/t/acme/pools/tpch/bi')).toEqual({ scope: acme, section: 'pools/tpch/bi' });
    expect(parseScope('/all/users')).toEqual({ scope: ALL, section: 'users' });
    expect(parseScope('/t/a%20b/pools')).toEqual({ scope: { kind: 'tenant', tenant: 'a b' }, section: 'pools' });
  });
  it('returns an empty section for a bare prefix', () => {
    expect(parseScope('/t/acme')).toEqual({ scope: acme, section: '' });
    expect(parseScope('/all')).toEqual({ scope: ALL, section: '' });
  });
  it('returns null outside any scope', () => {
    expect(parseScope('/settings/config')).toBeNull();
    expect(parseScope('/t')).toBeNull();
    expect(parseScope('/')).toBeNull();
  });
  it('keeps a malformed escape raw instead of throwing', () => {
    expect(parseScope('/t/%E0/pools')).toEqual({ scope: { kind: 'tenant', tenant: '%E0' }, section: 'pools' });
  });
});

describe('sectionNeedsTenant / sectionRoot', () => {
  it('flags tenant-only sections by their first segment', () => {
    for (const s of ['databases', 'catalog/db/s/t', 'pools', 'maintenance', 'branches',
      'auth-provider', 'access-control', 'groups', 'roles']) {
      expect(sectionNeedsTenant(s)).toBe(true);
    }
    for (const s of ['dashboard', 'users', 'audit/statements', '']) {
      expect(sectionNeedsTenant(s)).toBe(false);
    }
  });
  it('drops detail segments but keeps the audit sub-page', () => {
    expect(sectionRoot('pools/tpch/bi')).toBe('pools');
    expect(sectionRoot('catalog/db/s/t')).toBe('catalog');
    expect(sectionRoot('audit/statements')).toBe('audit/statements');
    expect(sectionRoot('')).toBe('dashboard');
  });
});

describe('switchTenantPath', () => {
  it('keeps the section across tenants', () => {
    expect(switchTenantPath('/t/acme/pools', globex)).toBe('/t/globex/pools');
    expect(switchTenantPath('/t/acme/audit/usage', globex)).toBe('/t/globex/audit/usage');
  });
  it('drops detail routes to their list', () => {
    expect(switchTenantPath('/t/acme/pools/tpch/bi', globex)).toBe('/t/globex/pools');
  });
  it('falls back to the dashboard when All tenants cannot show the section', () => {
    expect(switchTenantPath('/t/acme/maintenance', ALL)).toBe('/all/dashboard');
    expect(switchTenantPath('/t/acme/users', ALL)).toBe('/all/users');
  });
  it('goes to the new scope dashboard from an unscoped page', () => {
    expect(switchTenantPath('/settings/config', acme)).toBe('/t/acme/dashboard');
    expect(switchTenantPath('/tenants', ALL)).toBe('/all/dashboard');
  });
});

describe('defaultScope / scopeRedirect', () => {
  const su = { superuser: true, ownTenant: null };
  const admin = { superuser: false, ownTenant: 'acme' };
  it('defaults superusers to all and tenant admins to their tenant', () => {
    expect(defaultScope(su)).toEqual(ALL);
    expect(defaultScope(admin)).toEqual(acme);
  });
  it('lets superusers use any scope', () => {
    expect(scopeRedirect(ALL, su)).toBeNull();
    expect(scopeRedirect(globex, su)).toBeNull();
  });
  it('pins tenant admins to their own tenant', () => {
    expect(scopeRedirect(acme, admin)).toBeNull();
    expect(scopeRedirect(ALL, admin)).toEqual(acme);
    expect(scopeRedirect(globex, admin)).toEqual(acme);
  });
});
