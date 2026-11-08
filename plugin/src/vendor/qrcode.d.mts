// 只声明本项目用到的部分。qrcode.mjs 是原样复制的第三方代码，不做类型检查。
export interface QRCode {
  addData(data: string, mode?: 'Byte' | 'Numeric' | 'Alphanumeric' | 'Kanji'): void
  make(): void
  getModuleCount(): number
  isDark(row: number, col: number): boolean
  createSvgTag(options?: { cellSize?: number; margin?: number; scalable?: boolean; alt?: string; title?: string }): string
}

declare function qrcode(typeNumber: number, errorCorrectionLevel: 'L' | 'M' | 'Q' | 'H'): QRCode
export default qrcode
