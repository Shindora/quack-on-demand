import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { PoolResponse } from '../api/types';

/** Pool filter data shared by the History / Usage pages: the pool list with a per-tenant
 * pool-name derivation. The tenant itself comes from the sidebar scope. */
export function useTenantPoolOptions() {
  const [poolOptions, setPoolOptions] = useState<PoolResponse[]>([]);

  useEffect(() => {
    api.listPools().then(r => setPoolOptions(r.pools)).catch(() => setPoolOptions([]));
  }, []);

  /** Distinct pool names, narrowed to `tenant` when non-empty. */
  const poolNamesFor = (tenant: string): string[] =>
    [...new Set(poolOptions.filter(p => !tenant || p.tenant === tenant).map(p => p.pool))].sort();

  return { poolNamesFor };
}
