/* 生成 64x64 经典版 Steve 皮肤 PNG（仅 Node 内置模块） */
const fs = require('node:fs')
const path = require('node:path')
const zlib = require('node:zlib')

const W = 64
const H = 64
const data = new Uint8Array(W * H * 4) // RGBA，默认全透明

function px(x, y, r, g, b, a = 255) {
  if (x < 0 || y < 0 || x >= W || y >= H) return
  const i = (y * W + x) * 4
  data[i] = r
  data[i + 1] = g
  data[i + 2] = b
  data[i + 3] = a
}

// 可复现伪随机，做轻微噪点纹理
let seed = 20260906
function rand() {
  seed = (seed * 1103515245 + 12345) & 0x7fffffff
  return seed / 0x7fffffff
}

function shade(base, delta) {
  const d = Math.round(delta * (rand() - 0.5) * 2)
  return base.map(c => Math.max(0, Math.min(255, c + d)))
}

function fill(x0, y0, x1, y1, color, noise = 6) {
  for (let y = y0; y < y1; y++) {
    for (let x = x0; x < x1; x++) {
      const [r, g, b] = shade(color, noise)
      px(x, y, r, g, b)
    }
  }
}

const SKIN = [198, 141, 98]
const SKIN_DARK = [166, 113, 76]
const HAIR = [58, 42, 24]
const SHIRT = [0, 168, 168]
const PANTS = [62, 62, 168]
const SHOES = [110, 110, 110]
const EYE_WHITE = [235, 235, 235]
const EYE_BLUE = [70, 57, 175]
const MOUTH = [142, 90, 62]

// ---- 头部 (8,8)-(16,16) 正面，周围侧面/背面/顶部 ----
fill(0, 8, 32, 16, SKIN) // 左右侧面+正面+背面整行
fill(8, 0, 24, 8, SKIN) // 顶部与底部
// 头发：顶部 + 正面/侧面/背面上缘
fill(8, 0, 16, 8, HAIR)
fill(0, 8, 32, 11, HAIR)
// 鬓角
fill(0, 11, 2, 14, HAIR)
fill(6, 11, 8, 14, HAIR)
fill(16, 11, 18, 14, HAIR)
fill(22, 11, 24, 14, HAIR)
fill(24, 11, 32, 11, HAIR)
// 脸（正面 8..16, 8..16）
fill(8, 11, 16, 16, SKIN)
// 眼睛 y=12
px(10, 12, ...EYE_WHITE)
px(11, 12, ...EYE_BLUE)
px(14, 12, ...EYE_BLUE)
px(15, 12, ...EYE_WHITE)
// 鼻子/嘴
px(11, 14, ...MOUTH)
px(12, 14, ...SKIN_DARK)
px(13, 14, ...SKIN_DARK)
px(14, 14, ...MOUTH)
fill(11, 15, 15, 16, MOUTH, 4)
// 头后侧头发延续
fill(24, 8, 32, 12, HAIR)

// ---- 躯干：区块 x16..40, y16..32（顶/底 8x4，右/前/左/背 4|8|4|8 宽 x 12 高）----
// 顶(20..28,16..20) 底(28..36,16..20) 右(16..20,20..32) 前(20..28,20..32) 左(28..32,20..32) 背(32..40,20..32)
fill(20, 16, 28, 20, SHIRT) // 顶面
fill(28, 16, 36, 20, SHIRT) // 底面
fill(16, 20, 40, 32, SHIRT) // 四个侧面（含背面）

function limb(baseX, baseY, color, sleeveColor) {
  // 4x12 肢体，正面 baseX+4..baseX+8, baseY+4..baseY+16
  fill(baseX + 4, baseY, baseX + 8, baseY + 4, color) // 顶面
  fill(baseX + 8, baseY, baseX + 12, baseY + 4, color) // 底面
  fill(baseX, baseY + 4, baseX + 16, baseY + 16, color) // 四个侧面（含背面）
  if (sleeveColor) {
    fill(baseX, baseY + 4, baseX + 16, baseY + 8, sleeveColor) // 袖口
  }
}

// 64x64 布局：右臂(40,16) 左臂(32,48) 右腿(0,16) 左腿(16,48)
limb(40, 16, SKIN, SHIRT) // 右臂：袖子上 1/3 衣服色，其余皮肤
limb(32, 48, SKIN, SHIRT) // 左臂
limb(0, 16, PANTS) // 右腿
limb(16, 48, PANTS) // 左腿

// 鞋子：腿侧面底部 2 行 + 脚底
function shoes(baseX, baseY) {
  fill(baseX, baseY + 14, baseX + 16, baseY + 16, SHOES, 4)
  fill(baseX + 8, baseY, baseX + 12, baseY + 4, SHOES, 4)
}
shoes(0, 16)
shoes(16, 48)

// ---- PNG 编码 ----
function crc32(buf) {
  let table = crc32.table
  if (!table) {
    table = crc32.table = new Int32Array(256)
    for (let n = 0; n < 256; n++) {
      let c = n
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
      table[n] = c
    }
  }
  let c = ~0
  for (let i = 0; i < buf.length; i++) c = table[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return ~c >>> 0
}

function chunk(type, payload) {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(payload.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), payload])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body))
  return Buffer.concat([len, body, crc])
}

const ihdr = Buffer.alloc(13)
ihdr.writeUInt32BE(W, 0)
ihdr.writeUInt32BE(H, 4)
ihdr[8] = 8 // bit depth
ihdr[9] = 6 // color type RGBA
const raw = Buffer.alloc(H * (W * 4 + 1))
for (let y = 0; y < H; y++) {
  raw[y * (W * 4 + 1)] = 0 // filter none
  Buffer.from(data.buffer, y * W * 4, W * 4).copy(raw, y * (W * 4 + 1) + 1)
}
const png = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  chunk('IHDR', ihdr),
  chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
  chunk('IEND', Buffer.alloc(0)),
])

const out = path.join(__dirname, '..', 'public', 'assets', 'steve.png')
fs.mkdirSync(path.dirname(out), { recursive: true })
fs.writeFileSync(out, png)
console.log(`written ${out} (${png.length} bytes)`)
