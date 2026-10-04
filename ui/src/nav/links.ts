import { scopePrefix, type Scope } from './scope';

/** Every internal deep link goes through these, so the URL scheme lives in one place. */
const enc = encodeURIComponent;
const tenantPrefix = (tenant: string) => scopePrefix({ kind: 'tenant', tenant });

export function tenantHome(tenant: string): string {
  return `${tenantPrefix(tenant)}/databases`;
}

export function poolsPath(tenant: string): string {
  return `${tenantPrefix(tenant)}/pools`;
}

export function poolPath(tenant: string, tenantDb: string, pool: string): string {
  return `${tenantPrefix(tenant)}/pools/${enc(tenantDb)}/${enc(pool)}`;
}

export function catalogPath(tenant: string, q: { tenantDb?: string; schema?: string } = {}): string {
  const params = new URLSearchParams();
  if (q.tenantDb) params.set('tenantDb', q.tenantDb);
  if (q.schema) params.set('schema', q.schema);
  const s = params.toString();
  return `${tenantPrefix(tenant)}/catalog${s ? `?${s}` : ''}`;
}

export function catalogTablePath(tenant: string, tenantDb: string, schema: string, table: string): string {
  return `${tenantPrefix(tenant)}/catalog/${enc(tenantDb)}/${enc(schema)}/${enc(table)}`;
}

export function icebergTablePath(
  tenant: string, tenantDb: string, alias: string, schema: string, table: string,
): string {
  return `${tenantPrefix(tenant)}/catalog/${enc(tenantDb)}/iceberg/${enc(alias)}/${enc(schema)}/${enc(table)}`;
}

export function dashboardPath(scope: Scope, node?: string): string {
  return `${scopePrefix(scope)}/dashboard${node ? `?node=${enc(node)}` : ''}`;
}
