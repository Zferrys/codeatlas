// Force-directed layout Web Worker for 3D graph
// Offloads O(n²) computation from the main thread to prevent UI jank

const REPULSION = 50
const SPRING_LEN = 8
const SPRING_K = 0.06
const DAMPING = 0.85

self.onmessage = function (e) {
  const { nodes, edges, iterations = 80 } = e.data

  const nodeIds = nodes.map(n => n.id)
  const nodeIdx = {}
  nodeIds.forEach((id, i) => { nodeIdx[id] = i })

  // Initialize random positions
  const positions = nodes.map(() => ({
    x: (Math.random() - 0.5) * 20,
    y: (Math.random() - 0.5) * 20,
    z: (Math.random() - 0.5) * 20
  }))

  // Progress reporting: emit every 10 iterations
  const reportInterval = 10

  for (let iter = 0; iter < iterations; iter++) {
    const forces = positions.map(() => ({ x: 0, y: 0, z: 0 }))

    // Repulsion between all pairs
    for (let i = 0; i < nodes.length; i++) {
      for (let j = i + 1; j < nodes.length; j++) {
        const dx = positions[i].x - positions[j].x
        const dy = positions[i].y - positions[j].y
        const dz = positions[i].z - positions[j].z
        const dist = Math.sqrt(dx * dx + dy * dy + dz * dz) + 0.01
        const f = REPULSION / (dist * dist)
        const fx = (dx / dist) * f
        const fy = (dy / dist) * f
        const fz = (dz / dist) * f
        forces[i].x += fx; forces[i].y += fy; forces[i].z += fz
        forces[j].x -= fx; forces[j].y -= fy; forces[j].z -= fz
      }
    }

    // Spring attraction along edges
    for (const e of edges) {
      const si = nodeIdx[e.source]
      const ti = nodeIdx[e.target]
      if (si == null || ti == null) continue
      const dx = positions[ti].x - positions[si].x
      const dy = positions[ti].y - positions[si].y
      const dz = positions[ti].z - positions[si].z
      const dist = Math.sqrt(dx * dx + dy * dy + dz * dz) + 0.01
      const f = (dist - SPRING_LEN) * SPRING_K
      const fx = (dx / dist) * f
      const fy = (dy / dist) * f
      const fz = (dz / dist) * f
      forces[si].x += fx; forces[si].y += fy; forces[si].z += fz
      forces[ti].x -= fx; forces[ti].y -= fy; forces[ti].z -= fz
    }

    // Apply forces with damping
    for (let i = 0; i < nodes.length; i++) {
      positions[i].x += forces[i].x * DAMPING
      positions[i].y += forces[i].y * DAMPING
      positions[i].z += forces[i].z * DAMPING
    }

    // Report progress periodically
    if ((iter + 1) % reportInterval === 0 || iter === iterations - 1) {
      self.postMessage({
        type: 'progress',
        iteration: iter + 1,
        total: iterations,
        positions: positions
      })
    }
  }

  // Send final result
  self.postMessage({
    type: 'complete',
    positions: positions
  })
}
