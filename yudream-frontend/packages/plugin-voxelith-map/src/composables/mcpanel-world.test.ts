import { describe, expect, it } from 'vitest'
import { entryNameOf, instanceDimensions, modJarPaths, requireCompleteZip } from './mcpanel-world'

/**
 * 模组阶段「只压 jar」的取舍逻辑。
 *
 * <p>这段直接决定打进包里的内容：漏掉某个 jar = 渲染少模组（还可能悄悄少几何），
 * 多带上缓存/停用包 = 把节点压缩拖长、更容易撞反向代理超时。所以按形态逐条钉住。</p>
 */
describe('modJarPaths', () => {
  it('keeps plain jar file names under the mods directory', () => {
    expect(modJarPaths(['a.jar', 'b.JAR'])).toEqual(['mods/a.jar', 'mods/b.JAR'])
  })

  it('skips disabled packages and non-jar files', () => {
    expect(modJarPaths(['a.jar', 'b.jar.disabled', 'readme.txt', 'cache.zip', 'logs']))
      .toEqual(['mods/a.jar'])
  })

  it('accepts already-relative paths and normalises ./ and backslashes', () => {
    expect(modJarPaths(['mods/a.jar', './mods/b.jar', 'mods\\c.jar']))
      .toEqual(['mods/a.jar', 'mods/b.jar', 'mods/c.jar'])
  })

  it('resolves entries returned as objects (node versions differ in field names)', () => {
    expect(modJarPaths([
      { name: 'a.jar' },
      { fileName: 'b.jar' },
      { file: 'c.jar' },
      { path: 'mods/d.jar' },
      { size: 12 },
    ])).toEqual(['mods/a.jar', 'mods/b.jar', 'mods/c.jar', 'mods/d.jar'])
  })

  it('deduplicates and tolerates empty input', () => {
    expect(modJarPaths(['a.jar', 'mods/a.jar', 'a.jar'])).toEqual(['mods/a.jar'])
    expect(modJarPaths([])).toEqual([])
    expect(modJarPaths([null, undefined, '', '   '])).toEqual([])
  })
})

describe('entryNameOf', () => {
  it('reads a name from strings, objects and unknown shapes', () => {
    expect(entryNameOf('a.jar')).toBe('a.jar')
    expect(entryNameOf({ name: 'b.jar' })).toBe('b.jar')
    expect(entryNameOf({ size: 1 })).toBe('')
    expect(entryNameOf(null)).toBe('')
  })
})

/**
 * 维度目录探测：这份清单同时决定「打包哪些目录」与「服务端建几张地图」。
 *
 * <p>少认一个目录 = 少一张地图（下界/末地静默丢失）；多认一个非维度目录 = 打包与建图对不上。
 * 顺序也必须固定（主世界 → 下界 → 末地），否则同一个实例两次导入的地图顺序会变。</p>
 */
describe('instanceDimensions', () => {
  it('picks modern dimension directories in a fixed order and ignores everything else', () => {
    const dims = instanceDimensions(['logs', 'world_the_end', 'mods', 'world_nether', 'world', 'server.jar'])
    expect(dims.map(d => d.dimension)).toEqual(['minecraft:overworld', 'minecraft:nether', 'minecraft:the_end'])
    expect(dims.map(d => d.rootName)).toEqual(['world', 'world_nether', 'world_the_end'])
  })

  it('returns only the dimensions that exist, falling back to the overworld', () => {
    expect(instanceDimensions(['world', 'mods']).map(d => d.dimension)).toEqual(['minecraft:overworld'])
    expect(instanceDimensions([]).map(d => d.rootName)).toEqual(['world'])
  })

  /**
   * 老版存档：下界/末地在 world 里面（DIM-1/DIM1），且打包路径是父目录 world
   * （DIM 子目录没有 level.dat，单独打包出来不是一份完整存档）。
   */
  it('recognises legacy nested dimensions inside the world directory', () => {
    const dims = instanceDimensions(['world', 'server.jar'], ['region', 'DIM-1', 'DIM1', 'data'])
    expect(dims.map(d => d.dimension)).toEqual(['minecraft:overworld', 'minecraft:nether', 'minecraft:the_end'])
    expect(dims.map(d => d.rootName)).toEqual(['world', 'world_nether', 'world_the_end'])
    expect(dims.map(d => d.packPath)).toEqual(['world', 'world', 'world'])
  })

  it('prefers modern sibling directories when both layouts exist', () => {
    const dims = instanceDimensions(['world', 'world_nether'], ['DIM-1', 'DIM1'])
    expect(dims.map(d => d.rootName)).toEqual(['world', 'world_nether'])
    expect(dims.find(d => d.dimension === 'minecraft:nether')?.packPath).toBe('world_nether')
  })

  it('does not treat region/data or archive names as dimensions', () => {
    const dims = instanceDimensions(['world', 'world_nether.zip'], ['region', 'data', 'level.dat'])
    expect(dims.map(d => d.dimension)).toEqual(['minecraft:overworld'])
  })
})

describe('requireCompleteZip', () => {
  /**
   * 手工拼一个「形状合法」的最小 zip：判据只看头尾签名（本地头 PK + 中央目录结束 PK），
   * 所以不需要真的压缩内容，也不需要额外依赖。
   */
  function zipBytes(): Uint8Array {
    const bytes = new Uint8Array(4096)
    bytes.set([0x50, 0x4b, 0x03, 0x04], 0)
    // 中央目录结束记录在文件最末尾（真实 zip 就是这样）：截断尾部就会把它截掉
    bytes.set([0x50, 0x4b, 0x05, 0x06], bytes.length - 22)
    return bytes
  }

  it('accepts a complete zip', async () => {
    await expect(requireCompleteZip(new Blob([zipBytes().buffer as ArrayBuffer]))).resolves.toBeUndefined()
  })

  it('rejects a zip whose tail is missing', async () => {
    const complete = zipBytes()
    const truncated = complete.slice(0, complete.length - 30)
    await expect(requireCompleteZip(new Blob([truncated.buffer as ArrayBuffer])))
      .rejects.toThrow(/缺少 zip 尾部记录/)
  })

  it('leaves non-zip payloads alone', async () => {
    await expect(requireCompleteZip(new Blob([new TextEncoder().encode('not a zip at all, just text')])))
      .resolves.toBeUndefined()
  })
})
