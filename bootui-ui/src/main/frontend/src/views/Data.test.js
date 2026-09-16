import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Data from './Data.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function repositoriesReport() {
  return {
    total: 1,
    repositories: [
      {
        beanName: 'userRepository',
        repositoryInterface: 'com.example.UserRepository',
        domainType: 'com.example.User',
        idType: 'java.lang.Long',
        storeModule: 'JPA',
        queryMethodCount: 2,
        fragmentCount: 0
      }
    ]
  }
}

function repositoryDetail() {
  return {
    repositoryInterface: 'com.example.UserRepository',
    storeModule: 'JPA',
    domainType: 'com.example.User',
    idType: 'java.lang.Long',
    beanName: 'userRepository',
    customImplementation: null,
    methods: [
      {
        origin: 'ANNOTATED',
        signature: 'findUsersWithAnIntentionallyLongMethodSignature(java.lang.String)',
        query: 'select user from User user where user.displayName = :displayName',
        namedQuery: null,
        nativeQuery: false
      }
    ]
  }
}

describe('Data', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('shows a shared unavailable reason when Spring Data is not on the classpath', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(null, false, 404)))

    wrapper = mount(Data)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.classes()).toContain('alert-info')
    expect(alert.text()).toContain('Spring Data is not on the classpath')
    expect(alert.find('code').text()).toBe('spring-boot-starter-data-jpa')
  })

  it('reports when Spring Data is present but no repositories are detected', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({total: 0, repositories: []})))

    wrapper = mount(Data)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.classes()).toContain('alert-secondary')
    expect(alert.text()).toContain('no repository beans were detected')
  })

  it('renders repositories when repository beans are present', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(repositoriesReport())))

    wrapper = mount(Data)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/data/repositories', expect.anything())
    expect(wrapper.text()).not.toContain('not on the classpath')
    expect(wrapper.text()).toContain('UserRepository')
    expect(wrapper.text()).toContain('1 / 1 repositories')
  })

  it('contains long repository method values in a responsive table', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(repositoriesReport()))
        .mockResolvedValueOnce(jsonResponse(repositoryDetail()))
    )

    wrapper = mount(Data)
    await flushPromises()
    await wrapper.get('.list-group-item-action').trigger('click')
    await flushPromises()

    expect(wrapper.get('.table-responsive.bootui-table-scroll .data-methods-table').exists()).toBe(true)
    expect(wrapper.findAll('.data-methods-table .bootui-break-anywhere')).toHaveLength(2)
  })

  it('shows Mongo declaration kind and unresolved mapping without rendering query literals or inspecting', async () => {
    const summary = repositoriesReport()
    summary.repositories[0].storeModule = 'MONGO'
    summary.discovery = {complete: false, truncated: false, warnings: ['A lazy factory was not initialized.']}
    const detail = repositoryDetail()
    detail.storeModule = 'MONGO'
    detail.executionKind = 'REACTIVE'
    detail.mongodb = {
      binding: 'UNRESOLVED',
      mapping: {collection: null, collectionState: 'DYNAMIC', fields: []},
      declaredIndexes: [],
      limitations: ['Dynamic collection expressions were not evaluated.']
    }
    detail.methods[0].query = 'DO_NOT_RENDER_MONGO_LITERAL'
    detail.methods[0].queryMetadata = {
      language: 'MONGODB',
      kind: 'MONGO_AGGREGATION',
      dynamic: true,
      textWithheld: true
    }
    const requests = vi.fn().mockResolvedValueOnce(jsonResponse(summary)).mockResolvedValueOnce(jsonResponse(detail))
    vi.stubGlobal('fetch', requests)
    wrapper = mount(Data, {global: {stubs: {RouterLink: true}}})
    await flushPromises()
    await wrapper.get('.list-group-item-action').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('REACTIVE')
    expect(wrapper.text()).toContain('MONGO_AGGREGATION')
    expect(wrapper.text()).toContain('Literal text withheld')
    expect(wrapper.text()).toContain('DYNAMIC')
    expect(wrapper.text()).not.toContain('DO_NOT_RENDER_MONGO_LITERAL')
    expect(wrapper.get('router-link-stub').attributes('to')).toBe('/mongodb')
    expect(requests).toHaveBeenCalledTimes(2)
    expect(requests.mock.calls.every(([url]) => url.startsWith('api/data/'))).toBe(true)
  })
  it.each([
    ['******', 'STATIC', 'Withheld'],
    [null, 'STATIC', 'Withheld (metadata-only)'],
    [null, 'DYNAMIC', 'Unresolved'],
    [null, 'UNRESOLVED', 'Unresolved']
  ])('distinguishes collection %s / %s from unresolved mapping', async (collection, collectionState, expected) => {
    const detail = repositoryDetail()
    detail.storeModule = 'MONGO'
    detail.mongodb = {
      binding: 'UNRESOLVED',
      mapping: {
        collection,
        collectionState,
        idField: '******',
        versionField: '******',
        fields: [{property: 'id', persistedName: '******', javaType: 'java.lang.String', state: 'STATIC'}]
      },
      declaredIndexes: [],
      limitations: []
    }
    const requests = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(repositoriesReport()))
      .mockResolvedValueOnce(jsonResponse(detail))
    vi.stubGlobal('fetch', requests)
    wrapper = mount(Data, {global: {stubs: {RouterLink: true}}})
    await flushPromises()
    await wrapper.get('.list-group-item-action').trigger('click')
    await flushPromises()
    const declarations = wrapper.get('[aria-label="MongoDB repository declarations"]')
    expect(declarations.get('dd').text()).toBe(`${expected} · ${collectionState}`)
    expect(declarations.text()).toContain('Withheld / Withheld')
    expect(declarations.text()).toContain('id → Withheld')
    expect(declarations.text()).not.toContain('******')
    if (collectionState === 'STATIC') expect(declarations.text()).not.toContain('Unresolved')
    expect(requests).toHaveBeenCalledTimes(2)
  })
})
