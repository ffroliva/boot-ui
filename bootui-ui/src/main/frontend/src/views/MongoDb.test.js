import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import MongoDb from './MongoDb.vue'

const client = {
  id: 'client',
  name: 'application',
  driverStyle: 'SYNC',
  lifecycle: 'INITIALIZED',
  inspectable: true,
  configuredDatabases: [{id: 'database', clientId: 'client', name: 'catalog', provenance: 'CONFIGURED'}],
  settings: [],
  limitations: []
}
function fixture(overrides = {}) {
  return {
    available: true,
    status: 'NOT_READ',
    message: 'No inspection has run.',
    disclaimer: 'No documents are read.',
    inventory: {clients: [client]},
    inspection: null,
    catalog: {
      section: 'DATABASES',
      databases: [],
      collections: [],
      indexes: [],
      page: {total: 0, matched: 0, returned: 0, offset: 0, limit: 50, hasMore: false}
    },
    limits: {
      inspectEnabled: true,
      authorizedDatabaseEnumerationEnabled: false,
      timeoutMillis: 10000,
      operationTimeoutMillis: 2000,
      maxTotalItems: 1000,
      maxMetadataBytes: 524288
    },
    diagnostics: [],
    ...overrides
  }
}
function inspected(overrides = {}) {
  return fixture({
    status: 'READ',
    inspection: {
      snapshotId: 'snapshot',
      clientId: 'client',
      scope: 'CONFIGURED',
      completedAt: 1700000000000,
      databasesRetained: 1,
      collectionsRetained: 1,
      indexesRetained: 1,
      limitations: []
    },
    catalog: {
      section: 'DATABASES',
      databases: client.configuredDatabases,
      page: {total: 1, matched: 1, returned: 1, offset: 0, limit: 50, hasMore: false}
    },
    ...overrides
  })
}
function response(value, status = 200) {
  return new Response(JSON.stringify(value), {status, headers: {'content-type': 'application/json'}})
}
const wrappers = []
async function setup(value = fixture(), props = {}) {
  document.cookie = 'XSRF-TOKEN=mongodb-test'
  const fetch = vi.fn().mockImplementation(() => Promise.resolve(response(value)))
  vi.stubGlobal('fetch', fetch)
  const wrapper = mount(MongoDb, {props, global: {stubs: {RouterLink: {template: '<a><slot /></a>'}}}})
  wrappers.push(wrapper)
  await flushPromises()
  return {wrapper, fetch}
}
function button(wrapper, text) {
  return wrapper.findAll('button').find((item) => item.text() === text)
}
const collection = {id: 'collection', databaseId: 'database', clientId: 'client', name: 'products', type: 'collection'}
function catalogPage(section, rows, page = {}, overrides = {}) {
  return inspected({
    catalog: {
      section,
      databases: [],
      collections: [],
      indexes: [],
      [section.toLowerCase()]: rows,
      page: {
        total: rows.length,
        matched: rows.length,
        returned: rows.length,
        offset: 0,
        limit: 50,
        hasMore: false,
        ...page
      }
    },
    ...overrides
  })
}
async function browseCollections(wrapper, fetch, page = {}) {
  fetch.mockResolvedValueOnce(response(catalogPage('COLLECTIONS', [collection], page)))
  await button(wrapper, 'View collections').trigger('click')
  await flushPromises()
}
function requestQuery(fetch) {
  return new URL(fetch.mock.calls.at(-1)[0], 'http://localhost/').searchParams
}
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.cookie = 'XSRF-TOKEN=; Max-Age=0'
  vi.unstubAllGlobals()
})
describe('MongoDB explicit inspection', () => {
  it('loads local/cached metadata once, with no health call or inspection', async () => {
    const {wrapper, fetch} = await setup()
    expect(fetch).toHaveBeenCalledExactlyOnceWith('api/mongodb', {})
    expect(wrapper.text()).toContain('not a fresh health')
    expect(wrapper.findAll('[role="status"]')).toHaveLength(1)
    expect(button(wrapper, 'Inspect selected scope').attributes('disabled')).toBeUndefined()
  })
  it('does not fetch unavailable or disabled endpoints', async () => {
    const {wrapper, fetch} = await setup(null, {
      panel: {id: 'mongodb', available: false, unavailableReason: 'No Mongo client'}
    })
    expect(fetch).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('No Mongo client')
  })
  it.each([{readOnly: true}, {available: true, readOnly: true}])(
    'blocks explicit action in read-only mode',
    async (panel) => {
      const {wrapper, fetch} = await setup(fixture(), {panel})
      expect(button(wrapper, 'Inspect selected scope').attributes('disabled')).toBeDefined()
      expect(fetch).toHaveBeenCalledTimes(1)
    }
  )
  it('refuses to initialize a lazy client and explains the reason', async () => {
    const {wrapper} = await setup(
      fixture({inventory: {clients: [{...client, inspectable: false, lifecycle: 'NOT_INITIALIZED'}]}})
    )
    expect(button(wrapper, 'Inspect selected scope').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('BootUI never initializes one')
  })
  it('inspects the selected client only after the explicit button', async () => {
    const {wrapper, fetch} = await setup()
    fetch.mockResolvedValueOnce(response(inspected()))
    await button(wrapper, 'Inspect selected scope').trigger('click')
    await flushPromises()
    expect(fetch.mock.calls[1][0]).toBe('api/mongodb/inspect')
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({clientId: 'client', scope: 'CONFIGURED'})
    expect(wrapper.text()).toContain('catalog')
  })
  it('pages the retained snapshot without inspecting it', async () => {
    const {wrapper, fetch} = await setup(inspected())
    fetch.mockResolvedValueOnce(
      response(
        inspected({
          catalog: {
            section: 'COLLECTIONS',
            collections: [
              {id: 'collection', databaseId: 'database', clientId: 'client', name: 'products', type: 'collection'}
            ],
            page: {total: 1, matched: 1, returned: 1}
          }
        })
      )
    )
    await button(wrapper, 'View collections').trigger('click')
    await flushPromises()
    expect(fetch.mock.calls[1][0]).toContain('snapshotId=snapshot')
    expect(fetch.mock.calls[1][0]).toContain('section=COLLECTIONS')
    expect(fetch.mock.calls.every(([url]) => !url.endsWith('/inspect'))).toBe(true)
    await button(wrapper, 'Select for inspection').trigger('click')
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('Use Inspect selected scope')
  })
  it('keeps successful rows on a busy or failed action without retrying', async () => {
    const {wrapper, fetch} = await setup(inspected())
    fetch.mockResolvedValueOnce(
      response(
        {
          error: 'BootUI action already in progress',
          operation: 'mongodb.inspect',
          activeOperation: 'mongodb.inspect',
          message: 'Inspection busy'
        },
        409
      )
    )
    await button(wrapper, 'Inspect selected scope').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('catalog')
    expect(wrapper.get('[role="status"]').text()).toBe('Inspection busy')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it.each([
    [409, {error: 'MongoDB snapshot changed; reload retained metadata before selecting'}],
    [400, {error: 'MongoDB section is invalid'}],
    [404, {message: 'Unknown MongoDB database'}]
  ])('keeps the accepted database section and canonical %i reason after a failed drill-down', async (status, body) => {
    const {wrapper, fetch} = await setup(inspected())
    let finish
    fetch.mockImplementationOnce(() => new Promise((resolve) => (finish = resolve)))
    await button(wrapper, 'View collections').trigger('click')
    expect(wrapper.get('table').attributes('aria-label')).toBe('MongoDB databases')
    expect(button(wrapper, 'Databases').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('table').text()).toContain('catalog')
    finish(response(body, status))
    await flushPromises()
    expect(wrapper.get('table').attributes('aria-label')).toBe('MongoDB databases')
    expect(wrapper.get('table').text()).toContain('catalog')
    expect(wrapper.text()).not.toContain('No retained rows')
    expect(wrapper.get('[role="alert"]').text()).toContain(body.error || body.message)
    expect(wrapper.text()).not.toContain(`HTTP ${status}`)
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('keeps accepted rows and section on a network failure', async () => {
    const {wrapper, fetch} = await setup(inspected())
    fetch.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await button(wrapper, 'Indexes').trigger('click')
    await flushPromises()
    expect(wrapper.get('table').attributes('aria-label')).toBe('MongoDB databases')
    expect(wrapper.get('table').text()).toContain('catalog')
    expect(wrapper.get('[role="alert"]').text()).toContain('Server unreachable')
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it('commits filters, parents and offsets only after a successful retained read', async () => {
    const {wrapper, fetch} = await setup(inspected())
    const page = {total: 3, matched: 3, limit: 1, hasMore: true}
    await browseCollections(wrapper, fetch, page)
    const filter = wrapper.get('input[aria-label="Filter retained MongoDB metadata"]')
    await filter.setValue('prod')
    fetch.mockResolvedValueOnce(response(catalogPage('COLLECTIONS', [collection], page)))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const next = {...collection, id: 'next', name: 'products_archive'}
    fetch.mockResolvedValueOnce(response(catalogPage('COLLECTIONS', [next], {...page, offset: 1})))
    await button(wrapper, 'Next').trigger('click')
    await flushPromises()

    for (const action of ['indexes', 'filter', 'next']) {
      fetch.mockResolvedValueOnce(response({error: 'MongoDB snapshot changed; reload retained metadata'}, 409))
      if (action === 'indexes') await button(wrapper, 'View indexes').trigger('click')
      else if (action === 'filter') {
        await filter.setValue('other')
        await wrapper.get('form').trigger('submit')
      } else await button(wrapper, 'Next').trigger('click')
      await flushPromises()
      expect(wrapper.get('table').attributes('aria-label')).toBe('MongoDB collections')
      expect(wrapper.get('table').text()).toContain('products_archive')
      expect(button(wrapper, 'Collections').attributes('aria-pressed')).toBe('true')
      expect(filter.element.value).toBe('prod')
      expect(button(wrapper, 'Previous').attributes('disabled')).toBeUndefined()
    }
    fetch.mockResolvedValueOnce(response(catalogPage('COLLECTIONS', [collection], page)))
    await button(wrapper, 'Previous').trigger('click')
    await flushPromises()
    expect(Object.fromEntries(requestQuery(fetch))).toEqual({
      snapshotId: 'snapshot',
      section: 'COLLECTIONS',
      databaseId: 'database',
      query: 'prod',
      limit: '1'
    })
    expect(fetch.mock.calls.every(([url]) => !url.endsWith('/inspect'))).toBe(true)
  })
  it.each([400, 404, 409])(
    'displays a safe non-busy %i inspect refusal without discarding evidence',
    async (status) => {
      const {wrapper, fetch} = await setup(inspected())
      const reason = 'MongoDB snapshot changed; reload retained metadata before selecting'
      fetch.mockResolvedValueOnce(response({error: reason}, status))
      await button(wrapper, 'Inspect selected scope').trigger('click')
      await flushPromises()
      expect(wrapper.get('[role="alert"]').text()).toContain(reason)
      expect(wrapper.get('table').text()).toContain('catalog')
      expect(wrapper.get('[role="status"]').text()).toBe('')
      expect(fetch).toHaveBeenCalledTimes(2)
    }
  )
  it('retains the accepted index page and both parent selectors when Next fails', async () => {
    const {wrapper, fetch} = await setup(inspected())
    await browseCollections(wrapper, fetch)
    const index = {id: 'index', name: 'products_unique', keys: [{field: 'sku', kind: 'ASC'}]}
    const page = {total: 51, matched: 51, hasMore: true}
    fetch.mockResolvedValueOnce(response(catalogPage('INDEXES', [index], page)))
    await button(wrapper, 'View indexes').trigger('click')
    await flushPromises()
    fetch.mockResolvedValueOnce(response({error: 'Unknown MongoDB collection'}, 404))
    await button(wrapper, 'Next').trigger('click')
    await flushPromises()
    expect(wrapper.get('table').attributes('aria-label')).toBe('MongoDB indexes')
    expect(wrapper.get('table').text()).toContain('products_unique')
    expect(button(wrapper, 'Previous').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[role="alert"]').text()).toContain('Unknown MongoDB collection')
    fetch.mockResolvedValueOnce(
      response(
        catalogPage('INDEXES', [{...index, id: 'next', name: 'last_index'}], {...page, offset: 50, hasMore: false})
      )
    )
    await button(wrapper, 'Next').trigger('click')
    await flushPromises()
    expect(Object.fromEntries(requestQuery(fetch))).toEqual({
      snapshotId: 'snapshot',
      section: 'INDEXES',
      databaseId: 'database',
      collectionId: 'collection',
      offset: '50'
    })
    expect(wrapper.get('table').text()).toContain('last_index')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })
  it('does not display arbitrary server-error details', async () => {
    const {wrapper, fetch} = await setup(inspected())
    fetch.mockResolvedValueOnce(response({error: 'PRIVATE_DRIVER_DETAILS'}, 500))
    await button(wrapper, 'Inspect selected scope').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('HTTP 500')
    expect(wrapper.text()).not.toContain('PRIVATE_DRIVER_DETAILS')
  })
  it('moves from authorized names to an explicit selected database and repeats with the returned snapshot', async () => {
    const initial = fixture()
    initial.limits.authorizedDatabaseEnumerationEnabled = true
    initial.inventory = {clients: [{...client, configuredDatabases: []}]}
    const {wrapper, fetch} = await setup(initial)
    const observed = {...client.configuredDatabases[0], provenance: 'AUTHORIZED_VISIBLE'}
    const result = (snapshotId, scope) =>
      catalogPage(
        'DATABASES',
        [observed],
        {},
        {
          inventory: initial.inventory,
          limits: initial.limits,
          inspection: {...inspected().inspection, snapshotId, scope}
        }
      )
    await wrapper.get('#mongodb-scope').setValue('AUTHORIZED_NAMES')
    expect(fetch).toHaveBeenCalledTimes(1)
    fetch.mockResolvedValueOnce(response(result('names-snapshot', 'AUTHORIZED_NAMES')))
    await button(wrapper, 'Inspect selected scope').trigger('click')
    await flushPromises()
    expect(JSON.parse(fetch.mock.calls.at(-1)[1].body)).toEqual({clientId: 'client', scope: 'AUTHORIZED_NAMES'})
    await wrapper.get('#mongodb-scope').setValue('SELECTED')
    await wrapper.get('#mongodb-database').setValue('database')
    expect(fetch).toHaveBeenCalledTimes(2)
    for (const [previous, next] of [
      ['names-snapshot', 'selected-one'],
      ['selected-one', 'selected-two']
    ]) {
      fetch.mockResolvedValueOnce(response(result(next, 'SELECTED')))
      await button(wrapper, 'Inspect selected scope').trigger('click')
      await flushPromises()
      expect(JSON.parse(fetch.mock.calls.at(-1)[1].body)).toEqual({
        clientId: 'client',
        scope: 'SELECTED',
        databaseId: 'database',
        snapshotId: previous
      })
      expect(wrapper.get('#mongodb-database').element.value).toBe('database')
      expect(wrapper.get('#mongodb-scope').element.value).toBe('SELECTED')
      expect(button(wrapper, 'Inspect selected scope').attributes('disabled')).toBeUndefined()
      expect(wrapper.get('section[aria-label="Retained MongoDB catalog"]').text()).toContain(next)
    }
    expect(fetch).toHaveBeenCalledTimes(4)
  })
  it('keeps an explicitly selected collection repeatable without broadening the scope', async () => {
    const {wrapper, fetch} = await setup(inspected())
    await browseCollections(wrapper, fetch)
    await button(wrapper, 'Select for inspection').trigger('click')
    for (const [previous, next] of [
      ['snapshot', 'selected-one'],
      ['selected-one', 'selected-two']
    ]) {
      fetch.mockResolvedValueOnce(
        response(inspected({inspection: {...inspected().inspection, snapshotId: next, scope: 'SELECTED'}}))
      )
      await button(wrapper, 'Inspect selected scope').trigger('click')
      await flushPromises()
      expect(JSON.parse(fetch.mock.calls.at(-1)[1].body)).toEqual({
        clientId: 'client',
        scope: 'SELECTED',
        databaseId: 'database',
        collectionId: 'collection',
        snapshotId: previous
      })
      expect(wrapper.get('#mongodb-database').element.value).toBe('database')
      expect(wrapper.text()).toContain('One collection selected from the retained snapshot.')
      expect(button(wrapper, 'Inspect selected scope').attributes('disabled')).toBeUndefined()
    }
  })
  it('reloads local metadata only on request and preserves selection/catalog if the refresh fails', async () => {
    const {wrapper, fetch} = await setup(inspected())
    await browseCollections(wrapper, fetch)
    await button(wrapper, 'Select for inspection').trigger('click')
    fetch.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await button(wrapper, 'Reload local metadata').trigger('click')
    await flushPromises()
    expect(fetch.mock.calls.at(-1)[0]).toBe('api/mongodb')
    expect(wrapper.get('table').text()).toContain('products')
    expect(wrapper.get('#mongodb-database').element.value).toBe('database')
    expect(wrapper.text()).toContain('One collection selected')
    expect(wrapper.text()).toContain('snapshot')
    fetch.mockResolvedValueOnce(response(fixture()))
    await button(wrapper, 'Reload local metadata').trigger('click')
    await flushPromises()
    expect(fetch.mock.calls.at(-1)[0]).toBe('api/mongodb')
    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.get('#mongodb-scope').element.value).toBe('CONFIGURED')
    expect(wrapper.find('#mongodb-database').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).toBe('Local metadata reloaded. No inspection was run.')
    expect(fetch.mock.calls.every(([url]) => !url.endsWith('/inspect'))).toBe(true)
  })
  it.each([true, false])(
    'shows inventory failures and truncation even with available=%s and no clients',
    async (available) => {
      const {wrapper, fetch} = await setup(
        fixture({
          available,
          inventory: {
            clients: [],
            complete: false,
            truncated: true,
            limitations: ['Client discovery failed; details withheld.']
          }
        })
      )
      const inventory = wrapper.get('[aria-label="MongoDB inventory limitations"]')
      expect(inventory.text()).toContain('Local client inventory is truncated')
      expect(inventory.text()).toContain('Client discovery failed; details withheld.')
      expect(fetch).toHaveBeenCalledTimes(1)
    }
  )
  it('shows cap-only inspection limitations without inventing a failed source', async () => {
    const {wrapper} = await setup(
      inspected({
        status: 'PARTIAL',
        inspection: {
          ...inspected().inspection,
          truncated: true,
          limitations: ['Retained metadata reached the item limit.']
        }
      })
    )
    expect(wrapper.text()).toContain('Limited results')
    expect(wrapper.text()).toContain('Retained metadata reached the item limit.')
    expect(wrapper.text()).not.toContain('Some requested evidence is unavailable')
  })
  it('explains disabled inspection while retaining local inventory', async () => {
    const initial = fixture()
    initial.limits.inspectEnabled = false
    const {wrapper, fetch} = await setup(initial)
    expect(wrapper.text()).toContain('MongoDB inspection is disabled by configuration.')
    expect(button(wrapper, 'Inspect selected scope').attributes('disabled')).toBeDefined()
    expect(wrapper.get('#mongodb-client').element.value).toBe('client')
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('gives row actions unique contextual accessible names, including masked duplicate targets', async () => {
    const {wrapper, fetch} = await setup(inspected())
    expect(button(wrapper, 'View collections').attributes('aria-label')).toBe(
      'View collections for database catalog in application'
    )
    const repeated = [
      {...collection, name: '******'},
      {...collection, id: 'second', name: '******'}
    ]
    fetch.mockResolvedValueOnce(response(catalogPage('COLLECTIONS', repeated)))
    await button(wrapper, 'View collections').trigger('click')
    await flushPromises()
    const labels = wrapper.findAll('tbody button').map((item) => item.attributes('aria-label'))
    expect(new Set(labels).size).toBe(4)
    expect(labels).toEqual([
      'View indexes for collection ****** in catalog (target collection)',
      'Select for inspection collection ****** in catalog (target collection)',
      'View indexes for collection ****** in catalog (target second)',
      'Select for inspection collection ****** in catalog (target second)'
    ])
  })
  it('renders exact TTL values and partial-filter presence without BSON', async () => {
    const {wrapper, fetch} = await setup(inspected())
    fetch.mockResolvedValueOnce(
      response(
        inspected({
          catalog: {
            section: 'INDEXES',
            indexes: [
              {
                id: 'index',
                name: 'ttl',
                keys: [{field: 'created', kind: 'DESC'}],
                expireAfterSeconds: '9007199254740993',
                partialFilterPresent: true
              }
            ],
            page: {total: 1, matched: 1, returned: 1}
          }
        })
      )
    )
    await button(wrapper, 'Indexes').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('9007199254740993')
    expect(wrapper.text()).toContain('Partial predicate withheld')
  })
})
