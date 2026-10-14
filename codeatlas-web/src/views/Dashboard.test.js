import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createI18n } from 'vue-i18n'
import Antd, { message } from 'ant-design-vue'

vi.mock('../api', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  },
  handleUnauthorized: vi.fn()
}))

import api from '../api'
import Dashboard from './Dashboard.vue'
import zhCN from '../locales/zh-CN.js'
import enUS from '../locales/en-US.js'

const DummyRouteComponent = { template: '<div class="dummy-route" />' }
const MINUTE = 60 * 1000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/dashboard', component: Dashboard },
      { path: '/project/:id/overview', component: DummyRouteComponent }
    ]
  })
}

function createTestI18n() {
  return createI18n({
    legacy: false,
    locale: 'zh-CN',
    fallbackLocale: 'zh-CN',
    messages: { 'zh-CN': zhCN, 'en-US': enUS }
  })
}

function listResponse(records) {
  return { data: { data: { records } } }
}

function offsetIso(offsetMs) {
  return new Date(Date.now() - offsetMs - 5000).toISOString()
}

// 弹窗内容经 Teleport 渲染到 body，VTU 无法穿透 Teleport 遍历组件树，
// 因此通过 DOM 元素上的 __vueParentComponent 向上找到目标组件实例
function findTeleportedComponent(selector, componentName) {
  const element = document.body.querySelector(selector)
  let instance = element?.__vueParentComponent
  while (instance && instance.type?.name !== componentName) {
    instance = instance.parent
  }
  return instance
}

function sampleProjects() {
  return [
    {
      id: 1,
      name: 'Alpha',
      description: 'order service',
      language: 'Java',
      totalClasses: 10,
      totalScans: 2,
      totalInsights: 3,
      healthScore: 85,
      lastScanTime: offsetIso(5 * MINUTE)
    },
    {
      id: 2,
      name: 'Beta',
      description: 'python api',
      language: 'Python',
      totalClasses: 4,
      healthScore: 70,
      lastScanTime: offsetIso(3 * HOUR)
    },
    {
      id: 3,
      name: '',
      description: undefined,
      language: null,
      healthScore: 50,
      lastScanTime: offsetIso(2 * DAY)
    },
    {
      id: 4,
      name: 'Delta',
      description: undefined,
      language: 'Rust',
      totalClasses: 5,
      healthScore: null,
      lastScanTime: offsetIso(30 * DAY)
    },
    {
      id: 5,
      name: 'Epsilon',
      description: undefined,
      language: 'Go',
      totalClasses: 1,
      healthScore: undefined
    }
  ]
}

let wrapper = null

async function mountDashboard() {
  const router = createTestRouter()
  await router.push('/dashboard')
  await router.isReady()
  wrapper = mount(Dashboard, {
    global: { plugins: [router, createPinia(), createTestI18n(), Antd] }
  })
  return { wrapper, router }
}

describe('Dashboard.vue', () => {
  let messageSuccess
  let messageWarning
  let messageError

  beforeEach(() => {
    vi.stubGlobal('matchMedia', vi.fn().mockImplementation(query => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn()
    })))
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      unobserve() {}
      disconnect() {}
    })
    messageSuccess = vi.spyOn(message, 'success').mockImplementation(() => {})
    messageWarning = vi.spyOn(message, 'warning').mockImplementation(() => {})
    messageError = vi.spyOn(message, 'error').mockImplementation(() => {})
    vi.mocked(api.get).mockReset()
    vi.mocked(api.post).mockReset()
    vi.mocked(api.delete).mockReset()
    localStorage.clear()
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.restoreAllMocks()
  })

  it('onMounted_whenProjectsLoaded_shouldAggregateStatsAndRenderCards', async () => {
    api.get.mockResolvedValue(listResponse(sampleProjects()))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    expect(api.get).toHaveBeenCalledWith('/projects')
    expect(wrapper.findAll('.stat-value').map(node => node.text())).toEqual(['5', '20', '2', '3'])
    expect(wrapper.findAll('.project-card')).toHaveLength(5)
    expect(wrapper.find('.ant-skeleton').exists()).toBe(false)
    expect(wrapper.find('.ant-empty').exists()).toBe(false)
    expect(wrapper.findAll('.card-avatar').map(node => node.text())).toEqual(['A', 'B', 'P', 'D', 'E'])
    expect(wrapper.findAll('.card-title').map(node => node.text()))
      .toEqual(['Alpha', 'Beta', '', 'Delta', 'Epsilon'])
    expect(wrapper.findAll('.card-desc').map(node => node.text()))
      .toEqual(['order service', 'python api', '暂无描述', '暂无描述', '暂无描述'])
  })

  it('onMounted_whenLanguageAndHealthScoresPresent_shouldRenderTagsAndHealthColors', async () => {
    api.get.mockResolvedValue(listResponse(sampleProjects()))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    const tags = wrapper.findAll('.card-title-wrap .ant-tag')
    expect(tags.map(node => node.text())).toEqual(['Java', 'Python', 'Java', 'Rust', 'Go'])
    expect(tags[0].classes()).toContain('ant-tag-blue')
    expect(tags[1].classes()).toContain('ant-tag-green')

    const progresses = wrapper.findAllComponents({ name: 'AProgress' })
    expect(progresses).toHaveLength(3)
    expect(progresses.map(node => node.props('percent'))).toEqual([85, 70, 50])
    expect(progresses.map(node => node.props('strokeColor'))).toEqual(['#52c41a', '#faad14', '#ff4d4f'])
    expect(progresses[0].props('format')(70.4)).toBe('70分')
  })

  it('onMounted_whenProjectsLoaded_shouldFormatScanTimesByAge', async () => {
    api.get.mockResolvedValue(listResponse(sampleProjects()))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    const stats = wrapper.findAll('.card-stats').map(node => node.text())
    expect(stats[0]).toContain('5 分钟前')
    expect(stats[1]).toContain('3 小时前')
    expect(stats[2]).toContain('2 天前')
    expect(stats[3]).toMatch(/\d{4}\/\d{1,2}\/\d{1,2}/)
    expect(stats[4]).toContain('未扫描')
    expect(stats[0]).toContain('10 类')
  })

  it('onMounted_whenLoadingPending_shouldRenderSkeletonUntilResolved', async () => {
    let resolveList
    api.get.mockImplementation(() => new Promise(resolve => { resolveList = resolve }))
    const { wrapper } = await mountDashboard()
    await nextTick()

    expect(wrapper.vm.loading).toBe(true)
    expect(wrapper.find('.ant-skeleton').exists()).toBe(true)
    expect(wrapper.findAll('.project-card')).toHaveLength(0)

    resolveList(listResponse(sampleProjects()))
    await flushPromises()

    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.find('.ant-skeleton').exists()).toBe(false)
    expect(wrapper.findAll('.project-card')).toHaveLength(5)
  })

  it('onMounted_whenResponseMissesRecords_shouldRenderEmptyState', async () => {
    api.get.mockResolvedValue({ data: { data: {} } })
    const { wrapper } = await mountDashboard()
    await flushPromises()

    expect(wrapper.vm.projects).toEqual([])
    expect(wrapper.find('.ant-empty').exists()).toBe(true)
    expect(wrapper.find('.ant-empty').text()).toContain('还没有项目')
    expect(wrapper.findAll('.stat-value').map(node => node.text())).toEqual(['0', '0', '0', '0'])
  })

  it('onMounted_whenRequestFails_shouldFallBackToEmptyState', async () => {
    api.get.mockRejectedValue(new Error('network down'))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    expect(wrapper.vm.loading).toBe(false)
    expect(wrapper.vm.projects).toEqual([])
    expect(wrapper.find('.ant-empty').exists()).toBe(true)
  })

  it('filteredProjects_whenSearchTextChanges_shouldFilterByNameOrDescription', async () => {
    api.get.mockResolvedValue(listResponse(sampleProjects()))
    const { wrapper } = await mountDashboard()
    await flushPromises()
    const searchInput = wrapper.find('.section-actions input')

    await searchInput.setValue('beta')
    expect(wrapper.findAll('.card-title').map(node => node.text())).toEqual(['Beta'])

    await searchInput.setValue('python')
    expect(wrapper.findAll('.card-title').map(node => node.text())).toEqual(['Beta'])

    await searchInput.setValue('zeta')
    expect(wrapper.findAll('.project-card')).toHaveLength(0)

    await searchInput.setValue('')
    expect(wrapper.findAll('.project-card')).toHaveLength(5)
  })

  it('beforeZipUpload_whenAllowedArchiveUnderLimit_shouldStoreFileAndBlockAutoUpload', async () => {
    api.get.mockResolvedValue(listResponse([]))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    const zipFile = { name: 'source.zip', size: 1024 }
    const tarGzFile = { name: 'source.tar.gz', size: 1024 }
    const tgzFile = { name: 'source.tgz', size: 1024 }

    expect(wrapper.vm.beforeZipUpload(zipFile)).toBe(false)
    expect(wrapper.vm.uploadFile).toEqual(zipFile)
    expect(messageError).not.toHaveBeenCalled()

    expect(wrapper.vm.beforeZipUpload(tarGzFile)).toBe(false)
    expect(wrapper.vm.uploadFile).toEqual(tarGzFile)

    expect(wrapper.vm.beforeZipUpload(tgzFile)).toBe(false)
    expect(wrapper.vm.uploadFile).toEqual(tgzFile)

    const nearLimit = { name: 'near-limit.zip', size: 100 * 1024 * 1024 - 1 }
    expect(wrapper.vm.beforeZipUpload(nearLimit)).toBe(false)
    expect(wrapper.vm.uploadFile).toEqual(nearLimit)
    expect(messageError).not.toHaveBeenCalled()
  })

  it('beforeZipUpload_whenExtensionNotAllowed_shouldRejectWithError', async () => {
    api.get.mockResolvedValue(listResponse([]))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    expect(wrapper.vm.beforeZipUpload({ name: 'payload.exe', size: 1024 })).toBe(false)

    expect(messageError).toHaveBeenCalledWith('仅支持 .zip / .tar.gz / .tgz 格式文件')
    expect(wrapper.vm.uploadFile).toBeNull()
  })

  it('beforeZipUpload_whenFileExceedsSizeLimit_shouldRejectWithError', async () => {
    api.get.mockResolvedValue(listResponse([]))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    const oversized = { name: 'huge.zip', size: 101 * 1024 * 1024 }
    expect(wrapper.vm.beforeZipUpload(oversized)).toBe(false)
    expect(messageError).toHaveBeenCalledWith('文件大小不能超过 100MB')

    const exactLimit = { name: 'exact.zip', size: 100 * 1024 * 1024 }
    expect(wrapper.vm.beforeZipUpload(exactLimit)).toBe(false)
    expect(messageError).toHaveBeenCalledTimes(2)
    expect(wrapper.vm.uploadFile).toBeNull()
  })

  it('handleCreateProject_whenNameMissing_shouldWarnAndSkipRequest', async () => {
    api.get.mockResolvedValue(listResponse([]))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    await wrapper.vm.handleCreateProject()

    expect(messageWarning).toHaveBeenCalledWith('请输入项目名称')
    expect(api.post).not.toHaveBeenCalled()
  })

  it('handleCreateProject_whenGitUrlMissing_shouldWarnAndSkipRequest', async () => {
    api.get.mockResolvedValue(listResponse([]))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    wrapper.vm.createForm.name = 'payments'
    await wrapper.vm.handleCreateProject()

    expect(messageWarning).toHaveBeenCalledWith('请输入 Git 仓库 URL')
    expect(api.post).not.toHaveBeenCalled()
  })

  it('handleCreateProject_whenGitUrlMalformed_shouldWarnAndSkipRequest', async () => {
    api.get.mockResolvedValue(listResponse([]))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    wrapper.vm.createForm.name = 'payments'
    wrapper.vm.createForm.sourceUrl = 'ftp://github.com/acme/payments.git'
    await wrapper.vm.handleCreateProject()

    expect(messageWarning).toHaveBeenCalledWith('Git URL 格式不正确，示例：https://github.com/user/repo.git')
    expect(api.post).not.toHaveBeenCalled()
  })

  it('handleCreateProject_whenZipSourceWithoutFile_shouldWarnAndSkipRequest', async () => {
    api.get.mockResolvedValue(listResponse([]))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    wrapper.vm.createForm.name = 'payments'
    wrapper.vm.createForm.sourceType = 'ZIP_UPLOAD'
    await wrapper.vm.handleCreateProject()

    expect(messageWarning).toHaveBeenCalledWith('请先选择要上传的 ZIP 文件')
    expect(api.post).not.toHaveBeenCalled()
  })

  it('handleCreateProject_whenGitProjectCreated_shouldResetFormAndNavigate', async () => {
    api.get.mockResolvedValue(listResponse([]))
    api.post.mockResolvedValue({ data: { data: { id: 77, name: 'payments' } } })
    const { wrapper, router } = await mountDashboard()
    await flushPromises()
    const pushSpy = vi.spyOn(router, 'push')

    wrapper.vm.showCreateModal = true
    wrapper.vm.createForm.name = 'payments'
    wrapper.vm.createForm.sourceUrl = 'http://gitlab.internal/acme/payments.git'
    wrapper.vm.createForm.description = 'billing service'
    const pending = wrapper.vm.handleCreateProject()
    expect(api.post).toHaveBeenCalledWith('/projects', expect.objectContaining({
      name: 'payments',
      sourceType: 'GIT_URL',
      sourceUrl: 'http://gitlab.internal/acme/payments.git',
      description: 'billing service'
    }))
    await pending
    await flushPromises()

    expect(messageSuccess).toHaveBeenCalledWith('项目创建成功')
    expect(pushSpy).toHaveBeenCalledWith('/project/77/overview')
    expect(wrapper.vm.showCreateModal).toBe(false)
    expect(wrapper.vm.createForm).toMatchObject({
      name: '',
      sourceType: 'GIT_URL',
      sourceUrl: '',
      description: ''
    })
    expect(wrapper.vm.createLoading).toBe(false)
  })

  it('handleCreateProject_whenCreatedResponseHasNoId_shouldRefetchList', async () => {
    api.get
      .mockResolvedValueOnce(listResponse([]))
      .mockResolvedValueOnce(listResponse(sampleProjects()))
    api.post.mockResolvedValue({ data: { data: null } })
    const { wrapper, router } = await mountDashboard()
    await flushPromises()
    const pushSpy = vi.spyOn(router, 'push')

    wrapper.vm.createForm.name = 'payments'
    wrapper.vm.createForm.sourceUrl = 'https://github.com/acme/payments.git'
    await wrapper.vm.handleCreateProject()
    await flushPromises()

    expect(api.get).toHaveBeenCalledTimes(2)
    expect(wrapper.vm.projects).toHaveLength(5)
    expect(pushSpy).not.toHaveBeenCalled()
  })

  it('handleCreateProject_whenRefetchResponseMissesRecords_shouldFallBackToEmptyList', async () => {
    api.get
      .mockResolvedValueOnce(listResponse([]))
      .mockResolvedValueOnce({ data: { data: null } })
    api.post.mockResolvedValue({ data: { data: null } })
    const { wrapper } = await mountDashboard()
    await flushPromises()

    wrapper.vm.createForm.name = 'payments'
    wrapper.vm.createForm.sourceUrl = 'https://github.com/acme/payments.git'
    await wrapper.vm.handleCreateProject()
    await flushPromises()

    expect(api.get).toHaveBeenCalledTimes(2)
    expect(wrapper.vm.projects).toEqual([])
  })

  it('handleCreateProject_whenZipUploadSucceeds_shouldTrackProgressAndNavigate', async () => {
    api.get.mockResolvedValue(listResponse([]))
    let resolveUpload
    api.post.mockImplementation(() => new Promise(resolve => { resolveUpload = resolve }))
    const { wrapper, router } = await mountDashboard()
    await flushPromises()
    const pushSpy = vi.spyOn(router, 'push')

    wrapper.vm.showCreateModal = true
    wrapper.vm.createForm.name = 'archive-project'
    wrapper.vm.createForm.sourceType = 'ZIP_UPLOAD'
    wrapper.vm.beforeZipUpload({ name: 'source.zip', size: 2048 })
    await nextTick()
    expect(document.body.textContent).toContain('已选择: source.zip')

    const pending = wrapper.vm.handleCreateProject()
    await nextTick()

    const uploadCall = api.post.mock.calls.find(call => call[0] === '/files/upload')
    expect(uploadCall).toBeDefined()
    expect(uploadCall[1]).toBeInstanceOf(FormData)
    expect(uploadCall[2].timeout).toBe(300000)

    uploadCall[2].onUploadProgress({ loaded: 0, total: 0 })
    expect(wrapper.vm.uploadPercent).toBe(0)
    uploadCall[2].onUploadProgress({ loaded: 51, total: 100 })
    expect(wrapper.vm.uploadPercent).toBe(51)
    expect(wrapper.vm.uploading).toBe(true)

    resolveUpload({ data: { data: { id: 55 } } })
    await pending
    await flushPromises()

    expect(messageSuccess).toHaveBeenCalledWith('项目创建成功')
    expect(pushSpy).toHaveBeenCalledWith('/project/55/overview')
    expect(wrapper.vm.uploading).toBe(false)
    expect(wrapper.vm.uploadFile).toBeNull()
  })

  it('handleCreateProject_whenUploadFails_shouldResetLoadingStates', async () => {
    api.get.mockResolvedValue(listResponse([]))
    api.post.mockRejectedValue(new Error('upload failed'))
    const { wrapper, router } = await mountDashboard()
    await flushPromises()
    const pushSpy = vi.spyOn(router, 'push')

    wrapper.vm.createForm.name = 'archive-project'
    wrapper.vm.createForm.sourceType = 'ZIP_UPLOAD'
    wrapper.vm.beforeZipUpload({ name: 'source.zip', size: 2048 })
    await wrapper.vm.handleCreateProject()
    await flushPromises()

    expect(messageSuccess).not.toHaveBeenCalled()
    expect(pushSpy).not.toHaveBeenCalled()
    expect(wrapper.vm.uploading).toBe(false)
    expect(wrapper.vm.createLoading).toBe(false)
  })

  it('confirmDelete_whenNoTargetSelected_shouldSkipRequest', async () => {
    api.get.mockResolvedValue(listResponse(sampleProjects()))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    await wrapper.vm.confirmDelete()

    expect(api.delete).not.toHaveBeenCalled()
    expect(wrapper.vm.deleteLoading).toBe(false)
  })

  it('cardDelete_whenDeleteIconClicked_shouldOpenConfirmWithoutNavigating', async () => {
    api.get.mockResolvedValue(listResponse(sampleProjects()))
    const { wrapper, router } = await mountDashboard()
    await flushPromises()
    const pushSpy = vi.spyOn(router, 'push')

    await wrapper.find('.card-delete').trigger('click')

    expect(pushSpy).not.toHaveBeenCalled()
    expect(wrapper.vm.deleteTarget.id).toBe(1)
    expect(wrapper.vm.showDeleteModal).toBe(true)
    expect(document.body.textContent).toContain('Alpha')
  })

  it('confirmDelete_whenConfirmed_shouldRemoveProjectAndCloseModal', async () => {
    api.get.mockResolvedValue(listResponse(sampleProjects()))
    api.delete.mockResolvedValue({ data: {} })
    const { wrapper } = await mountDashboard()
    await flushPromises()

    await wrapper.find('.card-delete').trigger('click')
    const deleteModal = wrapper.findAllComponents({ name: 'AModal' })[1]
    deleteModal.vm.$emit('ok')
    await flushPromises()

    expect(api.delete).toHaveBeenCalledWith('/projects/1')
    expect(messageSuccess).toHaveBeenCalledWith('项目 "Alpha" 已删除')
    expect(wrapper.vm.projects).toHaveLength(4)
    expect(wrapper.findAll('.project-card')).toHaveLength(4)
    expect(wrapper.vm.showDeleteModal).toBe(false)
    expect(wrapper.vm.deleteTarget).toBeNull()
    expect(wrapper.vm.deleteLoading).toBe(false)
  })

  it('confirmDelete_whenRequestFails_shouldKeepTargetAndResetLoading', async () => {
    api.get.mockResolvedValue(listResponse(sampleProjects()))
    api.delete.mockRejectedValue(new Error('delete failed'))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    await wrapper.find('.card-delete').trigger('click')
    const deleteModal = wrapper.findAllComponents({ name: 'AModal' })[1]
    deleteModal.vm.$emit('ok')
    await flushPromises()

    expect(wrapper.vm.projects).toHaveLength(5)
    expect(wrapper.vm.deleteTarget.id).toBe(1)
    expect(wrapper.vm.deleteLoading).toBe(false)
  })

  it('createModal_whenOpenedAndCancelled_shouldToggleOpenStateAndClearFile', async () => {
    api.get.mockResolvedValue(listResponse([]))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    const createButton = wrapper.findAll('.section-actions button')
      .find(node => node.text().includes('新建项目'))
    await createButton.trigger('click')
    expect(wrapper.vm.showCreateModal).toBe(true)
    await nextTick()
    expect(document.body.querySelector('input[placeholder="https://github.com/user/repo.git"]')).not.toBeNull()

    wrapper.vm.beforeZipUpload({ name: 'source.zip', size: 2048 })
    await nextTick()

    const createModal = wrapper.findAllComponents({ name: 'AModal' })[0]
    createModal.vm.$emit('cancel')
    await nextTick()

    expect(wrapper.vm.showCreateModal).toBe(false)
    expect(wrapper.vm.uploadFile).toBeNull()

    wrapper.vm.showCreateModal = true
    await nextTick()
    createModal.vm.$emit('update:open', false)
    await nextTick()

    expect(wrapper.vm.showCreateModal).toBe(false)
  })

  it('createModal_whenFieldsEditedThroughVModel_shouldSyncCreateForm', async () => {
    api.get.mockResolvedValue(listResponse([]))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    wrapper.vm.showCreateModal = true
    await nextTick()

    findTeleportedComponent('input[placeholder="例如：order-service"]', 'AInput')
      .emit('update:value', 'payments')
    findTeleportedComponent('input[placeholder="https://github.com/user/repo.git"]', 'AInput')
      .emit('update:value', 'https://github.com/acme/payments.git')
    findTeleportedComponent('textarea[placeholder="可选描述"]', 'ATextarea')
      .emit('update:value', 'billing service')
    await nextTick()

    expect(wrapper.vm.createForm).toMatchObject({
      name: 'payments',
      sourceUrl: 'https://github.com/acme/payments.git',
      description: 'billing service'
    })
  })

  it('createModal_whenSourceTypeSwitchedToZip_shouldRenderUploadDraggerTexts', async () => {
    api.get.mockResolvedValue(listResponse([]))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    wrapper.vm.showCreateModal = true
    await nextTick()

    const selectInstance = findTeleportedComponent('.ant-select', 'ASelect')
    selectInstance.emit('update:value', 'ZIP_UPLOAD')
    await nextTick()
    expect(wrapper.vm.createForm.sourceType).toBe('ZIP_UPLOAD')

    const uploadText = document.body.querySelector('.upload-text')
    expect(uploadText.textContent).toContain('点击或拖拽 ZIP / TAR.GZ 文件到此处')
    expect(document.body.querySelector('.upload-hint').textContent).toContain('文件大小不超过 100MB')

    wrapper.vm.beforeZipUpload({ name: 'source.zip', size: 2048 })
    await nextTick()

    expect(document.body.querySelector('.upload-text').textContent).toContain('已选择: source.zip')
  })

  it('deleteModal_whenCancelEmitted_shouldClearTargetAndClose', async () => {
    api.get.mockResolvedValue(listResponse(sampleProjects()))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    await wrapper.find('.card-delete').trigger('click')
    const deleteModal = wrapper.findAllComponents({ name: 'AModal' })[1]
    deleteModal.vm.$emit('cancel')
    await nextTick()

    expect(wrapper.vm.showDeleteModal).toBe(false)
    expect(wrapper.vm.deleteTarget).toBeNull()

    wrapper.vm.showDeleteModal = true
    await nextTick()
    deleteModal.vm.$emit('update:open', false)
    await nextTick()

    expect(wrapper.vm.showDeleteModal).toBe(false)
  })

  it('goProject_whenCardClicked_shouldNavigateToOverview', async () => {
    api.get.mockResolvedValue(listResponse(sampleProjects()))
    const { wrapper, router } = await mountDashboard()
    await flushPromises()
    const pushSpy = vi.spyOn(router, 'push')

    await wrapper.find('.project-card').trigger('click')

    expect(pushSpy).toHaveBeenCalledWith('/project/1/overview')
  })

  it('formatTime_whenDateMissing_shouldReturnEmptyString', async () => {
    api.get.mockResolvedValue(listResponse([]))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    expect(wrapper.vm.formatTime(null)).toBe('')
    expect(wrapper.vm.formatTime('')).toBe('')
  })

  it('langColor_whenLanguageKnownOrUnknown_shouldReturnMappedOrDefaultGradient', async () => {
    api.get.mockResolvedValue(listResponse([]))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    expect(wrapper.vm.langColor('Java')).toContain('f093fb')
    expect(wrapper.vm.langColor('TypeScript')).toContain('3178c6')
    expect(wrapper.vm.langColor('Haskell')).toBe('linear-gradient(135deg, #1677ff, #0050b3)')
  })

  it('healthColor_whenScoreAtBoundaries_shouldReturnMatchingColor', async () => {
    api.get.mockResolvedValue(listResponse([]))
    const { wrapper } = await mountDashboard()
    await flushPromises()

    expect(wrapper.vm.healthColor(80)).toBe('#52c41a')
    expect(wrapper.vm.healthColor(79)).toBe('#faad14')
    expect(wrapper.vm.healthColor(60)).toBe('#faad14')
    expect(wrapper.vm.healthColor(59)).toBe('#ff4d4f')
  })
})
