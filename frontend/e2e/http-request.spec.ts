import { expect, test } from '@playwright/test'

test('configures an HTTP request and records the destination policy result', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('Username').fill(process.env.FLOW_OPERATOR_USERNAME ?? 'operator')
  const password = process.env.FLOW_OPERATOR_PASSWORD
  if (!password) throw new Error('Set FLOW_OPERATOR_PASSWORD for the running FlowEngine instance.')
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.getByRole('complementary', { name: 'Workflow navigation' }).getByRole('button', { name: 'New workflow' }).click()
  await page.getByRole('dialog').getByLabel('Workflow name').fill(`HTTP Request ${Date.now()}`)
  await page.getByRole('button', { name: 'Create workflow', exact: true }).click()

  const library = page.getByRole('complementary', { name: 'Node library' })
  const canvas = page.getByRole('region', { name: 'Workflow canvas' })
  const config = page.getByRole('complementary', { name: 'Node configuration' })
  await library.getByRole('button', { name: /^Manual Trigger/ }).click()
  await library.getByRole('button', { name: /^HTTP Request/ }).click()
  const url = config.getByRole('textbox', { name: /^URL/ })
  await url.fill('https://127.0.0.1/')
  await expect(config.getByRole('alert')).toContainText('HTTPS URL with a DNS hostname')
  const liveUrl = process.env.FLOW_E2E_HTTP_URL
  await url.fill(liveUrl ?? 'https://blocked.example.invalid/')
  await config.getByRole('combobox', { name: /^Method/ }).selectOption('POST')
  await canvas.locator('.flow-node').filter({ hasText: 'Manual Trigger' }).locator('.node-glyph').click()
  await config.getByLabel('Connection target').selectOption({ label: 'HTTP Request' })
  await config.getByRole('button', { name: 'Connect selected nodes' }).click()
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByText('Saved · r1')).toBeVisible()

  await page.getByRole('button', { name: 'Run workflow' }).click()
  const dialog = page.getByRole('dialog', { name: 'Run workflow' })
  await dialog.getByLabel('JSON input').fill('{"productCode":"FLOWENGINE-TEST","priceEur":1299}')
  await dialog.getByRole('button', { name: 'Run', exact: true }).click()
  const executions = page.getByRole('region', { name: 'Executions' })
  const selectedRun = executions.locator('.history-row[aria-pressed="true"]')
  if (liveUrl) {
    await expect(selectedRun).toContainText('Completed')
    const output = executions.locator('.result-data pre').last()
    await expect(output).toContainText('"status": 200')
    await expect(output).toContainText('"productCode": "FLOWENGINE-TEST"')
    await expect(output).toContainText('"priceEur": 1299')
  } else {
    await expect(selectedRun).toContainText('Failed')
    await expect(executions.locator('.result-data').getByText('HTTP destination is outside the server allowlist.')).toBeVisible()
  }
})
