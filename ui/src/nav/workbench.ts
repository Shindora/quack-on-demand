import { api } from '../api/client';

/** Mints a single-use SSO ticket then opens Starlake's handoff endpoint in a new tab. The tab is
  * opened synchronously inside the click, before the ticket fetch, because popup blockers only
  * trust window.open calls made directly from a user gesture; it is pointed at the handoff URL
  * once the ticket arrives. The qod_session cookie rides along same-origin, so the ticket mint
  * needs no extra credentials. Best-effort: on failure this logs and closes the blank tab. If the
  * browser blocked the tab anyway, fall back to navigating the current page. */
export async function goToStarlake(starlakeUrl: string): Promise<void> {
  const tab = window.open('', '_blank');
  if (tab) tab.opener = null;
  let ticket: string;
  try {
    ({ ticket } = await api.ssoTicket());
  } catch (e) {
    console.warn('Starlake SSO ticket mint failed', e);
    tab?.close();
    return;
  }
  const url =
    `${starlakeUrl.replace(/\/$/, '')}/api/v1/auth/qod/sso?ticket=${encodeURIComponent(ticket)}`;
  if (tab) tab.location.href = url;
  else window.location.href = url;
}
