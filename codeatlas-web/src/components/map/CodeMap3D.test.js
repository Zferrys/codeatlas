import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import { nextTick } from 'vue'
import {
  Spin as ASpin, Result as AResult, Button as AButton, Empty as AEmpty,
  AutoComplete as AAutoComplete, Divider as ADivider, Switch as ASwitch, Tag as ATag
} from 'ant-design-vue'

// ---- three.js 全模块 mock ----
// jsdom 无 WebGL，THREE 用最小桩；所有实例 push 进 state 供断言。
const { state, apiMock, THREE, MockCSS2DRenderer, MockCSS2DObject, MockOrbitControls, MockWorker } = vi.hoisted(() => {
  const state = {
    scenes: [], cameras: [], renderers: [], labelRenderers: [], controls: [],
    raycasters: [], meshes: [], materials: [], geometries: [], lines: [],
    frustums: [], matrix4s: [], vector2s: [], workers: [], resizeObservers: [],
    rafs: [], cancelledFrames: []
  }

  class V3 {
    constructor(x = 0, y = 0, z = 0) { this.x = x; this.y = y; this.z = z }
    set(x, y, z) { this.x = x; this.y = y; this.z = z; return this }
    setScalar(s) { this.x = s; this.y = s; this.z = s; return this }
    copy(v) { this.x = v.x; this.y = v.y; this.z = v.z; return this }
    clone() { return new V3(this.x, this.y, this.z) }
  }

  class MockColor {
    constructor(value) { this.value = value }
    set(value) { this.value = value; return this }
    setRGB(r, g, b) { this.rgb = [r, g, b]; return this }
  }

  class MockMaterial {
    constructor(params = {}) {
      Object.assign(this, params)
      this.color = params.color instanceof MockColor ? params.color : new MockColor(params.color)
      this.opacity = params.opacity ?? 1
      this.transparent = params.transparent ?? false
      this.dispose = vi.fn()
      state.materials.push(this)
    }
  }

  class MockStandardMaterial extends MockMaterial {
    constructor(params = {}) {
      super(params)
      this.emissive = new MockColor(params.emissive)
      this.emissiveIntensity = params.emissiveIntensity ?? 1
    }
  }

  class MockGeometry {
    constructor(type) {
      this.type = type
      this.setAttribute = vi.fn(() => this)
      this.setFromPoints = vi.fn(() => this)
      this.dispose = vi.fn()
      state.geometries.push(this)
    }
  }

  class MockMesh {
    constructor(geometry, material) {
      this.geometry = geometry
      this.material = material
      this.position = new V3()
      this.scale = new V3(1, 1, 1)
      this.rotation = new V3()
      this.children = []
      this.userData = {}
      this.isMesh = true
      this.visible = true
      this.type = 'Mesh'
      state.meshes.push(this)
    }
    add(child) { this.children.push(child); return this }
  }

  class MockLine {
    constructor(geometry, material) {
      this.geometry = geometry
      this.material = material
      this.userData = {}
      this.visible = true
      this.computeLineDistances = vi.fn()
      state.lines.push(this)
    }
  }

  class MockScene {
    constructor() {
      this.children = []
      this.background = null
      this.fog = null
      state.scenes.push(this)
    }
    add(obj) { this.children.push(obj); return this }
    remove(obj) {
      const idx = this.children.indexOf(obj)
      if (idx >= 0) this.children.splice(idx, 1)
      return this
    }
  }

  class MockPerspectiveCamera {
    constructor(fov, aspect, near, far) {
      this.fov = fov
      this.aspect = aspect
      this.near = near
      this.far = far
      this.position = new V3()
      this.projectionMatrix = { multiplyMatrices: vi.fn() }
      this.matrixWorldInverse = { multiplyMatrices: vi.fn() }
      this.lookAt = vi.fn()
      this.updateProjectionMatrix = vi.fn()
      this.updateMatrixWorld = vi.fn()
      state.cameras.push(this)
    }
  }

  class MockWebGLRenderer {
    constructor(params) {
      this.params = params
      this.domElement = document.createElement('canvas')
      this.shadowMap = { enabled: false }
      this.setSize = vi.fn()
      this.setPixelRatio = vi.fn()
      this.render = vi.fn()
      this.dispose = vi.fn()
      state.renderers.push(this)
    }
  }

  class MockRaycaster {
    constructor() {
      this.params = { Points: { threshold: 0 } }
      this.setFromCamera = vi.fn()
      this.intersectObjects = vi.fn(() => [])
      state.raycasters.push(this)
    }
  }

  class MockLight {
    constructor(...args) {
      this.args = args
      this.position = new V3()
    }
  }

  class MockFrustum {
    constructor() {
      this.setFromProjectionMatrix = vi.fn()
      this.containsPoint = vi.fn(() => true)
      state.frustums.push(this)
    }
  }

  class MockMatrix4 {
    constructor() {
      this.multiplyMatrices = vi.fn()
      state.matrix4s.push(this)
    }
  }

  class MockVector2 {
    constructor(x = 0, y = 0) { this.x = x; this.y = y; state.vector2s.push(this) }
  }

  class MockPoints {
    constructor(geometry, material) { this.geometry = geometry; this.material = material }
  }

  const createGeometryClass = (type) => class extends MockGeometry {
    constructor(...args) { super(type); this.args = args }
  }

  const THREE = {
    Scene: MockScene,
    Color: MockColor,
    Fog: class { constructor(...args) { this.args = args } },
    PerspectiveCamera: MockPerspectiveCamera,
    WebGLRenderer: MockWebGLRenderer,
    Raycaster: MockRaycaster,
    AmbientLight: MockLight,
    DirectionalLight: MockLight,
    PolarGridHelper: class { constructor(...args) { this.args = args } },
    BufferGeometry: class extends MockGeometry { constructor() { super('BufferGeometry') } },
    Float32BufferAttribute: class { constructor(array, itemSize) { this.array = array; this.itemSize = itemSize } },
    Points: MockPoints,
    PointsMaterial: MockMaterial,
    SphereGeometry: createGeometryClass('SphereGeometry'),
    OctahedronGeometry: createGeometryClass('OctahedronGeometry'),
    TetrahedronGeometry: createGeometryClass('TetrahedronGeometry'),
    IcosahedronGeometry: createGeometryClass('IcosahedronGeometry'),
    TorusGeometry: createGeometryClass('TorusGeometry'),
    MeshStandardMaterial: MockStandardMaterial,
    MeshBasicMaterial: MockMaterial,
    LineBasicMaterial: MockMaterial,
    LineDashedMaterial: MockMaterial,
    Mesh: MockMesh,
    Line: MockLine,
    Frustum: MockFrustum,
    Matrix4: MockMatrix4,
    Vector2: MockVector2,
    Vector3: V3
  }

  class MockCSS2DRenderer {
    constructor() {
      this.domElement = document.createElement('div')
      this.setSize = vi.fn()
      this.render = vi.fn()
      state.labelRenderers.push(this)
    }
  }

  class MockCSS2DObject {
    constructor(element) {
      this.element = element
      this.position = new V3()
      this.isCSS2DObject = true
      this.visible = true
    }
  }

  class MockOrbitControls {
    constructor(camera, domElement) {
      this.camera = camera
      this.domElement = domElement
      this.target = new V3()
      this.update = vi.fn()
      state.controls.push(this)
    }
  }

  class MockWorker {
    constructor(url) {
      this.url = url
      this.onmessage = null
      this.onerror = null
      this.postMessage = vi.fn()
      this.terminate = vi.fn()
      state.workers.push(this)
    }
  }

  const apiMock = { get: vi.fn(), post: vi.fn(), put: vi.fn() }

  return { state, apiMock, THREE, MockCSS2DRenderer, MockCSS2DObject, MockOrbitControls, MockWorker }
})

vi.mock('three', () => THREE)
vi.mock('three/examples/jsm/controls/OrbitControls.js', () => ({ OrbitControls: MockOrbitControls }))
vi.mock('three/examples/jsm/renderers/CSS2DRenderer.js', () => ({
  CSS2DRenderer: MockCSS2DRenderer,
  CSS2DObject: MockCSS2DObject
}))
vi.mock('../../api', () => ({ default: apiMock }))

import CodeMap3D from './CodeMap3D.vue'

enableAutoUnmount(afterEach)

const globalComponents = {
  ASpin, AResult, AButton, AEmpty, AAutoComplete, ADivider, ASwitch, ATag
}

function makeGraphData() {
  return {
    nodes: [
      { id: 'com.a.Controller', label: 'com.a.Controller', layer: 'controller', classType: 'CLASS', methods: 10, lineCount: 200 },
      { id: 'com.a.Service', label: 'com.a.Service', layer: 'service', classType: 'INTERFACE', methods: 8, lineCount: 150 },
      { id: 'com.a.Enum', label: 'com.a.Enum', layer: 'weird-layer', classType: 'ENUM', methods: 2, lineCount: 30 },
      { id: 'com.a.Abstract', label: 'com.a.Abstract', layer: 'abstract-x', classType: 'ABSTRACT', methods: 4, lineCount: 60 },
      { id: 'com.a.TestHelper', label: 'com.a.TestHelper', layer: 'TEST', classType: 'CLASS', methods: 1, lineCount: 10 },
      { id: 'com.a.UtilHelper', label: 'com.a.UtilHelper', layer: 'util', classType: 'CLASS', methods: 1, lineCount: 10 },
      { id: 'com.a.NoMeta', label: '' }
    ],
    edges: [
      { source: 'com.a.Controller', target: 'com.a.Service' },
      { source: 'com.a.Service', target: 'com.a.Enum', type: 'extends' },
      { source: 'com.a.Controller', target: 'com.a.Enum', type: 'implements' },
      { source: 'com.a.Service', target: 'com.a.NoMeta', type: 'unknown-rel' },
      { source: 'com.a.TestHelper', target: 'com.a.Service' },
      { source: 'com.a.UtilHelper', target: 'com.a.Controller' },
      { source: 'com.a.Ghost', target: 'com.a.Service' }
    ]
  }
}

function resetState() {
  Object.values(state).forEach((arr) => { if (Array.isArray(arr)) arr.length = 0 })
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function mountMap3D(props = {}) {
  return mount(CodeMap3D, {
    props: { projectId: 1, ...props },
    global: { components: globalComponents }
  })
}

async function settle() {
  await flushPromises()
  await nextTick()
  await flushPromises()
}

const meshOf = (id) => state.meshes.find((m) => m.userData.id === id)
const lineOf = (source, target) => state.lines.find(
  (l) => l.userData.source === source && l.userData.target === target
)

describe('CodeMap3D', () => {
  beforeEach(() => {
    resetState()
    apiMock.get.mockReset()
    apiMock.get.mockResolvedValue({ data: { data: makeGraphData() } })
    vi.stubGlobal('Worker', MockWorker)
    vi.stubGlobal('ResizeObserver', class {
      constructor(cb) {
        this.cb = cb
        this.observe = vi.fn()
        this.disconnect = vi.fn()
        state.resizeObservers.push(this)
      }
    })
    vi.stubGlobal('requestAnimationFrame', vi.fn((cb) => {
      state.rafs.push(cb)
      return state.rafs.length
    }))
    vi.stubGlobal('cancelAnimationFrame', vi.fn((id) => state.cancelledFrames.push(id)))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('CodeMap3D_whenMountedWithData_shouldInitSceneAndRenderSmallGraphSync', async () => {
    const wrapper = mountMap3D()
    expect(apiMock.get).toHaveBeenCalledWith('/projects/1/map')
    await settle()

    // 场景骨架
    expect(state.scenes).toHaveLength(1)
    expect(state.cameras).toHaveLength(1)
    expect(state.renderers).toHaveLength(1)
    expect(state.labelRenderers).toHaveLength(1)
    expect(state.controls).toHaveLength(1)
    expect(state.raycasters).toHaveLength(1)

    const scene = state.scenes[0]
    const camera = state.cameras[0]
    const renderer = state.renderers[0]
    const controls = state.controls[0]

    expect(scene.background.value).toBe(0x0f1922)
    expect(scene.fog.args).toEqual([0x0f1922, 30, 80])
    expect(camera.position).toMatchObject({ x: 15, y: 12, z: 20 })
    expect(camera.lookAt).toHaveBeenCalledWith(0, 0, 0)
    expect(renderer.params).toEqual({ antialias: true, alpha: true })
    expect(renderer.shadowMap.enabled).toBe(true)
    expect(renderer.setPixelRatio).toHaveBeenCalledWith(1)
    expect(controls.enableDamping).toBe(true)
    expect(controls.dampingFactor).toBe(0.08)
    expect(controls.minDistance).toBe(5)
    expect(controls.maxDistance).toBe(50)
    expect(state.raycasters[0].params.Points.threshold).toBe(0.5)

    // DOM 挂载：canvas 与 labelRenderer 都进了容器
    const canvasContainer = wrapper.find('.map3d-canvas').element
    expect(canvasContainer.contains(renderer.domElement)).toBe(true)
    expect(canvasContainer.contains(state.labelRenderers[0].domElement)).toBe(true)

    // 小图（<10 节点）走同步布局：不创建 Worker
    expect(state.workers).toHaveLength(0)

    // 每个节点一个 mesh + 光环 + CSS2D 标签
    expect(state.meshes.filter((m) => m.userData.id)).toHaveLength(7)
    const label = meshOf('com.a.Service').children.find((c) => c.isCSS2DObject)
    expect(label.element.textContent).toBe('Service')
    expect(label.element.style.color).toBe('rgba(255, 255, 255, 0.75)')

    // 动画循环启动且渲染了一帧
    expect(state.rafs.length).toBeGreaterThanOrEqual(1)
    expect(controls.update).toHaveBeenCalled()
    expect(renderer.render).toHaveBeenCalledWith(scene, camera)
    expect(state.labelRenderers[0].render).toHaveBeenCalledWith(scene, camera)

    // ready 后搜索栏/图例/提示出现
    expect(wrapper.find('.map3d-search').exists()).toBe(true)
    expect(wrapper.find('.map3d-legend').exists()).toBe(true)
    expect(wrapper.find('.map3d-hint').exists()).toBe(true)
  })

  it('CodeMap3D_whenNodesHaveClassTypes_shouldPickGeometryLayerColorAndImportance', async () => {
    mountMap3D()
    await settle()

    const geometryType = (id) => meshOf(id).geometry.type
    expect(geometryType('com.a.Controller')).toBe('SphereGeometry')
    expect(geometryType('com.a.Service')).toBe('OctahedronGeometry')
    expect(geometryType('com.a.Enum')).toBe('TetrahedronGeometry')
    expect(geometryType('com.a.Abstract')).toBe('IcosahedronGeometry')
    // 缺省 classType 回落 CLASS → 球体
    expect(geometryType('com.a.NoMeta')).toBe('SphereGeometry')

    // 分层配色：未知分层回落 0x999999
    expect(meshOf('com.a.Controller').material.color.value).toBe(0x1890ff)
    expect(meshOf('com.a.Service').material.color.value).toBe(0x52c41a)
    expect(meshOf('com.a.Enum').material.color.value).toBe(0x999999)
    expect(meshOf('com.a.NoMeta').material.color.value).toBe(0x999999)

    // importance 缩放：Controller (10 方法 + 2 依赖*1.5=13)/20=0.65；Enum 0.1 → 下限 0.4
    expect(meshOf('com.a.Controller').scale.x).toBeCloseTo(0.65)
    expect(meshOf('com.a.Enum').scale.x).toBeCloseTo(0.4)

    // userData 缺省值补齐
    expect(meshOf('com.a.NoMeta').userData).toMatchObject({
      id: 'com.a.NoMeta', layer: undefined, classType: 'CLASS', methods: 0, lineCount: 0, depCount: 0, hidden: false
    })
    expect(meshOf('com.a.Controller').userData.depCount).toBe(2)
    // 缺省 label 生成空文本标签
    const bareLabel = meshOf('com.a.NoMeta').children.find((c) => c.isCSS2DObject)
    expect(bareLabel.element.textContent).toBe('')
  })

  it('CodeMap3D_whenEdgesProvided_shouldBuildRelationStylesAndSkipMissingEndpoints', async () => {
    mountMap3D()
    await settle()

    // 缺失端点的幽灵边被剔除
    expect(state.lines).toHaveLength(6)

    const depLine = lineOf('com.a.Controller', 'com.a.Service')
    expect(depLine.material.constructor.name).toBe('MockMaterial')
    expect(depLine.material.color.value).toBe(0x334466)
    expect(depLine.material.opacity).toBe(0.14)
    expect(depLine.userData).toMatchObject({
      source: 'com.a.Controller', target: 'com.a.Service', relation: 'dependency',
      baseColor: 0x334466, baseOpacity: 0.14
    })

    const extLine = lineOf('com.a.Service', 'com.a.Enum')
    expect(extLine.material.color.value).toBe(0xff7a45)
    expect(extLine.material.opacity).toBe(1)
    expect(extLine.computeLineDistances).not.toHaveBeenCalled()

    // implements 用虚线材质且计算线长
    const implLine = lineOf('com.a.Controller', 'com.a.Enum')
    expect(implLine.material.dashSize).toBe(0.6)
    expect(implLine.material.gapSize).toBe(0.4)
    expect(implLine.computeLineDistances).toHaveBeenCalledTimes(1)

    // 未知关系回落 dependency 样式
    const unknownLine = lineOf('com.a.Service', 'com.a.NoMeta')
    expect(unknownLine.userData.relation).toBe('unknown-rel')
    expect(unknownLine.material.color.value).toBe(0x334466)

    // 线段几何：两点
    expect(depLine.geometry.setFromPoints).toHaveBeenCalled()
    expect(depLine.geometry.setFromPoints.mock.calls[0][0]).toHaveLength(2)
  })

  it('CodeMap3D_whenPayloadLacksEdges_shouldFallbackToEmptyEdgeList', async () => {
    const data = makeGraphData()
    delete data.edges
    apiMock.get.mockResolvedValue({ data: { data } })
    const wrapper = mountMap3D()
    await settle()

    // edges 缺省回退空数组：不构建连线，节点照常渲染
    expect(state.lines).toHaveLength(0)
    expect(state.meshes.filter((m) => m.userData.id)).toHaveLength(7)
    expect(wrapper.find('.map3d-search').exists()).toBe(true)
  })

  it('CodeMap3D_whenEmptyData_shouldShowEmptyStateWithoutScene', async () => {
    apiMock.get.mockResolvedValue({ data: { data: { nodes: [], edges: [] } } })
    const wrapper = mountMap3D()
    await settle()

    expect(state.scenes).toHaveLength(0)
    expect(wrapper.find('.ant-empty').exists()).toBe(true)
    expect(wrapper.text()).toContain('暂无图谱数据')
    expect(wrapper.find('.map3d-search').exists()).toBe(false)
  })

  it('CodeMap3D_whenLoading_shouldShowOverlayAndHideCanvas', async () => {
    const d = deferred()
    apiMock.get.mockReturnValue(d.promise)
    const wrapper = mountMap3D()
    await nextTick()

    expect(wrapper.find('.map3d-overlay .ant-spin').exists()).toBe(true)
    expect(wrapper.find('.map3d-canvas').attributes('style')).toContain('display: none')

    d.resolve({ data: { data: makeGraphData() } })
    await settle()

    expect(wrapper.find('.map3d-overlay .ant-spin').exists()).toBe(false)
    expect(wrapper.find('.map3d-canvas').attributes('style') || '').not.toContain('display: none')
  })

  it('CodeMap3D_whenApiFails_shouldShowErrorAndRetryRefetches', async () => {
    apiMock.get.mockRejectedValueOnce({ response: { data: { message: '后端炸了' } } })
    const wrapper = mountMap3D()
    await settle()

    expect(wrapper.find('.ant-result').exists()).toBe(true)
    expect(wrapper.text()).toContain('后端炸了')
    expect(state.scenes).toHaveLength(0)

    apiMock.get.mockResolvedValueOnce({ data: { data: makeGraphData() } })
    await wrapper.find('.ant-result-extra button').trigger('click')
    await settle()

    expect(apiMock.get).toHaveBeenCalledTimes(2)
    expect(state.scenes).toHaveLength(1)
    expect(wrapper.find('.map3d-search').exists()).toBe(true)
  })

  it('CodeMap3D_whenApiFailsWithoutResponseBody_shouldFallbackToGenericMessage', async () => {
    apiMock.get.mockRejectedValue(new Error('network down'))
    const wrapper = mountMap3D()
    await settle()

    expect(wrapper.text()).toContain('获取图谱数据失败')
  })

  it('CodeMap3D_whenUnmountedBeforeDataArrives_shouldNotInitScene', async () => {
    const d = deferred()
    apiMock.get.mockReturnValue(d.promise)
    const wrapper = mountMap3D()
    wrapper.unmount()

    d.resolve({ data: { data: makeGraphData() } })
    await settle()

    expect(state.scenes).toHaveLength(0)
  })

  it('CodeMap3D_whenLargeGraph_shouldUseWorkerLayoutWithProgress', async () => {
    const nodes = Array.from({ length: 12 }, (_, i) => ({
      id: `n${i}`, label: `C${i}`, layer: 'service', classType: 'CLASS', methods: 1, lineCount: 10
    }))
    const edges = [{ source: 'n0', target: 'n1' }]
    const positions = nodes.map((_, i) => ({ x: i, y: i * 2, z: -i }))
    apiMock.get.mockResolvedValue({ data: { data: { nodes, edges } } })

    const wrapper = mountMap3D()
    await flushPromises()
    await nextTick()

    // Worker 已创建并按协议投递任务
    expect(state.workers).toHaveLength(1)
    const worker = state.workers[0]
    expect(worker.url).toBe('/workers/force-layout.worker.js')
    expect(worker.postMessage).toHaveBeenCalledWith({ nodes, edges, iterations: 80 })

    // 布局计算中：进度覆盖层可见
    expect(wrapper.vm.layoutComputing).toBe(true)
    expect(wrapper.find('.map3d-overlay').exists()).toBe(true)

    worker.onmessage({ data: { type: 'progress', iteration: 40, total: 80 } })
    await nextTick()
    expect(wrapper.vm.layoutProgress).toBe(50)
    expect(wrapper.text()).toContain('计算力导向布局... 50%')

    // 未知消息类型被忽略，进度不变
    worker.onmessage({ data: { type: 'unknown' } })
    expect(wrapper.vm.layoutProgress).toBe(50)

    worker.onmessage({ data: { type: 'complete', positions } })
    await settle()

    expect(worker.terminate).toHaveBeenCalledTimes(1)
    expect(wrapper.vm.layoutComputing).toBe(false)
    // 节点位置来自 worker 计算结果（i, 2i, -i）
    expect(meshOf('n3').position).toMatchObject({ x: 3, y: 6, z: -3 })
    expect(meshOf('n11').position).toMatchObject({ x: 11, y: 22, z: -11 })
  })

  it('CodeMap3D_whenWorkerFails_shouldWarnAndFallbackToSyncLayout', async () => {
    const nodes = Array.from({ length: 12 }, (_, i) => ({
      id: `n${i}`, label: `C${i}`, layer: 'service', classType: 'CLASS', methods: 1, lineCount: 10
    }))
    apiMock.get.mockResolvedValue({ data: { data: { nodes, edges: [] } } })
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

    const wrapper = mountMap3D()
    await flushPromises()
    await nextTick()

    const worker = state.workers[0]
    const boom = new Error('worker boom')
    worker.onerror(boom)
    await settle()

    expect(worker.terminate).toHaveBeenCalledTimes(1)
    expect(warnSpy).toHaveBeenCalledWith('Web Worker layout failed, using sync fallback:', boom)
    // 同步兜底布局仍产出节点
    expect(state.meshes.filter((m) => m.userData.id)).toHaveLength(12)
    expect(wrapper.vm.layoutComputing).toBe(false)
  })

  it('CodeMap3D_whenNodeCountOver500_shouldCullByFrustumDuringAnimate', async () => {
    const nodes = Array.from({ length: 500 }, (_, i) => ({
      id: `n${i}`, label: `C${i}`, layer: 'service', classType: 'CLASS', methods: 1, lineCount: 10
    }))
    const positions = nodes.map((_, i) => ({ x: i - 250, y: 0, z: 0 }))
    const edges = [{ source: 'n0', target: 'n499' }]
    apiMock.get.mockResolvedValue({ data: { data: { nodes, edges } } })

    mountMap3D()
    await flushPromises()
    state.workers[0].onmessage({ data: { type: 'complete', positions } })
    await settle()

    const camera = state.cameras[0]
    const renderer = state.renderers[0]
    const frustum = state.frustums[0]

    // 视锥内（x>0）可见，视锥外被剔除
    frustum.containsPoint.mockImplementation((pos) => pos.x > 0)
    state.rafs.at(-1)()
    await nextTick()

    expect(camera.updateMatrixWorld).toHaveBeenCalled()
    expect(frustum.setFromProjectionMatrix).toHaveBeenCalled()
    expect(meshOf('n499').visible).toBe(true)
    expect(meshOf('n0').visible).toBe(false)
    // 端点不可见的边一并隐藏
    const ghostLine = lineOf('n0', 'n499')
    expect(ghostLine.visible).toBe(false)
    expect(renderer.render).toHaveBeenCalled()
  })

  it('CodeMap3D_onCanvasClick_whenIntersectsNode_shouldHighlightSelectAndEmit', async () => {
    const wrapper = mountMap3D()
    await settle()

    const renderer = state.renderers[0]
    const raycaster = state.raycasters[0]
    const controls = state.controls[0]
    const camera = state.cameras[0]
    const target = meshOf('com.a.Service')
    raycaster.intersectObjects.mockReturnValue([{ object: target }])
    renderer.domElement.getBoundingClientRect = () => ({ left: 10, top: 20, width: 100, height: 100 })

    renderer.domElement.dispatchEvent(new MouseEvent('click', { clientX: 60, clientY: 45, bubbles: true }))
    await nextTick()

    // 归一化设备坐标经过换算
    const mouse = state.vector2s.at(-1)
    expect(mouse.x).toBeCloseTo(0)
    expect(mouse.y).toBeCloseTo(0.5)
    expect(raycaster.setFromCamera).toHaveBeenCalledWith(mouse, camera)
    expect(raycaster.intersectObjects).toHaveBeenCalledWith(state.meshes.filter((m) => m.userData.id))

    // 选中态：信息面板 + 事件
    expect(wrapper.emitted('node-click')[0][0].id).toBe('com.a.Service')
    expect(wrapper.find('.map3d-info-panel').exists()).toBe(true)
    expect(wrapper.find('.map3d-info-panel h4').text()).toBe('com.a.Service')
    expect(wrapper.find('.map3d-info-panel').text()).toContain('INTERFACE')

    // 高亮：邻居亮、非邻居暗、相连边变青色
    expect(target.material.opacity).toBe(1)
    expect(meshOf('com.a.Controller').material.opacity).toBe(1) // 依赖边邻居
    expect(meshOf('com.a.Enum').material.opacity).toBe(1) // extends 邻居
    expect(meshOf('com.a.Abstract').material.opacity).toBe(0.12)
    const connectedLine = lineOf('com.a.Controller', 'com.a.Service')
    expect(connectedLine.material.color.value).toBe(0x00e5ff)
    expect(connectedLine.material.opacity).toBe(1)
    const unrelatedLine = lineOf('com.a.UtilHelper', 'com.a.Controller')
    expect(unrelatedLine.material.color.value).toBe(0x334466)
    expect(unrelatedLine.material.opacity).toBe(0.03)
    // 光环加粗
    const ring = target.children.find((c) => c.isMesh && c.geometry.type === 'TorusGeometry')
    expect(ring.material.opacity).toBe(0.7)
    expect(controls.update).toHaveBeenCalled()
  })

  it('CodeMap3D_onCanvasClick_whenNoIntersects_shouldDeselectAndResetHighlight', async () => {
    const wrapper = mountMap3D()
    await settle()

    const renderer = state.renderers[0]
    const raycaster = state.raycasters[0]
    const target = meshOf('com.a.Service')
    raycaster.intersectObjects.mockReturnValue([{ object: target }])
    renderer.domElement.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()
    expect(wrapper.vm.selectedNode).not.toBeNull()

    raycaster.intersectObjects.mockReturnValue([])
    renderer.domElement.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()

    expect(wrapper.vm.selectedNode).toBeNull()
    expect(wrapper.find('.map3d-info-panel').exists()).toBe(false)
    expect(wrapper.emitted('node-click').at(-1)).toEqual([null])
    // 复位：不透明、恢复原色
    expect(target.material.opacity).toBe(1)
    expect(target.material.transparent).toBe(false)
    expect(target.material.color.value).toBe(0x52c41a)
  })

  it('CodeMap3D_onCanvasClick_whenRendererMissing_shouldReturnSafely', async () => {
    const wrapper = mountMap3D()
    await settle()

    const domElement = state.renderers[0].domElement
    wrapper.unmount()

    expect(() => domElement.dispatchEvent(new MouseEvent('click', { bubbles: true }))).not.toThrow()
  })

  it('CodeMap3D_whenDeselectButtonClicked_shouldClearSelection', async () => {
    const wrapper = mountMap3D()
    await settle()

    const raycaster = state.raycasters[0]
    raycaster.intersectObjects.mockReturnValue([{ object: meshOf('com.a.Service') }])
    state.renderers[0].domElement.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()
    expect(wrapper.find('.map3d-info-panel').exists()).toBe(true)

    await wrapper.find('.map3d-info-panel button').trigger('click')
    expect(wrapper.vm.selectedNode).toBeNull()
    expect(wrapper.find('.map3d-info-panel').exists()).toBe(false)
  })

  it('CodeMap3D_onKeyDown_whenCtrlK_shouldFocusSearchInput', async () => {
    // jsdom 中仅挂载到 document 的元素才能获得焦点
    const wrapper = mount(CodeMap3D, {
      props: { projectId: 1 },
      attachTo: document.body,
      global: { components: globalComponents }
    })
    await settle()

    const input = wrapper.find('.map3d-search input').element
    const keydownEvent = new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, cancelable: true, bubbles: true })
    window.dispatchEvent(keydownEvent)
    expect(keydownEvent.defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(input)

    // Meta 组合键同样生效
    input.blur()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', metaKey: true, cancelable: true }))
    expect(document.activeElement).toBe(input)

    // 非 K / 无修饰键不触发
    input.blur()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'j', ctrlKey: true }))
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k' }))
    expect(document.activeElement).not.toBe(input)
  })

  it('CodeMap3D_searchEscape_shouldClearSearchText', async () => {
    const wrapper = mountMap3D()
    await settle()

    await wrapper.findComponent(AAutoComplete).vm.$emit('keydown', { key: 'Escape' })
    expect(wrapper.vm.searchText).toBe('')

    // v-model:value 回写
    await wrapper.findComponent(AAutoComplete).vm.$emit('update:value', 'abc')
    expect(wrapper.vm.searchText).toBe('abc')

    await wrapper.findComponent(AAutoComplete).vm.$emit('keydown', { key: 'Escape' })
    expect(wrapper.vm.searchText).toBe('')
  })

  it('CodeMap3D_onSearchSelect_whenMatched_shouldFlyCameraAndSelect', async () => {
    const wrapper = mountMap3D()
    await settle()

    const controls = state.controls[0]
    const camera = state.cameras[0]
    const target = meshOf('com.a.Service')
    const pos = target.position

    await wrapper.findComponent(AAutoComplete).vm.$emit('select', 'com.a.Service')

    expect(controls.target.x).toBeCloseTo(pos.x)
    expect(camera.position.x).toBeCloseTo(pos.x + 5)
    expect(camera.position.y).toBeCloseTo(pos.y + 3)
    expect(camera.position.z).toBeCloseTo(pos.z + 5)
    expect(controls.update).toHaveBeenCalled()
    expect(target.material.opacity).toBe(1)
    expect(wrapper.emitted('node-click')[0][0].id).toBe('com.a.Service')

    // 未匹配到的值直接返回，不再 emit
    await wrapper.findComponent(AAutoComplete).vm.$emit('select', 'com.not.exist')
    expect(wrapper.emitted('node-click')).toHaveLength(1)
  })

  it('CodeMap3D_searchOptions_shouldFilterLimitAndFallback', async () => {
    const wrapper = mountMap3D()
    await settle()

    // 空搜索词返回空
    expect(wrapper.vm.searchOptions).toEqual([])

    wrapper.vm.searchText = 'com.a.'
    await nextTick()
    // 7 个节点中 label 为空串的 NoMeta 不匹配
    expect(wrapper.vm.searchOptions).toHaveLength(6)

    wrapper.vm.searchText = 'SERVICE'
    await nextTick()
    expect(wrapper.vm.searchOptions).toEqual([{ value: 'com.a.Service', label: 'com.a.Service' }])

    wrapper.vm.searchText = 'nomatch'
    await nextTick()
    expect(wrapper.vm.searchOptions).toEqual([])
  })

  it('CodeMap3D_searchOptions_whenTooManyMatches_shouldLimitTo8', async () => {
    const nodes = Array.from({ length: 12 }, (_, i) => ({
      id: `same${i}`, label: `Same${i}`, layer: 'service', classType: 'CLASS', methods: 1, lineCount: 1
    }))
    const positions = nodes.map((_, i) => ({ x: i, y: 0, z: 0 }))
    apiMock.get.mockResolvedValue({ data: { data: { nodes, edges: [] } } })
    const wrapper = mountMap3D()
    await flushPromises()
    state.workers[0].onmessage({ data: { type: 'complete', positions } })
    await settle()

    wrapper.vm.searchText = 'same'
    await nextTick()
    expect(wrapper.vm.searchOptions).toHaveLength(8)
  })

  it('CodeMap3D_getLayerColor_shouldMapLayerToHexWithFallback', async () => {
    const wrapper = mountMap3D()
    await settle()

    expect(wrapper.vm.getLayerColor('controller')).toBe('#1890ff')
    expect(wrapper.vm.getLayerColor('CONTROLLER')).toBe('#1890ff')
    expect(wrapper.vm.getLayerColor('nope')).toBe('#999999')
    expect(wrapper.vm.getLayerColor(null)).toBe('#999999')
  })

  it('CodeMap3D_focusNode_shouldDeferUntilReadyRestoreHiddenAndSkipMissing', async () => {
    const wrapper = mountMap3D({ focusFqn: 'com.a.TestHelper', focusNonce: 1, hideTest: true })
    // 数据未到：暂存 FQN
    await wrapper.vm.focusNode('com.a.TestHelper')
    await settle()

    const target = meshOf('com.a.TestHelper')
    // 先被过滤标记 hidden，定位时恢复
    expect(target.userData.hidden).toBe(false)
    expect(target.visible).toBe(true)
    const edge = lineOf('com.a.TestHelper', 'com.a.Service')
    expect(edge.visible).toBe(true)
    expect(wrapper.emitted('node-click').at(-1)[0].id).toBe('com.a.TestHelper')

    // 空 FQN / 不存在的节点：直接返回
    const emitCount = wrapper.emitted('node-click').length
    await wrapper.vm.focusNode('')
    await wrapper.vm.focusNode('com.missing.Cls')
    expect(wrapper.emitted('node-click')).toHaveLength(emitCount)
  })

  it('CodeMap3D_whenFocusNonceChanges_shouldRefocusTargetNode', async () => {
    const wrapper = mountMap3D()
    await settle()

    await wrapper.setProps({ focusFqn: 'com.a.Controller', focusNonce: 2 })
    const controls = state.controls[0]
    expect(controls.target.x).toBeCloseTo(meshOf('com.a.Controller').position.x)
    expect(wrapper.emitted('node-click').at(-1)[0].id).toBe('com.a.Controller')
  })

  it('CodeMap3D_whenHideFlagsChange_shouldToggleLayerVisibilitySmallGraph', async () => {
    const wrapper = mountMap3D()
    await settle()

    await wrapper.setProps({ hideTest: true })
    const testMesh = meshOf('com.a.TestHelper')
    const testEdge = lineOf('com.a.TestHelper', 'com.a.Service')
    expect(testMesh.userData.hidden).toBe(true)
    expect(testMesh.visible).toBe(false)
    expect(testMesh.children.find((c) => c.isCSS2DObject).visible).toBe(false)
    expect(testEdge.visible).toBe(false)
    // 非 test 节点不受影响
    expect(meshOf('com.a.Controller').visible).toBe(true)

    await wrapper.setProps({ hideUtil: true })
    expect(meshOf('com.a.UtilHelper').visible).toBe(false)

    await wrapper.setProps({ hideTest: false, hideUtil: false })
    expect(testMesh.visible).toBe(true)
    expect(testEdge.visible).toBe(true)
    expect(meshOf('com.a.UtilHelper').visible).toBe(true)
  })

  it('CodeMap3D_whenHideFlagsChangeBeforeData_shouldSkipFilterSafely', async () => {
    const d = deferred()
    apiMock.get.mockReturnValue(d.promise)
    const wrapper = mountMap3D()

    await wrapper.setProps({ hideTest: true })
    d.resolve({ data: { data: makeGraphData() } })
    await settle()

    // 数据到达后过滤已生效
    expect(meshOf('com.a.TestHelper').userData.hidden).toBe(true)
  })

  it('CodeMap3D_whenLargeGraphHideFlagsChange_shouldDeferVisibilityToAnimate', async () => {
    const nodes = Array.from({ length: 500 }, (_, i) => ({
      id: `n${i}`, label: `C${i}`, layer: i === 0 ? 'test' : 'service', classType: 'CLASS', methods: 1, lineCount: 1
    }))
    const positions = nodes.map((_, i) => ({ x: i, y: 0, z: 0 }))
    apiMock.get.mockResolvedValue({ data: { data: { nodes, edges: [] } } })
    const wrapper = mountMap3D()
    await flushPromises()
    state.workers[0].onmessage({ data: { type: 'complete', positions } })
    await settle()

    await wrapper.setProps({ hideTest: true })
    const hidden = meshOf('n0')
    // 大图只标记 hidden，可见性交给 animate 视锥剔除
    expect(hidden.userData.hidden).toBe(true)
    expect(hidden.visible).toBe(true)
    expect(hidden.children.find((c) => c.isCSS2DObject).visible).toBe(false)
  })

  it('CodeMap3D_toggleHeatmap_shouldRecolorByLineCountAndRestore', async () => {
    const wrapper = mountMap3D()
    await settle()

    const controller = meshOf('com.a.Controller') // 最大 200 行 → ratio 1
    const enumNode = meshOf('com.a.Enum') // 30/200=0.15

    wrapper.vm.toggleHeatmap(true)
    expect(controller.material.color.rgb).toEqual([1, 0, 0.15])
    expect(controller.material.emissive.rgb).toEqual([0.4, 0, 0.08])
    expect(controller.material.emissiveIntensity).toBeCloseTo(0.7)
    expect(controller.scale.x).toBeCloseTo(2)
    expect(enumNode.material.color.rgb[0]).toBeCloseTo(0.3)
    expect(enumNode.material.color.rgb[1]).toBeCloseTo(1)

    wrapper.vm.toggleHeatmap(false)
    expect(controller.material.color.value).toBe(0x1890ff)
    expect(controller.material.emissive.value).toBe(0x1890ff)
    expect(controller.material.emissiveIntensity).toBe(0.2)
    expect(controller.scale.x).toBeCloseTo(0.65)
  })

  it('CodeMap3D_whenHeatmapSwitchClicked_shouldToggleModeAndRecolor', async () => {
    const wrapper = mountMap3D()
    await settle()

    await wrapper.find('.map3d-legend .ant-switch').trigger('click')
    expect(wrapper.vm.heatmapMode).toBe(true)
    expect(meshOf('com.a.Controller').material.color.rgb).toEqual([1, 0, 0.15])

    await wrapper.find('.map3d-legend .ant-switch').trigger('click')
    expect(wrapper.vm.heatmapMode).toBe(false)
    expect(meshOf('com.a.Controller').material.color.value).toBe(0x1890ff)
  })

  it('CodeMap3D_whenHeatmapAndDeselect_shouldKeepHeatEmissiveOnReset', async () => {
    const wrapper = mountMap3D()
    await settle()

    wrapper.vm.heatmapMode = true // 模拟开关已打开
    wrapper.vm.toggleHeatmap(true)
    wrapper.vm.resetHighlight()

    const controller = meshOf('com.a.Controller')
    // heatmap 模式下复位不清色，emissiveIntensity 按 lineCount 比例
    expect(controller.material.color.rgb).toEqual([1, 0, 0.15])
    expect(controller.material.emissiveIntensity).toBeCloseTo(0.6)
    expect(controller.material.transparent).toBe(false)
  })

  it('CodeMap3D_whenObserverFires_shouldResizeRendererCameraAndLabelRenderer', async () => {
    const wrapper = mountMap3D()
    await settle()

    const renderer = state.renderers[0]
    const labelRenderer = state.labelRenderers[0]
    const camera = state.cameras[0]
    renderer.setSize.mockClear()

    state.resizeObservers[0].cb()
    expect(renderer.setSize).toHaveBeenCalledWith(0, 0)
    expect(labelRenderer.setSize).toHaveBeenCalledWith(0, 0)
    expect(camera.updateProjectionMatrix).toHaveBeenCalled()
  })

  it('CodeMap3D_whenObserverFiresBeforeInit_shouldSkipResize', async () => {
    const d = deferred()
    apiMock.get.mockReturnValue(d.promise)
    mountMap3D()
    await nextTick()

    // renderer 尚未创建：回调应安全返回
    expect(() => state.resizeObservers[0].cb()).not.toThrow()

    d.resolve({ data: { data: makeGraphData() } })
    await settle()
    expect(state.renderers).toHaveLength(1)
  })

  it('CodeMap3D_whenProjectIdChanges_shouldClearSceneAndRefetch', async () => {
    const wrapper = mountMap3D()
    await settle()
    const oldMesh = meshOf('com.a.Service')
    const oldScene = state.scenes[0]

    await wrapper.setProps({ projectId: 2 })
    await settle()

    // 旧场景清理：动画取消 + 资源释放 + 从场景移除
    expect(state.cancelledFrames.length).toBeGreaterThanOrEqual(1)
    expect(oldMesh.geometry.dispose).toHaveBeenCalled()
    expect(oldMesh.material.dispose).toHaveBeenCalled()
    expect(oldScene.children).not.toContain(oldMesh)
    expect(apiMock.get).toHaveBeenLastCalledWith('/projects/2/map')
    expect(state.scenes).toHaveLength(2)
    expect(wrapper.vm.selectedNode).toBeNull()
  })

  it('CodeMap3D_whenUnmounted_shouldDisposeResourcesAndDetachListeners', async () => {
    const wrapper = mountMap3D()
    await settle()

    const renderer = state.renderers[0]
    const labelRenderer = state.labelRenderers[0]
    const observer = state.resizeObservers[0]
    const input = wrapper.find('.map3d-search input').element

    wrapper.unmount()

    expect(renderer.dispose).toHaveBeenCalledTimes(1)
    expect(labelRenderer.domElement.isConnected).toBe(false)
    expect(observer.disconnect).toHaveBeenCalled()

    // keydown 监听已移除
    input.blur()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }))
    expect(document.activeElement).not.toBe(input)
  })

  it('CodeMap3D_whenUnmountedInErrorState_shouldSkipRendererCleanup', async () => {
    apiMock.get.mockRejectedValue(new Error('boom'))
    const wrapper = mountMap3D()
    await settle()

    const observer = state.resizeObservers[0]
    expect(() => wrapper.unmount()).not.toThrow()
    expect(observer.disconnect).toHaveBeenCalled()
  })

  it('CodeMap3D_whenRenderGraphCalledWithEmptyNodes_shouldReturnEarly', async () => {
    const wrapper = mountMap3D()
    await settle()
    const scene = state.scenes[0]
    // 初始：5 个静态对象（环境光/双方向光/网格/星点）+ 7 节点 + 6 边
    expect(scene.children).toHaveLength(18)

    await wrapper.vm.renderGraph({ nodes: [], edges: [] })
    await wrapper.vm.renderGraph({ edges: [] }) // nodes 缺省走 || [] 分支

    // clearScene 先执行：节点/边被清掉，仅剩静态对象，且提前返回不再建图
    expect(scene.children).toHaveLength(5)
    expect(state.meshes.filter((m) => m.userData.id)).toHaveLength(7)
  })
})
