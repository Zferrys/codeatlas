<template>
  <div class="code-map-wrapper">
    <div v-if="loading" class="map-status">
      <a-spin size="large" tip="加载图谱数据..." />
    </div>
    <div v-else-if="error" class="map-status">
      <a-result status="error" title="加载失败" :sub-title="error">
        <template #extra>
          <a-button type="primary" @click="fetchMapData">重试</a-button>
        </template>
      </a-result>
    </div>
    <div v-else-if="isEmpty" class="map-status">
      <a-empty description="暂无图谱数据，请先触发扫描" />
    </div>
    <div v-show="!loading && !error && !isEmpty" ref="graphContainer" class="graph-container"></div>
    <div v-if="!loading && !error && !isEmpty" class="graph-legend">
      <span v-for="item in legend" :key="item.label" class="legend-item">
        <span class="legend-dot" :style="{ background: item.color }"></span>
        {{ item.label }}
      </span>
      <span class="legend-sep"></span>
      <span class="legend-item">◇ 接口</span>
      <span class="legend-item">▭ 类</span>
      <span class="legend-item">▭ 抽象类（虚线框）</span>
      <span class="legend-item">◯ 枚举</span>
      <span class="legend-sep"></span>
      <span class="legend-item"><i class="legend-line thin" style="border-color:#bfbfbf"></i> 依赖</span>
      <span class="legend-item"><i class="legend-line bold" style="border-color:#fa541c"></i> 继承</span>
      <span class="legend-item"><i class="legend-line dashed bold" style="border-color:#52c41a"></i> 实现</span>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount, onActivated, nextTick } from 'vue'
import G6 from '@antv/g6'
import api from '../../api'

const props = defineProps({
  projectId: { type: [Number, String], required: true },
  hideTest: { type: Boolean, default: false },
  hideUtil: { type: Boolean, default: false },
  focusFqn: { type: String, default: null },
  focusNonce: { type: Number, default: 0 }
})

const emit = defineEmits(['node-click'])

const graphContainer = ref(null)
const loading = ref(false)
const error = ref(null)
const isEmpty = ref(false)
let graph = null

const LAYER_COLORS = {
  'controller': '#1890ff',
  'service': '#52c41a',
  'repository': '#fa8c16',
  'mapper': '#fa8c16',
  'domain': '#13c2c2',
  'entity': '#13c2c2',
  'dto': '#722ed1',
  'config': '#f5222d',
  'util': '#8c8c8c',
  'security': '#eb2f96',
  'exception': '#fa541c',
  'filter': '#2f54eb',
  'unknown': '#d9d9d9'
}

const legend = [
  { label: 'Controller', color: '#1890ff' },
  { label: 'Service', color: '#52c41a' },
  { label: 'Mapper/Repo', color: '#fa8c16' },
  { label: 'Domain', color: '#13c2c2' },
  { label: 'DTO', color: '#722ed1' },
  { label: 'Config', color: '#f5222d' },
  { label: 'Util', color: '#8c8c8c' }
]

// classType → G6 节点形状；null/其他默认 rect
function nodeShape(classType) {
  switch (classType) {
    case 'INTERFACE': return { type: 'diamond', size: [76, 60] }
    case 'ABSTRACT': return { type: 'rect', size: [90, 36], lineDash: [5, 3] }
    case 'ENUM': return { type: 'ellipse', size: [82, 46] }
    default: return { type: 'rect', size: [90, 36] }
  }
}

// 边关系 → 样式；后端 edge.type 值为 dependency/extends/implements
// 依赖线弱化后退为背景，继承/实现用高对比粗线突出（一眼区分）
const EDGE_STYLES = {
  dependency: { stroke: '#d9d9d9', lineWidth: 0.6, opacity: 0.55 },
  extends: { stroke: '#fa541c', lineWidth: 2.5, opacity: 1 },
  implements: { stroke: '#52c41a', lineWidth: 2.5, lineDash: [6, 4], opacity: 1 }
}

function getNodeColor(layer) {
  if (!layer) return LAYER_COLORS['unknown']
  return LAYER_COLORS[layer.toLowerCase()] || LAYER_COLORS['unknown']
}

async function fetchMapData() {
  loading.value = true
  error.value = null
  isEmpty.value = false
  try {
    const res = await api.get(`/projects/${props.projectId}/map`)
    const mapData = res.data.data
    if (!mapData || !mapData.nodes || mapData.nodes.length === 0) {
      isEmpty.value = true
      return
    }
    await nextTick()
    renderGraph(mapData)
  } catch (e) {
    error.value = e.response?.data?.message || '获取图谱数据失败'
  } finally {
    loading.value = false
  }
}

function renderGraph(data) {
  if (graph) {
    graph.destroy()
    graph = null
  }

  const nodes = data.nodes.map(n => {
    const shape = nodeShape(n.classType)
    const style = { fill: getNodeColor(n.layer) }
    if (shape.lineDash) {
      style.lineDash = shape.lineDash
      style.lineWidth = 2
      style.stroke = getNodeColor(n.layer)
    }
    return {
      id: n.id,
      label: n.label,
      layer: n.layer,
      classType: n.classType || 'CLASS',
      methods: n.methods || 0,
      lineCount: n.lineCount || 0,
      group: n.group || 'unknown',
      type: shape.type,
      size: shape.size,
      style
    }
  })

  const edges = (data.edges || []).map(e => {
    const relation = e.type || 'dependency'
    const st = EDGE_STYLES[relation] || EDGE_STYLES.dependency
    const isTyped = relation === 'extends' || relation === 'implements'
    const style = {
      stroke: st.stroke,
      lineWidth: st.lineWidth,
      opacity: st.opacity ?? 1,
      // 继承/实现用更大箭头强化方向感
      endArrow: { path: isTyped ? G6.Arrow.triangle(5, 9, 0) : G6.Arrow.triangle(4, 6, 0), fill: st.stroke }
    }
    if (st.lineDash) style.lineDash = st.lineDash
    return {
      source: e.source,
      target: e.target,
      relation,
      style
    }
  })

  const container = graphContainer.value
  if (!container) return

  const width = container.clientWidth || 900
  const height = container.clientHeight || 550

  // 大节点量使用 Canvas 渲染器以提升性能
  const useCanvas = nodes.length >= 1000

  graph = new G6.Graph({
    container: container,
    width,
    height,
    renderer: useCanvas ? 'canvas' : 'svg',
    layout: {
      type: 'force',
      preventOverlap: true,
      nodeStrength: -120,
      edgeStrength: 0.15,
      linkDistance: 150,
      workerEnabled: true
    },
    defaultNode: {
      size: [90, 36],
      type: 'rect',
      style: { radius: 4, stroke: '#fff', lineWidth: 1 },
      labelCfg: {
        style: { fill: '#fff', fontSize: 10, textAlign: 'center' }
      }
    },
    defaultEdge: {
      type: 'line',
      style: { stroke: '#bfbfbf', lineWidth: 0.8, endArrow: { path: G6.Arrow.triangle(4, 6, 0), fill: '#bfbfbf' } }
    },
    modes: {
      default: ['drag-canvas', 'zoom-canvas', 'drag-node']
    },
    fitView: true,
    animate: false
  })

  graph.data({ nodes, edges })
  graph.render()

  // 手动 tooltip
  const tooltipEl = document.createElement('div')
  tooltipEl.style.cssText = 'position:absolute;padding:8px 12px;max-width:260px;background:rgba(0,0,0,0.75);color:#fff;border-radius:4px;font-size:12px;pointer-events:none;display:none;z-index:9999'
  container.appendChild(tooltipEl)

  graph.on('node:mouseenter', (e) => {
    const node = e.item
    const model = node.getModel()
    graph.updateItem(node, {
      style: { stroke: '#000', lineWidth: 2 }
    })
    const rect = container.getBoundingClientRect()
    const x = e.canvasX + rect.left + 12
    const y = e.canvasY + rect.top + 12
    tooltipEl.innerHTML = `<b>${model.label}</b><br/>
      <span style="opacity:0.7;font-size:11px">${model.id}</span><br/>
      方法: ${model.methods} | 行数: ${model.lineCount} | 分层: ${model.layer || 'unknown'}`
    tooltipEl.style.display = 'block'
    tooltipEl.style.left = x + 'px'
    tooltipEl.style.top = y + 'px'
  })

  graph.on('node:mousemove', (e) => {
    const rect = container.getBoundingClientRect()
    tooltipEl.style.left = (e.canvasX + rect.left + 12) + 'px'
    tooltipEl.style.top = (e.canvasY + rect.top + 12) + 'px'
  })

  graph.on('node:mouseleave', (e) => {
    const model = e.item.getModel()
    const shape = nodeShape(model.classType)
    graph.updateItem(e.item, {
      style: {
        stroke: shape.lineDash ? getNodeColor(model.layer) : '#fff',
        lineWidth: shape.lineDash ? 2 : 1
      }
    })
    tooltipEl.style.display = 'none'
  })

  // click to highlight neighbors + emit detail event
  graph.on('node:click', (e) => {
    selectNode(e.item)
  })

  // click canvas to reset
  graph.on('canvas:click', () => {
    graph.getNodes().forEach(n => {
      const model = n.getModel()
      const shape = nodeShape(model.classType)
      graph.updateItem(n, {
        style: {
          opacity: 1,
          stroke: shape.lineDash ? getNodeColor(model.layer) : '#fff',
          lineWidth: shape.lineDash ? 2 : 1
        }
      })
    })
    graph.getEdges().forEach(e => {
      const st = EDGE_STYLES[e.getModel().relation] || EDGE_STYLES.dependency
      graph.updateItem(e, { style: { opacity: st.opacity ?? 1 } })
    })
    // 通知父组件清除选中节点，否则问答面板会一直沿用旧类上下文
    emit('node-click', null)
  })

  // resize handler
  const resizeFn = () => {
    if (graph && !graph.destroyed) {
      const w = container.clientWidth || 900
      const h = container.clientHeight || 550
      graph.changeSize(w, h)
    }
  }
  window.addEventListener('resize', resizeFn)
  graph._resizeHandler = resizeFn
  graph._tooltipEl = tooltipEl

  // 先应用过滤，再处理暂存的跳转定位请求（顺序反了会把刚恢复的节点重新隐藏）
  applyLayerFilter()

  if (pendingFocusFqn) {
    const fqn = pendingFocusFqn
    pendingFocusFqn = null
    focusNode(fqn)
  }
}

let pendingFocusFqn = null

// 高亮选中节点及其邻居，并向外通知选中信息
function selectNode(item) {
  if (!graph || graph.destroyed || !item) return
  const model = item.getModel()
  emit('node-click', {
    id: model.id,
    label: model.label,
    layer: model.layer,
    classType: model.classType || 'CLASS',
    methods: model.methods || 0,
    lineCount: model.lineCount || 0,
    group: model.group || 'unknown'
  })

  const neighbors = new Set()
  item.getEdges().forEach(edge => {
    neighbors.add(edge.getSource().getID())
    neighbors.add(edge.getTarget().getID())
  })
  graph.getNodes().forEach(n => {
    graph.updateItem(n, {
      style: { opacity: neighbors.has(n.getID()) ? 1 : 0.15 }
    })
  })
  graph.getEdges().forEach(edge => {
    const isNeighbor = neighbors.has(edge.getSource().getID()) && neighbors.has(edge.getTarget().getID())
    graph.updateItem(edge, {
      style: { opacity: isNeighbor ? 1 : 0.05 }
    })
  })
}

// 跳转定位：选中并聚焦到指定 FQN 的节点（被过滤隐藏时先恢复显示）
function focusNode(fqn) {
  if (!fqn) return
  if (!graph || graph.destroyed) {
    pendingFocusFqn = fqn
    return
  }
  const item = graph.findById(fqn)
  if (!item) return
  // hideItem 只改 item 的可见状态、不写 model.hidden，必须用 isVisible() 判断
  if (!item.isVisible()) {
    graph.showItem(item)
    item.getEdges().forEach(e => graph.showItem(e))
  }
  selectNode(item)
  graph.focusItem(item, true)
}

function shouldHideNode(model) {
  const layer = (model.layer || '').toLowerCase()
  if (props.hideTest && layer === 'test') return true
  if (props.hideUtil && layer === 'util') return true
  return false
}

// 按开关隐藏/显示测试类与工具类节点；任一端被隐藏的边一并隐藏
function applyLayerFilter() {
  if (!graph || graph.destroyed) return
  graph.getNodes().forEach(n => {
    if (shouldHideNode(n.getModel())) graph.hideItem(n)
    else graph.showItem(n)
  })
  graph.getEdges().forEach(edge => {
    const src = graph.findById(edge.getSource().getID())
    const tgt = graph.findById(edge.getTarget().getID())
    const hide = (src && shouldHideNode(src.getModel())) || (tgt && shouldHideNode(tgt.getModel()))
    if (hide) graph.hideItem(edge)
    else graph.showItem(edge)
  })
}

onMounted(() => {
  fetchMapData()
})

// keep-alive 激活时重新适配 G6 画布大小
onActivated(() => {
  if (graph && !graph.destroyed) {
    nextTick(() => {
      const container = graphContainer.value
      if (container) {
        const w = container.clientWidth || 900
        const h = container.clientHeight || 550
        graph.changeSize(w, h)
        graph.fitView()
        // fitView 之后恢复跳转定位
        if (props.focusFqn) {
          focusNode(props.focusFqn)
        }
      }
    })
  }
})

watch(() => props.focusNonce, () => {
  if (props.focusFqn) focusNode(props.focusFqn)
}, { immediate: true })

watch([() => props.hideTest, () => props.hideUtil], () => {
  applyLayerFilter()
})

watch(() => props.projectId, () => {
  if (graph) {
    graph.destroy()
    graph = null
  }
  fetchMapData()
})

onBeforeUnmount(() => {
  if (graph) {
    if (graph._resizeHandler) {
      window.removeEventListener('resize', graph._resizeHandler)
    }
    if (graph._tooltipEl && graph._tooltipEl.parentNode) {
      graph._tooltipEl.parentNode.removeChild(graph._tooltipEl)
    }
    graph.destroy()
    graph = null
  }
})
</script>

<style scoped>
.code-map-wrapper {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 500px;
  background: #fafafa;
  border-radius: 4px;
  overflow: hidden;
}
.map-status {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 500px;
}
.graph-container {
  width: 100%;
  height: 550px;
}
.graph-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  padding: 8px 16px;
  background: #fff;
  border-top: 1px solid #f0f0f0;
}
.legend-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #666;
}
.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  display: inline-block;
}
.legend-sep {
  width: 1px;
  height: 14px;
  background: #f0f0f0;
  display: inline-block;
}
.legend-line {
  width: 18px;
  height: 0;
  border-top: 2px solid;
  display: inline-block;
  vertical-align: middle;
}
.legend-line.thin {
  border-top-width: 1px;
}
.legend-line.bold {
  border-top-width: 3px;
}
.legend-line.dashed {
  border-top-style: dashed;
}
</style>
