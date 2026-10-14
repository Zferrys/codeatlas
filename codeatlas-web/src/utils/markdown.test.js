import { describe, it, expect } from 'vitest'
import { renderMarkdown } from './markdown'

describe('renderMarkdown', () => {
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
    const out = renderMarkdown('```java\nint a = 1;\n```')
    expect(out).toContain('hljs')
  })

  it('renderMarkdown_whenUnknownLanguageCodeBlock_shouldFallbackToAutoHighlight', () => {
    const out = renderMarkdown('```notalang\nfoo bar\n```')
    expect(out).toContain('hljs')
  })
})
