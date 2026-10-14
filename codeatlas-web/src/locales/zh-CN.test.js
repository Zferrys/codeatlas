import { describe, it, expect } from 'vitest'
import zhCN from './zh-CN'
import enUS from './en-US'

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

describe('zh-CN locale', () => {
  it('zhCN_whenComparedWithEnUS_shouldHaveIdenticalKeyStructure', () => {
    const zhKeys = collectKeyPaths(zhCN).sort()
    const enKeys = collectKeyPaths(enUS).sort()

    expect(zhKeys.length).toBeGreaterThan(100)
    expect(zhKeys).toEqual(enKeys)
  })

  it('zhCN_whenWalked_shouldHaveNonEmptyStringForEveryLeaf', () => {
    const leaves = collectLeafValues(zhCN)

    expect(leaves.length).toBeGreaterThan(100)
    for (const value of leaves) {
      expect(typeof value).toBe('string')
      expect(value.trim().length).toBeGreaterThan(0)
    }
  })

  it('zhCN_whenTopLevelSections_shouldMatchExpectedSet', () => {
    expect(Object.keys(zhCN).sort()).toEqual([
      'auth', 'brand', 'common', 'dashboard', 'nav', 'profile', 'project', 'search'
    ])
  })

  it('zhCN_whenKeyNodes_shouldRenderChineseCopy', () => {
    expect(zhCN.brand.title).toBe('CodeAtlas')
    expect(zhCN.brand.desc).toBe('AI 驱动的代码地图与架构叙事平台')
    expect(zhCN.nav.workspace).toBe('工作台')
    expect(zhCN.nav.admin).toBe('管理后台')
    expect(zhCN.nav.logout).toBe('退出登录')
    expect(zhCN.common.confirm).toBe('确认')
    expect(zhCN.auth.loginSuccess).toBe('登录成功')
    expect(zhCN.auth.passwordNotMatch).toBe('两次输入的密码不一致')
    expect(zhCN.dashboard.newProject).toBe('新建项目')
    expect(zhCN.search.placeholder).toBe('搜索项目或类名...')
    expect(zhCN.project.downloadPdf).toBe('下载 PDF')
    expect(zhCN.profile.role_admin).toBe('管理员')
  })
})
