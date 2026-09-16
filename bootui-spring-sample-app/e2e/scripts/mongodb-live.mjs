import {chromium, expect} from '@playwright/test'
import sharp from 'sharp'
import fs from 'node:fs/promises'
import path from 'node:path'

const [origin, uiPath = '/bootui', mode = 'inspect', screenshot] = process.argv.slice(2)
if (!origin || !['inspect', 'passive', 'data'].includes(mode))
  throw new Error('Expected origin, UI path and inspect/passive/data mode')
const url = new URL(origin)
if (!['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)) throw new Error('Live fixture must be loopback')
const browser = await chromium.launch({headless: true})
let page
const failures = []
try {
  page = await browser.newPage({viewport: {width: 1600, height: 900}})
  page.setDefaultTimeout(15000)
  page.on('pageerror', (error) => {
    if (failures.length < 10) failures.push(error.message.slice(0, 500))
  })
  page.on('response', (response) => {
    if (response.status() >= 400 && failures.length < 10)
      failures.push(`${response.status()} ${new URL(response.url()).pathname}`)
  })
  let actions = 0
  let healthReads = 0
  page.on('request', (request) => {
    if (new URL(request.url()).pathname.endsWith('/mongodb/inspect')) actions++
    if (new URL(request.url()).pathname.endsWith('/health')) healthReads++
  })
  await page.goto(`${origin.replace(/\/$/, '')}${uiPath}/#/${mode === 'data' ? 'data' : 'mongodb'}`)
  if (mode === 'data') {
    await page.getByRole('heading', {name: 'Spring Data repositories', exact: true}).waitFor()
    await page.getByLabel('Filter repositories by store').selectOption('MONGO')
    await page.locator('main .list-group-item-action').first().click()
    await page.getByRole('region', {name: 'MongoDB repository declarations'}).waitFor()
  } else {
    await page.getByRole('heading', {name: 'MongoDB', exact: true}).waitFor()
    await page.getByLabel('Application client', {exact: true}).waitFor()
  }
  if (actions || healthReads) throw new Error('Opening MongoDB caused an inspection or health evaluation')
  if (mode === 'inspect') {
    let expectedActions = 0
    async function inspectScope(expected) {
      const [response] = await Promise.all([
        page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/mongodb/inspect')),
        page.getByRole('button', {name: 'Inspect selected scope', exact: true}).click()
      ])
      expect(response.ok()).toBe(true)
      expect(response.request().postDataJSON()).toEqual(expected)
      const report = await response.json()
      expect(['READ', 'PARTIAL']).toContain(report.status)
      expect(report.inspection?.snapshotId).toBeTruthy()
      await expect(page.getByRole('region', {name: 'Retained MongoDB catalog'})).toContainText(
        report.inspection.snapshotId
      )
      await expect(page.getByRole('button', {name: 'Inspect selected scope', exact: true})).toBeEnabled()
      expectedActions++
      expect(actions).toBe(expectedActions)
      return report
    }
    async function browseCollections() {
      const databaseTable = page.getByRole('table', {name: 'MongoDB databases', exact: true})
      const target = databaseTable.getByRole('button', {name: /^View collections for database /}).first()
      await target.focus()
      const [response] = await Promise.all([
        page.waitForResponse((response) => new URL(response.url()).searchParams.get('section') === 'COLLECTIONS'),
        page.keyboard.press('Enter')
      ])
      expect(response.ok()).toBe(true)
      const report = await response.json()
      const collection = report.catalog.collections.find((item) => item.type === 'collection')
      expect(collection, 'The live fixture must retain an ordinary collection').toBeTruthy()
      const table = page.getByRole('table', {name: 'MongoDB collections', exact: true})
      const row = table.getByRole('row').filter({has: page.getByText(collection.name, {exact: true})})
      await expect(row).toHaveCount(1)
      expect(actions).toBe(expectedActions)
      return {collection, row}
    }
    const select = page.getByLabel('Application client', {exact: true})
    const options = await select
      .locator('option')
      .evaluateAll((elements) =>
        elements
          .filter(
            (element) => element.textContent.includes('INITIALIZED') && !element.textContent.includes('NOT_INITIALIZED')
          )
          .map((element) => ({value: element.value, text: element.textContent}))
      )
    if (!options.length) throw new Error('Live test requires an initialized MongoDB client')
    const usable =
      options.find(
        (option) => /sample|sync|default|named/i.test(option.text) && !/unused|timeout|restricted/i.test(option.text)
      ) || options[0]
    await select.selectOption(usable.value)
    let report = await inspectScope({clientId: usable.value, scope: 'CONFIGURED'})
    const database = report.catalog.databases[0]
    expect(database, 'The live fixture must retain its configured database').toBeTruthy()
    const scope = page.getByLabel('Inspection scope', {exact: true})
    if (!(await scope.locator('option[value="AUTHORIZED_NAMES"]').isDisabled())) {
      await scope.selectOption('AUTHORIZED_NAMES')
      const names = await inspectScope({clientId: usable.value, scope: 'AUTHORIZED_NAMES'})
      expect(names.catalog.databases.some((item) => item.id === database.id)).toBe(true)
      await scope.selectOption('SELECTED')
      await page.getByLabel('Known database', {exact: true}).selectOption(database.id)
      report = await inspectScope({
        clientId: usable.value,
        scope: 'SELECTED',
        databaseId: database.id,
        snapshotId: names.inspection.snapshotId
      })
    }
    const selected = await browseCollections()
    const selection = selected.row.getByRole('button', {name: /^Select for inspection collection /})
    await expect(selection).toHaveAccessibleName(
      new RegExp(selected.collection.name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    )
    await selection.focus()
    await page.keyboard.press('Enter')
    await expect(page.getByLabel('Known database', {exact: true})).toHaveValue(selected.collection.databaseId)
    expect(actions).toBe(expectedActions)
    for (let attempt = 0; attempt < 2; attempt++) {
      const previousSnapshot = report.inspection.snapshotId
      report = await inspectScope({
        clientId: usable.value,
        scope: 'SELECTED',
        databaseId: selected.collection.databaseId,
        collectionId: selected.collection.id,
        snapshotId: previousSnapshot
      })
      expect(report.inspection.snapshotId).not.toBe(previousSnapshot)
      await expect(page.getByLabel('Inspection scope', {exact: true})).toHaveValue('SELECTED')
      await expect(page.getByLabel('Known database', {exact: true})).toHaveValue(selected.collection.databaseId)
      await expect(page.getByText('One collection selected from the retained snapshot.', {exact: false})).toBeVisible()
    }
    const retained = await browseCollections()
    await retained.row.getByRole('button', {name: /^View indexes for collection /}).click()
    const indexes = page.getByRole('table', {name: 'MongoDB indexes', exact: true})
    await expect(indexes.getByText('_id_', {exact: true})).toBeVisible()
    await page.getByLabel('Filter retained MongoDB metadata', {exact: true}).fill('_id_')
    const [filtered] = await Promise.all([
      page.waitForResponse((response) => new URL(response.url()).searchParams.get('query') === '_id_'),
      page.getByRole('button', {name: 'Filter', exact: true}).click()
    ])
    expect(filtered.ok()).toBe(true)
    const query = new URL(filtered.url()).searchParams
    expect(query.get('snapshotId')).toBe(report.inspection.snapshotId)
    expect(query.get('databaseId')).toBe(selected.collection.databaseId)
    expect(query.get('collectionId')).toBe(selected.collection.id)
    await expect(indexes.getByText('_id_', {exact: true})).toBeVisible()
    const [reloaded] = await Promise.all([
      page.waitForResponse((response) => new URL(response.url()).pathname.endsWith('/mongodb')),
      page.getByRole('button', {name: 'Reload local metadata', exact: true}).click()
    ])
    expect(reloaded.ok()).toBe(true)
    expect(new URL(reloaded.url()).search).toBe('')
    await expect(page.getByRole('status')).toHaveText('Local metadata reloaded. No inspection was run.')
    expect(actions).toBe(expectedActions)
    if (healthReads) throw new Error('MongoDB page evaluated health')
  }
  if (screenshot) {
    await page.evaluate(() => {
      window.scrollTo(0, 0)
      document.querySelector('.bootui-workspace')?.scrollTo(0, 0)
    })
    await fs.mkdir(path.dirname(screenshot), {recursive: true})
    await sharp(await page.screenshot())
      .webp({quality: 80})
      .toFile(screenshot)
  }
  console.log(JSON.stringify({browser: 'passed', mode, actions, healthReads, origin, uiPath}))
} catch (error) {
  console.error(
    JSON.stringify({
      failures,
      url: page?.url(),
      buttons: await page
        ?.locator('main button')
        .evaluateAll((elements) =>
          elements.slice(0, 15).map((element) => ({
            text: element.textContent,
            disabled: element.disabled,
            label: element.getAttribute('aria-label')
          }))
        )
        .catch(() => []),
      body: (
        (await page
          ?.locator('body')
          .innerText()
          .catch(() => '')) || ''
      ).slice(0, 1800)
    })
  )
  throw error
} finally {
  await browser.close()
}
