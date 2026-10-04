import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, errorMessage } from '../api/client';
import type { TenantResponse } from '../api/types';

interface TenantsValue {
  /** null until the first fetch resolves. */
  tenants: TenantResponse[] | null;
  error: string | null;
  reload: () => Promise<void>;
}

const Ctx = createContext<TenantsValue | null>(null);

/** One shared tenant list for the switcher, the scope check and the tenant pages; pages that
  * create, delete or edit a tenant call `reload()`. */
export function TenantsProvider({ children }: { children: ReactNode }) {
  const [tenants, setTenants] = useState<TenantResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      const r = await api.listTenants();
      setTenants(r.tenants);
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  }, []);

  useEffect(() => { void reload(); }, [reload]);

  return <Ctx.Provider value={{ tenants, error, reload }}>{children}</Ctx.Provider>;
}

export function useTenants(): TenantsValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('useTenants outside TenantsProvider');
  return v;
}
