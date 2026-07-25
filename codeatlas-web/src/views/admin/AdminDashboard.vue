<template>
  <div class="admin-dashboard">
    <div class="page-header">
      <h2 class="page-title">系统概览</h2>
      <a-space>
        <a-tag v-if="status" color="green">在线</a-tag>
        <a-button size="small" @click="fetchStatus" :loading="loading">
          <template #icon><ReloadOutlined /></template>
          刷新
        </a-button>
        <span class="refresh-hint" v-if="autoRefreshing">每 30s 自动刷新</span>
      </a-space>
    </div>

    <a-spin :spinning="loading && !status" tip="加载中...">
      <a-row :gutter="16" v-if="status">
        <!-- 运行时长 -->
        <a-col :xs="24" :sm="12" :lg="6">
          <a-card class="stat-card" :bordered="false">
            <div class="stat-icon" style="background:#e6f4ff;color:#1677ff">
              <ClockCircleOutlined />
            </div>
            <div class="stat-content">
              <div class="stat-label">运行时长</div>
              <div class="stat-value">{{ status.uptimeFormatted || '--' }}</div>
              <div class="stat-desc" v-if="status.startedAt">自 {{ status.startedAt }}</div>
            </div>
          </a-card>
        </a-col>

        <!-- 项目总数 -->
        <a-col :xs="24" :sm="12" :lg="6">
          <a-card class="stat-card" :bordered="false">
            <div class="stat-icon" style="background:#e6f4ff;color:#1677ff">
              <ProjectOutlined />
            </div>
            <div class="stat-content">
              <div class="stat-label">项目总数</div>
              <div class="stat-value">{{ status.totalProjects }}</div>
            </div>
          </a-card>
        </a-col>

        <!-- 工作空间 -->
        <a-col :xs="24" :sm="12" :lg="6">
          <a-card class="stat-card" :bordered="false">
            <div class="stat-icon" style="background:#f6ffed;color:#52c41a">
              <FolderOutlined />
            </div>
            <div class="stat-content">
              <div class="stat-label">工作空间</div>
              <div class="stat-value">{{ status.workspaceSizeMb }} MB</div>
            </div>
          </a-card>
        </a-col>

        <!-- 清理记录 -->
        <a-col :xs="24" :sm="12" :lg="6">
          <a-card class="stat-card" :bordered="false">
            <div class="stat-icon" style="background:#fff7e6;color:#fa8c16">
              <DeleteOutlined />
            </div>
            <div class="stat-content">
              <div class="stat-label">累计清理</div>
              <div class="stat-value">{{ status.lastCleanupFreedMb }} MB / {{ status.lastCleanupFileCount }} 文件</div>
              <div class="stat-desc" v-if="status.lastCleanupTime !== 'never'">最近: {{ status.lastCleanupTime }}</div>
            </div>
          </a-card>
        </a-col>

        <!-- 磁盘使用率 -->
        <a-col :xs="24" :lg="12">
          <a-card title="磁盘使用率" :bordered="false" class="usage-card">
            <div class="usage-body">
              <a-progress
                type="circle"
                :percent="100 - status.diskFreePercent"
                :status="diskStatus"
                :width="140"
              />
              <div class="usage-detail">
                <div class="usage-row">
                  <span class="usage-label">总容量</span>
                  <span class="usage-value">{{ status.diskTotalGb }} GB</span>
                </div>
                <div class="usage-row">
                  <span class="usage-label">已用</span>
                  <span class="usage-value" :style="{ color: diskUsedColor }">
                    {{ (status.diskTotalGb - status.diskFreeGb).toFixed(1) }} GB
                  </span>
                </div>
                <div class="usage-row">
                  <span class="usage-label">可用</span>
                  <span class="usage-value green">{{ status.diskFreeGb }} GB</span>
                </div>
                <div class="usage-row">
                  <span class="usage-label">可用占比</span>
                  <span class="usage-value" :style="{ color: diskStatusColor }">{{ status.diskFreePercent }}%</span>
                </div>
              </div>
            </div>
          </a-card>
        </a-col>

        <!-- 内存使用率 -->
        <a-col :xs="24" :lg="12">
          <a-card title="JVM 堆内存" :bordered="false" class="usage-card">
            <div class="usage-body">
              <a-progress
                type="circle"
                :percent="status.heapUsedPercent"
                :status="heapStatus"
                :width="140"
              />
              <div class="usage-detail">
                <div class="usage-row">
                  <span class="usage-label">最大堆</span>
                  <span class="usage-value">{{ status.heapMaxMb }} MB</span>
                </div>
                <div class="usage-row">
                  <span class="usage-label">已分配</span>
                  <span class="usage-value">{{ status.heapTotalMb }} MB</span>
                </div>
                <div class="usage-row">
                  <span class="usage-label">空闲</span>
                  <span class="usage-value green">{{ status.heapFreeMb }} MB</span>
                </div>
                <div class="usage-row">
                  <span class="usage-label">已用</span>
                  <span class="usage-value" :style="{ color: heapUsedColor }">
                    {{ (status.heapTotalMb - status.heapFreeMb).toFixed(0) }} MB ({{ status.heapUsedPercent }}%)
                  </span>
                </div>
              </div>
            </div>
          </a-card>
        </a-col>

        <!-- 数据源总览 -->
        <a-col :span="24">
          <a-card title="数据源状态" :bordered="false">
            <a-row :gutter="16">
              <a-col :span="8">
                <div class="ds-item">
                  <div class="ds-dot green"></div>
                  <div>
                    <div class="ds-name">MySQL 5.7</div>
                    <div class="ds-host">127.0.0.1:3306 / codeatlas</div>
                  </div>
                </div>
              </a-col>
              <a-col :span="8">
                <div class="ds-item">
                  <div class="ds-dot green"></div>
                  <div>
                    <div class="ds-name">Redis 7</div>
                    <div class="ds-host">127.0.0.1:6379</div>
                  </div>
                </div>
              </a-col>
              <a-col :span="8">
                <div class="ds-item">
                  <div class="ds-dot green"></div>
                  <div>
                    <div class="ds-name">Neo4j 5</div>
                    <div class="ds-host">bolt://127.0.0.1:7687</div>
                  </div>
                </div>
              </a-col>
            </a-row>
          </a-card>
        </a-col>
      </a-row>
    </a-spin>

    <a-result
      v-if="!loading && error"
      status="error"
      title="加载失败"
      :sub-title="error"
    >
      <template #extra>
        <a-button type="primary" @click="fetchStatus">重试</a-button>
      </template>
    </a-result>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import {
  ClockCircleOutlined, ProjectOutlined, FolderOutlined,
  DeleteOutlined, ReloadOutlined
} from '@ant-design/icons-vue'
import api from '../../api'

const status = ref(null)
const loading = ref(false)
const error = ref(null)
let refreshTimer = null
const autoRefreshing = ref(true)

const diskStatus = computed(() => {
  if (!status.value) return 'normal'
  return status.value.diskFreePercent <= 10 ? 'exception'
    : status.value.diskFreePercent <= 20 ? 'active'
    : 'normal'
})

const diskStatusColor = computed(() => {
  if (!status.value) return 'inherit'
  return status.value.diskFreePercent <= 10 ? '#ff4d4f'
    : status.value.diskFreePercent <= 20 ? '#faad14'
    : '#52c41a'
})

const diskUsedColor = computed(() => {
  if (!status.value) return 'inherit'
  return (100 - status.value.diskFreePercent) > 80 ? '#ff4d4f' : 'inherit'
})

const heapStatus = computed(() => {
  if (!status.value) return 'normal'
  return status.value.heapUsedPercent >= 90 ? 'exception'
    : status.value.heapUsedPercent >= 70 ? 'active'
    : 'normal'
})

const heapUsedColor = computed(() => {
  if (!status.value) return 'inherit'
  return status.value.heapUsedPercent >= 90 ? '#ff4d4f'
    : status.value.heapUsedPercent >= 70 ? '#faad14'
    : 'inherit'
})

async function fetchStatus() {
  loading.value = true
  error.value = null
  try {
    const res = await api.get('/admin/system-status')
    status.value = res.data.data
  } catch (e) {
    error.value = e.response?.data?.message || '加载系统状态失败'
  } finally {
    loading.value = false
  }
}

function startAutoRefresh() {
  autoRefreshing.value = true
  refreshTimer = setInterval(() => {
    fetchStatus()
  }, 30000)
}

function stopAutoRefresh() {
  autoRefreshing.value = false
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

onMounted(() => {
  fetchStatus()
  startAutoRefresh()
})

onBeforeUnmount(() => {
  stopAutoRefresh()
})
</script>

<style scoped>
.admin-dashboard {
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.refresh-hint {
  font-size: 12px;
  color: var(--color-text-tertiary);
}

.stat-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.stat-card :deep(.ant-card-body) {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  flex-shrink: 0;
}

.stat-content {
  flex: 1;
  min-width: 0;
}

.stat-label {
  font-size: 13px;
  color: var(--color-text-tertiary);
  margin-bottom: 4px;
}

.stat-value {
  font-size: 18px;
  font-weight: 600;
  color: var(--color-text-primary);
}

.stat-desc {
  font-size: 12px;
  color: var(--color-text-tertiary);
  margin-top: 2px;
}

.usage-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.usage-body {
  display: flex;
  align-items: center;
  gap: 32px;
}

.usage-detail {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.usage-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 0;
  border-bottom: 1px dashed var(--color-border-light);
}

.usage-row:last-child {
  border-bottom: none;
}

.usage-label {
  font-size: 13px;
  color: var(--color-text-tertiary);
}

.usage-value {
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text-primary);
}

.usage-value.green {
  color: #52c41a;
}

.ds-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
}

.ds-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

.ds-dot.green {
  background: #52c41a;
  box-shadow: 0 0 6px rgba(82, 196, 26, 0.4);
}

.ds-name {
  font-size: 14px;
  font-weight: 500;
}

.ds-host {
  font-size: 12px;
  color: var(--color-text-tertiary);
}
</style>
