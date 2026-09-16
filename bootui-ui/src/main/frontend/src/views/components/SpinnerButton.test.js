import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import SpinnerButton from './SpinnerButton.vue'

describe('SpinnerButton', () => {
  it('defaults to type="button" and renders the label', () => {
    const wrapper = mount(SpinnerButton, {props: {label: 'Run checks'}})
    const button = wrapper.get('button')
    expect(button.attributes('type')).toBe('button')
    expect(button.text()).toBe('Run checks')
    expect(button.find('.spinner-border').exists()).toBe(false)
  })

  it('shows the spinner and swaps to the loading label while loading', () => {
    const wrapper = mount(SpinnerButton, {
      props: {loading: true, label: 'Run checks', loadingLabel: 'Running...'}
    })
    expect(wrapper.get('button').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('.spinner-border').attributes('aria-hidden')).toBe('true')
    expect(wrapper.text()).toBe('Running...')
  })

  it('keeps the label constant while loading when no loadingLabel is given', () => {
    const wrapper = mount(SpinnerButton, {props: {loading: true, label: 'Scan'}})
    expect(wrapper.text()).toBe('Scan')
  })

  it('keeps the decorative icon and spinner hidden from assistive technology across loading states', async () => {
    const wrapper = mount(SpinnerButton, {props: {icon: 'bi-play-circle', label: 'Migrate'}})
    expect(wrapper.get('i.bi.bi-play-circle').attributes('aria-hidden')).toBe('true')
    expect(wrapper.get('button').attributes('aria-hidden')).toBeUndefined()
    await wrapper.setProps({loading: true})
    expect(wrapper.find('i.bi.bi-play-circle').exists()).toBe(false)
    expect(wrapper.get('.spinner-border').attributes('aria-hidden')).toBe('true')
    await wrapper.setProps({loading: false})
    expect(wrapper.get('i.bi.bi-play-circle').attributes('aria-hidden')).toBe('true')
    expect(wrapper.find('.spinner-border').exists()).toBe(false)
    expect(wrapper.get('button').attributes('aria-busy')).toBeUndefined()
    expect(wrapper.text()).toBe('Migrate')
  })

  it('forwards class, disabled, title and click to the root button', async () => {
    const wrapper = mount(SpinnerButton, {
      attrs: {class: 'btn btn-primary', disabled: true, title: 'tip'},
      props: {label: 'Go'}
    })
    const button = wrapper.get('button')
    expect(button.classes()).toContain('btn')
    expect(button.classes()).toContain('btn-primary')
    expect(button.attributes('disabled')).toBeDefined()
    expect(button.attributes('title')).toBe('tip')
  })

  it('prefers default slot content over the label prop', () => {
    const wrapper = mount(SpinnerButton, {props: {label: 'fallback'}, slots: {default: 'Slotted'}})
    expect(wrapper.text()).toBe('Slotted')
  })

  it('uses the requested spinner spacing class', () => {
    const wrapper = mount(SpinnerButton, {props: {loading: true, label: 'Send', spinnerClass: 'me-2'}})
    expect(wrapper.find('.spinner-border').classes()).toContain('me-2')
  })
})
