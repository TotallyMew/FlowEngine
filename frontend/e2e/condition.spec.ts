import { expect, test } from '@playwright/test'

test('routes SKU input through the saved condition and records skipped nodes', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('Username').fill(process.env.FLOW_OPERATOR_USERNAME ?? 'operator')
  const password = process.env.FLOW_OPERATOR_PASSWORD
  if (!password) throw new Error('Set FLOW_OPERATOR_PASSWORD for the running FlowEngine instance.')
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.getByRole('complementary', { name: 'Workflow navigation' }).getByRole('button', { name: 'New workflow' }).click()
  await page.getByRole('dialog').getByLabel('Workflow name').fill(`SKU routing ${Date.now()}`)
  await page.getByRole('button', { name: 'Create workflow', exact: true }).click()

  const library = page.getByRole('complementary', { name: 'Node library' })
  const canvas = page.getByRole('region', { name: 'Workflow canvas' })
  const config = page.getByRole('complementary', { name: 'Node configuration' })
  const node = (name: string) => canvas.locator('.flow-node').filter({ hasText: name })
  for (const [kind, name] of [
    ['Manual Trigger', 'Manual Trigger'], ['Condition', 'Check SKU'], ['Set Data', 'Needs review'], ['Transform Data', 'Ready'],
  ] as const) {
    await library.getByRole('button', { name: new RegExp(kind) }).click()
    await config.getByLabel('Node name').fill(name)
    if (kind === 'Condition') await config.getByLabel('Field name').fill('sku')
    if (kind === 'Set Data') {
      await config.getByLabel('Values').fill('{"busena":"reikia_perziuros"}')
    }
    if (kind === 'Transform Data') {
      await config.getByLabel('Field mapping').fill('{"productCode":42}')
      await expect(config.getByRole('alert')).toContainText('Each output and input key')
      await config.getByLabel('Field mapping').fill('{"productCode":"sku","priceEur":"price"}')
    }
  }

  const connect = async (source: string, target: string, port?: string) => {
    await node(source).locator('.node-glyph').click()
    if (port) await config.getByRole('combobox', { name: 'Output', exact: true }).selectOption(port)
    await config.getByLabel('Connection target').selectOption({ label: target })
    await config.getByRole('button', { name: 'Connect selected nodes' }).click()
  }
  await connect('Manual Trigger', 'Check SKU')
  await connect('Check SKU', 'Needs review', 'true')
  await connect('Check SKU', 'Ready', 'false')
  await expect(canvas.getByText('4 nodes · 3 edges')).toBeVisible()
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByText('Saved · r1')).toBeVisible()
  await page.reload()
  await expect(node('Check SKU').getByLabel('true output')).toBeVisible()
  await expect(node('Check SKU').getByLabel('false output')).toBeVisible()
  await expect(canvas.locator('.react-flow__edge.branch-true')).toHaveCount(1)
  await expect(canvas.locator('.react-flow__edge.branch-false')).toHaveCount(1)

  const executions = page.getByRole('region', { name: 'Executions' })
  const results = executions.locator('.result-nodes')
  const run = async (input: string) => {
    await page.getByRole('button', { name: 'Run workflow', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: 'Run workflow' })
    await dialog.getByLabel('JSON input').fill(input)
    await dialog.getByRole('button', { name: 'Run', exact: true }).click()
  }
  await run('{"sku":""}')
  await expect(results.getByRole('button', { name: 'Needs review Completed' })).toBeVisible()
  await expect(results.getByRole('button', { name: 'Ready Skipped' })).toBeVisible()
  await expect(executions.locator('pre').last()).toContainText('reikia_perziuros')
  await results.getByRole('button', { name: 'Check SKU Completed' }).click()
  await expect(executions.locator('.result-data .branch-tag')).toHaveText('true')

  await page.getByRole('button', { name: 'Back to editor' }).click()
  await run('{"sku":"BIKE-123","price":1299,"busena":"paruošta"}')
  await expect(results.getByRole('button', { name: 'Ready Completed' })).toBeVisible()
  await expect(results.getByRole('button', { name: 'Needs review Skipped' })).toBeVisible()
  const output = executions.locator('pre').last()
  await expect(output).toContainText('"productCode": "BIKE-123"')
  await expect(output).toContainText('"priceEur": 1299')
  await expect(output).not.toContainText('"busena"')
  await expect(page.getByRole('region', { name: 'Execution graph' }).locator('.react-flow__edge.branch-true.edge-skipped')).toHaveCount(1)

  await page.reload()
  await page.getByRole('button', { name: 'Executions', exact: true }).click()
  await expect(executions.locator('.history-row')).toHaveCount(2)
  await executions.locator('.history-row').last().click()
  await expect(results.getByRole('button', { name: 'Needs review Completed' })).toBeVisible()
  await expect(executions.locator('pre').last()).toContainText('reikia_perziuros')
})
