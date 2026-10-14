import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import path from 'path'

// 独立于 vite.config.js 的测试配置：不污染开发/构建配置，
// alias 与 vite.config.js 保持一致（@ → src）
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  test: {
    // jsdom 而非 happy-dom：DOMPurify 官方只支持浏览器/jsdom，
    // happy-dom 下 sanitize 会把合法标签整体剥掉、XSS 断言全部假绿
    environment: 'jsdom',
    include: ['src/**/*.test.js'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json-summary', 'html'],
      include: ['src/**/*.{js,vue}'],
      clean: true
    }
  }
})
