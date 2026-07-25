<template>
  <a-layout class="app-layout">
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
          <span>{{ $t('nav.workspace') }}</span>
        </a-menu-item>

        <a-menu-item-group v-if="authStore.user?.role === 'ADMIN'" :title="$t('nav.admin')">
          <a-menu-item key="admin-users">
            <template #icon><TeamOutlined /></template>
            <span>{{ $t('nav.userManagement') }}</span>
          </a-menu-item>
          <a-menu-item key="admin-audit-log">
            <template #icon><AuditOutlined /></template>
            <span>{{ $t('nav.auditLog') }}</span>
          </a-menu-item>
        </a-menu-item-group>

        <a-menu-item-group v-if="currentProjectId" :title="$t('nav.currentProject')">
          <a-menu-item key="overview">
            <template #icon><AppstoreOutlined /></template>
            <span>{{ $t('nav.overview') }}</span>
          </a-menu-item>
          <a-menu-item key="map">
            <template #icon><AimOutlined /></template>
            <span>{{ $t('nav.codeMap') }}</span>
          </a-menu-item>
          <a-menu-item key="story">
            <template #icon><ReadOutlined /></template>
            <span>{{ $t('nav.archStory') }}</span>
          </a-menu-item>
          <a-menu-item key="rules">
            <template #icon><SafetyOutlined /></template>
            <span>{{ $t('nav.rules') }}</span>
          </a-menu-item>
          <a-menu-item key="violations">
            <template #icon><WarningOutlined /></template>
            <span>{{ $t('nav.violations') }}</span>
          </a-menu-item>
          <a-menu-item key="insights">
            <template #icon><BulbOutlined /></template>
            <span>{{ $t('nav.insights') }}</span>
          </a-menu-item>
          <a-menu-item key="settings">
            <template #icon><SettingOutlined /></template>
            <span>{{ $t('nav.settings') }}</span>
          </a-menu-item>
        </a-menu-item-group>
      </a-menu>

      <div class="sider-footer" v-show="!collapsed">
        <span class="version-tag">v0.1.0</span>
      </div>
    </a-layout-sider>

    <a-layout class="app-main">
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
          <a-tooltip :title="$t('nav.globalSearch')">
            <a-button type="text" class="header-btn search-trigger" @click="showSearch = true">
              <SearchOutlined />
              <span class="search-shortcut">Ctrl+K</span>
            </a-button>
          </a-tooltip>

          <a-tooltip :title="isDark ? $t('nav.switchLight') : $t('nav.switchDark')">
            <a-button type="text" class="header-btn" @click="toggleTheme">
              <BulbOutlined />
            </a-button>
          </a-tooltip>

          <a-dropdown :trigger="['click']">
            <a-tooltip :title="$t('nav.notifications')">
              <a-badge :count="0" :dot="false">
                <a-button type="text" class="header-btn">
                  <BellOutlined />
                </a-button>
              </a-badge>
            </a-tooltip>
            <template #overlay>
              <a-menu style="width:280px">
                <div style="padding:12px 16px;border-bottom:1px solid var(--color-border-light)">
                  <span style="font-weight:600;font-size:14px;color:var(--color-text-primary)">{{ $t('nav.notifications') }}</span>
                </div>
                <div style="padding:24px 16px;text-align:center">
                  <a-empty :description="$t('nav.noNotifications')" :image-style="{ height: '40px' }" />
                </div>
              </a-menu>
            </template>
          </a-dropdown>

          <a-dropdown :trigger="['click']">
            <a-avatar style="background:#1677ff;cursor:pointer;margin-left:8px" :size="32">
              {{ userInitial }}
            </a-avatar>
            <template #overlay>
              <a-menu @click="onUserMenuClick">
                <a-menu-item key="profile">
                  <UserOutlined /> {{ $t('nav.profile') }}
                </a-menu-item>
                <a-menu-divider />
                <a-menu-item key="logout" danger>
                  <LogoutOutlined /> {{ $t('nav.logout') }}
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
          <span class="user-name" v-if="!collapsed || !isMobile">{{ authStore.user?.username }}</span>
        </div>
      </a-layout-header>

      <a-layout-content class="app-content">
        <slot />
      </a-layout-content>

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
            :placeholder="$t('search.placeholder')"
            allow-clear
            @input="onSearchInput"
            @keydown.esc="closeSearch"
          >
            <template #prefix><SearchOutlined /></template>
            <template #suffix>
              <a-tag color="processing" v-if="searching">{{ $t('search.searching') }}</a-tag>
            </template>
          </a-input>

          <div class="search-results" v-if="searchQuery.trim().length > 0">
            <a-empty
              v-if="!searching && searchResults.projects.length === 0 && searchResults.classes.length === 0"
              :description="$t('search.noResults')"
              :image-style="{ height: '40px' }"
            />

            <div class="search-group" v-if="searchResults.projects.length > 0">
              <div class="search-group-title">{{ $t('search.projectGroup') }}</div>
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

            <div class="search-group" v-if="searchResults.classes.length > 0">
              <div class="search-group-title">{{ $t('search.classGroup') }}</div>
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

      <a-layout-footer class="app-footer">
        <span>{{ $t('brand.footer') }}</span>
        <a href="https://github.com/zferrys/codeatlas" target="_blank" style="color:#1677ff;margin-left:12px">
          <GithubOutlined /> GitHub
        </a>
      </a-layout-footer>
    </a-layout>
  </a-layout>
</template>

<script setup>
import { ref, reactive, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import {
  DashboardOutlined, AppstoreOutlined, AimOutlined, ReadOutlined,
  SafetyOutlined, WarningOutlined, BulbOutlined, SettingOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined, BellOutlined, SearchOutlined,
  UserOutlined, LogoutOutlined, GithubOutlined, ProjectOutlined, FileTextOutlined,
  TeamOutlined, AuditOutlined
} from '@ant-design/icons-vue'
import api from '../api'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const { t } = useI18n()

const collapsed = ref(false)
const isMobile = ref(false)
const isDark = ref(false)

function initTheme() {
  const saved = localStorage.getItem('codeatlas_theme')
  if (saved === 'dark') {
    isDark.value = true
    document.documentElement.setAttribute('data-theme', 'dark')
  } else if (saved === 'light') {
    isDark.value = false
    document.documentElement.setAttribute('data-theme', 'light')
  }
}
initTheme()

const selectedKeys = ref(['dashboard'])

const userInitial = computed(() => {
  return (authStore.user?.username || 'U')[0].toUpperCase()
})

const currentProjectId = computed(() => route.params.id)

const breadcrumbs = computed(() => {
  const items = []
  const pathParts = route.path.split('/').filter(Boolean)

  if (pathParts[0] === 'dashboard') {
    items.push({ label: t('nav.workspace') })
  } else if (pathParts[0] === 'admin') {
    items.push({ label: t('nav.workspace'), path: '/dashboard' })
    items.push({ label: t('nav.admin') })
    const adminTabMap = { users: t('nav.userManagement'), 'audit-log': t('nav.auditLog') }
    if (pathParts.length >= 2) {
      items.push({ label: adminTabMap[pathParts[1]] || pathParts[1] })
    }
  } else if (pathParts[0] === 'project') {
    items.push({ label: t('nav.workspace'), path: '/dashboard' })
    if (pathParts.length >= 2) {
      items.push({ label: `Project #${pathParts[1]}` })
    }
    if (pathParts.length >= 3) {
      const tabMap = {
        overview: t('nav.overview'), map: t('nav.codeMap'), story: t('nav.archStory'),
        rules: t('nav.rules'), violations: t('nav.violations'), insights: t('nav.insights'),
        settings: t('nav.settings')
      }
      items.push({ label: tabMap[pathParts[2]] || pathParts[2] })
    }
  }

  return items.length ? items : [{ label: t('nav.workspace') }]
})

watch(() => route.name, (name) => {
  if (!name) return
  if (name === 'Dashboard') {
    selectedKeys.value = ['dashboard']
  } else if (name === 'AdminUsers') {
    selectedKeys.value = ['admin-users']
  } else if (name === 'AdminAuditLog') {
    selectedKeys.value = ['admin-audit-log']
  } else {
    const key = name.replace('Project', '').toLowerCase()
    selectedKeys.value = [key]
  }
}, { immediate: true })

// 防抖：500ms 内忽略重复点击
let lastMenuClickTime = 0

function onMenuClick({ key }) {
  const now = Date.now()
  if (now - lastMenuClickTime < 500) return
  lastMenuClickTime = now

  if (key === 'dashboard') {
    router.push('/dashboard')
  } else if (key === 'admin-users') {
    router.push('/admin/users')
  } else if (key === 'admin-audit-log') {
    router.push('/admin/audit-log')
  } else if (currentProjectId.value) {
    router.push(`/project/${currentProjectId.value}/${key}`)
  }
}

function onUserMenuClick({ key }) {
  if (key === 'profile') {
    router.push('/profile')
  } else if (key === 'logout') {
    authStore.logout()
    router.push('/login')
  }
}

function toggleTheme() {
  isDark.value = !isDark.value
  const theme = isDark.value ? 'dark' : 'light'
  document.documentElement.setAttribute('data-theme', theme)
  localStorage.setItem('codeatlas_theme', theme)
}

function onBreakpoint(broken) {
  isMobile.value = broken
  if (broken) collapsed.value = true
}

// ---- Global search ----
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
.logo-icon { color: #1677ff; display: flex; align-items: center; }
.logo-text { font-size: 18px; font-weight: 700; white-space: nowrap; letter-spacing: 0.5px; }
.sider-menu { border-right: none; margin-top: 8px; }
.sider-footer {
  position: absolute;
  bottom: 16px;
  left: 20px;
  right: 20px;
}
.version-tag { color: rgba(255,255,255,0.3); font-size: 12px; }

.app-main { background: var(--color-bg-body); }
.app-header {
  background: var(--color-bg-component);
  padding: 0 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  height: 56px;
  line-height: 56px;
  box-shadow: 0 1px 4px var(--color-shadow);
  z-index: 9;
}
.header-left { display: flex; align-items: center; gap: 12px; }
.header-right { display: flex; align-items: center; gap: 4px; }
.trigger-icon { font-size: 18px; cursor: pointer; color: var(--color-text-secondary); padding: 4px; }
.trigger-icon:hover { color: var(--color-primary); }
.header-breadcrumb { font-size: 14px; }
.header-btn { font-size: 18px; color: var(--color-text-secondary); }
.header-btn:hover { color: var(--color-primary); }
.user-name { color: var(--color-text-primary); font-size: 13px; margin-left: 8px; }

.app-content {
  padding: 20px 24px;
  min-height: calc(100vh - 56px - 48px);
}

.app-footer {
  text-align: center;
  height: 48px;
  padding: 0 24px;
  line-height: 48px;
  color: var(--color-text-tertiary);
  font-size: 12px;
  background: var(--color-bg-component);
  border-top: 1px solid var(--color-border-light);
}

.search-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  height: 32px;
  border-radius: 6px;
  background: var(--color-bg-input);
  border: 1px solid var(--color-border);
  color: var(--color-text-tertiary);
  font-size: 13px;
  transition: all 0.2s;
}

.search-trigger:hover {
  background: var(--color-bg-component-hover);
  border-color: var(--color-text-placeholder);
  color: var(--color-primary);
}

.search-shortcut {
  font-size: 11px;
  color: var(--color-text-placeholder);
  background: var(--color-bg-component);
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
  color: var(--color-text-tertiary);
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
  background: var(--color-bg-input);
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
  color: var(--color-text-primary);
}

.search-item-desc {
  font-size: 12px;
  color: var(--color-text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

@media (max-width: 768px) {
  .app-content { padding: 12px; }
  .user-name { display: none; }
  .search-shortcut { display: none; }
}
</style>
