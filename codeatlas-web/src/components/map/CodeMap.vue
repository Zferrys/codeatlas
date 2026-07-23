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
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import G6 from '@antv/g6'
import api from '../../api'

const props = defineProps({
  projectId: { type: [Number, String], required: true }
})

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

  const nodes = data.nodes.map(n => ({
    id: n.id,
    label: n.label,
    layer: n.layer,
    methods: n.methods || 0,
    lineCount: n.lineCount || 0,
    group: n.group || 'unknown',
    style: { fill: getNodeColor(n.layer) }
  }))

  const edges = (data.edges || []).map(e => ({
    source: e.source,
    target: e.target,
    style: { stroke: '#bfbfbf', lineWidth: 1, endArrow: true }
  }))

  const container = graphContainer.value
  if (!container) return

  const width = container.clientWidth || 900
  const height = container.clientHeight || 550

  graph = new G6.Graph({
    container: container,
    width,
    height,
    layout: {
      type: 'force',
      preventOverlap: true,
      nodeStrength: -120,
      edgeStrength: 0.15,
      linkDistance: 150
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
    animate: true
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
    graph.updateItem(e.item, {
      style: { stroke: '#fff', lineWidth: 1 }
    })
    tooltipEl.style.display = 'none'
  })

  // click to highlight neighbors
  graph.on('node:click', (e) => {
    const node = e.item
    const neighbors = new Set()
    node.getEdges().forEach(edge => {
      const src = edge.getSource()
      const tgt = edge.getTarget()
      neighbors.add(src.getID())
      neighbors.add(tgt.getID())
    })
    graph.getNodes().forEach(n => {
      const isNeighbor = neighbors.has(n.getID())
      graph.updateItem(n, {
        style: { opacity: isNeighbor ? 1 : 0.15 }
      })
    })
    graph.getEdges().forEach(edge => {
      const srcId = edge.getSource().getID()
      const tgtId = edge.getTarget().getID()
      const isNeighbor = neighbors.has(srcId) && neighbors.has(tgtId)
      graph.updateItem(edge, {
        style: { opacity: isNeighbor ? 1 : 0.05 }
      })
    })
  })

  // click canvas to reset
  graph.on('canvas:click', () => {
    graph.getNodes().forEach(n => graph.updateItem(n, { style: { opacity: 1, stroke: '#fff', lineWidth: 1 } }))
    graph.getEdges().forEach(e => graph.updateItem(e, { style: { opacity: 1 } }))
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
}

onMounted(() => {
  fetchMapData()
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
</style>
