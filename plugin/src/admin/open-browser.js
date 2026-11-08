// @ts-check
/**
 * 用系统默认浏览器打开本机配对页。只允许打开 http://127.0.0.1 的地址。
 * 不经过 shell：Windows 用 rundll32，Linux 用 xdg-open。失败时静默忽略（例如没有图形界面的服务器）。
 */
import { spawn } from 'node:child_process'

/** @param {string} url */
export function openInBrowser(url) {
  if (!/^http:\/\/127\.0\.0\.1:\d+\/$/.test(url)) throw new Error('只允许打开本机配对页')
  const [command, args] = process.platform === 'win32'
    ? ['rundll32.exe', ['url.dll,FileProtocolHandler', url]]
    : ['xdg-open', [url]]
  const child = spawn(command, args, { detached: true, stdio: 'ignore', windowsHide: true })
  child.on('error', () => { /* 没有可用的浏览器时忽略 */ })
  child.unref()
}
