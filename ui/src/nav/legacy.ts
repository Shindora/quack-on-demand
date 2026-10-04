import { scopePrefix, type Scope } from './scope';

function withQuery(path: string, params: URLSearchParams): string {
  const s = params.toString();
  return s ? `${path}?${s}` : path;
}

/** Maps a pre-sidebar URL onto the scoped layout so old bookmarks keep working; null when
  * `pathname` is not a legacy route. `def` is the session's default scope. Path segments are
  * reused still encoded, exactly as they arrived. */
export function legacyRedirect(pathname: string, search: string, def: Scope): string | null {
  const segs = pathname.split('/').filter(s => s !== '');
  const q = new URLSearchParams(search);
  const defPrefix = scopePrefix(def);
  // Old pages took the tenant as ?tenant=; it becomes the scope prefix.
  const fromTenantQuery = (): [string, URLSearchParams] => {
    const t = q.get('tenant');
    const rest = new URLSearchParams(q);
    rest.delete('tenant');
    return [t ? scopePrefix({ kind: 'tenant', tenant: t }) : defPrefix, rest];
  };
  switch (segs[0] ?? '') {
    case '':
    case 'nodes': {
      if (segs.length > 1) return null;
      const [prefix, rest] = fromTenantQuery();
      return withQuery(`${prefix}/dashboard`, rest);
    }
    case 'tenant':
      return segs.length === 2 ? `/t/${segs[1]}/databases` : null;
    case 'pool':
      return segs.length === 4 ? `/t/${segs[1]}/pools/${segs[2]}/${segs[3]}` : null;
    case 'catalog': {
      if (segs.length === 1) {
        const [prefix, rest] = fromTenantQuery();
        return withQuery(`${prefix}/catalog`, rest);
      }
      if (segs.length < 5) return null;
      return `/t/${segs[1]}/catalog/${segs.slice(2).join('/')}${search}`;
    }
    case 'users':
      return segs.length === 1 ? `${defPrefix}/users${search}` : null;
    case 'audit':
      return segs.length === 1 ? `${defPrefix}/audit/control-plane${search}` : null;
    case 'history':
      return segs.length === 1 ? `${defPrefix}/audit/statements${search}` : null;
    case 'usage':
      return segs.length === 1 ? `${defPrefix}/audit/usage${search}` : null;
    case 'config':
      return segs.length === 1 ? '/settings/config' : null;
    case 'profile':
      return segs.length === 1 ? '/settings/profile' : null;
    default:
      return null;
  }
}
