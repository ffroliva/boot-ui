<script setup>
import {computed, nextTick, ref, useId} from 'vue'
import {rangeLabel, useAdvisorRuleViolations} from '../../utils/useAdvisorRuleViolations.js'
import SpinnerButton from './SpinnerButton.vue'

const props = defineProps({
  apiPath: {type: String, required: true},
  rule: {type: Object, required: true},
  details: {type: Object, default: null},
  refreshReport: {type: Function, required: true}
})

const {page, loading, error, stale, announcement, available, load, retry, backToSamples} =
  useAdvisorRuleViolations(props)
const samples = computed(() => props.rule.sampleViolations || [])
const hasMoreThanSamples = computed(() => props.rule.violationCount > samples.value.length)
const regionId = `advisor-violations-${useId()}`
const heading = ref(null)
const viewButton = ref(null)
const refreshing = ref(false)

async function showPage(offset = 0) {
  if (await load(offset)) {
    await nextTick()
    heading.value?.focus()
  }
}

async function retryPage() {
  if (await retry()) {
    await nextTick()
    heading.value?.focus()
  }
}

async function showSamples() {
  backToSamples()
  await nextTick()
  viewButton.value?.$el.focus()
}

async function refreshCachedReport() {
  refreshing.value = true
  try {
    await props.refreshReport()
  } finally {
    refreshing.value = false
    await nextTick()
    // The scan may have changed, replacing the stale-state button.
    if (!stale.value) viewButton.value?.$el.focus()
  }
}
</script>

<template>
  <div v-if="samples.length || hasMoreThanSamples" class="advisor-rule-violations mb-2">
    <div class="d-flex flex-wrap align-items-center gap-2 mb-1">
      <div :id="`${regionId}-label`" ref="heading" tabindex="-1" class="small fw-semibold">
        <template v-if="page">{{ rangeLabel(page) }}</template>
        <template v-else>Sample details (showing {{ samples.length }} of {{ rule.violationCount }})</template>
      </div>
      <SpinnerButton
        v-if="!page && available && hasMoreThanSamples"
        ref="viewButton"
        class="btn btn-sm btn-outline-secondary"
        :loading="loading"
        :disabled="loading || refreshing || stale"
        :aria-label="`View violations for ${rule.id}`"
        :aria-controls="regionId"
        label="View violations"
        @click="showPage()"
      />
      <button
        v-if="page"
        type="button"
        class="btn btn-sm btn-outline-secondary"
        :aria-label="`Back to samples for ${rule.id}`"
        :aria-controls="regionId"
        @click="showSamples"
      >
        Back to samples
      </button>
    </div>
    <div :id="regionId" :aria-labelledby="`${regionId}-label`">
      <ul v-if="(page ? page.violations : samples).length" class="small mb-0">
        <li v-for="(violation, index) in page ? page.violations : samples" :key="index" class="font-monospace">
          {{ violation }}
        </li>
      </ul>
      <p v-else-if="page" class="small text-muted mb-0">No retained violations on this page.</p>
    </div>
    <p v-if="page?.truncated" class="small text-muted mt-2 mb-0">
      Incomplete details: {{ page.retainedCount }} of {{ page.violationCount }} violations retained for this rule.
      Missing details cannot be retrieved from this scan.
    </p>
    <p v-else-if="!page && details?.truncated" class="small text-muted mt-2 mb-0">
      This scan retained {{ details.retained }} of {{ details.total }} violation details across all rules (limit
      {{ details.retentionLimit }}). Some details may be unavailable.
    </p>
    <div v-if="page && (page.page.offset > 0 || page.page.hasMore)" class="d-flex flex-wrap gap-2 mt-2">
      <SpinnerButton
        class="btn btn-sm btn-outline-secondary"
        :loading="loading"
        :disabled="loading || refreshing || stale || page.page.offset === 0"
        :aria-label="`Previous violations for ${rule.id}`"
        :aria-controls="regionId"
        label="Previous"
        @click="showPage(Math.max(0, page.page.offset - page.page.limit))"
      />
      <SpinnerButton
        class="btn btn-sm btn-outline-secondary"
        :loading="loading"
        :disabled="loading || refreshing || stale || !page.page.hasMore"
        :aria-label="`Next violations for ${rule.id}`"
        :aria-controls="regionId"
        label="Next"
        @click="showPage(page.page.offset + page.page.returned)"
      />
    </div>
    <div v-if="error" class="small mt-2">
      <p class="text-danger mb-1">{{ error }}</p>
      <SpinnerButton
        v-if="stale"
        class="btn btn-sm btn-outline-secondary"
        :loading="refreshing"
        :disabled="refreshing"
        :aria-label="`Refresh cached report for ${rule.id}`"
        label="Refresh cached report"
        @click="refreshCachedReport"
      />
      <SpinnerButton
        v-else
        class="btn btn-sm btn-outline-secondary"
        :loading="loading"
        :disabled="loading"
        :aria-label="`Retry violations for ${rule.id}`"
        label="Retry"
        @click="retryPage"
      />
    </div>
    <div role="status" aria-atomic="true" class="visually-hidden">{{ announcement }}</div>
  </div>
</template>

<style scoped>
.advisor-rule-violations li {
  overflow-wrap: anywhere;
}

.advisor-rule-violations [tabindex='-1']:focus-visible {
  outline: 2px solid var(--bs-primary);
  outline-offset: 2px;
}
</style>
