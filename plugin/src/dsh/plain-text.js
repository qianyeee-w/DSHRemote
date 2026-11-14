/**
 * 把一段 Markdown 变成一行能直接显示的纯文字（protocol.md 5.1 的 `lastReply`）。
 * 只做显示用的近似处理，不是完整的 Markdown 解析：去掉块级标记（标题、引用、列表、任务框、代码围栏、分隔线、
 * 表格的分隔行和竖线）和常见的行内标记（粗体、斜体、删除线、行内代码、链接、图片），保留文字本身。
 * 必须在合并换行之前做：块级标记只在行首才算。
 */

/** 行首的一个块级标记：标题、引用、无序列表、有序列表、任务框。和 CommonMark 一样，标记后面要有空白。 */
const LEADING_MARKER = /^(?:#{1,6}(?=\s|$)|>|[-*+](?=\s)|\d{1,9}[.)](?=\s)|\[[ xX]\](?=\s|$))\s*/

/** 代码围栏的一行（```lang 或 ~~~）。 */
const FENCE = /^\s*(?:`{3,}|~{3,})/

/** 分隔线（--- *** ___），以及表格的分隔行（|---|:--:|）。 */
const RULE = /^\s*(?:(?:[-*_]\s*){3,}|\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)*\|?)\s*$/

/** @type {readonly [RegExp, string][]} */
const INLINE = [
  // 图片和链接：只留文字
  [/!\[([^\]]*)\]\([^)]*\)/g, '$1'],
  [/\[([^\]]+)\]\([^)]*\)/g, '$1'],
  // 自动链接 <https://…>
  [/<((?:https?|mailto):[^>\s]+)>/g, '$1'],
  // 行内代码
  [/`+([^`]+?)`+/g, '$1'],
  // 粗体、删除线、斜体。斜体的 `_` 两边不能是字母数字，免得改坏 snake_case
  [/(\*\*|__)(?=\S)(.+?)(?<=\S)\1/g, '$2'],
  [/~~(?=\S)(.+?)(?<=\S)~~/g, '$1'],
  [/(?<![*\w])\*(?=\S)(.+?)(?<=\S)\*(?![*\w])/g, '$1'],
  [/(?<![_\p{L}\p{N}])_(?=\S)(.+?)(?<=\S)_(?![_\p{L}\p{N}])/gu, '$1'],
]

/**
 * @param {string} markdown
 * @returns {string} 一行纯文字，连续空白合成一个空格，首尾不留空白
 */
export function markdownToPlainLine(markdown) {
  const lines = []
  for (const raw of markdown.split(/\r?\n/)) {
    if (FENCE.test(raw) || RULE.test(raw)) continue
    let line = raw.trim()
    // 标记可以叠加：「> - [ ] 任务」
    for (let match = LEADING_MARKER.exec(line); match && match[0] !== ''; match = LEADING_MARKER.exec(line)) {
      line = line.slice(match[0].length)
    }
    // 表格的一行：竖线换成空格
    if (line.startsWith('|')) line = line.replace(/\|/g, ' ')
    for (const [pattern, replacement] of INLINE) line = line.replace(pattern, replacement)
    lines.push(line)
  }
  return lines.join(' ').replace(/\s+/g, ' ').trim()
}
