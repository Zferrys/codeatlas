<template>
  <div class="admin-audit-log">
    <a-page-header title="审计日志" sub-title="查看系统操作记录">
      <template #extra>
        <a-button type="primary" @click="loadLogs">
          <ReloadOutlined /> 刷新
        </a-button>
      </template>
    </a-page-header>

    <a-card :bordered="false" style="margin-top:16px">
      <a-table
        :columns="columns"
        :data-source="logs"
        :loading="loading"
        :pagination="pagination"
        row-key="id"
        @change="onTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'action'">
            <a-tag :color="actionColor(record.action)">{{ record.action }}</a-tag>
          </template>
          <template v-else-if="column.key === 'targetType'">
            <a-tag v-if="record.targetType">{{ record.targetType }}</a-tag>
            <span v-else>-</span>
          </template>
          <template v-else-if="column.key === 'createdAt'">
            {{ formatDate(record.createdAt) }}
          </template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import api from '../../api'

const loading = ref(false)
const logs = ref([])

const pagination = reactive({
  current: 1,
  pageSize: 20,
  total: 0,
  showSizeChanger: true,
  showTotal: (total) => `共 ${total} 条`
})

const columns = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 70 },
  { title: '用户名', dataIndex: 'username', key: 'username', width: 110 },
  { title: '操作', dataIndex: 'action', key: 'action', width: 160 },
  { title: '目标类型', dataIndex: 'targetType', key: 'targetType', width: 100 },
  { title: '目标ID', dataIndex: 'targetId', key: 'targetId', width: 80 },
  { title: '详情', dataIndex: 'detail', key: 'detail' },
  { title: 'IP地址', dataIndex: 'ipAddress', key: 'ipAddress', width: 140 },
  { title: '时间', dataIndex: 'createdAt', key: 'createdAt', width: 170 }
]

function actionColor(action) {
  const map = {
    'CREATE_PROJECT': 'green',
    'DELETE_PROJECT': 'red',
    'UPDATE_PROJECT': 'blue',
    'TRIGGER_SCAN': 'purple',
    'LOGIN': 'cyan',
    'REGISTER': 'geekblue'
  }
  return map[action] || 'default'
}

function formatDate(val) {
  if (!val) return '-'
  const d = new Date(val)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

async function loadLogs() {
  loading.value = true
  try {
    const res = await api.get('/admin/audit-log', {
      params: { page: pagination.current, size: pagination.pageSize }
    })
    const data = res.data.data
    logs.value = data.records || []
    pagination.total = data.total || 0
  } catch (e) {
    // error handled by interceptor
  } finally {
    loading.value = false
  }
}

function onTableChange(pag) {
  pagination.current = pag.current
  pagination.pageSize = pag.pageSize
  loadLogs()
}

onMounted(() => {
  loadLogs()
})
</script>
