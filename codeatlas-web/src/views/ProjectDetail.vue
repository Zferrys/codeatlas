<template>
  <a-layout class="project-layout">
    <a-layout-header class="project-header">
      <a-breadcrumb>
        <a-breadcrumb-item><router-link to="/dashboard">项目</router-link></a-breadcrumb-item>
        <a-breadcrumb-item>{{ projectName }}</a-breadcrumb-item>
      </a-breadcrumb>
      <a-menu v-model:selectedKeys="currentTab" mode="horizontal" @click="onTabClick">
        <a-menu-item key="overview">概览</a-menu-item>
        <a-menu-item key="map">代码地图</a-menu-item>
        <a-menu-item key="story">架构叙事</a-menu-item>
        <a-menu-item key="rules">宪法规则</a-menu-item>
        <a-menu-item key="violations">违规列表</a-menu-item>
        <a-menu-item key="insights">AI 洞察</a-menu-item>
        <a-menu-item key="settings">设置</a-menu-item>
      </a-menu>
    </a-layout-header>
    <a-layout-content class="project-content">
      <router-view />
    </a-layout-content>
  </a-layout>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const projectName = ref('加载中...')

const tabKeyMap = {
  'overview': ['overview'],
  'map': ['map'],
  'story': ['story'],
  'rules': ['rules'],
  'violations': ['violations'],
  'insights': ['insights'],
  'settings': ['settings']
}

const currentTab = ref([route.name?.replace('Project', '').toLowerCase() || 'overview'])

watch(() => route.name, (name) => {
  if (name) {
    const key = name.replace('Project', '').toLowerCase()
    currentTab.value = [key]
  }
})

function onTabClick({ key }) {
  const projectId = route.params.id
  router.push(`/project/${projectId}/${key}`)
}
</script>

<style scoped>
.project-layout { min-height: 100vh; background: #f0f2f5; }
.project-header { background: #fff; padding: 0 24px; }
.project-content { padding: 24px; }
</style>
