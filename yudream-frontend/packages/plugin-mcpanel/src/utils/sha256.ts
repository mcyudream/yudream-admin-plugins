/**
 * 流式 SHA-256：WebCrypto 的 digest 需要整份数据进内存，大文件不可行；
 * 上传前按块读文件逐段 update（节点 commit 用同源 sha256 校验，必须整文件摘要）。
 * 实现为标准 FIPS 180-4 压缩函数，hex() 输出 64 位小写十六进制。
 */
const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
])

const rotr = (value: number, bits: number) => (value >>> bits) | (value << (32 - bits))

export class Sha256Stream {
  private state = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ])
  private block = new Uint8Array(64)
  private blockLength = 0
  private byteLength = 0

  /** 追加一段数据（可任意长度、任意次）。 */
  update(data: Uint8Array): this {
    this.byteLength += data.length
    let offset = 0
    if (this.blockLength > 0) {
      const take = Math.min(64 - this.blockLength, data.length)
      this.block.set(data.subarray(0, take), this.blockLength)
      this.blockLength += take
      offset = take
      if (this.blockLength === 64) {
        this.compress(this.block)
        this.blockLength = 0
      }
    }
    while (offset + 64 <= data.length) {
      this.compress(data.subarray(offset, offset + 64))
      offset += 64
    }
    if (offset < data.length) {
      this.block.set(data.subarray(offset), 0)
      this.blockLength = data.length - offset
    }
    return this
  }

  /** 结束填充并输出 64 位小写十六进制。 */
  hex(): string {
    const bitsHigh = Math.floor((this.byteLength / 0x100000000) * 8)
    const bitsLow = (this.byteLength * 8) % 0x100000000
    // 追加 0x80 + 零 + 8 字节大端位长，使总长 ≡ 0 (mod 64)；长度必须落在追加段末尾
    const appendLength = this.blockLength < 56 ? 64 - this.blockLength : 128 - this.blockLength
    const tail = new Uint8Array(appendLength)
    tail[0] = 0x80
    const view = new DataView(tail.buffer)
    view.setUint32(appendLength - 8, bitsHigh)
    view.setUint32(appendLength - 4, bitsLow)
    this.update(tail)
    let out = ''
    for (const word of this.state) {
      out += word.toString(16).padStart(8, '0')
    }
    return out
  }

  private compress(input: Uint8Array): void {
    const w = new Uint32Array(64)
    for (let i = 0; i < 16; i++) {
      w[i] = (input[i * 4] << 24) | (input[i * 4 + 1] << 16) | (input[i * 4 + 2] << 8) | input[i * 4 + 3]
    }
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3)
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10)
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0
    }
    let [a, b, c, d, e, f, g, h] = this.state
    for (let i = 0; i < 64; i++) {
      const s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)
      const ch = (e & f) ^ (~e & g)
      const temp1 = (h + s1 + ch + K[i] + w[i]) | 0
      const s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)
      const maj = (a & b) ^ (a & c) ^ (b & c)
      const temp2 = (s0 + maj) | 0
      h = g
      g = f
      f = e
      e = (d + temp1) | 0
      d = c
      c = b
      b = a
      a = (temp1 + temp2) | 0
    }
    this.state[0] = (this.state[0] + a) | 0
    this.state[1] = (this.state[1] + b) | 0
    this.state[2] = (this.state[2] + c) | 0
    this.state[3] = (this.state[3] + d) | 0
    this.state[4] = (this.state[4] + e) | 0
    this.state[5] = (this.state[5] + f) | 0
    this.state[6] = (this.state[6] + g) | 0
    this.state[7] = (this.state[7] + h) | 0
  }
}

/** 分块读取文件计算整文件 sha256（进度可选回调，块大小默认 4MiB）。 */
export async function sha256HexOfFile(
  file: File,
  onProgress?: (readBytes: number, totalBytes: number) => void,
  chunkSize = 4 * 1024 * 1024,
): Promise<string> {
  const hasher = new Sha256Stream()
  for (let offset = 0; offset < file.size; offset += chunkSize) {
    const buffer = await file.slice(offset, Math.min(offset + chunkSize, file.size)).arrayBuffer()
    hasher.update(new Uint8Array(buffer))
    onProgress?.(Math.min(offset + chunkSize, file.size), file.size)
  }
  return hasher.hex()
}
