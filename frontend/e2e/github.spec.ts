import { expect, test } from '@playwright/test'

test('configures GitHub Create issue and reports an unavailable connection', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('Username').fill(process.env.FLOW_OPERATOR_USERNAME ?? 'operator')
  const password = process.env.FLOW_OPERATOR_PASSWORD
  if (!password) throw new Error('Set FLOW_OPERATOR_PASSWORD for the running FlowEngine instance.')
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.getByRole('complementary', { name: 'Workflow navigation' }).getByRole('button', { name: 'New workflow' }).click()
  await page.getByRole('dialog').getByLabel('Workflow name').fill(`GitHub issue ${Date.now()}`)
  await page.getByRole('button', { name: 'Create workflow', exact: true }).click()

  const library = page.getByRole('complementary', { name: 'Node library' })
  const canvas = page.getByRole('region', { name: 'Workflow canvas' })
  const config = page.getByRole('complementary', { name: 'Node configuration' })
  await library.getByRole('button', { name: /^Manual Trigger/ }).click()
  await library.getByRole('button', { name: /^GitHub · Create issue/ }).click()
  const repository = config.getByRole('textbox', { name: /^Repository/ })
  await repository.fill('https://github.com/flowengine-test/issues')
  await expect(config.getByRole('alert')).toContainText('owner/repository')
  await repository.fill('flowengine-test/issue-fixture')
  await config.getByRole('textbox', { name: /^Title field/ }).fill('issueTitle')
  await config.getByRole('textbox', { name: /^Description field/ }).fill('description')
  await canvas.locator('.flow-node').filter({ hasText: 'Manual Trigger' }).locator('.node-glyph').click()
  await config.getByLabel('Connection target').selectOption({ label: 'GitHub · Create issue' })
  await config.getByRole('button', { name: 'Connect selected nodes' }).click()
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByText('Saved · r1')).toBeVisible()

  await page.reload()
  await canvas.locator('.flow-node').filter({ hasText: 'GitHub · Create issue' }).locator('.node-glyph').click()
  await expect(repository).toHaveValue('flowengine-test/issue-fixture')
  await expect(config.getByRole('textbox', { name: /^Title field/ })).toHaveValue('issueTitle')
  await page.getByRole('button', { name: 'Run workflow' }).click()
  await page.getByRole('dialog', { name: 'Run workflow' }).getByRole('button', { name: 'Run', exact: true }).click()
  const executions = page.getByRole('region', { name: 'Executions' })
  await expect(executions.locator('.history-row[aria-pressed="true"]')).toContainText('Failed')
  await expect(executions.locator('.result-data')).toContainText(
    /GitHub connector is not configured on the server|GitHub repository is outside the server configuration|GitHub issue title must be nonblank/,
  )
})
