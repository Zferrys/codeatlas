<template>
  <div class="map-page-wrapper">
    <!-- 地图数据准备中提示 -->
    <a-alert
      v-if="mapPreparing"
      message="图谱数据正在准备中"
      description="Neo4j 正在导入依赖关系图，通常需要几十秒到几分钟。数据就绪后地图将自动显示。"
      type="info"
      show-icon
      closable
      style="margin: 0 0 12px 0; border-radius: 8px;"
    />

    <div class="map-toolbar">
      <a-space>
        <a-radio-group v-model:value="renderMode" size="small" button-style="solid">
          <a-radio-button value="2d">2D 力导向图</a-radio-button>
          <a-radio-button value="3d">3D 拓扑图</a-radio-button>
        </a-radio-group>
        <a-divider type="vertical" />
        <a-tooltip title="变更影响模拟">
          <a-button size="small" @click="openImpactModal" :disabled="!hasData">
            <template #icon><ThunderboltOutlined /></template>
            影响模拟
          </a-button>
        </a-tooltip>
        <a-tooltip title="上下文问答（选中类为精准问答，未选中则全局智能搜索）">
          <a-button size="small" @click="openQaPanel">
            <template #icon><QuestionCircleOutlined /></template>
            上下文问答
          </a-button>
        </a-tooltip>
        <a-divider type="vertical" />
        <a-checkbox v-model:checked="hideTest" size="small">隐藏测试类</a-checkbox>
        <a-checkbox v-model:checked="hideUtil" size="small">隐藏工具类</a-checkbox>
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

    <div class="map-canvas-area">
      <CodeMap v-if="renderMode === '2d'" ref="codeMap2dRef" :projectId="projectId"
        :hide-test="hideTest" :hide-util="hideUtil" :focus-fqn="focusFqn" :focus-nonce="focusNonce" @node-click="onNodeClick" />
      <CodeMap3D v-else :projectId="projectId"
        :hide-test="hideTest" :hide-util="hideUtil" :focus-fqn="focusFqn" :focus-nonce="focusNonce" @node-click="onNodeClick" />
    </div>

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

    <a-modal
      :open="showImpactModal"
      title="变更影响模拟"
      :footer="null"
      width="640px"
      @cancel="requestCloseImpact"
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
        <div v-if="impactLoading" class="task-progress">
          <a-spin size="small" />
          <span class="task-stage">{{ activeTask?.stage || '正在分析依赖影响链路…' }}</span>
          <span class="task-elapsed">已等待 {{ activeTask?.elapsedSec || 0 }} 秒</span>
          <a-button size="small" danger @click="cancelActiveAiTask">终止</a-button>
        </div>
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
            <a-badge :count="p.depth" :number-style="{ backgroundColor: '#1677ff' }" style="margin-left:4px" />
          </a-tag>
        </div>
        <a-empty v-else description="未发现影响链路" :image-style="{ height: '40px' }" />

        <div v-if="impactResult.aiAnalysis" class="ai-analysis-box">
          <h4>AI 风险分析</h4>
          <div class="markdown-body" v-html="renderSimpleMd(impactResult.aiAnalysis)"></div>
        </div>

        <a-button style="margin-top:16px" @click="impactResult = null">重新分析</a-button>
      </div>

      <a-collapse v-model:activeKey="impactHistoryActive" ghost class="history-collapse">
        <a-collapse-panel key="h" :header="'历史记录（' + impactHistory.length + '）'">
          <a-empty v-if="impactHistory.length === 0" description="暂无历史记录" :image-style="{ height: '36px' }" />
          <div v-else class="history-list">
            <div v-for="item in impactHistory" :key="item.id" class="history-item" @click="restoreImpactHistory(item)">
              <div class="history-item-main">
                <div class="history-question">{{ item.question }}</div>
                <div class="history-time">{{ formatHistoryTime(item.createdAt) }}</div>
              </div>
              <a-button type="text" size="small" @click.stop="deleteHistory(item.id, loadImpactHistory)">
                <template #icon><DeleteOutlined /></template>
              </a-button>
            </div>
          </div>
        </a-collapse-panel>
      </a-collapse>
    </a-modal>

    <a-modal
      :open="showQaModal"
      :title="qaTarget ? '上下文问答 — ' + qaTarget.label : '全局智能搜索'"
      :footer="null"
      width="600px"
      @cancel="requestCloseQa"
    >
      <div class="qa-class-info" v-if="qaTarget">
        <a-tag :color="getLayerColor(qaTarget.layer)">{{ qaTarget.layer }}</a-tag>
        <strong>{{ qaTarget.label }}</strong>
        <span style="color:#999;font-size:12px;margin-left:8px">{{ qaTarget.id }}</span>
        <a-button type="link" size="small" style="margin-left:auto;padding:0" @click="switchToGlobalQa">
          切换为全局搜索
        </a-button>
      </div>
      <a-alert v-else type="info" show-icon message="全局搜索模式" style="margin-bottom:8px">
        <template #description>
          AI 将自动搜索代码库中的相关类来回答问题。提示：在问题中包含关键词（如功能名、类名）效果更好。
        </template>
      </a-alert>
      <a-textarea
        v-model:value="qaQuestion"
        :placeholder="qaTarget ? '基于当前类的上下文提问，例如: 这个类的主要职责是什么？它有哪些直接依赖？' : '输入你的问题，例如: 支付相关的代码有哪些？项目中最核心的 Service 类是哪个？'"
        :rows="3"
        style="margin:12px 0"
      />
      <a-button type="primary" @click="doContextQa" :loading="qaLoading" block>
        {{ qaLoading ? 'AI 思考中...' : '提问' }}
      </a-button>
      <div v-if="qaLoading" class="task-progress">
        <a-spin size="small" />
        <span class="task-stage">{{ activeTask?.stage || 'AI 正在思考…' }}</span>
        <span class="task-elapsed">已等待 {{ activeTask?.elapsedSec || 0 }} 秒</span>
        <a-button size="small" danger @click="cancelActiveAiTask">终止</a-button>
      </div>
      <div v-if="qaAnswer" class="qa-answer-box">
        <div class="markdown-body" v-html="renderSimpleMd(qaAnswer)"></div>
        <div v-if="qaVerification" class="qa-verify-line">
          <a-tooltip :title="qaVerifyDetail">
            <a-tag :color="qaVerifyColor" size="small">{{ qaVerifyText }}</a-tag>
          </a-tooltip>
        </div>
      </div>

      <a-collapse v-model:activeKey="qaHistoryActive" ghost class="history-collapse">
        <a-collapse-panel key="h" :header="'历史记录（' + qaHistory.length + '）'">
          <a-empty v-if="qaHistory.length === 0" description="暂无历史记录" :image-style="{ height: '36px' }" />
          <div v-else class="history-list">
            <div v-for="item in qaHistory" :key="item.id" class="history-item" @click="restoreQaHistory(item)">
              <div class="history-item-main">
                <div class="history-question">
                  <a-tag v-if="item.contextFqn" color="purple" size="small">{{ shortName(item.contextFqn) }}</a-tag>
                  {{ item.question }}
                </div>
                <div class="history-time">{{ formatHistoryTime(item.createdAt) }}</div>
              </div>
              <a-button type="text" size="small" @click.stop="deleteHistory(item.id, loadQaHistory)">
                <template #icon><DeleteOutlined /></template>
              </a-button>
            </div>
          </div>
        </a-collapse-panel>
      </a-collapse>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { ThunderboltOutlined, QuestionCircleOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import CodeMap from '../components/map/CodeMap.vue'
import CodeMap3D from '../components/map/CodeMap3D.vue'
import api from '../api'
import { runAiTask, AI_TASK_CANCELLED, cancelActiveAiTask, activeTask } from '../composables/useAiTask'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const route = useRoute()
const projectId = route.params.id

const renderMode = ref('2d')
const searchQuery = ref('')
const codeMap2dRef = ref(null)
const hasData = ref(false)
const selectedNode = ref(null)
const drawerVisible = ref(false)
const mapPreparing = ref(false)
const hideTest = ref(false)
const hideUtil = ref(false)
const focusFqn = ref(null)
const focusNonce = ref(0)

// keep-alive 下从违规页跳回不触发 onMounted，必须用 watch 监听路由变化；
// nonce 自增保证同一 FQN 重复跳转也能重新聚焦
watch(() => route.fullPath, () => {
  const val = route.query.focus
  if (val) {
    focusFqn.value = String(val)
    focusNonce.value++
  } else {
    focusFqn.value = null
  }
}, { immediate: true })

onMounted(async () => {
  try {
    const res = await api.get(`/projects/${projectId}/scans/status`)
    const data = res.data.data
    if (data && (data.scanStatus === 'RUNNING' || data.scanStatus === 'NONE' || data.aiAnalysisRunning)) {
      mapPreparing.value = true
    }
  } catch (e) {
    // ignore status check error
  }
})

function onNodeClick(node) {
  if (!node) {
    // 地图上取消选中（点空白/关闭面板）时同步清除，问答回到全局模式
    selectedNode.value = null
    drawerVisible.value = false
    return
  }
  selectedNode.value = node
  drawerVisible.value = true
  hasData.value = true
  mapPreparing.value = false
}

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
  loadImpactHistory()
}

function openImpactModal() {
  showImpactModal.value = true
  loadImpactHistory()
}

async function doImpactSim() {
  if (!impactForm.targetClass) {
    message.warning('请输入目标类')
    return
  }
  impactLoading.value = true
  try {
    const data = await runAiTask({
      projectId,
      label: '影响模拟',
      execute: ({ taskId, signal }) =>
        api
          .post(`/projects/${projectId}/ai/impact-simulate`, impactForm, {
            headers: { 'X-Ai-Task-Id': taskId },
            signal
          })
          .then(res => res.data.data)
    })
    if (data === AI_TASK_CANCELLED) return
    impactResult.value = data
    loadImpactHistory()
  } catch (e) {
    message.error('影响分析失败: ' + (e.response?.data?.message || '网络错误'))
  } finally {
    impactLoading.value = false
  }
}

function requestCloseImpact() {
  if (impactLoading.value) {
    Modal.confirm({
      title: 'AI 分析正在进行中',
      content: '关闭将终止本次分析，确定要关闭吗？',
      okText: '终止并关闭',
      okType: 'danger',
      cancelText: '继续等待',
      onOk: () => {
        cancelActiveAiTask()
        showImpactModal.value = false
      }
    })
    return
  }
  showImpactModal.value = false
  impactResult.value = null
}

const showQaModal = ref(false)
const qaTarget = ref(null)
const qaQuestion = ref('')
const qaLoading = ref(false)
const qaAnswer = ref('')
const qaVerification = ref(null)
const qaHistory = ref([])
const qaHistoryActive = ref([])
const impactHistory = ref([])
const impactHistoryActive = ref([])

const qaVerifyText = computed(() => {
  const v = qaVerification.value
  if (!v) return ''
  const issues = (v.unsourced?.length || 0) + (v.relationMismatches?.length || 0)
  if (v.corrected) return '证据校验：已自动修正'
  if (issues > 0) return `证据校验：${issues} 项异常`
  return '证据校验通过'
})

const qaVerifyColor = computed(() => {
  const v = qaVerification.value
  if (!v) return 'green'
  if (v.corrected) return 'blue'
  if ((v.unsourced?.length || 0) + (v.relationMismatches?.length || 0) > 0) return 'orange'
  return 'green'
})

const qaVerifyDetail = computed(() => {
  const v = qaVerification.value
  if (!v) return ''
  const parts = []
  if (v.unsourced?.length) parts.push('未溯源引用: ' + v.unsourced.join('、'))
  if (v.relationMismatches?.length) parts.push('关系声明存疑: ' + v.relationMismatches.join('、'))
  if (!parts.length) {
    parts.push(`已校验 ${v.classRefsChecked || 0} 个类引用、${v.relationClaimsChecked || 0} 条关系声明，均通过`)
  }
  return parts.join('；')
})

function openQaPanel() {
  qaTarget.value = selectedNode.value
  qaQuestion.value = ''
  qaAnswer.value = ''
  qaVerification.value = null
  showQaModal.value = true
  loadQaHistory()
}

function switchToGlobalQa() {
  qaTarget.value = null
  qaAnswer.value = ''
  qaVerification.value = null
  loadQaHistory()
}

async function doContextQa() {
  if (!qaQuestion.value.trim()) {
    message.warning('请输入问题')
    return
  }
  qaLoading.value = true
  try {
    const data = await runAiTask({
      projectId,
      label: qaTarget.value ? '类问答 — ' + qaTarget.value.label : '全局问答',
      execute: ({ taskId, signal }) =>
        api
          .post(
            `/projects/${projectId}/ai/context-qa`,
            { question: qaQuestion.value, classFqn: qaTarget.value?.id || null },
            { headers: { 'X-Ai-Task-Id': taskId }, signal }
          )
          .then(res => res.data.data)
    })
    if (data === AI_TASK_CANCELLED) return
    qaAnswer.value = data?.answer || '未获取到回答'
    qaVerification.value = data?.verification || null
    loadQaHistory()
  } catch (e) {
    message.error('问答失败: ' + (e.response?.data?.message || '网络错误'))
  } finally {
    qaLoading.value = false
  }
}

function requestCloseQa() {
  if (qaLoading.value) {
    Modal.confirm({
      title: 'AI 问答正在进行中',
      content: '关闭将终止本次回答，确定要关闭吗？',
      okText: '终止并关闭',
      okType: 'danger',
      cancelText: '继续等待',
      onOk: () => {
        cancelActiveAiTask()
        showQaModal.value = false
      }
    })
    return
  }
  showQaModal.value = false
}

// ===== 对话历史（每人每项目每类型最多 10 条，服务端自动裁剪） =====

async function loadQaHistory() {
  const scope = qaTarget.value ? 'CLASS' : 'GLOBAL'
  try {
    const res = await api.get(`/projects/${projectId}/chat-history`, { params: { scope } })
    qaHistory.value = res.data.data || []
  } catch (e) {
    qaHistory.value = []
  }
}

async function loadImpactHistory() {
  try {
    const res = await api.get(`/projects/${projectId}/chat-history`, { params: { scope: 'IMPACT' } })
    impactHistory.value = res.data.data || []
  } catch (e) {
    impactHistory.value = []
  }
}

function restoreQaHistory(item) {
  qaQuestion.value = item.question || ''
  qaAnswer.value = item.answer || ''
  qaVerification.value = parseJson(item.verification)
}

function restoreImpactHistory(item) {
  const snapshot = parseJson(item.verification) || {}
  impactForm.targetClass = snapshot.targetClass || item.contextFqn || ''
  impactForm.changeType = snapshot.changeType || 'MODIFY_METHOD'
  impactForm.targetMethod = snapshot.targetMethod || ''
  impactForm.description = snapshot.description || ''
  impactResult.value = {
    targetClass: snapshot.targetClass || item.contextFqn,
    changeType: snapshot.changeType,
    totalImpacted: snapshot.totalImpacted || 0,
    impactPaths: snapshot.impactPaths || [],
    aiAnalysis: item.answer || ''
  }
}

async function deleteHistory(id, reload) {
  try {
    await api.delete(`/projects/${projectId}/chat-history/${id}`)
    reload()
  } catch (e) {
    message.error('删除失败: ' + (e.response?.data?.message || '网络错误'))
  }
}

function parseJson(str) {
  if (!str) return null
  try {
    return JSON.parse(str)
  } catch (e) {
    return null
  }
}

function shortName(fqn) {
  if (!fqn) return ''
  const parts = String(fqn).split('.')
  return parts[parts.length - 1]
}

function formatHistoryTime(t) {
  if (!t) return ''
  const d = new Date(t)
  return d.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
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
  return DOMPurify.sanitize(marked(content))
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
  color: #1677ff;
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

.qa-verify-line {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #e0e4ff;
  text-align: right;
}

.task-progress {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding: 12px 14px;
  background: #fafafa;
  border-radius: 8px;
}
.task-stage { flex: 1; min-width: 0; font-size: 13px; color: #333; }
.task-elapsed { font-size: 12px; color: #999; white-space: nowrap; }

.history-collapse {
  margin-top: 12px;
  border-top: 1px solid #f0f0f0;
}
.history-list {
  max-height: 220px;
  overflow-y: auto;
}
.history-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 4px;
  border-bottom: 1px dashed #f0f0f0;
  cursor: pointer;
}
.history-item:hover {
  background: #f9faff;
}
.history-item-main {
  flex: 1;
  min-width: 0;
}
.history-question {
  font-size: 13px;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  word-break: break-all;
}
.history-time {
  font-size: 12px;
  color: #bbb;
  margin-top: 2px;
}
.markdown-body { line-height: 1.7; font-size: 14px; color: #333; }
.markdown-body :deep(h4) { margin: 12px 0 4px; }
.markdown-body :deep(p) { margin: 4px 0; }
.markdown-body :deep(ul) { padding-left: 20px; }
</style>
