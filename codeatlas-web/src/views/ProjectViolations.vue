<template>
  <div class="violations-page">
    <!-- 加载态 -->
    <a-skeleton v-if="loading" active :paragraph="{ rows: 6 }" />

    <!-- 错误态 -->
    <div v-else-if="error" class="state-wrap">
      <a-result status="error" title="加载失败" :sub-title="error">
        <template #extra><a-button type="primary" @click="fetchViolations">重试</a-button></template>
      </a-result>
    </div>

    <!-- 空状态 -->
    <div v-else-if="violations.length === 0" class="state-wrap">
      <a-empty>
        <template #description>
          <span style="color: #52c41a; font-size: 15px;">恭喜！所有类均通过宪法规则检查</span>
        </template>
      </a-empty>
    </div>

    <!-- 违规表格 -->
    <div v-else class="violations-card">
      <div class="card-header">
        <h3 class="card-title">违规列表</h3>
        <a-space>
          <a-tag color="red">{{ blockerCount }} 阻断</a-tag>
          <a-tag color="orange">{{ errorCount }} 错误</a-tag>
          <a-tag color="gold">{{ warnCount }} 警告</a-tag>
        </a-space>
      </div>
      <a-table :data-source="violations" :pagination="{ pageSize: 20, showSizeChanger: true, showTotal: t => `共 ${t} 条` }"
        row-key="id" size="middle">
        <a-table-column title="严重度" data-index="severity" :width="90">
          <template #default="{ record }">
            <a-tag :color="severityColor(record.severity)">{{ severityLabel(record.severity) }}</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="类" data-index="classFqn" ellipsis>
          <template #default="{ record }">
            <code class="class-fqn">{{ shortName(record.classFqn) }}</code>
            <div class="class-fqn-full">{{ record.classFqn }}</div>
          </template>
        </a-table-column>
        <a-table-column title="违规说明" data-index="message" ellipsis />
        <a-table-column title="建议" data-index="suggestion" ellipsis />
        <a-table-column title="状态" data-index="isResolved" :width="90">
          <template #default="{ record }">
            <a-badge :status="record.isResolved ? 'success' : 'error'"
              :text="record.isResolved ? '已解决' : '未解决'" />
          </template>
        </a-table-column>
        <a-table-column title="操作" :width="90">
          <template #default="{ record }">
            <a-button v-if="!record.isResolved" type="link" size="small"
              @click="resolveViolation(record.id)">标记解决</a-button>
          </template>
        </a-table-column>
      </a-table>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import api from '../api'

const route = useRoute()
const projectId = computed(() => route.params.id)

const loading = ref(false)
const error = ref(null)
const violations = ref([])

const blockerCount = computed(() => violations.value.filter(v => v.severity === 'BLOCKER').length)
const errorCount = computed(() => violations.value.filter(v => v.severity === 'ERROR').length)
const warnCount = computed(() => violations.value.filter(v => v.severity === 'WARN').length)

onMounted(() => fetchViolations())

async function fetchViolations() {
  loading.value = true; error.value = null
  try {
    const res = await api.get(`/projects/${projectId.value}/violations`)
    violations.value = res.data.data || []
  } catch (e) {
    error.value = e.response?.data?.message || '加载违规列表失败'
  } finally {
    loading.value = false
  }
}

async function resolveViolation(id) {
  try {
    await api.put(`/projects/${projectId.value}/violations/${id}/resolve`)
    const v = violations.value.find(item => item.id === id)
    if (v) v.isResolved = true
    message.success('已标记为已解决')
  } catch (e) {
    message.error('操作失败')
  }
}

function severityColor(sev) {
  const map = { 'BLOCKER': 'red', 'ERROR': 'orange', 'WARN': 'gold', 'INFO': 'blue' }
  return map[sev] || 'default'
}

function severityLabel(sev) {
  const map = { 'BLOCKER': '阻断', 'ERROR': '错误', 'WARN': '警告', 'INFO': '建议' }
  return map[sev] || sev
}

function shortName(fqn) {
  if (!fqn) return ''
  const idx = fqn.lastIndexOf('.')
  return idx > 0 ? fqn.substring(idx + 1) : fqn
}
</script>

<style scoped>
.violations-page { padding: 0; }
.state-wrap { padding: 40px 0; }

.violations-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #1a1a2e;
  margin: 0;
}

.class-fqn {
  font-size: 13px;
  color: #667eea;
  background: #f5f5ff;
  padding: 1px 6px;
  border-radius: 3px;
}

.class-fqn-full {
  font-size: 11px;
  color: #999;
  margin-top: 2px;
}
</style>
