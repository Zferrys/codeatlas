<template>
  <ErrorBoundary>
    <AuthLayout v-if="isGuestRoute">
      <router-view />
    </AuthLayout>
    <DefaultLayout v-else-if="isAuthRoute">
      <router-view />
    </DefaultLayout>
    <router-view v-else />
  </ErrorBoundary>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import DefaultLayout from './layouts/DefaultLayout.vue'
import AuthLayout from './layouts/AuthLayout.vue'
import ErrorBoundary from './components/common/ErrorBoundary.vue'

const route = useRoute()

const isGuestRoute = computed(() => route.meta.guest)
const isAuthRoute = computed(() => route.meta.requiresAuth)
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
