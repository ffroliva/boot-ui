// @ts-check

export function registerPostgresqlTests(test, expect) {
  function report(sections, details, limitations = []) {
    return {
      localOnly: true,
      status: limitations.length ? 'PARTIAL' : 'READ',
      readAt: 1_700_000_000_000,
      databasesRead: 1,
      truncated: false,
      diagnostics: [],
      limitations,
      databases: [
        {
          name: 'primary',
          databaseName: 'app',
          status: limitations.length ? 'PARTIAL' : 'READ',
          sections,
          truncated: false,
          ...details
        }
      ]
    }
  }

  async function openReport(page, body) {
    const response = await page.request.get('/bootui/api/panels')
    expect(response.ok()).toBe(true)
    const manifest = await response.json()
    const panel = manifest.panels.find((candidate) => candidate.id === 'postgresql')
    panel.available = true
    panel.unavailableReason = null
    await page.route('**/bootui/api/panels', (route) => route.fulfill({json: manifest}))
    await page.route('**/bootui/api/postgresql', (route) => route.fulfill({json: body}))
    await page.goto('/bootui/#/postgresql')
    await expect(page.locator('main h2').filter({hasText: 'PostgreSQL'})).toBeVisible()
  }

  test.describe('PostgreSQL incomplete evidence', () => {
    for (const scenario of [
      {name: 'standby', inRecovery: true, replicasAvailable: false, reason: 'The standby replica list was not read.'},
      {name: 'query failure', inRecovery: false, replicasAvailable: false, reason: 'Replication statistics failed.'},
      {name: 'observed empty list', inRecovery: false, replicasAvailable: true, reason: 'Slots could not be read.'}
    ]) {
      test(`distinguishes replica absence from ${scenario.name}`, async ({page}) => {
        let reads = 0
        page.on('request', (request) => {
          if (request.method() === 'POST' && request.url().endsWith('/postgresql/read')) reads++
        })
        await openReport(
          page,
          report(
            [{id: 'replication', title: 'Replication', status: 'AVAILABLE', reason: scenario.reason, rowCount: 0}],
            {
              replication: {
                inRecovery: scenario.inRecovery,
                replicasAvailable: scenario.replicasAvailable,
                replicas: []
              }
            },
            [scenario.reason]
          )
        )
        await expect(page.getByRole('tabpanel')).toContainText(scenario.reason)
        if (scenario.replicasAvailable) {
          await expect(page.getByText('No streaming replica is connected to this server.', {exact: true})).toBeVisible()
          await expect(page.getByText('The replica list was not read.', {exact: false})).toHaveCount(0)
        } else {
          await expect(page.getByText('Connected replicas may be present', {exact: false})).toBeVisible()
          await expect(page.getByText('No streaming replica is connected', {exact: false})).toHaveCount(0)
        }
        expect(reads).toBe(0)
      })
    }

    test('retains budget-limited rows without claiming a row cap', async ({page}) => {
      const reason = 'The read budget ran out while reading Table statistics.'
      await openReport(
        page,
        report(
          [{id: 'tables', title: 'Largest relations', status: 'AVAILABLE', reason, rowCount: 1, truncated: false}],
          {tables: [{schema: 'public', table: 'orders', liveTuples: 10, deadTuples: 1}]},
          [reason]
        )
      )
      await expect(page.getByRole('tabpanel')).toContainText(reason)
      await expect(page.getByRole('cell', {name: 'public.orders'})).toBeVisible()
      await expect(page.getByRole('tabpanel').getByText('PARTIAL', {exact: true})).toBeVisible()
      await expect(page.getByText(/row bound|Truncated/)).toHaveCount(0)
    })
  })
}
