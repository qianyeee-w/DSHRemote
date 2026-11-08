// @ts-check
import js from '@eslint/js'
import globals from 'globals'

export default [
  { ignores: ['node_modules/**', 'src/vendor/**'] },
  js.configs.recommended,
  {
    files: ['**/*.js', '**/*.mjs'],
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'module',
      globals: { ...globals.node },
    },
    rules: {
      'no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      'no-console': 'error',
      eqeqeq: ['error', 'always'],
      'prefer-const': 'error',
    },
  },
  {
    // 测试和测试辅助脚本可以直接输出到控制台
    files: ['test/**'],
    rules: { 'no-console': 'off' },
  },
]
