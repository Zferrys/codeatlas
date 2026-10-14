import { describe, it, expect, vi, afterEach } from 'vitest'
import hljs from 'highlight.js'
import { renderMarkdown } from './markdown'

describe('renderMarkdown', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renderMarkdown_whenContentFalsy_shouldReturnEmptyString', () => {
    expect(renderMarkdown(null)).toBe('')
    expect(renderMarkdown(undefined)).toBe('')
    expect(renderMarkdown('')).toBe('')
  })

  it('renderMarkdown_whenBasicMarkdown_shouldRenderHtml', () => {
    expect(renderMarkdown('# 标题')).toContain('<h1')
    expect(renderMarkdown('**粗体**')).toContain('<strong>粗体</strong>')
    expect(renderMarkdown('- a\n- b')).toContain('<li>a</li>')
  })

  it('renderMarkdown_whenScriptTag_shouldSanitizeXss', () => {
    const out = renderMarkdown('<script>alert(1)</script>')
    expect(out).not.toContain('<script')
  })

  it('renderMarkdown_whenEventAttributeOnImg_shouldStripHandler', () => {
    const out = renderMarkdown('<img src=x onerror="alert(1)">')
    expect(out).not.toContain('onerror')
  })

  it('renderMarkdown_whenJavascriptHref_shouldStripDangerousUri', () => {
    const out = renderMarkdown('[x](javascript:alert(1))')
    expect(out).not.toContain('javascript:')
  })

  it('renderMarkdown_whenKnownLanguageCodeBlock_shouldHighlightWithHljs', () => {
    const highlightSpy = vi.spyOn(hljs, 'highlight')
    const autoSpy = vi.spyOn(hljs, 'highlightAuto')

    const out = renderMarkdown('```java\nint a = 1;\n```')

    // 已知语言必须走 hljs.highlight 指定语言，而不是自动探测
    expect(highlightSpy).toHaveBeenCalledTimes(1)
    expect(highlightSpy).toHaveBeenCalledWith(expect.any(String), { language: 'java' })
    expect(autoSpy).not.toHaveBeenCalled()
    expect(out).toContain('hljs-number')
  })

  it('renderMarkdown_whenUnknownLanguageCodeBlock_shouldFallbackToAutoHighlight', () => {
    const highlightSpy = vi.spyOn(hljs, 'highlight')
    const autoSpy = vi.spyOn(hljs, 'highlightAuto')

    const out = renderMarkdown('```notalang\nfoo bar\n```')

    // 未知语言回退到自动探测，且不指定语言调用 highlight
    expect(autoSpy).toHaveBeenCalled()
    expect(highlightSpy).not.toHaveBeenCalled()
    expect(out).toContain('hljs')
  })

  it('renderMarkdown_whenFreshlyImported_shouldApplyLangPrefixAndHighlightExtension', async () => {
    // marked.use 配置在模块求值期执行：须在用例体内重载模块，拦截式变异体才能被归属并杀灭
    vi.resetModules()
    const fresh = await import('./markdown')

    const out = fresh.renderMarkdown('```js\nconst x = 1\n```')

    expect(out).toContain('class="hljs language-js"')
    expect(out).toContain('hljs-keyword')
  })
})
