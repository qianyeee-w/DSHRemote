// @ts-check
/**
 * 插件安装包（M3 T3）的纯函数：安装包里的 package.json、文件名、内容检查。
 * 结论和理由见 docs/research/dsh-plugin-install.md。
 */

import { parseVersion } from './release-lib.mjs'

/** 安装包的文件名前缀，完整文件名为 dshremote-plugin-<版本>.tgz */
export const PACKAGE_PREFIX = 'dshremote-plugin'

/** 安装包里必须有的文件 */
export const REQUIRED_FILES = ['package.json', 'index.js', 'cordis.patch.yml', 'LICENSE']

/** 无论放在哪里都不能进安装包的文件：私钥、证书、签名密钥、环境变量文件 */
const SECRET_FILE = /(^|\/)(\.env(\..*)?|[^/]*\.(pem|key|p12|pfx|jks|keystore))$/i

/** @param {string} version */
export function packageFileName(version) {
  parseVersion(version)
  return `${PACKAGE_PREFIX}-${version}.tgz`
}

/**
 * 安装包里的 package.json：去掉开发依赖和脚本，把运行时依赖声明为 bundleDependencies，
 * 让 npm pack 把它们打进安装包。DSH 安装时（pnpm）直接使用包里的依赖，不需要联网。
 * @param {Record<string, unknown>} manifest 插件源码中的 package.json
 * @returns {Record<string, unknown>}
 */
export function stagedManifest(manifest) {
  const { devDependencies: _dev, scripts: _scripts, ...rest } = manifest
  const dependencies = /** @type {Record<string, string> | undefined} */ (manifest.dependencies) ?? {}
  return { ...rest, bundleDependencies: Object.keys(dependencies).sort() }
}

/**
 * 检查安装包的文件列表（npm pack --json 输出的 files[].path，不带 package/ 前缀）。
 * - 只允许：package.json、README.md、package.json 的 files 列出的文件或目录、打包的运行时依赖；
 * - 不允许：test/、scripts/、未打包声明的依赖（例如开发依赖）、私钥和证书等文件；
 * - 必须有 REQUIRED_FILES 和每个运行时依赖的 package.json。
 * @param {string[]} paths
 * @param {{ files: string[], bundled: string[] }} manifest files 和 bundled 分别为 package.json 的 files 和打包的依赖
 * @returns {string[]} 问题列表，空数组表示通过
 */
export function checkPackFiles(paths, manifest) {
  const problems = []
  const allowedRoots = new Set(['package.json', 'README.md', ...manifest.files])
  for (const raw of paths) {
    const path = raw.replace(/\\/g, '/')
    const top = path.split('/')[0] ?? ''
    if (SECRET_FILE.test(path)) problems.push(`不能包含密钥或证书文件：${path}`)
    else if (top === 'test' || top === 'scripts') problems.push(`不能包含测试和开发脚本：${path}`)
    else if (top === 'node_modules') {
      const segments = path.split('/')
      const name = segments[1]?.startsWith('@') ? `${segments[1]}/${segments[2]}` : segments[1]
      if (!name || !manifest.bundled.includes(name)) problems.push(`不能包含未声明为运行时依赖的包：${path}`)
    } else if (!allowedRoots.has(path) && !allowedRoots.has(top)) problems.push(`不在 package.json 的 files 中：${path}`)
  }
  const normalized = new Set(paths.map((p) => p.replace(/\\/g, '/')))
  for (const file of REQUIRED_FILES) if (!normalized.has(file)) problems.push(`缺少必需的文件：${file}`)
  for (const name of manifest.bundled) if (!normalized.has(`node_modules/${name}/package.json`)) problems.push(`缺少运行时依赖：${name}`)
  return problems
}
