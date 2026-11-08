// @ts-check
/**
 * 本机配对页的 HTML（服务端渲染，没有任何 JavaScript）。
 *
 * 所有会改变状态的操作都是带 CSRF token 的表单 POST，由 server.js 校验。
 * 页面里出现的所有外部数据（设备名等）都经过 HTML 转义。
 */
import qrcode from '../vendor/qrcode.mjs'

/** @param {unknown} value */
export function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] ?? c)
}

/**
 * 配对二维码的内容（protocol.md 2.1）。
 * @param {{ host: string, port: number, fingerprint: string, code: string, name: string }} p
 */
export function buildPairingUri(p) {
  const params = new URLSearchParams({ v: '1', h: p.host, p: String(p.port), fp: p.fingerprint, c: p.code, n: p.name })
  return `dshremote://pair?${params.toString()}`
}

/** @param {string} text */
function qrSvg(text) {
  const qr = qrcode(0, 'M')
  qr.addData(text)
  qr.make()
  return qr.createSvgTag({ cellSize: 6, margin: 3, scalable: true })
}

/** 指纹每 4 个字符一组，方便人工核对。 */
const groupFingerprint = (/** @type {string} */ fp) => fp.replace(/(.{4})/g, '$1 ').trim()

/** @param {number | undefined} ms */
const formatTime = (ms) => (ms === undefined ? '—' : new Date(ms).toLocaleString('zh-CN', { hour12: false }))

/**
 * @typedef {object} AdminView
 * @property {string} csrfToken
 * @property {string} pluginVersion
 * @property {string} hostName
 * @property {string} fingerprint
 * @property {number} lanPort
 * @property {string} lanBind
 * @property {import('./addresses.js').LanAddress[]} addresses
 * @property {string | undefined} selectedAddress
 * @property {{ code: string | undefined, expiresAt: number | undefined, lockedUntil: number | undefined }} pairing
 * @property {import('../pairing/devices.js').DeviceInfo[]} devices
 * @property {string | undefined} notice 操作结果提示
 */

/**
 * @param {AdminView} v
 * @returns {string}
 */
export function renderAdminPage(v) {
  const csrf = `<input type="hidden" name="csrf" value="${escapeHtml(v.csrfToken)}">`
  const addrQuery = v.selectedAddress ? `?addr=${encodeURIComponent(v.selectedAddress)}` : ''

  let pairingSection
  if (v.pairing.lockedUntil !== undefined) {
    pairingSection = `<p class="bad">配对接口已锁定到 ${escapeHtml(formatTime(v.pairing.lockedUntil))}（有人连续输错了配对码）。重新生成配对码会立即解除锁定。</p>
      <form method="post" action="/pair-code${addrQuery}">${csrf}<button>生成配对码</button></form>`
  } else if (v.pairing.code && v.selectedAddress) {
    const uri = buildPairingUri({ host: v.selectedAddress, port: v.lanPort, fingerprint: v.fingerprint, code: v.pairing.code, name: v.hostName })
    pairingSection = `<div class="qr">${qrSvg(uri)}</div>
      <p>用 DSHRemote App 扫描二维码。扫不了码时，在 App 中手动输入：</p>
      <table class="manual">
        <tr><th>地址</th><td class="mono">${escapeHtml(v.selectedAddress)}</td></tr>
        <tr><th>端口</th><td class="mono">${escapeHtml(v.lanPort)}</td></tr>
        <tr><th>配对码</th><td class="mono big">${escapeHtml(v.pairing.code.replace(/(.{4})/, '$1 '))}</td></tr>
      </table>
      <p class="muted">配对码 ${escapeHtml(formatTime(v.pairing.expiresAt))} 过期，只能使用一次。不要截图或发给别人。</p>
      <form method="post" action="/pair-code/cancel${addrQuery}">${csrf}<button class="secondary">取消配对码</button></form>`
  } else if (!v.selectedAddress) {
    pairingSection = '<p class="bad">没有找到可用的局域网地址。请确认电脑已连接网络。</p>'
  } else {
    pairingSection = `<p>需要配对新手机时，点击下面的按钮生成一次性配对码（5 分钟内有效）。</p>
      <form method="post" action="/pair-code${addrQuery}">${csrf}<button>生成配对码</button></form>`
  }

  const addressChooser = v.addresses.length > 1
    ? `<p class="muted">这台电脑有多个地址，二维码使用的是：${v.addresses.map((a) =>
      a.address === v.selectedAddress
        ? `<strong class="mono">${escapeHtml(a.address)}</strong>`
        : `<a class="mono" href="/?addr=${encodeURIComponent(a.address)}">${escapeHtml(a.address)}</a>`).join(' · ')}。
      手机连不上时，换一个地址试试。</p>`
    : ''

  const deviceRows = v.devices.length === 0
    ? '<tr><td colspan="5" class="muted">还没有配对的设备</td></tr>'
    : v.devices.map((d) => `<tr>
        <td>${escapeHtml(d.name)}</td>
        <td>${escapeHtml(d.platform)} ${escapeHtml(d.appVersion)}</td>
        <td>${escapeHtml(formatTime(d.createdAt))}</td>
        <td>${escapeHtml(formatTime(d.lastSeenAt))}</td>
        <td><form method="post" action="/devices/revoke${addrQuery}">${csrf}<input type="hidden" name="deviceId" value="${escapeHtml(d.deviceId)}"><button class="danger">撤销</button></form></td>
      </tr>`).join('')

  // 有配对码时每 15 秒刷新一次，过期后页面自动更新
  const refresh = v.pairing.code ? '<meta http-equiv="refresh" content="15">' : ''

  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
${refresh}
<title>DSHRemote 配对</title>
<style>
  body{font-family:system-ui,-apple-system,"Segoe UI","Microsoft YaHei",sans-serif;max-width:720px;margin:32px auto;padding:0 20px;color:#1f2328;line-height:1.6}
  h1{font-size:22px} h2{font-size:17px;margin-top:32px;border-bottom:1px solid #d0d7de;padding-bottom:4px}
  .muted{color:#656d76;font-size:14px} .bad{color:#cf222e} .ok{color:#1a7f37}
  .mono{font-family:ui-monospace,Consolas,monospace} .big{font-size:22px;letter-spacing:2px}
  .qr{width:280px;border:1px solid #d0d7de;border-radius:8px} .qr svg{width:100%;height:auto;display:block}
  table{border-collapse:collapse;width:100%} th,td{text-align:left;padding:6px 8px;border-bottom:1px solid #eaeef2;font-size:14px}
  table.manual{width:auto} table.manual th{color:#656d76;font-weight:normal}
  button{font:inherit;padding:6px 14px;border-radius:6px;border:1px solid #1f883d;background:#1f883d;color:#fff;cursor:pointer}
  button.secondary{background:#f6f8fa;color:#1f2328;border-color:#d0d7de} button.danger{background:#fff;color:#cf222e;border-color:#cf222e;padding:2px 10px}
  .notice{background:#ddf4ff;border:1px solid #54aeff;border-radius:6px;padding:8px 12px}
  .fp{font-size:14px;word-break:break-all}
</style></head><body>
<h1>DSHRemote · 手机配对</h1>
${v.notice ? `<p class="notice">${escapeHtml(v.notice)}</p>` : ''}
<h2>配对新手机</h2>
${addressChooser}
${pairingSection}
<h2>已配对的设备</h2>
<table><tr><th>名称</th><th>平台</th><th>配对时间</th><th>最近使用</th><th></th></tr>${deviceRows}</table>
<h2>这台电脑</h2>
<table class="manual">
  <tr><th>名称</th><td>${escapeHtml(v.hostName)}</td></tr>
  <tr><th>局域网服务</th><td class="mono">${escapeHtml(v.lanBind)}:${escapeHtml(v.lanPort)}（HTTPS）</td></tr>
  <tr><th>证书指纹</th><td class="mono fp"><strong>${escapeHtml(v.fingerprint.slice(0, 8))}</strong>${escapeHtml(v.fingerprint.slice(8))}<br><span class="muted">${escapeHtml(groupFingerprint(v.fingerprint))}</span></td></tr>
  <tr><th>插件版本</th><td>${escapeHtml(v.pluginVersion)}</td></tr>
</table>
<p class="muted">手动输入地址配对时，App 会显示电脑证书指纹的前 8 位，请和上面加粗的 8 位核对一致后再继续。<br>
这个页面只能在本机打开。</p>
</body></html>`
}
