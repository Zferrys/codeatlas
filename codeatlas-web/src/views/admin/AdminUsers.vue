<template>
  <div class="admin-users">
    <a-page-header title="用户管理" sub-title="管理系统用户、角色和状态">
      <template #extra>
        <a-button type="primary" @click="loadUsers">
          <ReloadOutlined /> 刷新
        </a-button>
      </template>
    </a-page-header>

    <a-card :bordered="false" style="margin-top:16px">
      <a-table
        :columns="columns"
        :data-source="users"
        :loading="loading"
        :pagination="pagination"
        row-key="id"
        @change="onTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'role'">
            <a-select
              :value="record.role"
              size="small"
              style="width:110px"
              @change="(val) => handleRoleChange(record, val)"
            >
              <a-select-option value="ADMIN">管理员</a-select-option>
              <a-select-option value="ARCHITECT">架构师</a-select-option>
              <a-select-option value="DEVELOPER">开发者</a-select-option>
              <a-select-option value="VIEWER">观察者</a-select-option>
            </a-select>
          </template>
          <template v-else-if="column.key === 'status'">
            <a-switch
              :checked="record.status === 1"
              checked-children="正常"
              un-checked-children="禁用"
              @change="(val) => handleStatusChange(record, val)"
            />
          </template>
          <template v-else-if="column.key === 'createdAt'">
            {{ formatDate(record.createdAt) }}
          </template>
          <template v-else-if="column.key === 'lastLoginAt'">
            {{ record.lastLoginAt ? formatDate(record.lastLoginAt) : '从未登录' }}
          </template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import api from '../../api'

const loading = ref(false)
const users = ref([])

const pagination = reactive({
  current: 1,
  pageSize: 20,
  total: 0,
  showSizeChanger: true,
  showTotal: (total) => `共 ${total} 条`
})

const columns = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 70 },
  { title: '用户名', dataIndex: 'username', key: 'username' },
  { title: '邮箱', dataIndex: 'email', key: 'email' },
  { title: '角色', key: 'role', width: 130 },
  { title: '状态', key: 'status', width: 90 },
  { title: '创建时间', key: 'createdAt', width: 170 },
  { title: '最后登录', key: 'lastLoginAt', width: 170 }
]

function formatDate(val) {
  if (!val) return '-'
  const d = new Date(val)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function loadUsers() {
  loading.value = true
  try {
    const res = await api.get('/admin/users', {
      params: { page: pagination.current, size: pagination.pageSize }
    })
    const data = res.data.data
    users.value = data.records || []
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
  loadUsers()
}

async function handleRoleChange(record, newRole) {
  try {
    await api.put(`/admin/users/${record.id}/role`, null, {
      params: { role: newRole }
    })
    record.role = newRole
    message.success(`用户 ${record.username} 角色已更新为 ${newRole}`)
  } catch (e) {
    // error handled by interceptor
  }
}

async function handleStatusChange(record, checked) {
  const newStatus = checked ? 1 : 0
  try {
    await api.put(`/admin/users/${record.id}/status`, null, {
      params: { status: newStatus }
    })
    record.status = newStatus
    message.success(`用户 ${record.username} 已${checked ? '启用' : '禁用'}`)
  } catch (e) {
    // error handled by interceptor
  }
}

onMounted(() => {
  loadUsers()
})
</script>
