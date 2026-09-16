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

  test('PostgreSQL read button has an exact accessible name and supports keyboard activation', async ({page}) => {
    let reads = 0
    let finishRead
    const pendingRead = new Promise((resolve) => {
      finishRead = resolve
    })
    await page.route('**/bootui/api/postgresql/read', async (route) => {
      expect(route.request().method()).toBe('POST')
      reads++
      await pendingRead
      await route.fulfill({json: report([], {})})
    })
    await openReport(page, {...report([], {}), status: 'NOT_READ', databases: []})

    const button = page.getByRole('button', {name: 'Run PostgreSQL read', exact: true})
    await expect(button).toHaveCount(1)
    await expect(button).toBeEnabled()
    expect(reads).toBe(0)
    await button.focus()
    await expect(button).toBeFocused()
    try {
      await page.keyboard.press('Enter')
      const loadingButton = page.getByRole('button', {name: 'Reading...', exact: true})
      await expect(loadingButton).toHaveCount(1)
      await expect(loadingButton).toBeDisabled()
      await expect(loadingButton).toHaveAttribute('aria-busy', 'true')
      await expect.poll(() => reads).toBe(1)
    } finally {
      finishRead()
    }
    await expect(button).toBeEnabled()
    await expect(button).not.toHaveAttribute('aria-busy', 'true')
    expect(reads).toBe(1)
  })

  test.describe('PostgreSQL incomplete evidence', () => {
    for (const count of [25, 100]) {
      test(`explains a top-${count} statement ranking without implying a failed read`, async ({page}) => {
        const limitation = `primary / Statement ranking: Showing the top ${count} statements by total execution time. Additional statements are not shown.`
        const body = report(
          [{id: 'statements', title: 'Statement ranking', status: 'AVAILABLE', rowCount: count, truncated: true}],
          {
            truncated: true,
            statements: Array.from({length: count}, (_, index) => ({
              queryId: String(index),
              query: `select $1 /* statement ${index + 1} */`,
              totalTimeMs: 250 - index
            }))
          },
          [limitation]
        )
        body.truncated = true
        body.message = 'Some results are incomplete; see the section details and diagnostics.'
        let reads = 0
        page.on('request', (request) => {
          if (request.method() === 'POST' && request.url().endsWith('/postgresql/read')) reads++
        })

        await openReport(page, body)

        await expect(page.locator('main .alert-warning, main .text-bg-warning')).toHaveCount(0)
        await expect(page.getByText('Limited results.', {exact: true})).toHaveCount(0)
        await expect(page.getByText('Incomplete read.', {exact: true})).toHaveCount(0)
        await expect(page.getByText(body.message, {exact: true})).toHaveCount(0)
        await expect(page.getByText('What this read does not cover', {exact: false})).toHaveCount(0)
        await expect(page.getByText(`Showing the top ${count} statements`, {exact: false})).toHaveCount(1)
        await expect(
          page.getByRole('tabpanel').getByText(`Showing the top ${count} statements`, {exact: false})
        ).toBeVisible()
        await expect(page.getByRole('tabpanel').locator('tbody tr')).toHaveCount(count)
        expect(reads).toBe(0)
      })
    }

    for (const reason of [null, 'Statement text is restricted.']) {
      test(`retains warnings when a statement cap accompanies ${reason ? 'restricted statistics' : 'a table cap'}`, async ({
        page
      }) => {
        const sections = [
          {id: 'statements', title: 'Statement ranking', status: 'AVAILABLE', rowCount: 1, truncated: true, reason}
        ]
        const limitations = ['primary / Statement ranking: Additional statements are not shown.']
        if (reason) {
          limitations.push(reason)
        } else {
          sections.push({
            id: 'tables',
            title: 'Largest relations',
            status: 'AVAILABLE',
            rowCount: 1,
            truncated: true,
            reason: null
          })
          limitations.push('primary / Largest relations: Additional rows are not shown.')
        }
        const body = report(
          sections,
          {
            truncated: true,
            statements: [{queryId: '1', query: 'select $1'}],
            tables: [{schema: 'public', table: 'orders'}]
          },
          limitations
        )
        body.truncated = true
        await openReport(page, body)

        await expect(
          page.getByRole('status').filter({hasText: reason ? 'Incomplete read.' : 'Limited results.'})
        ).toBeVisible()
        await page.getByText('What this read does not cover', {exact: false}).click()
        await expect(page.locator('details li')).toHaveCount(2)
        if (reason) {
          await expect(page.getByRole('tabpanel')).toContainText(reason)
          await expect(page.getByRole('tabpanel').getByText('Truncated', {exact: true})).toBeVisible()
        } else {
          await page.getByRole('tab', {name: 'Largest relations 1'}).click()
          await expect(page.getByRole('tabpanel').getByText('Truncated', {exact: true})).toBeVisible()
        }
      })
    }

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
