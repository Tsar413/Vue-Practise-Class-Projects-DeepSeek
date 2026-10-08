#!/usr/bin/env node
/**
 * make-fixtures.mjs  生成校验用的测试素材（不依赖第三方库）
 *
 * 产出：
 *   fixtures/test-image.png    16x16 的有效 PNG，用于图片上传 / 预览 / 下载验证
 *   fixtures/students.xlsx     最小可用的 xlsx，含表头行与 4 行学生数据
 *                              （1 行已存在、1 行班级不存在、2 行可导入）
 *
 * 用法：node scripts/make-fixtures.mjs
 */
import { writeFileSync, mkdirSync } from 'node:fs'
import { deflateSync } from 'node:zlib'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const outDir = join(here, 'fixtures')
mkdirSync(outDir, { recursive: true })

/* ------------------------------- PNG ------------------------------- */
function crc32(buf) {
  let c
  let crc = 0xffffffff
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    crc = c ^ (crc >>> 8)
  }
  return (crc ^ 0xffffffff) >>> 0
}

// 注意：IDAT 是二进制压缩数据，必须用 Buffer 拼接，
// 不要经过字符串转换，否则 zlib 数据会被破坏，后端会判定“图片内容损坏”。
function chunk(type, data) {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length, 0)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body), 0)
  return Buffer.concat([len, body, crc])
}

const W = 16
const H = 16
// 每行 1 字节过滤器 + W*3 字节 RGB
const raw = Buffer.alloc((W * 3 + 1) * H)
for (let y = 0; y < H; y++) {
  const rowStart = y * (W * 3 + 1)
  raw[rowStart] = 0 // 过滤器类型：none
  for (let x = 0; x < W; x++) {
    const p = rowStart + 1 + x * 3
    const left = x < W / 2
    const top = y < H / 2
    raw[p] = left ? 0x1a : 0xff
    raw[p + 1] = top ? 0xc4 : 0x8e
    raw[p + 2] = left ? 0xe2 : 0x63
  }
}

const ihdr = Buffer.alloc(13)
ihdr.writeUInt32BE(W, 0)
ihdr.writeUInt32BE(H, 4)
ihdr[8] = 8  // 位深
ihdr[9] = 2  // 颜色类型：真彩色
ihdr[10] = 0 // 压缩方式
ihdr[11] = 0 // 过滤方式
ihdr[12] = 0 // 隔行扫描

const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr),
  chunk('IDAT', deflateSync(raw, { level: 9 })),
  chunk('IEND', Buffer.alloc(0))
])
writeFileSync(join(outDir, 'test-image.png'), png)

/* ------------------------------- XLSX ------------------------------ */
// xlsx 本质是一个 zip。内容全部是文本 XML，这里手写最小结构以避免引入依赖。
function zip(entries) {
  const files = []
  const central = []
  let offset = 0

  for (const [name, contentRaw] of entries) {
    const nameBuf = Buffer.from(name, 'utf8')
    const content = Buffer.from(contentRaw, 'utf8')
    const crc = crc32(content)

    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(0, 6)
    local.writeUInt16LE(0, 8) // 存储方式：不压缩
    local.writeUInt16LE(0, 10)
    local.writeUInt16LE(0, 12)
    local.writeUInt32LE(crc, 14)
    local.writeUInt32LE(content.length, 18)
    local.writeUInt32LE(content.length, 22)
    local.writeUInt16LE(nameBuf.length, 26)
    local.writeUInt16LE(0, 28)
    files.push(local, nameBuf, content)

    const cd = Buffer.alloc(46)
    cd.writeUInt32LE(0x02014b50, 0)
    cd.writeUInt16LE(20, 4)
    cd.writeUInt16LE(20, 6)
    cd.writeUInt16LE(0, 8)
    cd.writeUInt16LE(0, 10)
    cd.writeUInt16LE(0, 12)
    cd.writeUInt16LE(0, 14)
    cd.writeUInt32LE(crc, 16)
    cd.writeUInt32LE(content.length, 20)
    cd.writeUInt32LE(content.length, 24)
    cd.writeUInt16LE(nameBuf.length, 28)
    cd.writeUInt16LE(0, 30)
    cd.writeUInt16LE(0, 32)
    cd.writeUInt16LE(0, 34)
    cd.writeUInt16LE(0, 36)
    cd.writeUInt32LE(0, 38)
    cd.writeUInt32LE(offset, 42)
    central.push(cd, nameBuf)

    offset += local.length + nameBuf.length + content.length
  }

  const centralBuf = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(entries.length, 8)
  end.writeUInt16LE(entries.length, 10)
  end.writeUInt32LE(centralBuf.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...files, centralBuf, end])
}

const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

// 全部为虚构学号与占位姓名，不含真实学生信息
const rows = [
  ['学号', '姓名', '班级编号'],
  ['DEMO2026001', '学生甲', 'DEMO2026'],       // 已存在 -> 跳过
  ['IMPORT9001', '导入学生一', 'DEMO2026'],     // 成功
  ['IMPORT9002', '导入学生二', 'DEMO2026'],     // 成功
  ['IMPORT9003', '导入学生三', 'NO_SUCH_CLASS'] // 班级不存在 -> 失败
]

const sheetRows = rows.map((cells, r) => {
  const t = cells.map((v, c) => {
    const ref = String.fromCharCode(65 + c) + (r + 1)
    // 一律写成内联字符串，保证前导零与长学号不会失真
    return `<c r="${ref}" t="inlineStr"><is><t>${esc(v)}</t></is></c>`
  }).join('')
  return `<row r="${r + 1}">${t}</row>`
}).join('')

const xlsx = zip([
  ['[Content_Types].xml',
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>'],
  ['_rels/.rels',
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>'],
  ['xl/workbook.xml',
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="学生名单" sheetId="1" r:id="rId1"/></sheets></workbook>'],
  ['xl/_rels/workbook.xml.rels',
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>'],
  ['xl/worksheets/sheet1.xml',
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${sheetRows}</sheetData></worksheet>`]
])
writeFileSync(join(outDir, 'students.xlsx'), xlsx)

console.log('已生成：')
console.log('  fixtures/test-image.png  ', png.length, '字节')
console.log('  fixtures/students.xlsx   ', xlsx.length, '字节')
