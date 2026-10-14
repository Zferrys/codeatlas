import { describe, it, expect } from 'vitest'
import enUS from './en-US'
import zhCN from './zh-CN'

/** 递归收集对象的所有叶子节点路径（a.b.c 形式），用于结构比对 */
function collectKeyPaths(node, prefix = '') {
  return Object.entries(node).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key
    const isPlainObject = value !== null && typeof value === 'object' && !Array.isArray(value)
    return isPlainObject ? collectKeyPaths(value, path) : [path]
  })
}

/** 递归收集所有叶子值 */
function collectLeafValues(node) {
  return Object.values(node).flatMap(value => {
    const isPlainObject = value !== null && typeof value === 'object' && !Array.isArray(value)
    return isPlainObject ? collectLeafValues(value) : [value]
  })
}

describe('en-US locale', () => {
  it('enUS_whenComparedWithZhCN_shouldHaveIdenticalKeyStructure', () => {
    const enKeys = collectKeyPaths(enUS).sort()
    const zhKeys = collectKeyPaths(zhCN).sort()

    expect(enKeys.length).toBeGreaterThan(100)
    expect(enKeys).toEqual(zhKeys)
  })

  it('enUS_whenWalked_shouldHaveNonEmptyStringForEveryLeaf', () => {
    const leaves = collectLeafValues(enUS)

    expect(leaves.length).toBeGreaterThan(100)
    for (const value of leaves) {
      expect(typeof value).toBe('string')
      expect(value.trim().length).toBeGreaterThan(0)
    }
  })

  it('enUS_whenKeyNodes_shouldRenderEnglishCopy', () => {
    expect(enUS.brand.title).toBe('CodeAtlas')
    expect(enUS.nav.workspace).toBe('Workspace')
    expect(enUS.nav.logout).toBe('Logout')
    expect(enUS.common.confirm).toBe('Confirm')
    expect(enUS.auth.loginSuccess).toBe('Login successful')
    expect(enUS.dashboard.newProject).toBe('New Project')
    expect(enUS.search.placeholder).toBe('Search projects or classes...')
    expect(enUS.project.downloadHtml).toBe('Download HTML')
    expect(enUS.profile.role_viewer).toBe('Viewer')
  })

  it('enUS_whenTranslatedKeys_shouldDifferFromChineseCopy', () => {
    // 防止中文文案被原样复制进英文包（brand.title 等专有名词除外）
    expect(enUS.nav.workspace).not.toBe(zhCN.nav.workspace)
    expect(enUS.common.confirm).not.toBe(zhCN.common.confirm)
    expect(enUS.auth.loginSuccess).not.toBe(zhCN.auth.loginSuccess)
  })
})
