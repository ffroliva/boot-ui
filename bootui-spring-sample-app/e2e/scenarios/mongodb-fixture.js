export function mongodbReport(overrides = {}) {
  const database = {id: 'db', clientId: 'client', name: 'bootui_sample', provenance: 'CONFIGURED', capabilities: []}
  return {
    localOnly: true,
    available: true,
    unavailableReason: null,
    status: 'NOT_READ',
    message: 'No inspection has run.',
    disclaimer: 'Existing clients only. No documents, statistics or index advice.',
    valueExposure: 'MASKED',
    inventory: {
      observedAt: 1700000000000,
      complete: true,
      truncated: false,
      limitations: [],
      clients: [
        {
          id: 'client',
          name: 'sampleMongoClient',
          driverStyle: 'SYNC',
          driverVersion: '5.8.1',
          lifecycle: 'INITIALIZED',
          inspectable: true,
          configuredDatabases: [database],
          settings: [],
          limitations: [],
          topology: {
            observedAt: 1700000000000,
            type: 'STANDALONE',
            mode: 'SINGLE',
            servers: [{endpoint: 'localhost:27017', type: 'STANDALONE', state: 'CONNECTED'}],
            limitations: []
          }
        }
      ]
    },
    limits: {
      inspectEnabled: true,
      authorizedDatabaseEnumerationEnabled: false,
      timeoutMillis: 10000,
      operationTimeoutMillis: 2000,
      maxTotalItems: 1000,
      maxMetadataBytes: 524288
    },
    inspection: null,
    diagnostics: [],
    catalog: {
      section: 'DATABASES',
      databases: [],
      collections: [],
      indexes: [],
      page: {total: 0, matched: 0, offset: 0, limit: 50, returned: 0, hasMore: false}
    },
    ...overrides
  }
}
export function mongodbInspected(overrides = {}) {
  const database = mongodbReport().inventory.clients[0].configuredDatabases[0]
  return mongodbReport({
    status: 'READ',
    message: null,
    inspection: {
      snapshotId: 'snapshot',
      clientId: 'client',
      scope: 'CONFIGURED',
      startedAt: 1700000000000,
      completedAt: 1700000000500,
      databasesRetained: 1,
      collectionsRetained: 1,
      indexesRetained: 2,
      truncated: false,
      limitations: []
    },
    catalog: {
      section: 'DATABASES',
      databases: [database],
      collections: [],
      indexes: [],
      page: {total: 1, matched: 1, offset: 0, limit: 50, returned: 1, hasMore: false}
    },
    ...overrides
  })
}

export function mongodbCatalogPage(section, rows, page = {}, overrides = {}) {
  return mongodbInspected({
    catalog: {
      section,
      databases: [],
      collections: [],
      indexes: [],
      [section.toLowerCase()]: rows,
      page: {
        total: rows.length,
        matched: rows.length,
        offset: 0,
        limit: 50,
        returned: rows.length,
        hasMore: false,
        ...page
      }
    },
    ...overrides
  })
}

export function mongodbCollection(overrides = {}) {
  return {id: 'collection', databaseId: 'db', clientId: 'client', name: 'products', type: 'collection', ...overrides}
}
