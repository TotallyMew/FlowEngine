import { expect, test } from '@playwright/test'

test('enables a webhook, starts a run from HTTP, and revokes access', async ({ page, playwright }) => {
  await page.goto('/')
  await page.getByLabel('Username').fill(process.env.FLOW_OPERATOR_USERNAME ?? 'operator')
  const password = process.env.FLOW_OPERATOR_PASSWORD
  if (!password) throw new Error('Set FLOW_OPERATOR_PASSWORD for the running FlowEngine instance.')
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.getByRole('complementary', { name: 'Workflow navigation' }).getByRole('button', { name: 'New workflow' }).click()
  await page.getByRole('dialog').getByLabel('Workflow name').fill(`Webhook SKU ${Date.now()}`)
  await page.getByRole('button', { name: 'Create workflow', exact: true }).click()

  const library = page.getByRole('complementary', { name: 'Node library' })
  const canvas = page.getByRole('region', { name: 'Workflow canvas' })
  const config = page.getByRole('complementary', { name: 'Node configuration' })
  await library.getByRole('button', { name: /^Webhook Trigger/ }).click()
  await library.getByRole('button', { name: /^Transform Data/ }).click()
  await config.getByLabel('Field mapping').fill('{"productCode":"sku","priceEur":"price"}')
  await canvas.locator('.flow-node').filter({ hasText: 'Webhook Trigger' }).locator('.node-glyph').click()
  await config.getByLabel('Connection target').selectOption({ label: 'Transform Data' })
  await config.getByRole('button', { name: 'Connect selected nodes' }).click()
  await expect(canvas.getByText('2 nodes · 1 edge')).toBeVisible()
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByText('Saved · r1')).toBeVisible()

  await page.getByRole('button', { name: 'Webhook', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Webhook trigger' })
  await dialog.getByRole('button', { name: 'Enable webhook' }).click()
  const tokenField = dialog.getByRole('textbox', { name: /^Bearer token/ })
  await expect(tokenField).toBeVisible()
  const token = await tokenField.inputValue()
  const url = await dialog.getByLabel('Webhook URL').inputValue()
  await dialog.getByRole('button', { name: 'Close', exact: true }).click()
  await page.getByRole('button', { name: 'Executions', exact: true }).click()
  const executions = page.getByRole('region', { name: 'Executions' })
  await expect(executions.getByText('No executions yet.')).toBeVisible()

  const external = await playwright.request.newContext()
  try {
    const data = { sku: 'BIKE-789', price: 1599, stock: 5 }
    const rejected = await external.post(url, { data })
    expect(rejected.status()).toBe(404)
    const accepted = await external.post(url, { data, headers: { Authorization: `Bearer ${token}` } })
    expect(accepted.status()).toBe(202)
    const { executionId } = await accepted.json() as { executionId: string }

    await executions.locator('.history-row').filter({ hasText: executionId.slice(0, 8) }).click()
    await expect(executions.locator('pre').last()).toContainText('"productCode": "BIKE-789"')
    await expect(executions.locator('pre').last()).toContainText('"priceEur": 1599')

    await page.getByRole('button', { name: 'Back to editor' }).click()
    await page.getByRole('button', { name: 'Webhook', exact: true }).click()
    await expect(tokenField).toHaveCount(0)
    await dialog.getByRole('button', { name: 'Disable' }).click()
    await expect(dialog.getByText('Webhook disabled.')).toBeVisible()
    const revoked = await external.post(url, { data, headers: { Authorization: `Bearer ${token}` } })
    expect(revoked.status()).toBe(404)
  } finally {
    await external.dispose()
  }
})
