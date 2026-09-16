<script setup>
import {computed, inject, onMounted, ref} from 'vue'
import {ApiError, getJson, isActionBusyError, actionBusyMessage} from '../api.js'
import {describeLoadError} from '../utils/loadError.js'
import {formatClockTime} from '../utils/format.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import SpinnerButton from './components/SpinnerButton.vue'
import UnavailableState from './components/UnavailableState.vue'

const props = defineProps(panelProps)
const manifest = inject('panels', ref(null))
function relatedAvailable(id) {
  return (
    !manifest.value?.panels ||
    manifest.value.panels.some((panel) => panel.id === id && panel.available && panel.enabled !== false)
  )
}
const {readOnly, readOnlyReason, manifestAvailable, manifestUnavailableReason} = usePanelState(props)
const report = ref(null)
const initialLoading = ref(true)
const loading = ref(false)
const paging = ref(false)
const error = ref(null)
const notice = ref(null)
const clientId = ref('')
const scope = ref('CONFIGURED')
const databaseId = ref('')
const collectionId = ref('')
const selectionSnapshot = ref(null)
const knownDatabases = ref([])
const section = ref('DATABASES')
const parentDatabase = ref('')
const parentCollection = ref('')
const query = ref('')
const draftQuery = ref('')
const offset = ref(0)
let loadGeneration = 0
const clients = computed(() => report.value?.inventory?.clients || [])
const client = computed(() => clients.value.find((item) => item.id === clientId.value))
const inspection = computed(() => report.value?.inspection)
const catalog = computed(() => report.value?.catalog)
const rows = computed(() => catalog.value?.[section.value.toLowerCase()] || [])
const databases = computed(() => {
  const result = new Map((client.value?.configuredDatabases || []).map((db) => [db.id, db]))
  for (const db of knownDatabases.value) if (db.clientId === clientId.value) result.set(db.id, db)
  return [...result.values()]
})
const disabledReason = computed(() => {
  if (!manifestAvailable.value) return manifestUnavailableReason.value || 'MongoDB is unavailable.'
  if (readOnly.value) return readOnlyReason.value || 'MongoDB inspection is read-only.'
  if (report.value?.limits?.inspectEnabled === false) return 'MongoDB inspection is disabled by configuration.'
  if (!client.value?.inspectable) return 'Select an initialized application client. BootUI never initializes one.'
  if (scope.value === 'CONFIGURED' && !client.value.configuredDatabases?.length)
    return 'This client has no verified database scope. Configure its databases before inspection.'
  if (scope.value === 'SELECTED' && !databaseId.value) return 'Select a known database.'
  return null
})
const statusLabels = {
  NOT_READ: 'Not inspected',
  READ: 'Inspected',
  PARTIAL: 'Partly inspected',
  ERROR: 'Inspection failed',
  DISABLED: 'Unavailable'
}
const status = computed(() =>
  inspection.value?.truncated && !report.value?.diagnostics?.length
    ? 'Limited results'
    : statusLabels[report.value?.status] || 'Not inspected'
)
function accept(value) {
  const sameSnapshot = value.inspection?.snapshotId === inspection.value?.snapshotId
  report.value = value
  if (!value.inventory?.clients?.some((item) => item.id === clientId.value)) {
    clientId.value = value.inventory?.clients?.[0]?.id || ''
    changeClient()
  }
  if (!sameSnapshot) knownDatabases.value = []
  if (value.catalog?.section === 'DATABASES') {
    const known = new Map(knownDatabases.value.map((db) => [db.id, db]))
    for (const db of value.catalog.databases || []) known.set(db.id, db)
    knownDatabases.value = [...known.values()]
  }
}
function describeMongoError(failure, context) {
  if (failure instanceof ApiError && failure.status >= 400 && failure.status < 500) {
    const reason = [failure.body?.error, failure.body?.message].find(
      (value) => typeof value === 'string' && value.trim()
    )
    if (reason) return {title: 'Request refused', message: `${context}: ${reason}`, serverUnreachable: false}
  }
  return describeLoadError(failure, context)
}
async function loadPage(selection = {}, refresh = false) {
  if (!manifestAvailable.value) {
    initialLoading.value = false
    return
  }
  if (loading.value || paging.value) return
  const requested = {
    snapshotId: inspection.value?.snapshotId,
    section: section.value,
    databaseId: parentDatabase.value,
    collectionId: parentCollection.value,
    query: query.value,
    offset: offset.value,
    limit: catalog.value?.page?.limit || 50,
    ...selection
  }
  const generation = ++loadGeneration
  const params = new URLSearchParams()
  if (requested.snapshotId) params.set('snapshotId', requested.snapshotId)
  if (requested.section !== 'DATABASES') params.set('section', requested.section)
  if (requested.databaseId) params.set('databaseId', requested.databaseId)
  if (requested.collectionId) params.set('collectionId', requested.collectionId)
  if (requested.query) params.set('query', requested.query)
  if (requested.offset) params.set('offset', String(requested.offset))
  if (requested.limit !== 50) params.set('limit', String(requested.limit))
  paging.value = true
  notice.value = null
  error.value = null
  try {
    const value = await getJson(`api/mongodb${params.size ? `?${params}` : ''}`)
    if (generation !== loadGeneration) return
    accept(value)
    section.value = value.catalog?.section || requested.section
    parentDatabase.value = requested.databaseId
    parentCollection.value = requested.collectionId
    query.value = requested.query
    draftQuery.value = requested.query
    offset.value = value.catalog?.page?.offset ?? requested.offset
    if (refresh) {
      changeClient()
      notice.value = 'Local metadata reloaded. No inspection was run.'
    }
    error.value = null
  } catch (failure) {
    if (generation === loadGeneration) {
      draftQuery.value = query.value
      if (isActionBusyError(failure)) notice.value = actionBusyMessage(failure)
      else error.value = describeMongoError(failure, 'Unable to read retained MongoDB metadata')
    }
  } finally {
    if (generation === loadGeneration) {
      paging.value = false
      initialLoading.value = false
    }
  }
}
async function inspect() {
  if (loading.value || paging.value || initialLoading.value || disabledReason.value) return
  const request = {clientId: clientId.value, scope: scope.value}
  if (scope.value === 'SELECTED') {
    request.databaseId = databaseId.value
    if (collectionId.value) request.collectionId = collectionId.value
    if (selectionSnapshot.value) request.snapshotId = selectionSnapshot.value
  }
  loading.value = true
  notice.value = null
  error.value = null
  ++loadGeneration
  try {
    const value = await getJson('api/mongodb/inspect', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(request)
    })
    section.value = 'DATABASES'
    parentDatabase.value = ''
    parentCollection.value = ''
    query.value = ''
    draftQuery.value = ''
    offset.value = 0
    accept(value)
    if (request.scope === 'SELECTED') {
      const retainedDatabase = databases.value.some((db) => db.id === request.databaseId)
      const retainedCollection = !request.collectionId || value.inspection?.collectionsRetained > 0
      databaseId.value = retainedDatabase && retainedCollection ? request.databaseId : ''
      collectionId.value = databaseId.value ? request.collectionId || '' : ''
      selectionSnapshot.value = databaseId.value ? value.inspection?.snapshotId : null
    } else {
      databaseId.value = ''
      collectionId.value = ''
      selectionSnapshot.value = null
    }
    error.value = null
    notice.value =
      request.scope === 'SELECTED' && !databaseId.value
        ? `${status.value}. The selected target was not retained; choose a scope before inspecting again.`
        : `${status.value}. Retained metadata is ready to browse.`
  } catch (failure) {
    if (isActionBusyError(failure)) notice.value = actionBusyMessage(failure)
    else error.value = describeMongoError(failure, 'Unable to inspect the selected MongoDB scope')
  } finally {
    loading.value = false
    paging.value = false
  }
}
function changeClient() {
  scope.value = 'CONFIGURED'
  databaseId.value = ''
  collectionId.value = ''
  selectionSnapshot.value = null
}
function changeDatabase() {
  collectionId.value = ''
  selectionSnapshot.value = knownDatabases.value.some((db) => db.id === databaseId.value)
    ? inspection.value?.snapshotId
    : null
}
function browse(kind, database = '', collection = '') {
  loadPage({section: kind, databaseId: database, collectionId: collection, query: '', offset: 0})
}
function selectCollection(row) {
  clientId.value = row.clientId
  scope.value = 'SELECTED'
  databaseId.value = row.databaseId
  collectionId.value = row.id
  selectionSnapshot.value = inspection.value?.snapshotId
  notice.value = `Collection ${row.name} selected. Use Inspect selected scope to contact MongoDB.`
}
function rowTarget(row) {
  const label = (item) => {
    const owner = clients.value.find((candidate) => candidate.id === item.clientId)
    const database = knownDatabases.value.find((candidate) => candidate.id === item.databaseId)
    const context = section.value === 'DATABASES' ? owner?.name || item.clientId : database?.name || item.databaseId
    return `${item.name} in ${context}`
  }
  const target = label(row)
  const duplicate = rows.value.some((item) => item.id !== row.id && label(item) === target)
  return `${target}${duplicate ? ` (target ${row.id})` : ''}`
}
function turnPage(direction) {
  loadPage({offset: Math.max(0, offset.value + direction * (catalog.value?.page?.limit || 50))})
}
function localRefresh() {
  loadPage(
    {snapshotId: null, section: 'DATABASES', databaseId: '', collectionId: '', query: '', offset: 0, limit: 50},
    true
  )
}
onMounted(() => loadPage())
</script>

<template>
  <!-- Incumbent operational list/detail world: passive declarations first, explicit selected-scope
       inspection second. No new visual system, health score, document browser or automatic probe. -->
  <div>
    <PanelHeader icon="bi-leaf" title="MongoDB" :error="error" />
    <p class="text-muted">
      Understand the application's clients, collections and conventional indexes. No documents, statistics or index
      advice.
    </p>
    <section
      v-if="report?.inventory?.truncated || report?.inventory?.limitations?.length"
      class="small mb-3"
      aria-label="MongoDB inventory limitations"
    >
      <p v-if="report.inventory.truncated" class="mb-1">
        Local client inventory is truncated. Additional declarations were not retained.
      </p>
      <ul v-if="report.inventory.limitations?.length" class="mb-0">
        <li v-for="limitation in report.inventory.limitations" :key="limitation">{{ limitation }}</li>
      </ul>
    </section>
    <PanelSkeleton v-if="initialLoading" />
    <UnavailableState v-else-if="!manifestAvailable || report?.available === false">
      {{ manifestUnavailableReason || report?.unavailableReason || 'No managed MongoDB client is available.' }}
      Use the matching framework MongoDB integration; BootUI does not install or initialize clients.
    </UnavailableState>
    <template v-else-if="report">
      <div class="d-flex flex-wrap align-items-center gap-2 mb-3">
        <span class="badge text-bg-secondary">{{ status }}</span>
        <span class="small text-muted" v-if="inspection?.completedAt"
          >Last inspection {{ formatClockTime(inspection.completedAt) }} · {{ inspection.scope }}</span
        >
        <button class="btn btn-sm btn-outline-secondary ms-auto" :disabled="loading || paging" @click="localRefresh">
          Reload local metadata
        </button>
      </div>
      <p v-if="report.message" class="small text-muted">{{ report.message }}</p>
      <p class="small text-muted">Local topology is driver knowledge, not a fresh health or authentication check.</p>
      <section class="card mb-4" aria-label="MongoDB inspection scope">
        <div class="card-body">
          <div class="row g-3">
            <div class="col-lg-5">
              <label for="mongodb-client" class="form-label">Application client</label>
              <select
                id="mongodb-client"
                v-model="clientId"
                class="form-select font-monospace"
                :disabled="loading"
                @change="changeClient"
              >
                <option v-for="item in clients" :key="item.id" :value="item.id">
                  {{ item.name }} · {{ item.driverStyle }} · {{ item.lifecycle }}
                </option>
              </select>
            </div>
            <div class="col-lg-4">
              <label for="mongodb-scope" class="form-label">Inspection scope</label>
              <select id="mongodb-scope" v-model="scope" class="form-select" :disabled="loading">
                <option value="CONFIGURED">Configured databases</option>
                <option value="SELECTED">Selected database or collection</option>
                <option value="AUTHORIZED_NAMES" :disabled="!report.limits.authorizedDatabaseEnumerationEnabled">
                  Authorized database names only
                </option>
              </select>
            </div>
            <div class="col-lg-3 d-flex align-items-end">
              <SpinnerButton
                class="btn btn-success w-100"
                :loading="loading"
                :disabled="loading || paging || !!disabledReason"
                label="Inspect selected scope"
                loading-label="Inspecting…"
                icon="bi-search"
                @click="inspect"
              />
            </div>
            <div v-if="scope === 'SELECTED'" class="col-lg-7">
              <label for="mongodb-database" class="form-label">Known database</label>
              <select
                id="mongodb-database"
                v-model="databaseId"
                class="form-select font-monospace"
                :disabled="loading"
                @change="changeDatabase"
              >
                <option value="">Select a database</option>
                <option v-for="db in databases" :key="db.id" :value="db.id">{{ db.name }} · {{ db.provenance }}</option>
              </select>
              <p v-if="collectionId" class="small mt-2 mb-0">
                One collection selected from the retained snapshot.
                <button class="btn btn-sm btn-link" @click="collectionId = ''">Use the whole database</button>
              </p>
            </div>
          </div>
          <p class="small text-muted mt-3 mb-1">
            Inspect contacts only the selected existing client. Cooperative budget {{ report.limits.timeoutMillis }} ms;
            each operation at most {{ report.limits.operationTimeoutMillis }} ms. Retains at most
            {{ report.limits.maxTotalItems }} metadata items / {{ report.limits.maxMetadataBytes }} bytes.
          </p>
          <p v-if="scope === 'AUTHORIZED_NAMES'" class="small text-muted mb-1">
            MongoDB returns database names in one unpaged response. Retained limits do not bound initial decoding.
            Select a database for a separate collection inspection.
          </p>
          <p v-if="disabledReason" class="small text-muted mb-0">{{ disabledReason }}</p>
        </div>
      </section>
      <section v-if="client" class="mb-4" aria-label="Local client metadata">
        <h2 class="h5">Local client metadata</h2>
        <dl class="row small mb-2">
          <dt class="col-sm-3">Driver</dt>
          <dd class="col-sm-9">
            <code>{{ client.driverStyle }} {{ client.driverVersion || 'version unknown' }}</code>
          </dd>
          <dt class="col-sm-3">Topology snapshot</dt>
          <dd class="col-sm-9">
            <code>{{ client.topology?.type || 'Unknown' }}</code> · {{ client.topology?.mode || 'Unknown mode' }}
          </dd>
          <dt class="col-sm-3">Configured databases</dt>
          <dd class="col-sm-9">
            <code>{{ (client.configuredDatabases || []).map((db) => db.name).join(', ') || 'Unknown' }}</code>
          </dd>
        </dl>
        <ul v-if="client.topology?.servers?.length" class="list-unstyled small">
          <li v-for="server in client.topology.servers" :key="server.endpoint">
            <code>{{ server.endpoint }}</code> · {{ server.type }} · {{ server.state }}
          </li>
        </ul>
        <p v-for="limitation in client.limitations" :key="limitation" class="small text-muted mb-1">{{ limitation }}</p>
        <details v-if="client.settings?.length" class="small">
          <summary>Available settings and provenance</summary>
          <dl v-for="setting in client.settings" :key="setting.name" class="row mt-2 mb-0">
            <dt class="col-sm-4">
              <code>{{ setting.name }}</code>
            </dt>
            <dd class="col-sm-8">
              <code>{{ setting.value ?? 'Unknown' }}</code> · {{ setting.provenance }} · {{ setting.source }}
            </dd>
          </dl>
        </details>
      </section>
      <div role="status" aria-live="polite" class="small mb-3">{{ notice }}</div>
      <section v-if="inspection" aria-label="Retained MongoDB catalog">
        <h2 class="h5">Retained catalog</h2>
        <p class="small text-muted">
          Snapshot <code>{{ inspection.snapshotId }}</code
          >. Counts describe retained metadata, not the entire server.
        </p>
        <div v-if="report.diagnostics?.length" class="alert alert-warning">
          <p class="mb-1">Some requested evidence is unavailable. Successful neighbors remain visible.</p>
          <ul class="small mb-0">
            <li v-for="(item, index) in report.diagnostics" :key="index">
              {{ item.section }} · {{ item.code }}: {{ item.message }}
            </li>
          </ul>
        </div>
        <p v-for="limitation in inspection.limitations" :key="limitation" class="small text-muted">{{ limitation }}</p>
        <div class="d-flex flex-wrap gap-2 mb-3" role="group" aria-label="Catalog section">
          <button
            v-for="kind in ['DATABASES', 'COLLECTIONS', 'INDEXES']"
            :key="kind"
            class="btn btn-sm"
            :class="section === kind ? 'btn-success' : 'btn-outline-secondary'"
            :aria-pressed="section === kind"
            :disabled="paging || loading"
            @click="browse(kind)"
          >
            {{ kind[0] + kind.slice(1).toLowerCase() }}
          </button>
        </div>
        <form class="d-flex gap-2 mb-3" @submit.prevent="loadPage({query: draftQuery, offset: 0})">
          <input
            v-model="draftQuery"
            class="form-control"
            :disabled="paging || loading"
            aria-label="Filter retained MongoDB metadata"
            placeholder="Filter retained names…"
            maxlength="256"
          />
          <button class="btn btn-outline-secondary" :disabled="paging || loading">Filter</button>
        </form>
        <div class="table-responsive bootui-table-scroll" :aria-busy="paging || undefined">
          <table class="table table-sm table-hover bootui-data-table" :aria-label="`MongoDB ${section.toLowerCase()}`">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">{{ section === 'INDEXES' ? 'Ordered keys / options' : 'Evidence' }}</th>
                <th scope="col">Selection</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id">
                <td>
                  <code class="bootui-break-anywhere">{{ row.name }}</code>
                </td>
                <td v-if="section === 'DATABASES'">
                  <span class="small">{{ row.provenance }}</span>
                </td>
                <td v-else-if="section === 'COLLECTIONS'">
                  <span class="small">{{ row.type || 'Type unknown' }}</span>
                  <span v-if="row.validatorPresent" class="small"> · Validator present (withheld)</span>
                  <span v-if="row.encryptedFieldsPresent" class="small"> · Encryption metadata present</span>
                </td>
                <td v-else>
                  <span v-for="(key, index) in row.keys" :key="index"
                    ><span v-if="index">, </span><code>{{ key.field }} {{ key.kind }}</code></span
                  >
                  <div class="small text-muted">
                    <span v-if="row.unique">Unique · </span><span v-if="row.sparse">Sparse · </span
                    ><span v-if="row.hidden">Hidden · </span>
                    <span v-if="row.expireAfterSeconds != null">TTL {{ row.expireAfterSeconds }} seconds · </span>
                    <span v-if="row.partialFilterPresent">Partial predicate withheld · </span
                    ><span v-if="row.collationPresent">Collation present · </span>
                    <span v-if="row.wildcardProjectionPresent">Wildcard projection withheld · </span
                    ><span v-if="row.hasUnsupportedOptions">Other options not represented</span>
                  </div>
                </td>
                <td>
                  <button
                    v-if="section === 'DATABASES'"
                    class="btn btn-sm btn-outline-secondary"
                    :aria-label="`View collections for database ${rowTarget(row)}`"
                    :disabled="paging || loading"
                    @click="browse('COLLECTIONS', row.id)"
                  >
                    View collections
                  </button>
                  <template v-else-if="section === 'COLLECTIONS'"
                    ><button
                      class="btn btn-sm btn-outline-secondary me-1"
                      :aria-label="`View indexes for collection ${rowTarget(row)}`"
                      :disabled="paging || loading"
                      @click="browse('INDEXES', row.databaseId, row.id)"
                    >
                      View indexes
                    </button>
                    <button
                      class="btn btn-sm btn-link"
                      :aria-label="`Select for inspection collection ${rowTarget(row)}`"
                      :disabled="loading || paging"
                      @click="selectCollection(row)"
                    >
                      Select for inspection
                    </button></template
                  >
                  <span v-else class="small text-muted">Observed declaration</span>
                </td>
              </tr>
              <tr v-if="!rows.length">
                <td colspan="3" class="text-muted">
                  No retained rows match this scope. Unavailable evidence does not establish absence.
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 small">
          <span
            >{{ catalog?.page?.returned || 0 }} shown · {{ catalog?.page?.matched || 0 }} matching /
            {{ catalog?.page?.total || 0 }} retained</span
          >
          <div class="d-flex gap-2">
            <button
              class="btn btn-sm btn-outline-secondary"
              :disabled="!offset || paging || loading"
              @click="turnPage(-1)"
            >
              Previous
            </button>
            <button
              class="btn btn-sm btn-outline-secondary"
              :disabled="!catalog?.page?.hasMore || paging || loading"
              @click="turnPage(1)"
            >
              Next
            </button>
          </div>
        </div>
      </section>
      <section class="mt-4 small" aria-label="Related MongoDB evidence">
        <h2 class="h6">Related evidence</h2>
        <div class="d-flex flex-wrap gap-3">
          <RouterLink v-if="relatedAvailable('data')" to="/data">Repository declarations</RouterLink
          ><RouterLink to="/config">Configuration</RouterLink> <RouterLink to="/beans">Beans</RouterLink
          ><RouterLink to="/metrics">Existing metrics</RouterLink
          ><RouterLink to="/dev-services">Dev Services</RouterLink>
          <RouterLink to="/health">Health (evaluates existing checks)</RouterLink>
        </div>
        <p class="text-muted mt-2">{{ report.disclaimer }}</p>
      </section>
    </template>
  </div>
</template>
