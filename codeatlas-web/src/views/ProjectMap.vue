<template>
  <div class="map-page-wrapper">
    <!-- 工具栏 -->
    <div class="map-toolbar">
      <a-space>
        <a-radio-group v-model:value="renderMode" size="small" button-style="solid">
          <a-radio-button value="2d">2D 力导向图</a-radio-button>
          <a-radio-button value="3d">3D 拓扑图</a-radio-button>
        </a-radio-group>
        <a-divider type="vertical" />
        <a-tooltip title="变更影响模拟">
          <a-button size="small" @click="showImpactModal = true" :disabled="!hasData">
            <template #icon><ThunderboltOutlined /></template>
            影响模拟
          </a-button>
        </a-tooltip>
        <a-tooltip title="对选中节点提问">
          <a-button size="small" @click="openQaPanel" :disabled="!selectedNode">
            <template #icon><QuestionCircleOutlined /></template>
            上下文问答
          </a-button>
        </a-tooltip>
      </a-space>
      <a-space>
        <a-input-search
          v-model:value="searchQuery"
          placeholder="搜索类名..."
          size="small"
          style="width:200px"
          @search="onSearch"
        />
      </a-space>
    </div>

    <!-- 地图画布 -->
    <div class="map-canvas-area">
      <CodeMap v-if="renderMode === '2d'" ref="codeMap2dRef" :projectId="projectId" @node-click="onNodeClick" />
      <CodeMap3D v-else :projectId="projectId" />
    </div>

    <!-- 节点详情抽屉 -->
    <a-drawer
      :open="drawerVisible"
      title="节点详情"
      placement="right"
      :width="420"
      @close="drawerVisible = false"
    >
      <template v-if="selectedNode">
        <a-descriptions :column="1" size="small" bordered>
          <a-descriptions-item label="类名">{{ selectedNode.label }}</a-descriptions-item>
          <a-descriptions-item label="全限定名">
            <span style="font-size:12px;word-break:break-all">{{ selectedNode.id }}</span>
          </a-descriptions-item>
          <a-descriptions-item label="分层">
            <a-tag :color="getLayerColor(selectedNode.layer)">{{ selectedNode.layer || 'UNKNOWN' }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="方法数">{{ selectedNode.methods || 0 }}</a-descriptions-item>
          <a-descriptions-item label="代码行数">{{ selectedNode.lineCount || 0 }}</a-descriptions-item>
          <a-descriptions-item label="分组">{{ selectedNode.group || '-' }}</a-descriptions-item>
        </a-descriptions>
        <a-divider />
        <a-space>
          <a-button type="primary" size="small" @click="openQaPanel">
            <QuestionCircleOutlined /> 对此类提问
          </a-button>
          <a-button size="small" @click="runImpactSim(selectedNode)">
            <ThunderboltOutlined /> 影响模拟
          </a-button>
        </a-space>
      </template>
      <a-empty v-else description="请点击地图节点查看详情" />
    </a-drawer>

    <!-- 变更影响模拟弹窗 -->
    <a-modal
      v-model:open="showImpactModal"
      title="变更影响模拟"
      :footer="null"
      width="640px"
      @cancel="impactResult = null"
    >
      <a-form layout="vertical" v-if="!impactResult">
        <a-form-item label="目标类" required>
          <a-input v-model:value="impactForm.targetClass" placeholder="例如: com.example.OrderService" />
        </a-form-item>
        <a-form-item label="变更类型">
          <a-select v-model:value="impactForm.changeType">
            <a-select-option value="MODIFY_METHOD">修改方法</a-select-option>
            <a-select-option value="ADD_FIELD">添加字段</a-select-option>
            <a-select-option value="REMOVE_CLASS">删除类</a-select-option>
            <a-select-option value="CHANGE_SIGNATURE">修改方法签名</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="目标方法">
          <a-input v-model:value="impactForm.targetMethod" placeholder="可选" />
        </a-form-item>
        <a-form-item label="变更描述">
          <a-textarea v-model:value="impactForm.description" placeholder="描述你打算做的修改" :rows="3" />
        </a-form-item>
        <a-button type="primary" @click="doImpactSim" :loading="impactLoading" block>
          {{ impactLoading ? '分析中...' : '开始分析' }}
        </a-button>
      </a-form>

      <div v-else class="impact-result">
        <a-descriptions :column="2" size="small" bordered>
          <a-descriptions-item label="目标类">{{ impactResult.targetClass }}</a-descriptions-item>
          <a-descriptions-item label="变更类型">{{ impactResult.changeType }}</a-descriptions-item>
          <a-descriptions-item label="影响类数">
            <a-tag :color="impactResult.totalImpacted > 10 ? 'red' : impactResult.totalImpacted > 5 ? 'orange' : 'green'">
              {{ impactResult.totalImpacted }}
            </a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="风险等级">
            <a-tag :color="impactResult.totalImpacted > 10 ? 'red' : impactResult.totalImpacted > 5 ? 'orange' : 'green'">
              {{ impactResult.totalImpacted > 10 ? 'HIGH' : impactResult.totalImpacted > 5 ? 'MEDIUM' : 'LOW' }}
            </a-tag>
          </a-descriptions-item>
        </a-descriptions>

        <h4 style="margin-top:16px">影响链路</h4>
        <div class="impact-paths" v-if="impactResult.impactPaths && impactResult.impactPaths.length > 0">
          <a-tag v-for="(p, i) in impactResult.impactPaths.slice(0, 20)" :key="i" style="margin:2px">
            {{ p.source }} → {{ p.target }}
            <a-badge :count="p.depth" :number-style="{ backgroundColor: '#667eea' }" style="margin-left:4px" />
          </a-tag>
        </div>
        <a-empty v-else description="未发现影响链路" :image-style="{ height: '40px' }" />

        <div v-if="impactResult.aiAnalysis" class="ai-analysis-box">
          <h4>AI 风险分析</h4>
          <div class="markdown-body" v-html="renderSimpleMd(impactResult.aiAnalysis)"></div>
        </div>

        <a-button style="margin-top:16px" @click="impactResult = null">重新分析</a-button>
      </div>
    </a-modal>

    <!-- 上下文问答弹窗 -->
    <a-modal
      v-model:open="showQaModal"
      title="上下文问答"
      :footer="null"
      width="600px"
    >
      <div class="qa-class-info" v-if="qaTarget">
        <a-tag :color="getLayerColor(qaTarget.layer)">{{ qaTarget.layer }}</a-tag>
        <strong>{{ qaTarget.label }}</strong>
        <span style="color:#999;font-size:12px;margin-left:8px">{{ qaTarget.id }}</span>
      </div>
      <a-textarea
        v-model:value="qaQuestion"
        placeholder="基于当前类的上下文提问，例如: 这个类的主要职责是什么？它有哪些直接依赖？"
        :rows="3"
        style="margin:12px 0"
      />
      <a-button type="primary" @click="doContextQa" :loading="qaLoading" block>
        {{ qaLoading ? 'AI 思考中...' : '提问' }}
      </a-button>
      <div v-if="qaAnswer" class="qa-answer-box">
        <div class="markdown-body" v-html="renderSimpleMd(qaAnswer)"></div>
      </div>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { ThunderboltOutlined, QuestionCircleOutlined } from '@ant-design/icons-vue'
import CodeMap from '../components/map/CodeMap.vue'
import CodeMap3D from '../components/map/CodeMap3D.vue'
import api from '../api'
import { marked } from 'marked'

const route = useRoute()
const projectId = route.params.id

const renderMode = ref('2d')
const searchQuery = ref('')
const codeMap2dRef = ref(null)
const hasData = ref(false)
const selectedNode = ref(null)
const drawerVisible = ref(false)

// ---- 节点点击事件 (from CodeMap 2D) ----
function onNodeClick(node) {
  selectedNode.value = node
  drawerVisible.value = true
  hasData.value = true
}

// ---- 影响模拟 ----
const showImpactModal = ref(false)
const impactLoading = ref(false)
const impactResult = ref(null)
const impactForm = reactive({
  targetClass: '',
  changeType: 'MODIFY_METHOD',
  targetMethod: '',
  description: ''
})

function runImpactSim(node) {
  impactForm.targetClass = node?.id || node?.label || ''
  showImpactModal.value = true
}

async function doImpactSim() {
  if (!impactForm.targetClass) {
    message.warning('请输入目标类')
    return
  }
  impactLoading.value = true
  try {
    const res = await api.post(`/projects/${projectId}/ai/impact-simulate`, impactForm)
    impactResult.value = res.data.data
  } catch (e) {
    message.error('影响分析失败: ' + (e.response?.data?.message || '网络错误'))
  } finally {
    impactLoading.value = false
  }
}

// ---- 上下文问答 ----
const showQaModal = ref(false)
const qaTarget = ref(null)
const qaQuestion = ref('')
const qaLoading = ref(false)
const qaAnswer = ref('')

function openQaPanel() {
  qaTarget.value = selectedNode.value
  qaQuestion.value = ''
  qaAnswer.value = ''
  showQaModal.value = true
}

async function doContextQa() {
  if (!qaQuestion.value.trim()) {
    message.warning('请输入问题')
    return
  }
  qaLoading.value = true
  try {
    const res = await api.post(`/projects/${projectId}/ai/context-qa`, {
      question: qaQuestion.value,
      classFqn: qaTarget.value?.id || null
    })
    qaAnswer.value = res.data.data?.answer || '未获取到回答'
  } catch (e) {
    message.error('问答失败: ' + (e.response?.data?.message || '网络错误'))
  } finally {
    qaLoading.value = false
  }
}

function onSearch(value) {
  // search handled by CodeMap component itself
}

function getLayerColor(layer) {
  const map = {
    CONTROLLER: '#1890ff', SERVICE: '#52c41a', REPOSITORY: '#fa8c16',
    DOMAIN: '#13c2c2', DTO: '#722ed1', CONFIG: '#f5222d',
    UTIL: '#8c8c8c', SECURITY: '#eb2f96', EXCEPTION: '#fa541c', FILTER: '#2f54eb'
  }
  return map[layer] || '#d9d9d9'
}

function renderSimpleMd(content) {
  if (!content) return ''
  return marked(content)
}
</script>

<style scoped>
.map-page-wrapper {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 140px);
  min-height: 600px;
}
.map-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 16px;
  background: var(--color-bg-component);
  border-bottom: 1px solid var(--color-border-light);
  border-radius: 8px 8px 0 0;
  flex-shrink: 0;
}
.map-canvas-area {
  flex: 1;
  overflow: hidden;
  position: relative;
  background: #fafafa;
  border-radius: 0 0 8px 8px;
}
.impact-result {
  max-height: 500px;
  overflow-y: auto;
}
.impact-paths {
  max-height: 200px;
  overflow-y: auto;
  margin-top: 8px;
}
.ai-analysis-box {
  margin-top: 16px;
  padding: 12px 16px;
  background: #f9faff;
  border: 1px solid #e0e4ff;
  border-radius: 8px;
}
.ai-analysis-box h4 {
  margin: 0 0 8px;
  font-size: 14px;
  color: #667eea;
}
.qa-class-info {
  padding: 8px 12px;
  background: #f5f5f5;
  border-radius: 6px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.qa-answer-box {
  margin-top: 16px;
  padding: 16px;
  background: #f9faff;
  border: 1px solid #e0e4ff;
  border-radius: 8px;
  max-height: 360px;
  overflow-y: auto;
}
.markdown-body { line-height: 1.7; font-size: 14px; color: #333; }
.markdown-body :deep(h4) { margin: 12px 0 4px; }
.markdown-body :deep(p) { margin: 4px 0; }
.markdown-body :deep(ul) { padding-left: 20px; }
</style>
