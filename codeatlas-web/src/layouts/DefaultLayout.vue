<template>
  <a-layout class="app-layout">
    <!-- 左侧深色侧边栏 -->
    <a-layout-sider
      v-model:collapsed="collapsed"
      :trigger="null"
      collapsible
      width="220"
      class="app-sider"
      breakpoint="lg"
      @breakpoint="onBreakpoint"
    >
      <div class="sider-header">
        <router-link to="/dashboard" class="logo-link">
          <div class="logo-icon">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2"/>
              <path d="M12 2 L12 22 M2 12 L22 12" stroke="currentColor" stroke-width="1.5" opacity="0.5"/>
              <circle cx="12" cy="12" r="4" fill="currentColor" opacity="0.3"/>
            </svg>
          </div>
          <span v-show="!collapsed" class="logo-text">CodeAtlas</span>
        </router-link>
      </div>

      <a-menu
        v-model:selectedKeys="selectedKeys"
        theme="dark"
        mode="inline"
        class="sider-menu"
        @click="onMenuClick"
      >
        <a-menu-item key="dashboard">
          <template #icon><DashboardOutlined /></template>
          <span>工作台</span>
        </a-menu-item>

        <a-menu-item-group v-if="currentProjectId" title="当前项目">
          <a-menu-item key="overview">
            <template #icon><AppstoreOutlined /></template>
            <span>项目概览</span>
          </a-menu-item>
          <a-menu-item key="map">
            <template #icon><AimOutlined /></template>
            <span>代码地图</span>
          </a-menu-item>
          <a-menu-item key="story">
            <template #icon><ReadOutlined /></template>
            <span>架构叙事</span>
          </a-menu-item>
          <a-menu-item key="rules">
            <template #icon><SafetyOutlined /></template>
            <span>宪法规则</span>
          </a-menu-item>
          <a-menu-item key="violations">
            <template #icon><WarningOutlined /></template>
            <span>违规列表</span>
          </a-menu-item>
          <a-menu-item key="insights">
            <template #icon><BulbOutlined /></template>
            <span>AI 洞察</span>
          </a-menu-item>
          <a-menu-item key="settings">
            <template #icon><SettingOutlined /></template>
            <span>项目设置</span>
          </a-menu-item>
        </a-menu-item-group>
      </a-menu>

      <div class="sider-footer" v-show="!collapsed">
        <span class="version-tag">v0.1.0</span>
      </div>
    </a-layout-sider>

    <!-- 右侧主体 -->
    <a-layout class="app-main">
      <!-- 顶部栏 -->
      <a-layout-header class="app-header">
        <div class="header-left">
          <MenuFoldOutlined
            v-if="!collapsed"
            class="trigger-icon"
            @click="collapsed = true"
          />
          <MenuUnfoldOutlined
            v-else
            class="trigger-icon"
            @click="collapsed = false"
          />
          <a-breadcrumb class="header-breadcrumb">
            <a-breadcrumb-item v-for="item in breadcrumbs" :key="item.path">
              <router-link v-if="item.path" :to="item.path">{{ item.label }}</router-link>
              <span v-else>{{ item.label }}</span>
            </a-breadcrumb-item>
          </a-breadcrumb>
        </div>

        <div class="header-right">
          <!-- 全局搜索 -->
          <a-tooltip title="全局搜索 (Ctrl+K)">
            <a-button type="text" class="header-btn search-trigger" @click="showSearch = true">
              <SearchOutlined />
              <span class="search-shortcut">Ctrl+K</span>
            </a-button>
          </a-tooltip>

          <!-- 主题切换 -->
          <a-tooltip :title="isDark ? '切换亮色主题' : '切换暗色主题'">
            <a-button type="text" class="header-btn" @click="toggleTheme">
              <BulbOutlined />
            </a-button>
          </a-tooltip>

          <!-- 通知 -->
          <a-tooltip title="暂无新通知">
            <a-badge :count="5" :dot="true" :offset="[-2, 4]">
              <a-button type="text" class="header-btn">
                <BellOutlined />
              </a-button>
            </a-badge>
          </a-tooltip>

          <!-- 用户下拉 -->
          <a-dropdown :trigger="['click']">
            <a-avatar style="background:#667eea;cursor:pointer;margin-left:8px" :size="32">
              {{ userInitial }}
            </a-avatar>
            <template #overlay>
              <a-menu @click="onUserMenuClick">
                <a-menu-item key="profile">
                  <UserOutlined /> 个人信息
                </a-menu-item>
                <a-menu-divider />
                <a-menu-item key="logout" danger>
                  <LogoutOutlined /> 退出登录
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
          <span class="user-name" v-if="!collapsed || !isMobile">{{ authStore.user?.username }}</span>
        </div>
      </a-layout-header>

      <!-- 内容区 -->
      <a-layout-content class="app-content">
        <slot />
      </a-layout-content>

      <!-- 全局搜索弹窗 -->
      <a-modal
        v-model:open="showSearch"
        :footer="null"
        :closable="false"
        width="560px"
        wrap-class-name="search-modal-wrap"
        @cancel="closeSearch"
      >
        <div class="search-modal">
          <a-input
            ref="searchInputRef"
            v-model:value="searchQuery"
            size="large"
            placeholder="搜索项目或类名..."
            :prefix="SearchOutlined"
            allow-clear
            @input="onSearchInput"
            @keydown.esc="closeSearch"
          >
            <template #suffix>
              <a-tag color="processing" v-if="searching">搜索中...</a-tag>
            </template>
          </a-input>

          <div class="search-results" v-if="searchQuery.trim().length > 0">
            <!-- 无结果 -->
            <a-empty
              v-if="!searching && searchResults.projects.length === 0 && searchResults.classes.length === 0"
              description="未找到匹配结果"
              :image-style="{ height: '40px' }"
            />

            <!-- 项目结果 -->
            <div class="search-group" v-if="searchResults.projects.length > 0">
              <div class="search-group-title">项目</div>
              <div
                class="search-item"
                v-for="item in searchResults.projects"
                :key="'p-' + item.id"
                @click="goSearchResult(item)"
              >
                <ProjectOutlined class="search-item-icon" style="color:#1890ff" />
                <div class="search-item-body">
                  <span class="search-item-name">{{ item.name }}</span>
                  <span class="search-item-desc" v-if="item.description">{{ item.description }}</span>
                </div>
              </div>
            </div>

            <!-- 类结果 -->
            <div class="search-group" v-if="searchResults.classes.length > 0">
              <div class="search-group-title">类</div>
              <div
                class="search-item"
                v-for="item in searchResults.classes"
                :key="'c-' + item.id"
                @click="goSearchResult(item)"
              >
                <FileTextOutlined class="search-item-icon" style="color:#52c41a" />
                <div class="search-item-body">
                  <span class="search-item-name">{{ item.simpleName }}</span>
                  <span class="search-item-desc">{{ item.fqn }}</span>
                </div>
                <a-tag size="small" v-if="item.layer">{{ item.layer }}</a-tag>
              </div>
            </div>
          </div>
        </div>
      </a-modal>

      <!-- 全局页脚 -->
      <a-layout-footer class="app-footer">
        <span>CodeAtlas © 2026 — AI 驱动的代码地图与架构叙事平台</span>
        <a href="https://github.com/zferrys/codeatlas" target="_blank" style="color:#667eea;margin-left:12px">
          <GithubOutlined /> GitHub
        </a>
      </a-layout-footer>
    </a-layout>
  </a-layout>
</template>

<script setup>
import { ref, reactive, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import {
  DashboardOutlined, AppstoreOutlined, AimOutlined, ReadOutlined,
  SafetyOutlined, WarningOutlined, BulbOutlined, SettingOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined, BellOutlined, SearchOutlined,
  UserOutlined, LogoutOutlined, GithubOutlined, ProjectOutlined, FileTextOutlined
} from '@ant-design/icons-vue'
import api from '../api'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const collapsed = ref(false)
const isMobile = ref(false)
const isDark = ref(false)

const selectedKeys = ref(['dashboard'])

const userInitial = computed(() => {
  return (authStore.user?.username || 'U')[0].toUpperCase()
})

const currentProjectId = computed(() => route.params.id)

const breadcrumbs = computed(() => {
  const items = []
  const pathParts = route.path.split('/').filter(Boolean)

  if (pathParts[0] === 'dashboard') {
    items.push({ label: '工作台' })
  } else if (pathParts[0] === 'project') {
    items.push({ label: '工作台', path: '/dashboard' })
    if (pathParts.length >= 2) {
      items.push({ label: `项目 #${pathParts[1]}` })
    }
    if (pathParts.length >= 3) {
      const tabMap = {
        overview: '概览', map: '代码地图', story: '架构叙事',
        rules: '宪法规则', violations: '违规列表', insights: 'AI洞察',
        settings: '设置'
      }
      items.push({ label: tabMap[pathParts[2]] || pathParts[2] })
    }
  }

  return items.length ? items : [{ label: '工作台' }]
})

// 同步侧边栏选中项
watch(() => route.name, (name) => {
  if (!name) return
  if (name === 'Dashboard') {
    selectedKeys.value = ['dashboard']
  } else {
    const key = name.replace('Project', '').toLowerCase()
    selectedKeys.value = [key]
  }
}, { immediate: true })

function onMenuClick({ key }) {
  if (key === 'dashboard') {
    router.push('/dashboard')
  } else if (currentProjectId.value) {
    router.push(`/project/${currentProjectId.value}/${key}`)
  }
}

function onUserMenuClick({ key }) {
  if (key === 'logout') {
    authStore.logout()
    router.push('/login')
  }
}

function toggleTheme() {
  isDark.value = !isDark.value
  document.documentElement.setAttribute('data-theme', isDark.value ? 'dark' : 'light')
}

function onBreakpoint(broken) {
  isMobile.value = broken
  if (broken) collapsed.value = true
}

// ---- 全局搜索 ----
const showSearch = ref(false)
const searchQuery = ref('')
const searching = ref(false)
const searchInputRef = ref(null)
const searchResults = reactive({ projects: [], classes: [] })
let searchTimer = null

function onSearchInput() {
  if (searchTimer) clearTimeout(searchTimer)
  const q = searchQuery.value.trim()
  if (!q) {
    searchResults.projects = []
    searchResults.classes = []
    return
  }
  searchTimer = setTimeout(() => doSearch(q), 300)
}

async function doSearch(q) {
  searching.value = true
  try {
    const res = await api.get('/search', { params: { q, type: 'all' } })
    const data = res.data.data
    searchResults.projects = data?.projects || []
    searchResults.classes = data?.classes || []
  } catch (e) {
    searchResults.projects = []
    searchResults.classes = []
  } finally {
    searching.value = false
  }
}

function goSearchResult(item) {
  showSearch.value = false
  searchQuery.value = ''
  searchResults.projects = []
  searchResults.classes = []
  if (item.type === 'project') {
    router.push(`/project/${item.id}/overview`)
  } else if (item.type === 'class' && item.projectId) {
    router.push(`/project/${item.projectId}/map`)
  }
}

function closeSearch() {
  showSearch.value = false
  searchQuery.value = ''
  searchResults.projects = []
  searchResults.classes = []
}

function onGlobalKeydown(e) {
  if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
    e.preventDefault()
    showSearch.value = true
    nextTick(() => {
      searchInputRef.value?.focus()
    })
  }
}

onMounted(() => {
  document.addEventListener('keydown', onGlobalKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onGlobalKeydown)
})
</script>

<style scoped>
.app-layout { min-height: 100vh; }

/* ---- 侧边栏 ---- */
.app-sider {
  background: #001529 !important;
  box-shadow: 2px 0 8px rgba(0,0,0,0.15);
  z-index: 10;
}
.sider-header {
  height: 64px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  border-bottom: 1px solid rgba(255,255,255,0.08);
}
.logo-link {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #fff;
  text-decoration: none;
}
.logo-icon { color: #667eea; display: flex; align-items: center; }
.logo-text { font-size: 18px; font-weight: 700; white-space: nowrap; letter-spacing: 0.5px; }
.sider-menu { border-right: none; margin-top: 8px; }
.sider-footer {
  position: absolute;
  bottom: 16px;
  left: 20px;
  right: 20px;
}
.version-tag { color: rgba(255,255,255,0.3); font-size: 12px; }

/* ---- 顶部栏 ---- */
.app-main { background: #f0f2f5; }
.app-header {
  background: #fff;
  padding: 0 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  height: 56px;
  line-height: 56px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.04);
  z-index: 9;
}
.header-left { display: flex; align-items: center; gap: 12px; }
.header-right { display: flex; align-items: center; gap: 4px; }
.trigger-icon { font-size: 18px; cursor: pointer; color: #555; padding: 4px; }
.trigger-icon:hover { color: #667eea; }
.header-breadcrumb { font-size: 14px; }
.header-btn { font-size: 18px; color: #555; }
.header-btn:hover { color: #667eea; }
.user-name { color: #333; font-size: 13px; margin-left: 8px; }

/* ---- 内容区 ---- */
.app-content {
  padding: 20px 24px;
  min-height: calc(100vh - 56px - 48px);
}

/* ---- 页脚 ---- */
.app-footer {
  text-align: center;
  height: 48px;
  padding: 0 24px;
  line-height: 48px;
  color: #999;
  font-size: 12px;
  background: #fff;
  border-top: 1px solid #f0f0f0;
}

/* ---- 全局搜索 ---- */
.search-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  height: 32px;
  border-radius: 6px;
  background: #f5f5f5;
  border: 1px solid #e8e8e8;
  color: #999;
  font-size: 13px;
  transition: all 0.2s;
}

.search-trigger:hover {
  background: #ebebeb;
  border-color: #d9d9d9;
  color: #667eea;
}

.search-shortcut {
  font-size: 11px;
  color: #bbb;
  background: #eee;
  padding: 1px 6px;
  border-radius: 3px;
  margin-left: 4px;
}

.search-modal {
  padding-top: 4px;
}

.search-results {
  margin-top: 16px;
  max-height: 360px;
  overflow-y: auto;
}

.search-group {
  margin-bottom: 12px;
}

.search-group-title {
  font-size: 11px;
  font-weight: 600;
  color: #999;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  padding: 0 4px;
  margin-bottom: 6px;
}

.search-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}

.search-item:hover {
  background: #f0f2ff;
}

.search-item-icon {
  font-size: 18px;
  flex-shrink: 0;
}

.search-item-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.search-item-name {
  font-size: 14px;
  font-weight: 500;
  color: #1a1a2e;
}

.search-item-desc {
  font-size: 12px;
  color: #999;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ---- 响应式 ---- */
@media (max-width: 768px) {
  .app-content { padding: 12px; }
  .user-name { display: none; }
  .search-shortcut { display: none; }
}
</style>
