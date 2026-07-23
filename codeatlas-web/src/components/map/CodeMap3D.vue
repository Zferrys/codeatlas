<template>
  <div class="map3d-wrapper" ref="wrapperRef">
    <!-- 状态覆盖 -->
    <div v-if="loading" class="map3d-overlay"><a-spin size="large" tip="加载图谱数据..." /></div>
    <div v-else-if="error" class="map3d-overlay">
      <a-result status="error" title="加载失败" :sub-title="error">
        <template #extra><a-button type="primary" @click="fetchData">重试</a-button></template>
      </a-result>
    </div>
    <div v-else-if="isEmpty" class="map3d-overlay">
      <a-empty description="暂无图谱数据，请先触发扫描" />
    </div>

    <!-- Three.js 画布 -->
    <div v-show="ready" ref="canvasRef" class="map3d-canvas"></div>

    <!-- 搜索栏 -->
    <div v-if="ready" class="map3d-search">
      <a-auto-complete
        v-model:value="searchText"
        :options="searchOptions"
        placeholder="搜索类名... (Ctrl+K)"
        style="width:260px"
        @select="onSearchSelect"
        @keydown.esc="searchText = ''"
      />
    </div>

    <!-- 图例 -->
    <div v-if="ready" class="map3d-legend">
      <div class="legend-title">分层图例</div>
      <div v-for="item in legend" :key="item.label" class="legend-row">
        <span class="legend-dot" :style="{ background: item.color }"></span>
        <span>{{ item.label }}</span>
      </div>
      <a-divider style="margin:8px 0" />
      <div class="legend-row">
        <a-switch size="small" v-model:checked="heatmapMode" @change="toggleHeatmap" />
        <span style="margin-left:8px">热力模式</span>
      </div>
    </div>

    <!-- 选中节点信息面板 -->
    <transition name="slide">
      <div v-if="selectedNode" class="map3d-info-panel">
        <div class="panel-header">
          <h4>{{ selectedNode.label }}</h4>
          <a-button type="text" size="small" @click="selectedNode = null">✕</a-button>
        </div>
        <div class="panel-body">
          <div class="info-row"><span>全限定名</span><code>{{ selectedNode.id }}</code></div>
          <div class="info-row"><span>分层</span><a-tag :color="getLayerColor(selectedNode.layer)" size="small">{{ selectedNode.layer }}</a-tag></div>
          <div class="info-row"><span>方法数</span><strong>{{ selectedNode.methods }}</strong></div>
          <div class="info-row"><span>代码行数</span><strong>{{ selectedNode.lineCount }}</strong></div>
          <div class="info-row"><span>依赖数</span><strong>{{ selectedNode.depCount }}</strong></div>
        </div>
      </div>
    </transition>

    <!-- 使用提示 -->
    <div v-if="ready" class="map3d-hint">
      🖱 拖拽旋转 | 滚轮缩放 | 右键平移 | 点击节点查看详情
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import api from '../../api'

const props = defineProps({
  projectId: { type: [Number, String], required: true }
})

const wrapperRef = ref(null)
const canvasRef = ref(null)
const loading = ref(false)
const error = ref(null)
const isEmpty = ref(false)
const ready = ref(false)
const searchText = ref('')
const selectedNode = ref(null)
const heatmapMode = ref(false)

let scene, camera, renderer, controls, raycaster
let nodeMeshes = [], edgeLines = []
let animFrameId = null
let resizeObserver = null

const LAYER_COLORS = {
  'controller': 0x1890ff, 'service': 0x52c41a, 'repository': 0xfa8c16,
  'mapper': 0xfa8c16, 'domain': 0x13c2c2, 'entity': 0x13c2c2,
  'dto': 0x722ed1, 'config': 0xf5222d, 'util': 0x8c8c8c,
  'security': 0xeb2f96, 'exception': 0xfa541c, 'filter': 0x2f54eb, 'unknown': 0x999999
}
const LAYER_HEX = {
  'controller': '#1890ff', 'service': '#52c41a', 'repository': '#fa8c16',
  'mapper': '#fa8c16', 'domain': '#13c2c2', 'entity': '#13c2c2',
  'dto': '#722ed1', 'config': '#f5222d', 'util': '#8c8c8c',
  'security': '#eb2f96', 'exception': '#fa541c', 'filter': '#2f54eb', 'unknown': '#999999'
}

const legend = [
  { label: 'Controller', color: '#1890ff' }, { label: 'Service', color: '#52c41a' },
  { label: 'Repository', color: '#fa8c16' }, { label: 'Domain/Entity', color: '#13c2c2' },
  { label: 'DTO', color: '#722ed1' }, { label: 'Config', color: '#f5222d' }, { label: 'Util', color: '#8c8c8c' }
]

const searchOptions = computed(() => {
  if (!searchText.value) return []
  return nodeMeshes
    .filter(m => m.userData.label.toLowerCase().includes(searchText.value.toLowerCase()))
    .slice(0, 8)
    .map(m => ({ value: m.userData.label, label: m.userData.label }))
})

function getLayerColor(layer) {
  return LAYER_HEX[layer?.toLowerCase()] || LAYER_HEX['unknown']
}

// ---- 力导向布局 ----
function forceLayout(nodes, edges, iterations = 80) {
  const positions = nodes.map(() => ({
    x: (Math.random() - 0.5) * 20,
    y: (Math.random() - 0.5) * 20,
    z: (Math.random() - 0.5) * 20
  }))
  const nodeIdx = {}
  nodes.forEach((n, i) => { nodeIdx[n.id] = i })

  const repulsion = 50, springLen = 8, springK = 0.06, damping = 0.85
  for (let iter = 0; iter < iterations; iter++) {
    const forces = positions.map(() => ({ x: 0, y: 0, z: 0 }))
    // Repulsion between all pairs
    for (let i = 0; i < nodes.length; i++) {
      for (let j = i + 1; j < nodes.length; j++) {
        const dx = positions[i].x - positions[j].x
        const dy = positions[i].y - positions[j].y
        const dz = positions[i].z - positions[j].z
        const dist = Math.sqrt(dx * dx + dy * dy + dz * dz) + 0.01
        const f = repulsion / (dist * dist)
        const fx = (dx / dist) * f, fy = (dy / dist) * f, fz = (dz / dist) * f
        forces[i].x += fx; forces[i].y += fy; forces[i].z += fz
        forces[j].x -= fx; forces[j].y -= fy; forces[j].z -= fz
      }
    }
    // Spring attraction along edges
    for (const e of edges) {
      const si = nodeIdx[e.source], ti = nodeIdx[e.target]
      if (si == null || ti == null) continue
      const dx = positions[ti].x - positions[si].x
      const dy = positions[ti].y - positions[si].y
      const dz = positions[ti].z - positions[si].z
      const dist = Math.sqrt(dx * dx + dy * dy + dz * dz) + 0.01
      const f = (dist - springLen) * springK
      const fx = (dx / dist) * f, fy = (dy / dist) * f, fz = (dz / dist) * f
      forces[si].x += fx; forces[si].y += fy; forces[si].z += fz
      forces[ti].x -= fx; forces[ti].y -= fy; forces[ti].z -= fz
    }
    // Apply forces with damping
    for (let i = 0; i < nodes.length; i++) {
      positions[i].x += forces[i].x * damping
      positions[i].y += forces[i].y * damping
      positions[i].z += forces[i].z * damping
    }
  }
  return positions
}

// ---- 场景初始化 ----
function initScene(container) {
  const w = container.clientWidth, h = container.clientHeight

  scene = new THREE.Scene()
  scene.background = new THREE.Color(0x0f1922)
  scene.fog = new THREE.Fog(0x0f1922, 30, 80)

  camera = new THREE.PerspectiveCamera(50, w / h, 0.5, 200)
  camera.position.set(15, 12, 20)
  camera.lookAt(0, 0, 0)

  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
  renderer.setSize(w, h)
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  renderer.shadowMap.enabled = true
  container.appendChild(renderer.domElement)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.enableDamping = true
  controls.dampingFactor = 0.08
  controls.minDistance = 5
  controls.maxDistance = 50
  controls.maxPolarAngle = Math.PI * 0.85

  raycaster = new THREE.Raycaster()
  raycaster.params.Points.threshold = 0.5

  // Ambient + directional lights
  scene.add(new THREE.AmbientLight(0x404060, 0.6))
  const dirLight = new THREE.DirectionalLight(0xffffff, 0.8)
  dirLight.position.set(10, 20, 15)
  scene.add(dirLight)
  const dirLight2 = new THREE.DirectionalLight(0x667eea, 0.3)
  dirLight2.position.set(-10, -5, -10)
  scene.add(dirLight2)

  // Grid helper
  const grid = new THREE.PolarGridHelper(20, 32, 24, 64, 0x222244, 0x222244)
  scene.add(grid)

  // Stars background particles
  const starsGeo = new THREE.BufferGeometry()
  const starVerts = []
  for (let i = 0; i < 400; i++) {
    starVerts.push((Math.random() - 0.5) * 80, (Math.random() - 0.5) * 80, (Math.random() - 0.5) * 80)
  }
  starsGeo.setAttribute('position', new THREE.Float32BufferAttribute(starVerts, 3))
  scene.add(new THREE.Points(starsGeo, new THREE.PointsMaterial({ color: 0x334466, size: 0.08 })))

  // Click handler
  renderer.domElement.addEventListener('click', onCanvasClick)
  window.addEventListener('keydown', onKeyDown)
}

function renderGraph(mapData) {
  clearScene()

  const nodes = mapData.nodes || []
  const edges = mapData.edges || []
  if (nodes.length === 0) return

  // Compute layout
  const layoutPositions = forceLayout(nodes, edges, 80)

  // Build dep count lookup
  const depCount = {}
  for (const e of edges) {
    depCount[e.source] = (depCount[e.source] || 0) + 1
  }

  const sphereGeo = new THREE.SphereGeometry(1, 32, 32)

  // Create node meshes
  nodeMeshes = nodes.map((n, i) => {
    const pos = layoutPositions[i]
    const layer = (n.layer || 'unknown').toLowerCase()
    const color = LAYER_COLORS[layer] || LAYER_COLORS['unknown']
    const importance = Math.max(0.4, Math.min(1.8, ((n.methods || 1) + (depCount[n.id] || 0) * 1.5) / 20))

    const material = new THREE.MeshStandardMaterial({
      color,
      roughness: 0.35,
      metalness: 0.2,
      emissive: color,
      emissiveIntensity: 0.2
    })
    const mesh = new THREE.Mesh(sphereGeo, material)
    mesh.scale.setScalar(importance)
    mesh.position.set(pos.x, pos.y, pos.z)
    mesh.userData = {
      id: n.id, label: n.label, layer: n.layer, methods: n.methods || 0,
      lineCount: n.lineCount || 0, depCount: depCount[n.id] || 0, originalColor: color
    }
    scene.add(mesh)

    // Glow ring
    const ringGeo = new THREE.TorusGeometry(1.05, 0.06, 16, 32)
    const ringMat = new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.35 })
    const ring = new THREE.Mesh(ringGeo, ringMat)
    mesh.add(ring)

    return mesh
  })

  // Create edges
  const nodeMap = {}
  nodeMeshes.forEach(m => { nodeMap[m.userData.id] = m })

  edgeLines = edges.map(e => {
    const src = nodeMap[e.source], tgt = nodeMap[e.target]
    if (!src || !tgt) return null
    const points = [src.position.clone(), tgt.position.clone()]
    const geom = new THREE.BufferGeometry().setFromPoints(points)
    const line = new THREE.Line(geom, new THREE.LineBasicMaterial({
      color: 0x334466, transparent: true, opacity: 0.4
    }))
    line.userData = { source: e.source, target: e.target }
    scene.add(line)
    return line
  }).filter(Boolean)

  // Animate
  function animate() {
    animFrameId = requestAnimationFrame(animate)
    controls.update()
    renderer.render(scene, camera)
  }
  animate()
}

function clearScene() {
  if (animFrameId) { cancelAnimationFrame(animFrameId); animFrameId = null }
  nodeMeshes.forEach(m => {
    m.children.forEach(c => { if (c.geometry) c.geometry.dispose(); if (c.material) c.material.dispose() })
    m.geometry && m.geometry.dispose(); m.material && m.material.dispose()
  })
  edgeLines.forEach(l => { l.geometry.dispose(); l.material.dispose() })
  nodeMeshes.forEach(m => scene.remove(m))
  edgeLines.forEach(l => scene.remove(l))
  nodeMeshes = []; edgeLines = []
}

// ---- 交互 ----
function onCanvasClick(event) {
  if (!renderer) return
  const rect = renderer.domElement.getBoundingClientRect()
  const mouse = new THREE.Vector2(
    ((event.clientX - rect.left) / rect.width) * 2 - 1,
    -((event.clientY - rect.top) / rect.height) * 2 + 1
  )
  raycaster.setFromCamera(mouse, camera)
  const intersects = raycaster.intersectObjects(nodeMeshes)
  if (intersects.length > 0) {
    const obj = intersects[0].object
    highlightNode(obj)
    selectedNode.value = obj.userData
  } else {
    resetHighlight()
    selectedNode.value = null
  }
}

function highlightNode(mesh) {
  const neighbors = new Set()
  neighbors.add(mesh.userData.id)
  edgeLines.forEach(l => {
    if (l.userData.source === mesh.userData.id) neighbors.add(l.userData.target)
    if (l.userData.target === mesh.userData.id) neighbors.add(l.userData.source)
  })
  nodeMeshes.forEach(m => {
    const isNeighbor = neighbors.has(m.userData.id)
    m.material.opacity = isNeighbor ? 1 : 0.12
    m.material.transparent = true
    m.material.emissiveIntensity = isNeighbor ? 0.6 : 0
  })
  edgeLines.forEach(l => {
    const connected = l.userData.source === mesh.userData.id || l.userData.target === mesh.userData.id
    l.material.opacity = connected ? 0.9 : 0.03
  })
}

function resetHighlight() {
  nodeMeshes.forEach(m => {
    m.material.opacity = 1
    m.material.transparent = false
    m.material.emissiveIntensity = 0.2
    m.material.color.set(m.userData.originalColor)
  })
  edgeLines.forEach(l => { l.material.opacity = 0.4 })
}

function onKeyDown(e) {
  if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
    e.preventDefault()
    const input = wrapperRef.value?.querySelector('input')
    input?.focus()
  }
}

function onSearchSelect(val) {
  const mesh = nodeMeshes.find(m => m.userData.label === val)
  if (!mesh) return
  // Fly camera to node
  const pos = mesh.position
  controls.target.copy(pos)
  const dist = 5
  camera.position.set(pos.x + dist, pos.y + dist * 0.6, pos.z + dist)
  controls.update()
  highlightNode(mesh)
  selectedNode.value = mesh.userData
}

function toggleHeatmap(checked) {
  if (checked) {
    const maxLines = Math.max(1, ...nodeMeshes.map(m => m.userData.lineCount))
    nodeMeshes.forEach(m => {
      const ratio = m.userData.lineCount / maxLines
      const r = ratio < 0.5 ? ratio * 2 : 1
      const g = ratio < 0.5 ? 1 : 2 - ratio * 2
      m.material.color.setRGB(r, g, 0.15)
      m.material.emissive.setRGB(r * 0.3, g * 0.3, 0.05)
    })
  } else {
    nodeMeshes.forEach(m => {
      m.material.color.set(m.userData.originalColor)
      m.material.emissive.set(m.userData.originalColor)
    })
  }
}

// ---- 生命周期 ----
async function fetchData() {
  loading.value = true; error.value = null; isEmpty.value = false
  try {
    const res = await api.get(`/projects/${props.projectId}/map`)
    const mapData = res.data.data
    if (!mapData || !mapData.nodes || mapData.nodes.length === 0) {
      isEmpty.value = true; return
    }
    ready.value = true
    await nextTick()
    initScene(canvasRef.value)
    renderGraph(mapData)
  } catch (e) {
    error.value = e.response?.data?.message || '获取图谱数据失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchData()
  resizeObserver = new ResizeObserver(() => {
    if (!renderer || !canvasRef.value) return
    const w = canvasRef.value.clientWidth, h = canvasRef.value.clientHeight
    renderer.setSize(w, h)
    camera.aspect = w / h
    camera.updateProjectionMatrix()
  })
  if (canvasRef.value) resizeObserver.observe(canvasRef.value)
})

watch(() => props.projectId, () => {
  clearScene()
  selectedNode.value = null
  fetchData()
})

onBeforeUnmount(() => {
  clearScene()
  if (renderer) { renderer.dispose(); renderer = null }
  if (resizeObserver) { resizeObserver.disconnect(); resizeObserver = null }
  window.removeEventListener('keydown', onKeyDown)
  scene = null; camera = null; controls = null
})
</script>

<style scoped>
.map3d-wrapper {
  position: relative;
  width: 100%;
  min-height: 550px;
  height: calc(100vh - 200px);
  background: #0f1922;
  border-radius: 8px;
  overflow: hidden;
}
.map3d-overlay {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100%;
  background: #fff;
}
.map3d-canvas {
  width: 100%;
  height: 100%;
}
.map3d-search {
  position: absolute;
  top: 16px;
  left: 16px;
  z-index: 10;
}
.map3d-legend {
  position: absolute;
  bottom: 16px;
  left: 16px;
  background: rgba(15, 25, 34, 0.88);
  backdrop-filter: blur(8px);
  border-radius: 8px;
  padding: 12px 16px;
  z-index: 10;
  min-width: 150px;
}
.legend-title { color: #ccc; font-size: 12px; font-weight: 600; margin-bottom: 6px; }
.legend-row { display: flex; align-items: center; gap: 6px; color: #aaa; font-size: 11px; margin: 3px 0; }
.legend-dot { width: 8px; height: 8px; border-radius: 2px; flex-shrink: 0; }
.map3d-info-panel {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 240px;
  background: rgba(15, 25, 34, 0.9);
  backdrop-filter: blur(8px);
  border-radius: 8px;
  padding: 16px;
  z-index: 10;
  color: #ccc;
}
.panel-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.panel-header h4 { color: #fff; margin: 0; font-size: 14px; }
.info-row { display: flex; justify-content: space-between; align-items: center; margin: 6px 0; font-size: 12px; }
.info-row span { color: #999; }
.info-row code { font-size: 10px; color: #667eea; max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.map3d-hint {
  position: absolute;
  bottom: 16px;
  right: 16px;
  color: rgba(255,255,255,0.3);
  font-size: 11px;
  z-index: 5;
}
.slide-enter-active, .slide-leave-active { transition: transform 0.25s ease, opacity 0.25s ease; }
.slide-enter-from, .slide-leave-to { transform: translateX(20px); opacity: 0; }
</style>
