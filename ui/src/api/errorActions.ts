/** A suggested fix the manager attaches to an error (`ErrorResponse.actions`). */
export interface ErrorAction {
  where: string; // 'manager' | 'server'
  command: string;
  note?: string | null;
}

/** The "to fix" lines for `actions`, formatted as the qod CLI prints them; [] when there are none.
 * Several actions are alternatives; `server` names the machine of a `where: server` action. */
export function actionLines(actions: unknown, server?: string | null): string[] {
  if (!Array.isArray(actions)) return [];
  const rows = actions
    .filter((a): a is ErrorAction => !!a && typeof a === 'object' && typeof a.command === 'string' && a.command !== '')
    .map(a => [
      a.where === 'manager' ? 'on the manager:' : `on ${server || 'the server'}:`,
      a.command + (a.note ? `  (${a.note})` : ''),
    ] as const);
  if (rows.length === 0) return [];
  const width = Math.max(...rows.map(([label]) => label.length));
  return [
    rows.length === 1 ? 'to fix:' : 'to fix (any one):',
    ...rows.map(([label, text]) => `  ${label.padEnd(width)}  ${text}`),
  ];
}
