const KEY = 'qod.sidebar.collapsed';

/** `localStorage` can be missing or throw (private window, blocked site data); the sidebar then
  * just starts expanded and keeps its state in memory. */
function defaultStorage(): Storage | undefined {
  try {
    return typeof localStorage === 'undefined' ? undefined : localStorage;
  } catch {
    return undefined;
  }
}

export function readCollapsed(storage: Pick<Storage, 'getItem'> | undefined = defaultStorage()): boolean {
  try {
    return storage?.getItem(KEY) === '1';
  } catch {
    return false;
  }
}

export function writeCollapsed(v: boolean, storage: Pick<Storage, 'setItem'> | undefined = defaultStorage()): void {
  try {
    storage?.setItem(KEY, v ? '1' : '0');
  } catch {
    // Storage blocked: the in-memory state still applies for this page load.
  }
}
