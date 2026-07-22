<template>
  <div class="page-placeholder">
    <a-card>
      <a-empty :description="description" />
    </a-card>
  </div>
</template>

<script setup>
const descriptions = {
  ProjectMap: '代码地图 — 即将在 Phase 1 实现',
  ProjectStory: '架构叙事 — AI 分析后将在此展示架构故事',
  ProjectRules: '宪法规则 — 管理你的架构治理规则',
  ProjectViolations: '违规列表 — 查看架构合规检查结果',
  ProjectInsights: 'AI 洞察 — 查看 AI 分析成果',
  ProjectSettings: '项目设置 — 管理项目配置和成员'
}
const description = descriptions['ProjectMap']
</script>
