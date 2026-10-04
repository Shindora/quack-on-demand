import { defineConfig } from 'vitest/config';

// Pure-helper tests only (routing, nav model): no DOM environment needed.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
