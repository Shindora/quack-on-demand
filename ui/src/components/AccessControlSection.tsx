import { useState } from 'react';
import { api, errorMessage } from '../api/client';
import type { TenantResponse } from '../api/types';
import { EditIcon } from './Icons';
import { Modal } from './Modal';

/** Access control tab on the TenantDetail page: who may read or write data in this
  * tenant's pools. Mode `qod` (or unset, the manager default) enforces standard QoD
  * grants (roles, groups, per-pool permissions). Mode `opa` delegates the decision to
  * the tenant's own OPA server (docs/opa/README.md). The OPA bearer token is write-only:
  * the server only ever reports whether one is set, never its value, so this form must
  * never prefill or display it. */
export function AccessControlSection({ tenant, onSaved }: { tenant: TenantResponse; onSaved: () => void }) {
  const [editing, setEditing] = useState(false);
  const [mode, setMode] = useState<string>(tenant.aclMode ?? '');
  const [opaUrl, setOpaUrl] = useState(tenant.opaUrl ?? '');
  const [policyPath, setPolicyPath] = useState(tenant.opaPolicyPath ?? '');
  const [token, setToken] = useState(''); // write-only: blank = keep
  const [clearToken, setClearToken] = useState(false);
  const [sendText, setSendText] = useState(!!tenant.opaSendStatementText);
  const [error, setError] = useState<string | null>(null);

  function openEditor() {
    setMode(tenant.aclMode ?? '');
    setOpaUrl(tenant.opaUrl ?? '');
    setPolicyPath(tenant.opaPolicyPath ?? '');
    setToken('');
    setClearToken(false);
    setSendText(!!tenant.opaSendStatementText);
    setError(null);
    setEditing(true);
  }

  async function save(ev: React.FormEvent) {
    ev.preventDefault();
    setError(null);
    try {
      await api.setTenantAcl({
        name: tenant.name,
        mode,
        opaUrl,
        opaPolicyPath: policyPath,
        ...(clearToken ? { opaToken: '' } : token ? { opaToken: token } : {}),
        sendStatementText: sendText,
      });
      setEditing(false);
      setToken('');
      setClearToken(false);
      onSaved();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
        <div className="card-title" style={{ margin: 0 }}>Access control</div>
        {!editing && (
          <button className="icon-btn" title="Edit" aria-label="Edit access control" onClick={openEditor}><EditIcon /></button>
        )}
      </div>
      <p className="subtle">
        Controls who can read or write data in this tenant's pools: standard QoD
        grants (roles, groups, per-pool permissions), or a decision delegated to
        the tenant's own OPA server. The OPA bearer token is write-only - once
        set, it is never shown again.
      </p>
      {error && <div className="login-err">Error: {error}</div>}

      <table>
        <tbody>
          <tr>
            <th style={{ textAlign: 'left', width: 160 }}>Mode</th>
            <td><code>{tenant.aclMode ?? 'manager default'}</code></td>
          </tr>
          <tr>
            <th style={{ textAlign: 'left' }}>OPA URL</th>
            <td><code>{tenant.opaUrl ?? <span className="subtle">(manager QOD_OPA_URL)</span>}</code></td>
          </tr>
          <tr>
            <th style={{ textAlign: 'left' }}>Policy path</th>
            <td><code>{tenant.opaPolicyPath ?? <span className="subtle">(qod/authz)</span>}</code></td>
          </tr>
          <tr>
            <th style={{ textAlign: 'left' }}>Token</th>
            <td>{tenant.opaTokenSet ? 'set' : <span className="subtle">not set</span>}</td>
          </tr>
          <tr>
            <th style={{ textAlign: 'left' }}>Send SQL text to OPA</th>
            <td>{tenant.opaSendStatementText ? 'on' : 'off'}</td>
          </tr>
        </tbody>
      </table>

      {editing && (
        <Modal maxWidth={560} onClose={() => { setEditing(false); setError(null); }}>
          <div className="card-title">Edit access control</div>
          <form onSubmit={save}>
            <label>
              Mode
              <select value={mode} onChange={ev => setMode(ev.target.value)}>
                <option value="">Manager default</option>
                <option value="qod">qod</option>
                <option value="opa">opa</option>
              </select>
            </label>
            <label>
              OPA URL
              <input
                value={opaUrl}
                onChange={ev => setOpaUrl(ev.target.value)}
                placeholder="leave blank for manager QOD_OPA_URL"
              />
            </label>
            <label>
              Policy path
              <input
                value={policyPath}
                onChange={ev => setPolicyPath(ev.target.value)}
                placeholder="qod/authz"
              />
            </label>
            <label>
              Token
              <input
                type="password"
                value={token}
                onChange={ev => setToken(ev.target.value)}
                placeholder="leave blank to keep"
                disabled={clearToken}
                autoComplete="new-password"
              />
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={clearToken}
                onChange={ev => { setClearToken(ev.target.checked); if (ev.target.checked) setToken(''); }}
              />
              <span>Clear token</span>
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={sendText}
                onChange={ev => setSendText(ev.target.checked)}
              />
              <span>Send SQL text to OPA</span>
            </label>
            {error && <div className="login-err">Error: {error}</div>}
            <div className="row" style={{ gap: 8, marginTop: '1rem', justifyContent: 'flex-end' }}>
              <button type="button" className="cancel-button" style={{ minWidth: '7rem' }} onClick={() => { setEditing(false); setError(null); }}>Cancel</button>
              <button type="submit" style={{ minWidth: '7rem' }}>Save</button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
}
