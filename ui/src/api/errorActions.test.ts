import { describe, expect, it } from 'vitest';
import { actionLines } from './errorActions';

describe('actionLines', () => {
  it('renders nothing without actions', () => {
    expect(actionLines(undefined)).toEqual([]);
    expect(actionLines([{ where: 'manager' }])).toEqual([]);
  });

  it('aligns alternatives like the CLI', () => {
    expect(actionLines([
      { where: 'server', command: 'qod fleet join ... --name <another-name>' },
      { where: 'manager', command: 'qod fleet remove a', note: 'forgets the server' },
    ], 'a')).toEqual([
      'to fix (any one):',
      '  on a:            qod fleet join ... --name <another-name>',
      '  on the manager:  qod fleet remove a  (forgets the server)',
    ]);
  });
});
