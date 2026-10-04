import { describe, expect, it } from 'vitest';
import { ALL } from './scope';
import { legacyRedirect } from './legacy';

const acme = { kind: 'tenant', tenant: 'acme' } as const;

describe('legacyRedirect', () => {
  it('maps / and /nodes to the dashboard, honoring ?tenant and keeping ?node', () => {
    expect(legacyRedirect('/', '', ALL)).toBe('/all/dashboard');
    expect(legacyRedirect('/', '', acme)).toBe('/t/acme/dashboard');
    expect(legacyRedirect('/nodes', '?tenant=globex', ALL)).toBe('/t/globex/dashboard');
    expect(legacyRedirect('/nodes', '?tenant=globex&node=ro1', ALL)).toBe('/t/globex/dashboard?node=ro1');
    expect(legacyRedirect('/nodes', '?node=ro1', ALL)).toBe('/all/dashboard?node=ro1');
  });
  it('maps tenant and pool detail pages', () => {
    expect(legacyRedirect('/tenant/acme', '', ALL)).toBe('/t/acme/databases');
    expect(legacyRedirect('/pool/acme/tpch/bi', '', ALL)).toBe('/t/acme/pools/tpch/bi');
  });
  it('maps the catalog page and its table details', () => {
    expect(legacyRedirect('/catalog', '?tenant=acme&tenantDb=tpch', ALL)).toBe('/t/acme/catalog?tenantDb=tpch');
    expect(legacyRedirect('/catalog', '', acme)).toBe('/t/acme/catalog');
    expect(legacyRedirect('/catalog', '', ALL)).toBe('/all/catalog');
    expect(legacyRedirect('/catalog/acme/tpch/main/orders', '?asOf=3', ALL))
      .toBe('/t/acme/catalog/tpch/main/orders?asOf=3');
    expect(legacyRedirect('/catalog/acme/tpch/iceberg/lake/s/t', '', ALL))
      .toBe('/t/acme/catalog/tpch/iceberg/lake/s/t');
  });
  it('maps users, audit pages and settings', () => {
    expect(legacyRedirect('/users', '', ALL)).toBe('/all/users');
    expect(legacyRedirect('/users', '', acme)).toBe('/t/acme/users');
    expect(legacyRedirect('/audit', '', ALL)).toBe('/all/audit/control-plane');
    expect(legacyRedirect('/history', '', acme)).toBe('/t/acme/audit/statements');
    expect(legacyRedirect('/usage', '?x=1', ALL)).toBe('/all/audit/usage?x=1');
    expect(legacyRedirect('/config', '', ALL)).toBe('/settings/config');
    expect(legacyRedirect('/profile', '', acme)).toBe('/settings/profile');
  });
  it('returns null for anything else', () => {
    expect(legacyRedirect('/tenants', '', ALL)).toBeNull();
    expect(legacyRedirect('/nope', '', ALL)).toBeNull();
    expect(legacyRedirect('/tenant/acme/extra', '', ALL)).toBeNull();
    expect(legacyRedirect('/catalog/acme', '', ALL)).toBeNull();
  });
});
