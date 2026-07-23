<template>
  <div class="project-detail">
    <router-view />
  </div>
</template>

<script setup>
// Navigation is handled by DefaultLayout sidebar.
// This component is a thin wrapper that renders the active sub-route.
import { provide, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import api from '../api'

const route = useRoute()
const projectId = ref(route.params.id)
const projectName = ref('')

onMounted(async () => {
  try {
    const res = await api.get(`/projects/${projectId.value}`)
    projectName.value = res.data.data?.name || ''
  } catch (e) {
    // ignore
  }
})

provide('projectId', projectId)
provide('projectName', projectName)
</script>

<style scoped>
.project-detail {
  /* child views handle their own layout */
}
</style>
