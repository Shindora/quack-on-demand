import { describe, expect, it } from 'vitest';
import { ALL } from './scope';
import {
  catalogPath, catalogTablePath, dashboardPath, icebergTablePath, poolPath, poolsPath, tenantHome,
} from './links';

describe('links', () => {
  it('builds tenant-scoped paths, encoding every segment', () => {
    expect(tenantHome('acme')).toBe('/t/acme/databases');
    expect(poolsPath('acme')).toBe('/t/acme/pools');
    expect(poolPath('acme', 'tpch db', 'bi')).toBe('/t/acme/pools/tpch%20db/bi');
    expect(catalogTablePath('acme', 'tpch', 'main', 'orders')).toBe('/t/acme/catalog/tpch/main/orders');
    expect(icebergTablePath('acme', 'tpch', 'lake', 's', 't')).toBe('/t/acme/catalog/tpch/iceberg/lake/s/t');
  });
  it('adds catalog query params only when set', () => {
    expect(catalogPath('acme')).toBe('/t/acme/catalog');
    expect(catalogPath('acme', { tenantDb: 'tpch' })).toBe('/t/acme/catalog?tenantDb=tpch');
    expect(catalogPath('acme', { tenantDb: 'tpch', schema: 'main' })).toBe('/t/acme/catalog?tenantDb=tpch&schema=main');
  });
  it('builds the dashboard path with an optional node filter', () => {
    expect(dashboardPath(ALL)).toBe('/all/dashboard');
    expect(dashboardPath({ kind: 'tenant', tenant: 'acme' }, 'ro 1')).toBe('/t/acme/dashboard?node=ro%201');
  });
});
