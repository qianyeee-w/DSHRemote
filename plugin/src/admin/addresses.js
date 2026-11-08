// @ts-check
/**
 * 选出可以放进配对二维码的本机地址。
 *
 * 优先级：家用局域网常见网段（192.168/16 → 10/8 → 172.16/12）→ 100.64/10（Tailscale 等 CGNAT 网段）→ 其他 IPv4。
 * 虚拟机、容器、WSL 等虚拟网卡（按网卡名识别）排在所有真实网卡之后：它们的地址手机访问不到，
 * T10 实测 VMware 的 VMnet1（192.168.142.1）曾排在以太网前面。
 * 只列 IPv4：手机端手动输入和扫码时都更直观。
 */
import { networkInterfaces } from 'node:os'

/** 虚拟网卡的名字（Windows 和 Linux 上常见的） */
const VIRTUAL_INTERFACE = /vmware|vmnet|virtualbox|vbox|hyper-v|vethernet|wsl|docker|podman|^br-|^veth|^virbr|^lxc|^lxd|^cni|^flannel|^tap|^utun|loopback|npcap/i

/**
 * @param {string} ip
 * @returns {number} 越小越优先
 */
function rank(ip) {
  const [a = 0, b = 0] = ip.split('.').map(Number)
  if (a === 192 && b === 168) return 0
  if (a === 10) return 1
  if (a === 172 && b >= 16 && b <= 31) return 2
  if (a === 100 && b >= 64 && b <= 127) return 3
  if (a === 169 && b === 254) return 9 // 链路本地地址，一般不可用
  return 5
}

/**
 * @typedef {object} LanAddress
 * @property {string} address
 * @property {string} interfaceName
 */

/**
 * @param {string} lanBind 配置中的监听地址
 * @param {ReturnType<typeof networkInterfaces>} [interfaces] 测试时注入
 * @returns {LanAddress[]}
 */
export function lanAddresses(lanBind, interfaces = networkInterfaces()) {
  if (lanBind !== '0.0.0.0' && lanBind !== '::') return [{ address: lanBind, interfaceName: '配置指定' }]
  /** @type {LanAddress[]} */
  const found = []
  for (const [interfaceName, entries] of Object.entries(interfaces)) {
    for (const entry of entries ?? []) {
      if (entry.family !== 'IPv4' || entry.internal) continue
      if (!found.some((f) => f.address === entry.address)) found.push({ address: entry.address, interfaceName })
    }
  }
  /** @param {LanAddress} a */
  const score = (a) => (VIRTUAL_INTERFACE.test(a.interfaceName) ? 100 : 0) + rank(a.address)
  return found.sort((x, y) => score(x) - score(y) || x.address.localeCompare(y.address))
}
