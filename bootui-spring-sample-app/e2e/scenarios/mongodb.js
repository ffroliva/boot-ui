import {mongodbReport, mongodbInspected, mongodbCatalogPage, mongodbCollection} from './mongodb-fixture.js'

// Browser interaction evidence only. Required authenticated Java/profile tests establish driver/API behavior.
export function registerMongodbTests(test, expect, {uiPath = '/bootui', apiPath = '/bootui/api'} = {}) {
  async function open(page, body, panelOverrides = {}) {
    const response = await page.request.get(`${apiPath}/panels`)
    expect(response.ok()).toBe(true)
    const manifest = await response.json()
    const panel = manifest.panels.find((candidate) => candidate.id === 'mongodb')
    expect(panel, 'The real adapter must declare MongoDB').toBeTruthy()
    Object.assign(panel, {available: true, enabled: true, readOnly: false, unavailableReason: null}, panelOverrides)
    await page.route(`**${apiPath}/panels`, (route) => route.fulfill({json: manifest}))
    await page.route(`**${apiPath}/mongodb`, (route) => route.fulfill({json: body}))
    await page.goto(`${uiPath}/#/mongodb`)
    await expect(page.getByRole('heading', {name: 'MongoDB', exact: true})).toBeVisible()
  }
  test.describe('MongoDB selected-scope metadata', () => {
    test('opens passive inventory and only explicitly inspects the selected client', async ({page}) => {
      let inspections = 0
      await page.route(`**${apiPath}/mongodb/inspect`, (route) => {
        expect(route.request().postDataJSON()).toEqual({clientId: 'client', scope: 'CONFIGURED'})
        inspections++
        return route.fulfill({json: mongodbInspected()})
      })
      await open(page, mongodbReport())
      await expect(page.getByText('Not inspected', {exact: true})).toBeVisible()
      expect(inspections).toBe(0)
      await page.getByRole('button', {name: 'Inspect selected scope', exact: true}).click()
      await expect(page.getByRole('heading', {name: 'Retained catalog', exact: true})).toBeVisible()
      expect(inspections).toBe(1)
      await expect(page.getByRole('table', {name: 'MongoDB databases'})).toContainText('bootui_sample')
    })
    test('uses manifest absence without attempting discovery', async ({page}) => {
      let requests = 0
      page.on('request', (request) => {
        if (new URL(request.url()).pathname.startsWith(`${apiPath}/mongodb`)) requests++
      })
      await open(page, null, {available: false, unavailableReason: 'No managed MongoDB client declaration.'})
      await expect(page.getByText('Use the matching framework MongoDB integration;', {exact: false})).toBeVisible()
      expect(requests).toBe(0)
    })
    test('keeps cached evidence under read-only policy', async ({page}) => {
      await open(page, mongodbInspected(), {readOnly: true, readOnlyReason: 'External reads disabled.'})
      await expect(page.getByRole('button', {name: 'Inspect selected scope'})).toBeDisabled()
      await expect(page.getByRole('table', {name: 'MongoDB databases'})).toContainText('bootui_sample')
    })
    test('does not initialize lazy clients', async ({page}) => {
      const body = mongodbReport()
      body.inventory.clients[0].inspectable = false
      body.inventory.clients[0].lifecycle = 'NOT_INITIALIZED'
      await open(page, body)
      await expect(page.getByRole('button', {name: 'Inspect selected scope'})).toBeDisabled()
      await expect(page.getByText('Select an initialized application client.', {exact: false})).toBeVisible()
    })
    test('browses retained indexes with exact numbers without another action', async ({page}) => {
      let actions = 0
      await page.route(`**${apiPath}/mongodb/inspect`, () => {
        actions++
      })
      await page.route(`**${apiPath}/mongodb?*`, (route) => {
        const query = new URL(route.request().url()).searchParams
        expect(query.get('snapshotId')).toBe('snapshot')
        return route.fulfill({
          json: mongodbInspected({
            catalog: {
              section: 'INDEXES',
              databases: [],
              collections: [],
              indexes: [
                {
                  id: 'index',
                  name: 'catalog_ttl',
                  keys: [{field: 'created', kind: 'DESC'}],
                  expireAfterSeconds: '9007199254740993',
                  partialFilterPresent: true
                }
              ],
              page: {total: 1, matched: 1, returned: 1, offset: 0, limit: 50, hasMore: false}
            }
          })
        })
      })
      await open(page, mongodbInspected())
      await page.getByRole('button', {name: 'Indexes', exact: true}).click()
      await expect(page.getByRole('table', {name: 'MongoDB indexes'})).toContainText('9007199254740993')
      await expect(page.getByRole('table', {name: 'MongoDB indexes'})).toContainText('Partial predicate withheld')
      expect(actions).toBe(0)
    })
    test('preserves the snapshot on a conflict without retrying', async ({page}) => {
      let actions = 0
      await page.route(`**${apiPath}/mongodb/inspect`, (route) => {
        actions++
        return route.fulfill({
          status: 409,
          json: {
            error: 'BootUI action already in progress',
            operation: 'mongodb.inspect',
            activeOperation: 'mongodb.inspect',
            message: 'Mongo inspection is already running.'
          }
        })
      })
      await open(page, mongodbInspected())
      await page.getByRole('button', {name: 'Inspect selected scope'}).click()
      await expect(page.getByRole('status')).toContainText('Mongo inspection is already running.')
      await expect(page.getByRole('table', {name: 'MongoDB databases'})).toContainText('bootui_sample')
      expect(actions).toBe(1)
    })
    test('preserves the accepted page after a stale drill-down and reloads only local metadata', async ({page}) => {
      let catalogReads = 0
      let actions = 0
      let healthReads = 0
      page.on('request', (request) => {
        if (new URL(request.url()).pathname.endsWith('/mongodb/inspect')) actions++
        if (new URL(request.url()).pathname.endsWith('/health')) healthReads++
      })
      await page.route(`**${apiPath}/mongodb?*`, (route) => {
        catalogReads++
        expect(new URL(route.request().url()).searchParams.get('databaseId')).toBe('db')
        return route.fulfill({
          status: 409,
          json: {error: 'MongoDB snapshot changed; reload retained metadata before selecting'}
        })
      })
      await open(page, mongodbInspected())
      const drillDown = page.getByRole('button', {
        name: 'View collections for database bootui_sample in sampleMongoClient',
        exact: true
      })
      await expect(drillDown).toHaveCount(1)
      await drillDown.focus()
      await page.keyboard.press('Enter')
      await expect(page.getByRole('alert')).toContainText('reload retained metadata before selecting')
      await expect(page.getByRole('table', {name: 'MongoDB databases', exact: true})).toContainText('bootui_sample')
      await expect(page.getByRole('button', {name: 'Databases', exact: true})).toHaveAttribute('aria-pressed', 'true')
      await expect(page.getByText('No retained rows match this scope.', {exact: false})).toHaveCount(0)
      expect(catalogReads).toBe(1)
      await page.route(`**${apiPath}/mongodb`, (route) => route.fulfill({json: mongodbReport()}))
      await page.getByRole('button', {name: 'Reload local metadata', exact: true}).click()
      await expect(page.getByRole('status')).toHaveText('Local metadata reloaded. No inspection was run.')
      await expect(page.getByRole('heading', {name: 'Retained catalog', exact: true})).toHaveCount(0)
      expect(actions).toBe(0)
      expect(healthReads).toBe(0)
    })
    test('retains accepted parent, filter and offset after failed filtering or paging', async ({page}) => {
      const reads = []
      let fail = false
      const collections = [mongodbCollection(), mongodbCollection({id: 'archive', name: 'products_archive'})]
      await page.route(`**${apiPath}/mongodb?*`, (route) => {
        const query = new URL(route.request().url()).searchParams
        reads.push(Object.fromEntries(query))
        if (fail) return route.fulfill({status: 400, json: {error: 'MongoDB catalog selector is invalid'}})
        const offset = Number(query.get('offset') || 0)
        return route.fulfill({
          json: mongodbCatalogPage('COLLECTIONS', [collections[offset]], {
            total: 2,
            matched: 2,
            offset,
            limit: 1,
            hasMore: offset === 0
          })
        })
      })
      await open(page, mongodbInspected())
      await page.getByRole('button', {name: 'View collections for database bootui_sample in sampleMongoClient'}).click()
      const table = page.getByRole('table', {name: 'MongoDB collections', exact: true})
      await expect(table).toContainText('products')
      await page.getByLabel('Filter retained MongoDB metadata', {exact: true}).fill('prod')
      await page.getByRole('button', {name: 'Filter', exact: true}).click()
      await expect(page.getByRole('button', {name: 'Next', exact: true})).toBeEnabled()
      await page.getByRole('button', {name: 'Next', exact: true}).click()
      await expect(table).toContainText('products_archive')
      fail = true
      await page.getByLabel('Filter retained MongoDB metadata', {exact: true}).fill('missing')
      await page.getByRole('button', {name: 'Filter', exact: true}).click()
      await expect(page.getByRole('alert')).toContainText('MongoDB catalog selector is invalid')
      await expect(table).toContainText('products_archive')
      await expect(page.getByLabel('Filter retained MongoDB metadata', {exact: true})).toHaveValue('prod')
      await Promise.all([
        page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/mongodb')),
        page.getByRole('button', {name: 'Previous', exact: true}).click()
      ])
      await expect(page.getByRole('button', {name: 'Previous', exact: true})).toBeEnabled()
      await expect(table).toContainText('products_archive')
      expect(reads.at(-1)).toEqual({
        snapshotId: 'snapshot',
        section: 'COLLECTIONS',
        databaseId: 'db',
        query: 'prod',
        limit: '1'
      })
    })
    test('selects an authorized database explicitly and repeats selection against each returned snapshot', async ({
      page
    }) => {
      const initial = mongodbReport()
      initial.limits.authorizedDatabaseEnumerationEnabled = true
      initial.inventory.clients[0].configuredDatabases = []
      const requests = []
      const database = {id: 'observed-db', clientId: 'client', name: 'catalog', provenance: 'AUTHORIZED_VISIBLE'}
      await page.route(`**${apiPath}/mongodb/inspect`, (route) => {
        const request = route.request().postDataJSON()
        requests.push(request)
        return route.fulfill({
          json: mongodbCatalogPage(
            'DATABASES',
            [database],
            {},
            {
              inventory: initial.inventory,
              limits: initial.limits,
              inspection: {
                ...mongodbInspected().inspection,
                scope: request.scope,
                snapshotId: `snapshot-${requests.length}`
              }
            }
          )
        })
      })
      await open(page, initial)
      await page.getByLabel('Inspection scope', {exact: true}).selectOption('AUTHORIZED_NAMES')
      expect(requests).toHaveLength(0)
      await page.getByRole('button', {name: 'Inspect selected scope', exact: true}).click()
      await expect(page.getByRole('table', {name: 'MongoDB databases', exact: true})).toContainText('catalog')
      expect(requests).toEqual([{clientId: 'client', scope: 'AUTHORIZED_NAMES'}])
      await page.getByLabel('Inspection scope', {exact: true}).selectOption('SELECTED')
      await page.getByLabel('Known database', {exact: true}).selectOption('observed-db')
      expect(requests).toHaveLength(1)
      for (const previous of [1, 2]) {
        await page.getByRole('button', {name: 'Inspect selected scope', exact: true}).click()
        await expect(page.getByRole('region', {name: 'Retained MongoDB catalog'})).toContainText(
          `snapshot-${previous + 1}`
        )
        await expect(page.getByLabel('Known database', {exact: true})).toHaveValue('observed-db')
        await expect(page.getByRole('button', {name: 'Inspect selected scope', exact: true})).toBeEnabled()
        expect(requests.at(-1)).toEqual({
          clientId: 'client',
          scope: 'SELECTED',
          databaseId: 'observed-db',
          snapshotId: `snapshot-${previous}`
        })
      }
    })
    test('row actions identify masked targets uniquely and support keyboard-only selection', async ({page}) => {
      let actions = 0
      page.on('request', (request) => {
        if (new URL(request.url()).pathname.endsWith('/mongodb/inspect')) actions++
      })
      await page.route(`**${apiPath}/mongodb?*`, (route) =>
        route.fulfill({
          json: mongodbCatalogPage('COLLECTIONS', [
            mongodbCollection({name: '******'}),
            mongodbCollection({id: 'other', name: '******'})
          ])
        })
      )
      await open(page, mongodbInspected())
      await page.getByRole('button', {name: 'View collections for database bootui_sample in sampleMongoClient'}).click()
      const table = page.getByRole('table', {name: 'MongoDB collections', exact: true})
      await expect(table).toBeVisible()
      for (const id of ['collection', 'other']) {
        for (const prefix of ['View indexes for', 'Select for inspection']) {
          await expect(
            page.getByRole('button', {name: `${prefix} collection ****** in bootui_sample (target ${id})`, exact: true})
          ).toHaveCount(1)
        }
      }
      const view = page.getByRole('button', {
        name: 'View indexes for collection ****** in bootui_sample (target other)',
        exact: true
      })
      const select = page.getByRole('button', {
        name: 'Select for inspection collection ****** in bootui_sample (target other)',
        exact: true
      })
      await view.focus()
      await page.keyboard.press('Tab')
      await expect(select).toBeFocused()
      await page.keyboard.press('Enter')
      await expect(page.getByLabel('Known database', {exact: true})).toHaveValue('db')
      await expect(page.getByRole('status')).toContainText('Use Inspect selected scope to contact MongoDB.')
      expect(actions).toBe(0)
    })
    for (const available of [true, false]) {
      test(`reports inventory limitations with no clients when available=${available}`, async ({page}) => {
        await open(
          page,
          mongodbReport({
            available,
            inventory: {
              clients: [],
              complete: false,
              truncated: true,
              limitations: ['Client discovery failed; details withheld.']
            }
          })
        )
        const limitations = page.getByRole('region', {name: 'MongoDB inventory limitations', exact: true})
        await expect(limitations).toContainText('Local client inventory is truncated')
        await expect(limitations).toContainText('Client discovery failed; details withheld.')
      })
    }
    test('shows partial reasons without interpreting denied evidence as zero', async ({page}) => {
      await open(
        page,
        mongodbInspected({
          status: 'PARTIAL',
          diagnostics: [{section: 'indexes', code: 'DENIED', message: 'Index permission denied.'}]
        })
      )
      await expect(page.getByText('Some requested evidence is unavailable.', {exact: false})).toBeVisible()
      await expect(page.getByText('indexes · DENIED: Index permission denied.')).toBeVisible()
    })
    test('scope controls remain labelled and keyboard-operable in dark theme', async ({page}) => {
      await page.addInitScript(() => localStorage.setItem('bootui.theme', 'dark'))
      await open(page, mongodbReport())
      await page.getByLabel('Application client', {exact: true}).focus()
      await expect(page.getByLabel('Application client', {exact: true})).toBeFocused()
      await page.keyboard.press('Tab')
      await expect(page.getByLabel('Inspection scope', {exact: true})).toBeFocused()
      await expect(page.locator('main [role="status"]')).toHaveCount(1)
      await expect(
        page.getByLabel('Inspection scope', {exact: true}).locator('option[value="AUTHORIZED_NAMES"]')
      ).toBeDisabled()
    })
  })
}
