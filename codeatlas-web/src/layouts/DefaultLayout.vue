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
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import {
  DashboardOutlined, AppstoreOutlined, AimOutlined, ReadOutlined,
  SafetyOutlined, WarningOutlined, BulbOutlined, SettingOutlined,
  MenuFoldOutlined, MenuUnfoldOutlined, BellOutlined,
  UserOutlined, LogoutOutlined, GithubOutlined
} from '@ant-design/icons-vue'

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

/* ---- 响应式 ---- */
@media (max-width: 768px) {
  .app-content { padding: 12px; }
  .user-name { display: none; }
}
</style>
