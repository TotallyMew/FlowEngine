import { expect, test } from '@playwright/test'

test('creates a workflow visually, reloads it, and inspects a persisted execution', async ({ page }) => {
  const name = `Example ${Date.now()}`
  await page.goto('/')
  await page.getByLabel('Username').fill(process.env.FLOW_OPERATOR_USERNAME ?? 'operator')
  const password = process.env.FLOW_OPERATOR_PASSWORD
  if (!password) throw new Error('Set FLOW_OPERATOR_PASSWORD for the running FlowEngine instance.')
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()

  await page.getByRole('complementary', { name: 'Workflow navigation' }).getByRole('button', { name: 'New workflow' }).click()
  await page.getByRole('dialog').getByLabel('Workflow name').fill(name)
  await page.getByRole('button', { name: 'Create workflow', exact: true }).click()

  const library = page.getByRole('complementary', { name: 'Node library' })
  const canvas = page.getByRole('region', { name: 'Workflow canvas' })
  const bounds = await canvas.boundingBox()
  if (!bounds) throw new Error('Expected a visible workflow canvas.')
  for (const [label, x, y] of [
    ['Manual Trigger', 0.20, 0.35], ['Set Data', 0.55, 0.35], ['Log', 0.76, 0.62],
  ] as const) {
    const transfer = await page.evaluateHandle(() => new DataTransfer())
    await library.getByRole('button', { name: new RegExp(label) }).dispatchEvent('dragstart', { dataTransfer: transfer })
    await canvas.dispatchEvent('dragover', { dataTransfer: transfer })
    await canvas.dispatchEvent('drop', { dataTransfer: transfer, clientX: bounds.x + bounds.width * x, clientY: bounds.y + bounds.height * y })
    await transfer.dispose()
  }

  await page.getByLabel('Message').fill('Payload saved by FlowEngine.')
  await canvas.locator('.flow-node').filter({ hasText: 'Set Data' }).click()
  await page.getByLabel('Values').fill('[]')
  await expect(page.getByText('Values must be a JSON object.')).toBeVisible()
  await page.getByLabel('Values').fill('{"ticket":"FE-42","priority":2}')
  await expect(page.getByText('Values must be a JSON object.')).toHaveCount(0)

  const from = canvas.locator('.flow-node').filter({ hasText: 'Manual Trigger' }).locator('.react-flow__handle.source')
  const to = canvas.locator('.flow-node').filter({ hasText: 'Set Data' }).locator('.react-flow__handle.target')
  const start = await from.boundingBox()
  const end = await to.boundingBox()
  if (!start || !end) throw new Error('Expected visible React Flow connection handles.')
  await page.mouse.move(start.x + start.width / 2, start.y + start.height / 2)
  await page.mouse.down()
  await page.mouse.move(end.x + end.width / 2, end.y + end.height / 2, { steps: 14 })
  await page.mouse.up()
  await expect(canvas.getByText('3 nodes · 1 edge')).toBeVisible()

  await canvas.locator('.flow-node').filter({ hasText: 'Set Data' }).click()
  await page.getByLabel('Connection target').selectOption({ label: 'Log' })
  await page.getByRole('button', { name: 'Connect selected nodes' }).click()
  await expect(canvas.getByText('3 nodes · 2 edges')).toBeVisible()
  await expect(page.getByText('Unsaved changes')).toBeVisible()
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByText('Saved · r1')).toBeVisible()

  await page.reload()
  await expect(canvas.getByText('3 nodes · 2 edges')).toBeVisible()
  await page.getByRole('button', { name: 'Run workflow' }).click()
  const executions = page.getByRole('region', { name: 'Executions' })
  await expect(executions.getByText('Payload saved by FlowEngine.')).toBeVisible()
  await expect(executions.locator('.history-row[aria-pressed="true"]')).toContainText('Completed')
  await expect(executions.locator('pre').last()).toContainText('FE-42')
  await expect(page.getByRole('region', { name: 'Execution graph' }).locator('.node-result.completed')).toHaveCount(3)
  if (process.env.FLOW_E2E_SCREENSHOT) await page.screenshot({ path: process.env.FLOW_E2E_SCREENSHOT })

  await page.reload()
  await page.getByRole('button', { name: 'Executions', exact: true }).click()
  await executions.locator('.history-row').first().click()
  await expect(executions.getByText('Payload saved by FlowEngine.')).toBeVisible()
})
