import { defineConfig } from '@playwright/test';

const baseURL = process.env.WORKSPACE_E2E_ORIGIN;
if (!baseURL) throw new Error('Run browser tests through BrowserWorkspaceIntegrationTest');
export default defineConfig({
  testDir: './test', workers: 1, fullyParallel: false, retries: 0, timeout: 30000,
  use: {
    baseURL, browserName: 'chromium', headless: true,
    launchOptions: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH } : {},
    screenshot: 'only-on-failure',
  },
});
