import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, enableAutoUnmount } from '@vue/test-utils'
import { defineComponent, h, KeepAlive, nextTick } from 'vue'
import { Spin as ASpin, Result as AResult, Button as AButton, Empty as AEmpty } from 'ant-design-vue'

// ---- 模块级 mock 基座 ----
// jsdom 无 canvas/WebGL，G6 必须整模块 mock；
// MockGraph 记录实例与事件处理器，测试内可直接触发 G6 生命周期/交互事件。
const { graphs, MockGraph, apiMock, arrowTriangle } = vi.hoisted(() => {
  const graphs = []
  class MockGraph {
    constructor(config) {
      this.config = config
      // 记录 G6 初始化瞬间容器的可见性：真实浏览器里 display:none 的容器尺寸恒为 0
      this.containerDisplayAtConstruct = config.container ? config.container.style.display : null
      this.destroyed = false
      this.eventHandlers = {}
      this.nodeModels = []
      this.edgeModels = []
      this.nodeItems = []
      this.edgeItems = []
      this.hidden = new Set()
      this.destroy = vi.fn(() => { this.destroyed = true })
      this.data = vi.fn((d) => {
        this.nodeModels = d.nodes || []
        this.edgeModels = d.edges || []
      })
      this.render = vi.fn(() => this._buildItems())
      this.on = vi.fn((evt, fn) => {
        this.eventHandlers[evt] = this.eventHandlers[evt] || []
        this.eventHandlers[evt].push(fn)
      })
      this.off = vi.fn()
      this.updateItem = vi.fn()
      this.setAutoPaint = vi.fn()
      this.paint = vi.fn()
      this.hideItem = vi.fn((item) => this.hidden.add(item.getID()))
      this.showItem = vi.fn((item) => this.hidden.delete(item.getID()))
      this.findById = vi.fn((id) => this.nodeItems.find((i) => i.getID() === id) || null)
      this.focusItem = vi.fn()
      this.changeSize = vi.fn()
      this.fitView = vi.fn()
      this.getNodes = vi.fn(() => this.nodeItems)
      this.getEdges = vi.fn(() => this.edgeItems)
      graphs.push(this)
    }

    _buildItems() {
      const findNode = (id) => this.nodeItems.find((i) => i.getID() === id)
      const stubItem = (id) => ({
        getID: () => id,
        getModel: () => ({ id }),
        isVisible: () => true,
        getEdges: () => []
      })
      this.nodeItems = this.nodeModels.map((model) => ({
        getModel: () => model,
        getID: () => model.id,
        isVisible: () => !this.hidden.has(model.id),
        getEdges: () => this.edgeItems.filter(
          (e) => e.getSource().getID() === model.id || e.getTarget().getID() === model.id
        )
      }))
      this.edgeItems = this.edgeModels.map((model) => ({
        getModel: () => model,
        getID: () => `edge:${model.source}->${model.target}`,
        getSource: () => findNode(model.source) || stubItem(model.source),
        getTarget: () => findNode(model.target) || stubItem(model.target)
      }))
    }

    emitEvent(evt, payload) {
      ;(this.eventHandlers[evt] || []).forEach((fn) => fn(payload))
    }
  }
  return {
    graphs,
    MockGraph,
    arrowTriangle: vi.fn((a, b, c) => ({ triangle: [a, b, c] })),
    apiMock: { get: vi.fn(), post: vi.fn(), put: vi.fn() }
  }
})

vi.mock('../../api', () => ({ default: apiMock }))
vi.mock('@antv/g6', () => ({
  default: { Graph: MockGraph, Arrow: { triangle: arrowTriangle } }
}))

import CodeMap from './CodeMap.vue'

enableAutoUnmount(afterEach)

const globalComponents = { ASpin, AResult, AButton, AEmpty }

function makeMapData() {
  return {
    nodes: [
      {
        id: 'com.a.Controller', label: 'Controller', layer: 'controller',
        classType: 'INTERFACE', methods: 3, lineCount: 120, group: 'g1'
      },
      {
        id: 'com.a.Service', label: 'Service', layer: 'service',
        classType: 'ABSTRACT', methods: 5, lineCount: 88, group: 'g2'
      },
      {
        id: 'com.a.Enum', label: 'Enum', layer: 'weird-layer',
        classType: 'ENUM', methods: 0, lineCount: 0, group: 'g3'
      },
      // 缺省字段节点：classType/methods/lineCount/group/layer/label 全缺
      { id: 'com.a.Plain' }
    ],
    edges: [
      { source: 'com.a.Controller', target: 'com.a.Service' },
      { source: 'com.a.Service', target: 'com.a.Enum', type: 'extends' },
      { source: 'com.a.Plain', target: 'com.a.Controller', type: 'implements' },
      { source: 'com.a.Enum', target: 'com.a.Plain', type: 'unknown-rel' }
    ]
  }
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

function mountMap(props = {}) {
  return mount(CodeMap, {
    props: { projectId: 1, ...props },
    global: { components: globalComponents }
  })
}

async function settle() {
  await flushPromises()
  await nextTick()
  await flushPromises()
}

describe('CodeMap', () => {
  beforeEach(() => {
    graphs.length = 0
    apiMock.get.mockReset()
    apiMock.get.mockResolvedValue({ data: { data: makeMapData() } })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('CodeMap_whenMounted_shouldFetchMapDataAndRenderGraph', async () => {
    const wrapper = mountMap()
    expect(apiMock.get).toHaveBeenCalledWith('/projects/1/map')

    await settle()

    expect(graphs).toHaveLength(1)
    const graph = graphs[0]
    expect(graph.data).toHaveBeenCalledTimes(1)
    expect(graph.render).toHaveBeenCalledTimes(1)
    // 图容器保持可见，图例渲染
    expect(wrapper.find('.graph-container').isVisible()).toBe(true)
    expect(wrapper.find('.graph-legend').exists()).toBe(true)
  })

  it('CodeMap_whenMounted_shouldBuildGraphConfigWithLayoutAndContainerSize', async () => {
    // clientWidth/clientHeight 非 0 分支：在数据返回前给容器注入尺寸
    const d = deferred()
    apiMock.get.mockReturnValue(d.promise)
    const wrapper = mountMap()
    const container = wrapper.find('.graph-container').element
    Object.defineProperty(container, 'clientWidth', { configurable: true, value: 800 })
    Object.defineProperty(container, 'clientHeight', { configurable: true, value: 600 })

    d.resolve({ data: { data: makeMapData() } })
    await settle()

    const graph = graphs[0]
    expect(graph.config.container).toBe(container)
    expect(graph.config.width).toBe(800)
    expect(graph.config.height).toBe(600)
    expect(graph.config.renderer).toBe('svg')
    expect(graph.config.layout).toEqual({
      type: 'force',
      preventOverlap: true,
      nodeStrength: -120,
      edgeStrength: 0.15,
      linkDistance: 150,
      workerEnabled: true
    })
    expect(graph.config.modes).toEqual({ default: ['drag-canvas', 'zoom-canvas', 'drag-node'] })
    expect(graph.config.fitView).toBe(true)
    expect(graph.config.animate).toBe(false)
    expect(graph.config.defaultNode.type).toBe('rect')
    expect(graph.config.defaultEdge.type).toBe('line')
    // 事件绑定齐全
    const boundEvents = graph.on.mock.calls.map((c) => c[0])
    expect(boundEvents).toEqual(
      expect.arrayContaining(['node:mouseenter', 'node:mousemove', 'node:mouseleave', 'node:click', 'canvas:click'])
    )
  })

  it('CodeMap_whenContainerSizeIsZero_shouldFallbackToDefaultSize', async () => {
    mountMap()
    await settle()

    const graph = graphs[0]
    expect(graph.config.width).toBe(900)
    expect(graph.config.height).toBe(550)
  })

  it('CodeMap_whenDataArrivesWhileCanvasHidden_shouldRestoreVisibilityBeforeGraphInit', async () => {
    // 回归锁：加载完成时容器仍被 v-show 隐藏（display:none）。
    // 真实浏览器里此时 clientWidth/clientHeight 恒为 0，若在这时初始化 G6，
    // 会退回 900×550 兜底导致画布比面板窄，图内容拖到右边界被 svg 裁掉。
    // 因此 G6 构造时容器必须已恢复可见。
    const d = deferred()
    apiMock.get.mockReturnValue(d.promise)
    const wrapper = mountMap()
    await nextTick()
    expect(wrapper.find('.graph-container').attributes('style')).toContain('display: none')

    const container = wrapper.find('.graph-container').element
    Object.defineProperty(container, 'clientWidth', { configurable: true, value: 1200 })
    Object.defineProperty(container, 'clientHeight', { configurable: true, value: 550 })

    d.resolve({ data: { data: makeMapData() } })
    await settle()

    const graph = graphs[0]
    expect(graph.containerDisplayAtConstruct).not.toBe('none')
    expect(graph.config.width).toBe(1200)
    expect(graph.config.height).toBe(550)
  })

  it('CodeMap_whenNodesAndEdgesProvided_shouldMapShapesColorsAndEdgeStyles', async () => {
    mountMap()
    await settle()

    const graph = graphs[0]
    const { nodes, edges } = graph.data.mock.calls[0][0]

    expect(nodes).toEqual([
      {
        id: 'com.a.Controller', label: 'Controller', layer: 'controller', classType: 'INTERFACE',
        methods: 3, lineCount: 120, group: 'g1',
        type: 'diamond', size: [76, 60], style: { fill: '#1890ff' }
      },
      {
        id: 'com.a.Service', label: 'Service', layer: 'service', classType: 'ABSTRACT',
        methods: 5, lineCount: 88, group: 'g2',
        type: 'rect', size: [90, 36],
        style: { fill: '#52c41a', lineDash: [5, 3], lineWidth: 2, stroke: '#52c41a' }
      },
      {
        id: 'com.a.Enum', label: 'Enum', layer: 'weird-layer', classType: 'ENUM',
        methods: 0, lineCount: 0, group: 'g3',
        type: 'ellipse', size: [82, 46], style: { fill: '#d9d9d9' }
      },
      {
        id: 'com.a.Plain', label: undefined, layer: undefined, classType: 'CLASS',
        methods: 0, lineCount: 0, group: 'unknown',
        type: 'rect', size: [90, 36], style: { fill: '#d9d9d9' }
      }
    ])

    expect(edges).toEqual([
      {
        source: 'com.a.Controller', target: 'com.a.Service', relation: 'dependency',
        style: { stroke: '#d9d9d9', lineWidth: 0.6, opacity: 0.55, endArrow: { path: { triangle: [4, 6, 0] }, fill: '#d9d9d9' } }
      },
      {
        source: 'com.a.Service', target: 'com.a.Enum', relation: 'extends',
        style: { stroke: '#fa541c', lineWidth: 2.5, opacity: 1, endArrow: { path: { triangle: [5, 9, 0] }, fill: '#fa541c' } }
      },
      {
        source: 'com.a.Plain', target: 'com.a.Controller', relation: 'implements',
        style: { stroke: '#52c41a', lineWidth: 2.5, opacity: 1, endArrow: { path: { triangle: [5, 9, 0] }, fill: '#52c41a' }, lineDash: [6, 4] }
      },
      {
        // 未知关系回退到 dependency 样式且箭头用非 typed 尺寸
        source: 'com.a.Enum', target: 'com.a.Plain', relation: 'unknown-rel',
        style: { stroke: '#d9d9d9', lineWidth: 0.6, opacity: 0.55, endArrow: { path: { triangle: [4, 6, 0] }, fill: '#d9d9d9' } }
      }
    ])
  })

  it('CodeMap_whenNodeCountReaches1000_shouldUseCanvasRenderer', async () => {
    const nodes = Array.from({ length: 1000 }, (_, i) => ({
      id: `com.bulk.Class${i}`, label: `Class${i}`, layer: 'service', classType: 'CLASS'
    }))
    // edges 缺省：应回落为空边集合
    apiMock.get.mockResolvedValue({ data: { data: { nodes } } })

    mountMap()
    await settle()

    expect(graphs[0].config.renderer).toBe('canvas')
    expect(graphs[0].data.mock.calls[0][0].edges).toEqual([])
  })

  it('CodeMap_whenApiReturnsNoNodes_shouldShowEmptyStateWithoutCreatingGraph', async () => {
    apiMock.get.mockResolvedValue({ data: { data: { nodes: [], edges: [] } } })
    let wrapper = mountMap()
    await settle()

    expect(graphs).toHaveLength(0)
    expect(wrapper.find('.ant-empty').exists()).toBe(true)
    expect(wrapper.text()).toContain('暂无图谱数据')
    expect(wrapper.find('.graph-legend').exists()).toBe(false)

    // 数据为 null 的分支
    apiMock.get.mockResolvedValue({ data: { data: null } })
    wrapper = mountMap()
    await settle()
    expect(graphs).toHaveLength(0)
    expect(wrapper.find('.ant-empty').exists()).toBe(true)

    // graph 为 null 时切换项目：跳过销毁直接重新拉取
    apiMock.get.mockResolvedValue({ data: { data: makeMapData() } })
    await wrapper.setProps({ projectId: 2 })
    await settle()
    expect(graphs).toHaveLength(1)
  })

  it('CodeMap_whenLoading_shouldShowSpinnerAndHideCanvas', async () => {
    const d = deferred()
    apiMock.get.mockReturnValue(d.promise)
    const wrapper = mountMap()
    await nextTick() // onMounted 里置 loading 后需一次渲染刷新

    expect(wrapper.find('.ant-spin').exists()).toBe(true)
    expect(wrapper.find('.map-status').exists()).toBe(true)
    // 注意：jsdom 的 getComputedStyle 有缓存，v-show 状态用 inline style 断言更可靠
    expect(wrapper.find('.graph-container').attributes('style')).toContain('display: none')

    d.resolve({ data: { data: makeMapData() } })
    await settle()

    expect(wrapper.find('.ant-spin').exists()).toBe(false)
    expect(wrapper.find('.graph-container').attributes('style') || '').not.toContain('display: none')
    expect(wrapper.find('.graph-container').isVisible()).toBe(true)
  })

  it('CodeMap_whenApiFails_shouldShowErrorMessageAndRetryShouldRefetch', async () => {
    apiMock.get.mockRejectedValueOnce({ response: { data: { message: '后端炸了' } } })
    const wrapper = mountMap()
    await settle()

    expect(wrapper.find('.ant-result').exists()).toBe(true)
    expect(wrapper.text()).toContain('后端炸了')
    expect(graphs).toHaveLength(0)

    apiMock.get.mockResolvedValueOnce({ data: { data: makeMapData() } })
    await wrapper.find('.ant-result-extra button').trigger('click')
    await settle()

    expect(apiMock.get).toHaveBeenCalledTimes(2)
    expect(graphs).toHaveLength(1)
    expect(wrapper.find('.ant-result').exists()).toBe(false)
    expect(wrapper.find('.graph-legend').exists()).toBe(true)
  })

  it('CodeMap_whenApiFailsWithoutResponseBody_shouldFallbackToGenericMessage', async () => {
    apiMock.get.mockRejectedValue(new Error('network down'))
    const wrapper = mountMap()
    await settle()

    expect(wrapper.text()).toContain('获取图谱数据失败')
    expect(wrapper.find('.ant-result').exists()).toBe(true)
  })

  it('CodeMap_whenUnmountedBeforeDataArrives_shouldSkipGraphCreation', async () => {
    const d = deferred()
    apiMock.get.mockReturnValue(d.promise)
    const wrapper = mountMap()
    wrapper.unmount()

    d.resolve({ data: { data: makeMapData() } })
    await settle()

    expect(graphs).toHaveLength(0)
  })

  it('CodeMap_whenProjectIdChanges_shouldDestroyPreviousGraphAndRefetch', async () => {
    const wrapper = mountMap()
    await settle()
    const first = graphs[0]

    await wrapper.setProps({ projectId: 2 })
    await settle()

    expect(first.destroy).toHaveBeenCalledTimes(1)
    expect(graphs).toHaveLength(2)
    expect(apiMock.get).toHaveBeenLastCalledWith('/projects/2/map')
    expect(graphs[1].data).toHaveBeenCalledTimes(1)
  })

  it('CodeMap_whenRefetchWithExistingGraph_shouldDestroyOldGraphBeforeRerender', async () => {
    const wrapper = mountMap()
    await settle()
    const first = graphs[0]

    await wrapper.vm.fetchMapData()
    await settle()

    expect(first.destroy).toHaveBeenCalledTimes(1)
    expect(graphs).toHaveLength(2)
    expect(graphs[1].data).toHaveBeenCalledTimes(1)
  })

  it('CodeMap_whenNodeHovered_shouldShowEscapedTooltipAndHighlight', async () => {
    apiMock.get.mockResolvedValue({
      data: {
        data: {
          nodes: [
            { id: 'com.x.<Bad>', label: 'Bad<img/>', layer: 'controller', classType: 'CLASS', methods: 2, lineCount: 5, group: 'g' },
            { id: 'com.x.NoMeta' }
          ],
          edges: []
        }
      }
    })
    const wrapper = mountMap()
    await settle()

    const graph = graphs[0]
    const tooltip = wrapper.find('.graph-container').element.querySelector('div')

    graph.emitEvent('node:mouseenter', { item: graph.nodeItems[0], canvasX: 10, canvasY: 20 })

    expect(graph.updateItem).toHaveBeenCalledWith(graph.nodeItems[0], { style: { stroke: '#000', lineWidth: 2 } })
    expect(tooltip.style.display).toBe('block')
    expect(tooltip.style.left).toBe('22px')
    expect(tooltip.style.top).toBe('32px')
    expect(tooltip.innerHTML).toContain('&lt;img/&gt;')
    expect(tooltip.innerHTML).not.toContain('<img/>')
    expect(tooltip.innerHTML).toContain('&lt;Bad&gt;')
    expect(tooltip.innerHTML).toContain('方法: 2 | 行数: 5 | 分层: controller')

    // 缺省字段节点：label/methods/lineCount 为空、layer 回落 unknown
    graph.emitEvent('node:mouseenter', { item: graph.nodeItems[1], canvasX: 1, canvasY: 2 })
    expect(tooltip.innerHTML).toContain('<b></b>')
    expect(tooltip.innerHTML).toContain('分层: unknown')
  })

  it('CodeMap_whenMouseMovesAndLeaves_shouldMoveTooltipAndRestoreNodeStyle', async () => {
    const wrapper = mountMap()
    await settle()

    const graph = graphs[0]
    const tooltip = wrapper.find('.graph-container').element.querySelector('div')

    graph.emitEvent('node:mousemove', { canvasX: 5, canvasY: 6 })
    expect(tooltip.style.left).toBe('17px')
    expect(tooltip.style.top).toBe('18px')

    // ABSTRACT（虚线框）节点离开：恢复 layer 描边
    graph.emitEvent('node:mouseleave', { item: graph.nodeItems[1] })
    expect(graph.updateItem).toHaveBeenCalledWith(graph.nodeItems[1], {
      style: { stroke: '#52c41a', lineWidth: 2 }
    })
    expect(tooltip.style.display).toBe('none')

    // 普通节点离开：恢复默认白描边
    graph.emitEvent('node:mouseleave', { item: graph.nodeItems[0] })
    expect(graph.updateItem).toHaveBeenCalledWith(graph.nodeItems[0], {
      style: { stroke: '#fff', lineWidth: 1 }
    })
  })

  it('CodeMap_whenNodeClicked_shouldEmitNodePayloadAndDimNonNeighbors', async () => {
    const wrapper = mountMap()
    await settle()

    const graph = graphs[0]
    const [controller, service, enumNode, plain] = graph.nodeItems
    const [eDep, eExtends, eImplements, eUnknown] = graph.edgeItems

    graph.emitEvent('node:click', { item: controller })

    expect(wrapper.emitted('node-click')[0][0]).toEqual({
      id: 'com.a.Controller', label: 'Controller', layer: 'controller', classType: 'INTERFACE',
      methods: 3, lineCount: 120, group: 'g1'
    })

    // 邻居（Controller/Service/Plain）全亮，非邻居（Enum）压暗
    expect(graph.updateItem).toHaveBeenCalledWith(controller, { style: { opacity: 1 } })
    expect(graph.updateItem).toHaveBeenCalledWith(service, { style: { opacity: 1 } })
    expect(graph.updateItem).toHaveBeenCalledWith(plain, { style: { opacity: 1 } })
    expect(graph.updateItem).toHaveBeenCalledWith(enumNode, { style: { opacity: 0.15 } })

    // 边：两端都在邻居集合才点亮
    expect(graph.updateItem).toHaveBeenCalledWith(eDep, { style: { opacity: 1 } })
    expect(graph.updateItem).toHaveBeenCalledWith(eImplements, { style: { opacity: 1 } })
    expect(graph.updateItem).toHaveBeenCalledWith(eExtends, { style: { opacity: 0.05 } })
    expect(graph.updateItem).toHaveBeenCalledWith(eUnknown, { style: { opacity: 0.05 } })

    // 缺省字段节点：classType/methods/lineCount/group 回落默认值
    graph.emitEvent('node:click', { item: plain })
    expect(wrapper.emitted('node-click')[1][0]).toEqual({
      id: 'com.a.Plain', label: undefined, layer: undefined, classType: 'CLASS',
      methods: 0, lineCount: 0, group: 'unknown'
    })
  })

  it('CodeMap_whenClickedItemModelLacksOptionalFields_shouldFillDefaultsInPayload', async () => {
    const wrapper = mountMap()
    await settle()

    const graph = graphs[0]
    // 防御分支：item 的 model 缺 classType/methods/lineCount/group 时补默认值
    const bareItem = {
      getModel: () => ({ id: 'com.bare.Cls', label: 'Bare', layer: 'service' }),
      getEdges: () => []
    }
    graph.emitEvent('node:click', { item: bareItem })

    expect(wrapper.emitted('node-click')[0][0]).toEqual({
      id: 'com.bare.Cls', label: 'Bare', layer: 'service', classType: 'CLASS',
      methods: 0, lineCount: 0, group: 'unknown'
    })
  })

  it('CodeMap_whenNodeClickedWithoutItem_shouldNotEmit', async () => {
    const wrapper = mountMap()
    await settle()

    const graph = graphs[0]
    graph.emitEvent('node:click', { item: null })
    expect(wrapper.emitted('node-click')).toBeUndefined()

    // 卸载后 graph 为 null 的防御分支
    wrapper.unmount()
    graph.emitEvent('node:click', { item: graph.nodeItems[0] })
    expect(wrapper.emitted('node-click')).toBeUndefined()
  })

  it('CodeMap_whenCanvasClicked_shouldResetAllStylesAndEmitNull', async () => {
    const wrapper = mountMap()
    await settle()

    const graph = graphs[0]
    const [controller, , enumNode] = graph.nodeItems
    const [eDep, eExtends] = graph.edgeItems

    graph.emitEvent('canvas:click')

    expect(graph.updateItem).toHaveBeenCalledWith(controller, {
      style: { opacity: 1, stroke: '#fff', lineWidth: 1 }
    })
    expect(graph.updateItem).toHaveBeenCalledWith(enumNode, {
      style: { opacity: 1, stroke: '#fff', lineWidth: 1 }
    })
    // 边按关系恢复透明度（未知关系回退 dependency 的 0.55）
    expect(graph.updateItem).toHaveBeenCalledWith(eDep, { style: { opacity: 0.55 } })
    expect(graph.updateItem).toHaveBeenCalledWith(eExtends, { style: { opacity: 1 } })
    expect(wrapper.emitted('node-click')[0]).toEqual([null])
  })

  it('CodeMap_whenFocusFqnProvided_shouldDeferFocusUntilGraphReadyAndRestoreHiddenNode', async () => {
    const data = makeMapData()
    data.nodes.push({
      id: 'com.a.TestCls', label: 'TestCls', layer: 'test',
      classType: 'CLASS', methods: 1, lineCount: 10, group: 'g-test'
    })
    data.edges.push({ source: 'com.a.TestCls', target: 'com.a.Service' })
    apiMock.get.mockResolvedValue({ data: { data } })

    const wrapper = mountMap({ focusFqn: 'com.a.TestCls', focusNonce: 1, hideTest: true })
    await settle()

    const graph = graphs[0]
    const testItem = graph.findById('com.a.TestCls')
    const testEdge = graph.edgeItems[4]

    // 先被 hideTest 过滤隐藏（连同相邻边）
    expect(graph.hideItem).toHaveBeenCalledWith(testItem)
    expect(graph.hideItem).toHaveBeenCalledWith(testEdge)
    // 定位时恢复显示并选中 + 聚焦
    expect(graph.showItem).toHaveBeenCalledWith(testItem)
    expect(graph.showItem).toHaveBeenCalledWith(testEdge)
    expect(graph.focusItem).toHaveBeenCalledWith(testItem, true)
    expect(wrapper.emitted('node-click')[0][0].id).toBe('com.a.TestCls')
  })

  it('CodeMap_whenFocusToggledBeforeGraphReady_shouldRetainPendingFocus', async () => {
    // graph 尚未创建时改变 focusNonce：focusNode 暂存 FQN，渲染后再定位
    const d = deferred()
    apiMock.get.mockReturnValue(d.promise)
    const wrapper = mountMap({ focusFqn: 'com.a.Service' })

    await wrapper.setProps({ focusNonce: 5 })
    d.resolve({ data: { data: makeMapData() } })
    await settle()

    const graph = graphs[0]
    expect(graph.focusItem).toHaveBeenCalledWith(graph.nodeItems[1], true)
  })

  it('CodeMap_whenFocusNonceChanges_shouldFocusVisibleNodeAndIgnoreMissingNode', async () => {
    const wrapper = mountMap()
    await settle()

    const graph = graphs[0]
    graph.showItem.mockClear()
    await wrapper.setProps({ focusFqn: 'com.a.Service', focusNonce: 2 })

    expect(graph.focusItem).toHaveBeenCalledWith(graph.nodeItems[1], true)
    // 可见节点无需 showItem 恢复
    expect(graph.showItem).not.toHaveBeenCalled()

    graph.focusItem.mockClear()
    await wrapper.setProps({ focusFqn: 'com.missing.Cls', focusNonce: 3 })
    expect(graph.focusItem).not.toHaveBeenCalled()

    // 空 FQN 直接返回
    wrapper.vm.focusNode('')
    expect(graph.focusItem).not.toHaveBeenCalled()
  })

  it('CodeMap_whenHideFlagsChange_shouldHideAndShowLayerNodesAndTheirEdges', async () => {
    const data = makeMapData()
    data.nodes.push({ id: 'com.a.TestCls', label: 'TestCls', layer: 'TEST', classType: 'CLASS' })
    data.nodes.push({ id: 'com.a.Util', label: 'Util', layer: 'util', classType: 'CLASS' })
    data.edges.push({ source: 'com.a.TestCls', target: 'com.a.Util' })
    apiMock.get.mockResolvedValue({ data: { data } })

    const wrapper = mountMap()
    await settle()

    const graph = graphs[0]
    const testItem = graph.findById('com.a.TestCls')
    const utilItem = graph.findById('com.a.Util')
    const bothHiddenEdge = graph.edgeItems[4]

    // 初始：无隐藏，全部 showItem
    expect(graph.showItem).toHaveBeenCalledWith(testItem)
    expect(graph.showItem).toHaveBeenCalledWith(utilItem)

    graph.hideItem.mockClear()
    graph.showItem.mockClear()

    // hideTest 打开（layer 大小写不敏感）：仅隐藏测试类节点
    await wrapper.setProps({ hideTest: true })
    expect(graph.hideItem).toHaveBeenCalledWith(testItem)
    expect(graph.showItem).toHaveBeenCalledWith(utilItem)
    expect(graph.hideItem).toHaveBeenCalledWith(bothHiddenEdge)

    graph.hideItem.mockClear()
    graph.showItem.mockClear()

    // hideUtil 打开：工具类节点也隐藏，两个开关同时生效
    await wrapper.setProps({ hideUtil: true })
    expect(graph.hideItem).toHaveBeenCalledWith(testItem)
    expect(graph.hideItem).toHaveBeenCalledWith(utilItem)
    expect(graph.hideItem).toHaveBeenCalledWith(bothHiddenEdge)

    graph.hideItem.mockClear()
    graph.showItem.mockClear()

    // 关闭开关：恢复显示
    await wrapper.setProps({ hideTest: false, hideUtil: false })
    expect(graph.hideItem).not.toHaveBeenCalled()
    expect(graph.showItem).toHaveBeenCalledWith(testItem)
    expect(graph.showItem).toHaveBeenCalledWith(utilItem)
    expect(graph.showItem).toHaveBeenCalledWith(bothHiddenEdge)
  })

  it('CodeMap_whenHideFlagsChangeBeforeGraphReady_shouldSkipFilterSafely', async () => {
    const d = deferred()
    apiMock.get.mockReturnValue(d.promise)
    const wrapper = mountMap()

    // graph 尚未创建，过滤应直接返回不报错
    await wrapper.setProps({ hideTest: true })

    d.resolve({ data: { data: makeMapData() } })
    await settle()
    expect(graphs).toHaveLength(1)
  })

  it('CodeMap_whenWindowResized_shouldResizeGraph', async () => {
    const wrapper = mountMap()
    await settle()

    const graph = graphs[0]
    const container = wrapper.find('.graph-container').element

    window.dispatchEvent(new Event('resize'))
    expect(graph.changeSize).toHaveBeenCalledWith(900, 550)

    // 容器有真实尺寸时按容器尺寸调整
    Object.defineProperty(container, 'clientWidth', { configurable: true, value: 700 })
    Object.defineProperty(container, 'clientHeight', { configurable: true, value: 480 })
    window.dispatchEvent(new Event('resize'))
    expect(graph.changeSize).toHaveBeenLastCalledWith(700, 480)

    // 卸载后 resize 监听已移除，不再触发
    const callsAfterUnmount = graph.changeSize.mock.calls.length
    wrapper.unmount()
    window.dispatchEvent(new Event('resize'))
    expect(graph.changeSize.mock.calls.length).toBe(callsAfterUnmount)
  })

  it('CodeMap_whenActivatedInKeepAlive_shouldResizeFitAndRefocus', async () => {
    const host = defineComponent({
      props: { focusFqn: { type: String, default: null } },
      data() {
        return { show: true }
      },
      render() {
        return h(KeepAlive, null, [
          this.show
            ? h(CodeMap, { projectId: 1, focusFqn: this.focusFqn })
            : h('div', { class: 'placeholder' }, 'hidden')
        ])
      }
    })
    const wrapper = mount(host, {
      props: { focusFqn: 'com.a.Service' },
      global: { components: globalComponents }
    })
    await settle()

    const graph = graphs[0]
    graph.changeSize.mockClear()
    graph.fitView.mockClear()
    graph.focusItem.mockClear()

    wrapper.vm.show = false
    await nextTick()
    wrapper.vm.show = true
    await settle()

    expect(graph.changeSize).toHaveBeenCalledWith(900, 550)
    expect(graph.fitView).toHaveBeenCalled()
    expect(graph.focusItem).toHaveBeenCalledWith(graph.nodeItems[1], true)
  })

  it('CodeMap_whenActivatedWithoutFocusFqn_shouldOnlyResizeAndFit', async () => {
    const host = defineComponent({
      data() {
        return { show: true }
      },
      render() {
        return h(KeepAlive, null, [
          this.show ? h(CodeMap, { projectId: 1 }) : h('div', 'hidden')
        ])
      }
    })
    const wrapper = mount(host, { global: { components: globalComponents } })
    await settle()

    const graph = graphs[0]
    graph.changeSize.mockClear()
    graph.fitView.mockClear()

    wrapper.vm.show = false
    await nextTick()
    wrapper.vm.show = true
    await settle()

    expect(graph.changeSize).toHaveBeenCalledWith(900, 550)
    expect(graph.fitView).toHaveBeenCalled()
    expect(graph.focusItem).not.toHaveBeenCalled()
  })

  it('CodeMap_whenUnmounted_shouldDestroyGraphRemoveTooltipAndResizeListener', async () => {
    const removeSpy = vi.spyOn(window, 'removeEventListener')
    const wrapper = mountMap()
    await settle()

    const graph = graphs[0]
    const container = wrapper.find('.graph-container').element
    expect(container.querySelectorAll('div')).toHaveLength(1)

    wrapper.unmount()

    expect(graph.destroy).toHaveBeenCalledTimes(1)
    expect(removeSpy).toHaveBeenCalledWith('resize', graph._resizeHandler)
    expect(container.querySelectorAll('div')).toHaveLength(0)
  })

  it('CodeMap_whenUnmountedWithoutResizeHandlerOrAttachedTooltip_shouldStillDestroyGraph', async () => {
    const wrapper = mountMap()
    await settle()

    const graph = graphs[0]
    graph._resizeHandler = null
    graph._tooltipEl = document.createElement('div') // 游离节点：无 parentNode

    wrapper.unmount()

    expect(graph.destroy).toHaveBeenCalledTimes(1)
  })
})
