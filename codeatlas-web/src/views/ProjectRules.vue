<template>
  <div class="rules-page">
    <!-- 加载态 -->
    <a-skeleton v-if="loading" active :paragraph="{ rows: 6 }" />

    <!-- 错误态 -->
    <div v-else-if="error" class="state-wrap">
      <a-result status="error" title="加载失败" :sub-title="error">
        <template #extra><a-button type="primary" @click="fetchRules">重试</a-button></template>
      </a-result>
    </div>

    <!-- 空状态 -->
    <div v-else-if="rules.length === 0" class="state-wrap">
      <a-empty description="暂无宪法规则" />
    </div>

    <!-- 规则表格 -->
    <div v-else class="rules-card">
      <div class="card-header">
        <h3 class="card-title">架构宪法规则</h3>
        <span class="card-count">{{ rules.length }} 条规则</span>
      </div>
      <a-table :data-source="rules" :pagination="false" row-key="id" size="middle">
        <a-table-column title="规则名称" data-index="name" :width="220">
          <template #default="{ record }">
            <span :class="['rule-name', { disabled: !record.isEnabled }]">{{ record.name }}</span>
          </template>
        </a-table-column>
        <a-table-column title="描述" data-index="description" ellipsis />
        <a-table-column title="分类" data-index="category" :width="120">
          <template #default="{ record }">
            <a-tag :color="categoryColor(record.category)">{{ categoryLabel(record.category) }}</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="严重度" data-index="severity" :width="100">
          <template #default="{ record }">
            <a-badge :status="severityBadge(record.severity)" :text="record.severity" />
          </template>
        </a-table-column>
        <a-table-column title="启用" data-index="isEnabled" :width="80">
          <template #default="{ record }">
            <a-switch :checked="record.isEnabled" size="small"
              @change="(val) => toggleRule(record.id, val)" />
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
const rules = ref([])

onMounted(() => fetchRules())

async function fetchRules() {
  loading.value = true; error.value = null
  try {
    const res = await api.get(`/projects/${projectId.value}/rules`, { params: { enabledOnly: false } })
    rules.value = res.data.data || []
  } catch (e) {
    error.value = e.response?.data?.message || '加载宪法规则失败'
  } finally {
    loading.value = false
  }
}

async function toggleRule(ruleId, enabled) {
  try {
    await api.put(`/projects/${projectId.value}/rules/${ruleId}/toggle`, null, { params: { enabled } })
    const rule = rules.value.find(r => r.id === ruleId)
    if (rule) rule.isEnabled = enabled
    message.success(enabled ? '规则已启用' : '规则已禁用')
  } catch (e) {
    message.error('操作失败')
  }
}

function categoryColor(cat) {
  const map = { 'DEPENDENCY': 'red', 'STRUCTURE': 'blue', 'NAMING': 'green' }
  return map[cat] || 'default'
}

function categoryLabel(cat) {
  const map = { 'DEPENDENCY': '依赖', 'STRUCTURE': '结构', 'NAMING': '命名' }
  return map[cat] || cat
}

function severityBadge(sev) {
  const map = { 'BLOCKER': 'error', 'ERROR': 'processing', 'WARN': 'warning', 'INFO': 'default' }
  return map[sev] || 'default'
}
</script>

<style scoped>
.rules-page { padding: 0; }
.state-wrap { padding: 40px 0; }

.rules-card {
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

.card-count {
  font-size: 13px;
  color: #999;
}

.rule-name.disabled {
  color: #bbb;
  text-decoration: line-through;
}
</style>
