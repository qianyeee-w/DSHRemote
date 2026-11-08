// @ts-check
/**
 * 支持的 DSH 版本范围：唯一的定义处。
 *
 * package.json 的 `engines.dsh` 必须和这里一致（由单元测试保证）。
 * 升级基线时只改这里和 package.json，并新写一条 ADR（见 docs/decisions/0005）。
 */

/** 适配基线版本。 */
export const DSH_BASELINE_VERSION = '0.2.2-rc.1'

/** 支持的版本范围（semver range 语法）。 */
export const SUPPORTED_DSH_RANGE = '>=0.2.2-rc.1 <0.3.0-0'
