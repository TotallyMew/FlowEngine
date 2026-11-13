import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  timeout: 45_000,
  expect: { timeout: 12_000 },
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.FLOW_E2E_URL ?? 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
  },
  reporter: 'list',
})
