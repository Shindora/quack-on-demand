import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { api } from '../api/client';
import type { TenantDbResponse } from '../api/types';

/** Shared tab body for the per-database panels (maintenance, branches): pick one of the
  * tenant's ducklake databases and render `panel` for it. Both features apply only to ducklake
  * catalogs, so other kinds are not listed; branch catalogs themselves are hidden too, since they
  * are addressed through their parent. */
export default function DuckLakeDbSection({ tenant, title, emptyHint, panel }: {
  tenant: string;
  title: string;
  emptyHint: string;
  panel: (tenantDb: string) => ReactNode;
}) {
  const [dbs, setDbs] = useState<TenantDbResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setDbs(null);
    setError(null);
    setSelected(null);
    api.listTenantDbs(tenant)
      .then(r => {
        if (cancelled) return;
        const lakes = r.tenantDbs.filter(d =>
          (d.kind ?? 'ducklake') === 'ducklake' && !/__br_[0-9a-f]{8}$/.test(d.name));
        setDbs(lakes);
        setSelected(lakes[0]?.name ?? null);
      })
      .catch(e => { if (!cancelled) setError(String(e)); });
    return () => { cancelled = true; };
  }, [tenant]);

  if (error) return <div className="login-err">Error: {error}</div>;
  if (!dbs) return <div className="loading">Loading databases...</div>;
  if (dbs.length === 0) {
    return (
      <div className="card">
        <div className="card-title">{title}</div>
        <p className="subtle">No ducklake databases in this tenant. {emptyHint}</p>
      </div>
    );
  }

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>{title}</div>
        <label style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="subtle">Database</span>
          <select value={selected ?? ''} onChange={ev => setSelected(ev.target.value)}>
            {dbs.map(d => <option key={d.id} value={d.name}>{d.name}</option>)}
          </select>
        </label>
      </div>
      {selected && panel(selected)}
    </div>
  );
}
