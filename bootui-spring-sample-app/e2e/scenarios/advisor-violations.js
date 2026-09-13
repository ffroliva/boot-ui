// @ts-check

const advisors = ['architecture', 'hibernate', 'spring', 'rest-api', 'memory', 'security', 'database-advisor']
const ruleId = 'ARCH-SPRING-004'
const strings = (count, offset = 0) =>
  Array.from({length: count}, (_, index) => `example.Service${offset + index} violates ${ruleId}`)

function report(scanId = 'scan-1', count = 29) {
  return {
    scan: {status: 'SCANNED', scannedAt: 1700000000000},
    evidence: {usable: true, coverageComplete: true, limitations: []},
    severityCounts: [{severity: 'HIGH', count}],
    rulesEvaluated: 1,
    violationsFound: 1,
    basePackages: [],
    entityPackages: [],
    inspected: [],
    violationDetails: {scanId, total: count, retained: count, retentionLimit: 10000, truncated: false},
    results: [
      {
        id: ruleId,
        name: 'Retained finding',
        status: 'VIOLATION',
        severity: 'HIGH',
        category: 'TEST',
        violationCount: count,
        sampleViolations: strings(10),
        recommendation: 'Review these findings.'
      }
    ]
  }
}

function detailPage({scanId = 'scan-1', count = 29, retained = count, offset = 0} = {}) {
  const returned = Math.max(0, Math.min(100, retained - offset))
  return {
    scanId,
    ruleId,
    violationCount: count,
    retainedCount: retained,
    truncated: retained < count,
    violations: strings(returned, offset),
    page: {total: retained, matched: retained, offset, limit: 100, returned, hasMore: offset + returned < retained}
  }
}

/**
 * Deterministic UI contracts; the adapter/conformance suites test real collection.
 * @param {typeof import('@playwright/test').test} test
 * @param {typeof import('@playwright/test').expect} expect
 * @param {{uiPath?: string, apiPath?: string}} paths
 */
export function registerAdvisorViolationTests(test, expect, {uiPath = '/bootui', apiPath = '/bootui/api'} = {}) {
  test.describe('On-demand advisor violations', () => {
    test.beforeEach(async ({page}) => {
      await page.route(`**${apiPath}/panels`, async (route) => {
        const response = await route.fetch()
        const body = await response.json()
        body.panels = body.panels.map((panel) =>
          advisors.includes(panel.id) ? {...panel, available: true, enabled: true, readOnly: true} : panel
        )
        await route.fulfill({response, json: body})
      })
      await page.route(`**${apiPath}/rest-api/error-contract?*`, (route) =>
        route.fulfill({json: {available: true, entries: [], total: 0}})
      )
    })

    for (const advisor of advisors) {
      test(`${advisor}: keeps previews and reads 29 details only on keyboard activation`, async ({page}) => {
        const reads = []
        const scans = []
        await page.route(`**${apiPath}/${advisor}`, (route) => route.fulfill({json: report()}))
        await page.route(`**${apiPath}/${advisor}/scan`, (route) => {
          scans.push(route.request().url())
          return route.fulfill({json: report()})
        })
        await page.route(`**${apiPath}/${advisor}/rules/*/violations?*`, (route) => {
          reads.push(new URL(route.request().url()))
          return route.fulfill({json: detailPage()})
        })
        await page.goto(`${uiPath}/#/${advisor}`)
        const detail = page.locator('.advisor-rule-violations')
        await expect(detail.locator('li')).toHaveCount(10)
        expect(reads).toHaveLength(0)
        const view = detail.getByRole('button', {name: `View violations for ${ruleId}`, exact: true})
        await expect(view).toBeEnabled()
        await view.focus()
        await page.keyboard.press('Enter')
        await expect(detail.locator('li')).toHaveText(strings(29))
        expect(reads).toHaveLength(1)
        expect(reads[0].pathname).toBe(`${apiPath}/${advisor}/rules/${ruleId}/violations`)
        expect(Object.fromEntries(reads[0].searchParams)).toEqual({scanId: 'scan-1', offset: '0', limit: '100'})
        await expect(detail.locator('[tabindex="-1"]')).toBeFocused()
        await expect(detail.locator('[role="status"], [role="alert"], [aria-live]')).toHaveCount(1)
        await expect(detail.getByRole('status')).toContainText('Showing 1–29 of 29 retained violations')
        await page.keyboard.press('Tab')
        await expect(detail.getByRole('button', {name: `Back to samples for ${ruleId}`})).toBeFocused()
        await page.keyboard.press('Enter')
        await expect(view).toBeFocused()
        await expect(detail.locator('li')).toHaveCount(10)
        expect(reads).toHaveLength(1)
        expect(scans).toHaveLength(0)
      })
    }

    test('pages without appending and keeps the accepted page through retry', async ({page}) => {
      const offsets = []
      let failNext = true
      await page.route(`**${apiPath}/architecture`, (route) => route.fulfill({json: report('scan-1', 201)}))
      await page.route(`**${apiPath}/architecture/rules/*/violations?*`, (route) => {
        const offset = Number(new URL(route.request().url()).searchParams.get('offset'))
        offsets.push(offset)
        if (offset === 100 && failNext) {
          failNext = false
          return route.fulfill({status: 503, json: {error: 'Unavailable'}})
        }
        return route.fulfill({json: detailPage({count: 201, offset})})
      })
      await page.goto(`${uiPath}/#/architecture`)
      const detail = page.locator('.advisor-rule-violations')
      await detail.getByRole('button', {name: `View violations for ${ruleId}`}).click()
      await expect(detail.locator('li')).toHaveCount(100)
      await detail.getByRole('button', {name: `Next violations for ${ruleId}`}).click()
      await expect(detail).toContainText('Unable to load violations: HTTP 503')
      await expect(detail.locator('li')).toHaveText(strings(100))
      await detail.getByRole('button', {name: `Retry violations for ${ruleId}`}).click()
      await expect(detail.locator('li')).toHaveText(strings(100, 100))
      await detail.getByRole('button', {name: `Next violations for ${ruleId}`}).click()
      await expect(detail.locator('li')).toHaveText(strings(1, 200))
      await expect(detail.getByRole('button', {name: `Next violations for ${ruleId}`})).toBeDisabled()
      await detail.getByRole('button', {name: `Previous violations for ${ruleId}`}).click()
      await expect(detail.locator('li')).toHaveText(strings(100, 100))
      expect(offsets).toEqual([0, 100, 100, 200, 100])
    })

    test('refreshes a replaced cached report explicitly and discloses retention overflow', async ({page}) => {
      let reportReads = 0
      let detailReads = 0
      const methods = []
      await page.route(`**${apiPath}/architecture`, (route) => {
        reportReads++
        methods.push(route.request().method())
        const body = report(reportReads === 1 ? 'scan-1' : 'scan-2')
        if (reportReads > 1) body.violationDetails = {...body.violationDetails, retained: 12, truncated: true}
        return route.fulfill({json: body})
      })
      await page.route(`**${apiPath}/architecture/rules/*/violations?*`, (route) => {
        detailReads++
        return detailReads === 1
          ? route.fulfill({status: 409, json: {error: 'Stale snapshot'}})
          : route.fulfill({json: detailPage({scanId: 'scan-2', retained: 12})})
      })
      await page.goto(`${uiPath}/#/architecture`)
      const detail = page.locator('.advisor-rule-violations')
      const view = detail.getByRole('button', {name: `View violations for ${ruleId}`})
      await view.click()
      await expect(detail).toContainText('No scan will run')
      expect(reportReads).toBe(1)
      await detail.getByRole('button', {name: `Refresh cached report for ${ruleId}`}).click()
      await expect(view).toBeEnabled()
      expect(reportReads).toBe(2)
      expect(detailReads).toBe(1)
      await view.click()
      await expect(detail.locator('li')).toHaveCount(12)
      await expect(detail).toContainText('Incomplete details: 12 of 29 violations retained')
      await expect(detail.getByRole('button', {name: `Next violations for ${ruleId}`})).toHaveCount(0)
      expect(methods).toEqual(['GET', 'GET'])
    })

    test('command-line catalogue spells required scan identity and optional page flags', async ({page}) => {
      await page.route(`**${apiPath}/cli`, (route) =>
        route.fulfill({
          json: {
            enabled: true,
            serverName: 'bootui',
            serverVersion: 'dev',
            toolCount: 1,
            maxResults: 200,
            tools: [
              {
                name: 'get_architecture_rule_violations',
                command: 'architecture violations',
                description: 'Read retained violations from a completed scan.',
                panel: 'architecture',
                action: false,
                schema: 'RULE_VIOLATIONS',
                arguments: ['id', 'scanId', 'offset', 'limit'],
                panelEnabled: true,
                panelReadOnly: true
              }
            ]
          }
        })
      )
      await page.goto(`${uiPath}/#/cli`)
      await expect(page.getByText('bootui architecture violations', {exact: true})).toBeVisible()
      await expect(
        page.getByText('<id> --scan-id <scan-id> [--offset <offset>] [--limit <limit>]', {exact: true})
      ).toBeVisible()
    })
  })
}
