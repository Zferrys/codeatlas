<template>
  <a-config-provider :theme="antTheme">
    <ErrorBoundary>
      <AuthLayout v-if="isGuestRoute">
        <router-view />
      </AuthLayout>
      <DefaultLayout v-else-if="isAuthRoute">
        <router-view />
      </DefaultLayout>
      <router-view v-else />
    </ErrorBoundary>
  </a-config-provider>
</template>

<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import DefaultLayout from './layouts/DefaultLayout.vue'
import AuthLayout from './layouts/AuthLayout.vue'
import ErrorBoundary from './components/common/ErrorBoundary.vue'
import defaultAlgorithm from 'ant-design-vue/es/theme/themes/default'
import darkAlgorithm from 'ant-design-vue/es/theme/themes/dark'

const route = useRoute()

const isDark = ref(false)
let observer = null

function syncTheme() {
  isDark.value = document.documentElement.getAttribute('data-theme') === 'dark'
}

onMounted(() => {
  syncTheme()
  observer = new MutationObserver(syncTheme)
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
})

onBeforeUnmount(() => {
  if (observer) observer.disconnect()
})

const isGuestRoute = computed(() => route.meta.guest)
const isAuthRoute = computed(() => route.meta.requiresAuth)

const antTheme = computed(() => ({
  algorithm: isDark.value ? darkAlgorithm : defaultAlgorithm
}))
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body, #app {
  height: 100%;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}
</style>
