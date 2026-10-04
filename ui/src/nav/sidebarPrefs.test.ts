import { describe, expect, it } from 'vitest';
import { readCollapsed, writeCollapsed } from './sidebarPrefs';

describe('sidebarPrefs', () => {
  it('round-trips the collapsed flag', () => {
    const m = new Map<string, string>();
    const s = { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => { m.set(k, v); } };
    expect(readCollapsed(s)).toBe(false);
    writeCollapsed(true, s);
    expect(m.get('qod.sidebar.collapsed')).toBe('1');
    expect(readCollapsed(s)).toBe(true);
  });
  it('falls back to expanded when storage throws or is missing', () => {
    const throwing = {
      getItem: () => { throw new Error('blocked'); },
      setItem: () => { throw new Error('blocked'); },
    };
    expect(readCollapsed(throwing)).toBe(false);
    expect(() => writeCollapsed(true, throwing)).not.toThrow();
    expect(readCollapsed(undefined)).toBe(false);
  });
});
