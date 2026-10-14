/**
 * @stryker-mutator/vitest-runner 10.x 修补脚本（npm postinstall 自动执行，幂等）
 *
 * 问题 1（关键）：vitest 5 用 task.fullTestName（套件与用例名以 " > " 拼接）
 * 匹配 testNamePattern；而 runner 用 collectTestName 的空格拼接格式生成
 * pattern，永远匹配不到任何用例 —— 每个变异体运行时执行 0 个测试、全部假存活。
 * 修补：禁用 name 过滤（置 undefined），仅保留 testFilesToRun 的文件级收窄
 * （目标文件 = 覆盖该变异体的用例所在文件，杀灭能力不受影响）。
 *
 * 问题 2（次要）：--logLevel debug 时 JSON.stringify(vitest 配置) 遇 Vite 8
 * 循环引用直接抛 TypeError；修补为 try/catch 兜底。
 *
 * 上游修复该兼容性后，锚点缺失会打印警告，脚本静默跳过、不阻断安装。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const runnerFile = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  'node_modules',
  '@stryker-mutator',
  'vitest-runner',
  'dist',
  'src',
  'vitest-test-runner.js'
)

if (!fs.existsSync(runnerFile)) {
  console.log('[patch-stryker] vitest-runner 未安装，跳过')
  process.exit(0)
}

let source = fs.readFileSync(runnerFile, 'utf8')
let changed = false

const nameFilterAnchor = 'project.config.testNamePattern = regex;'
const nameFilterMark = 'project.config.testNamePattern = undefined; /* codeatlas: vitest5 */'
if (source.includes(nameFilterAnchor)) {
  source = source.replace(nameFilterAnchor, nameFilterMark)
  changed = true
}

const debugAnchor =
  'this.log.debug(`vitest final config: ${JSON.stringify(this.ctx.config, null, 2)}`);'
if (source.includes(debugAnchor)) {
  source = source.replace(
    debugAnchor,
    [
      'let __safeConfig;',
      'try {',
      '    __safeConfig = JSON.stringify(this.ctx.config, null, 2);',
      '}',
      'catch (e) {',
      "    __safeConfig = '[circular config]';",
      '}',
      'this.log.debug(`vitest final config: ${__safeConfig}`);'
    ].join('\n            ')
  )
  changed = true
}

if (changed) {
  fs.writeFileSync(runnerFile, source)
  console.log('[patch-stryker] vitest-test-runner 已修补')
} else if (source.includes(nameFilterMark) && source.includes('__safeConfig')) {
  console.log('[patch-stryker] 补丁已存在，跳过')
} else {
  console.warn(
    '[patch-stryker] 未找到补丁锚点：vitest-runner 版本可能已升级，请检查与 vitest 5 的兼容性'
  )
}
