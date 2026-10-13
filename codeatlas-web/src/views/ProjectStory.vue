<template>
  <div class="reports-page">
    <div class="reports-container">
      <a-tabs v-model:activeKey="activeTab" centered class="reports-tabs">
        <a-tab-pane key="ARCH_STORY" tab="架构解读" />
        <a-tab-pane key="PATTERN" tab="设计模式" />
        <a-tab-pane key="ANTI_PATTERN" tab="反模式" />
        <a-tab-pane key="CONSTITUTION_CHECK" tab="规约检查" />
      </a-tabs>
      <ReportPanel
        :key="activeTab"
        :project-id="projectId"
        :type="activeTab"
        :kind="activeMeta.kind"
        :title="activeMeta.title"
        :description="activeMeta.description"
        :auto-poll="activeTab === 'ARCH_STORY'"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import ReportPanel from '../components/ReportPanel.vue'

const route = useRoute()
const projectId = computed(() => route.params.id)

const activeTab = ref('ARCH_STORY')

const REPORT_META = {
  ARCH_STORY: {
    kind: 'story',
    title: '架构解读',
    description: 'AI 从整体视角讲述项目的架构故事：分层结构、关键模块、设计意图与演进方向。扫描完成后会自动生成。'
  },
  PATTERN: {
    kind: 'pattern',
    title: '设计模式',
    description: '识别项目采用的架构与设计模式（分层架构、MVC、事件驱动等），并说明识别依据。'
  },
  ANTI_PATTERN: {
    kind: 'anti-pattern',
    title: '反模式',
    description: '检测代码中的反模式（上帝类、循环依赖、重复代码等），给出位置与改进建议。'
  },
  CONSTITUTION_CHECK: {
    kind: 'constitution-check',
    title: '规约检查',
    description: '基于「宪法规则」页定义的规则逐条检查扫描结果，输出违规项与合规情况。'
  }
}

const activeMeta = computed(() => REPORT_META[activeTab.value])
</script>

<style scoped>
.reports-page { padding: 0; }
.reports-container { max-width: 960px; margin: 0 auto; }
.reports-tabs { margin-bottom: 8px; }
.reports-tabs :deep(.ant-tabs-nav) { margin-bottom: 16px; }
</style>
