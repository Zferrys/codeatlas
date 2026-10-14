import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import zhCN from '../locales/zh-CN'
import enUS from '../locales/en-US'
import AuthLayout from './AuthLayout.vue'

function createTestI18n(locale = 'zh-CN') {
  return createI18n({
    legacy: false,
    locale,
    fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS }
  })
}

function mountLayout(locale = 'zh-CN') {
  return mount(AuthLayout, {
    global: { plugins: [createTestI18n(locale)] },
    slots: { default: '<div class="slot-form">登录表单</div>' }
  })
}

describe('AuthLayout.vue', () => {
  it('AuthLayout_whenRendered_shouldShowChineseBrandCopyAndFeatures', () => {
    const wrapper = mountLayout('zh-CN')

    expect(wrapper.classes()).toContain('auth-layout')
    expect(wrapper.find('.brand-title').text()).toBe(zhCN.brand.title)
    expect(wrapper.find('.brand-desc').text()).toBe(zhCN.brand.desc)

    const featureTexts = wrapper.findAll('.feature-item').map(item => item.text())
    expect(featureTexts).toEqual([zhCN.brand.feature1, zhCN.brand.feature2, zhCN.brand.feature3])
  })

  it('AuthLayout_whenRendered_shouldRenderSlotInsideFormCard', () => {
    const wrapper = mountLayout('zh-CN')

    const card = wrapper.find('.auth-form-card')
    expect(card.exists()).toBe(true)
    expect(card.find('.slot-form').text()).toBe('登录表单')
  })

  it('AuthLayout_whenRendered_shouldShowFooterWithGithubLink', () => {
    const wrapper = mountLayout('zh-CN')

    const footer = wrapper.find('.auth-footer')
    expect(footer.text()).toContain(zhCN.brand.copyright)
    const link = footer.find('a.auth-footer-link')
    expect(link.text()).toBe('GitHub')
    expect(link.attributes('href')).toBe('https://github.com/zferrys/codeatlas')
    expect(link.attributes('target')).toBe('_blank')
  })

  it('AuthLayout_whenRendered_shouldRenderEightDecorativeParticles', () => {
    const wrapper = mountLayout('zh-CN')

    expect(wrapper.findAll('.particle')).toHaveLength(8)
    expect(wrapper.find('.brand-bg').exists()).toBe(true)
  })

  it('AuthLayout_whenLocaleIsEnglish_shouldSwitchBrandCopy', () => {
    const wrapper = mountLayout('en-US')

    expect(wrapper.find('.brand-desc').text()).toBe(enUS.brand.desc)
    expect(wrapper.findAll('.feature-item')).toHaveLength(3)
    expect(wrapper.find('.feature-item').text()).toBe(enUS.brand.feature1)
  })
})
