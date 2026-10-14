import { describe, it, expect, vi } from 'vitest'
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

/**
 * 在用例体内重置模块注册表后重新加载：模块求值期（import 时）创建的
 * 对象字面量与字符串才会被归属到本用例。静态 import 发生在收集阶段，
 * 其模块求值不属于任何用例的执行窗口，导致 locale 表变异无法被覆盖。
 */
async function loadFreshLocales() {
  vi.resetModules()
  const [enModule, zhModule] = await Promise.all([import('./en-US'), import('./zh-CN')])
  return { enUS: enModule.default, zhCN: zhModule.default }
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

  it('zhCN_whenFreshlyImported_shouldHaveExactly156NonEmptyStringLeaves', async () => {
    const { zhCN: freshZh, enUS: freshEn } = await loadFreshLocales()

    const zhLeaves = collectLeafValues(freshZh)
    const enLeaves = collectLeafValues(freshEn)

    expect(zhLeaves.length).toBe(156)
    expect(enLeaves.length).toBe(156)
    for (const value of [...zhLeaves, ...enLeaves]) {
      expect(typeof value).toBe('string')
      expect(value.trim().length).toBeGreaterThan(0)
    }
  })

  it('zhCN_whenFreshlyImported_shouldHaveExactly156KeyPathsMatchingEnUS', async () => {
    const { zhCN: freshZh, enUS: freshEn } = await loadFreshLocales()

    const zhKeys = collectKeyPaths(freshZh).sort()
    const enKeys = collectKeyPaths(freshEn).sort()

    expect(zhKeys.length).toBe(156)
    expect(zhKeys).toEqual(enKeys)
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
