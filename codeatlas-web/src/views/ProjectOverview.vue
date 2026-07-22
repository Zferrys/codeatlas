<template>
  <div>
    <a-row :gutter="16">
      <a-col :span="6">
        <a-statistic title="健康度评分" :value="72.5" suffix="分" />
      </a-col>
      <a-col :span="6">
        <a-statistic title="总类数" :value="0" />
      </a-col>
      <a-col :span="6">
        <a-statistic title="违规数" :value="0" />
      </a-col>
      <a-col :span="6">
        <a-statistic title="最近扫描" value="暂无" />
      </a-col>
    </a-row>
    <a-card title="扫描历史" style="margin-top: 24px">
      <a-empty description="暂无扫描记录，请先触发分析" />
    </a-card>
  </div>
</template>

<script setup>
</script>
