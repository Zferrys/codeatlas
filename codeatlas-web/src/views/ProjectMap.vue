<template>
  <div class="map-page">
    <CodeMap3D :projectId="projectId" />
  </div>
</template>

<script setup>
import { useRoute } from 'vue-router'
import CodeMap3D from '../components/map/CodeMap3D.vue'

const route = useRoute()
const projectId = route.params.id
</script>

<style scoped>
.map-page {
  height: 100%;
}
</style>
