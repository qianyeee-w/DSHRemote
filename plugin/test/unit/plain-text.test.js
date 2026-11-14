// @ts-check
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { markdownToPlainLine } from '../../src/dsh/plain-text.js'

test('去掉行首的块级标记，换行合成一个空格', () => {
  // 用户真机上看到的：首页最近列表里出现「## ✅ 电脑端插件」
  assert.equal(markdownToPlainLine('已经装好并跑起来了。核心情况如下：\n\n## ✅ 电脑端插件\n- 端口 3190\n- 已配对'), '已经装好并跑起来了。核心情况如下： ✅ 电脑端插件 端口 3190 已配对')
  assert.equal(markdownToPlainLine('> - [ ] 叠在一起的标记'), '叠在一起的标记')
  assert.equal(markdownToPlainLine('1. 第一步\n2) 第二步\n- [x] 完成'), '第一步 第二步 完成')
  assert.equal(markdownToPlainLine('###### 六级标题'), '六级标题')
})

test('去掉代码围栏、分隔线和表格的分隔行，保留代码和单元格的文字', () => {
  assert.equal(markdownToPlainLine('运行：\n```bash\nnpm test\n```\n---\n完成'), '运行： npm test 完成')
  assert.equal(markdownToPlainLine('| 项目 | 结果 |\n|---|:---:|\n| 插件 | 通过 |'), '项目 结果 插件 通过')
  assert.equal(markdownToPlainLine('***\n___'), '')
})

test('去掉行内标记，只留文字', () => {
  assert.equal(markdownToPlainLine('**粗体**、__粗体__、*斜体*、_斜体_、~~删除~~'), '粗体、粗体、斜体、斜体、删除')
  assert.equal(markdownToPlainLine('改了 `plugin/index.js`，见 [文档](https://example.com/a)'), '改了 plugin/index.js，见 文档')
  assert.equal(markdownToPlainLine('截图 ![示意图](a.png) 和 <https://example.com>'), '截图 示意图 和 https://example.com')
})

test('不是标记的符号原样保留', () => {
  // 标题、列表符号后面没有空白，不是标记
  assert.equal(markdownToPlainLine('#123 已修复'), '#123 已修复')
  assert.equal(markdownToPlainLine('-1 是错误码'), '-1 是错误码')
  // 单词中间的下划线、乘号、只有一边的星号
  assert.equal(markdownToPlainLine('改了 snake_case_name 和 2*3*4'), '改了 snake_case_name 和 2*3*4')
  assert.equal(markdownToPlainLine('文件名 *.log 不要提交'), '文件名 *.log 不要提交')
  // 截断后只剩一半的粗体
  assert.equal(markdownToPlainLine('结论：**这里被截'), '结论：**这里被截')
  // 正文中间的 # 不动
  assert.equal(markdownToPlainLine('第 2 步 # 注释'), '第 2 步 # 注释')
})
