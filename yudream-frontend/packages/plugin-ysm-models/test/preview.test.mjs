import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import ts from 'typescript'
const temporary = mkdtempSync(join(tmpdir(), 'ysm-preview-'))
after(() => rmSync(temporary, { recursive: true, force: true }))
for (const [name, source] of Object.entries({ cover: 'preview/cover-raster.ts', animation: 'preview/animation.ts', molang: 'preview/molang.ts', evaluator: 'vendor/ysm-model-manager/animation-evaluator.ts', quaternion: 'vendor/ysm-model-manager/quaternion.ts', cube: 'vendor/ysm-model-manager/cube-mesh.ts' })) {
  const code = ts.transpileModule(readFileSync(new URL(`../src/${source}`, import.meta.url), 'utf8'), { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
    .replaceAll('../../preview/animation', './animation.mjs').replaceAll('./quaternion', './quaternion.mjs')
    .replaceAll('../vendor/ysm-model-manager/cube-mesh', './cube.mjs').replaceAll('../vendor/ysm-model-manager/animation-evaluator', './evaluator.mjs')
    .replaceAll("'./molang'", "'./molang.mjs'").replaceAll("'./animation'", "'./animation.mjs'").replaceAll("'three'", JSON.stringify(import.meta.resolve('three')))
  writeFileSync(join(temporary, `${name}.mjs`), code)
}
const { parseAnimation, parseAnimations, evaluatePose } = await import(pathToFileURL(join(temporary, 'animation.mjs')))
const { evaluateClip } = await import(pathToFileURL(join(temporary, 'evaluator.mjs')))
const { buildCubeMeshData, computeBoneLocalPos } = await import(pathToFileURL(join(temporary, 'cube.mjs')))
const { rasterizeCover } = await import(pathToFileURL(join(temporary, 'cover.mjs')))
test('pre/post discontinuity and Catmull endpoint match runtime semantics', () => {
  for (const mode of ['linear', 'catmullrom']) {
    const clip = parseAnimation('walk', { animation_length: 2, loop: false, bones: { Head: { position: { 0: { post: [0,0,0], lerp_mode: mode }, 1: { pre: [10,0,0], post: [20,0,0] } } } } })
    assert.ok(Math.abs(evaluateClip(clip, .999999).get('Head').position[0] - 10) < .0001)
    assert.equal(evaluateClip(clip, 1).get('Head').position[0], 20)
  }
})
test('step, delayed first pre, looping, and non-loop hold remain distinct', () => {
  const raw = { animation_length: 2, loop: true, bones: { Head: { rotation: { 1: { pre: [1,0,0], post: [3,0,0], lerp_mode: 'step' }, 2: [5,0,0] } } } }
  const clip = parseAnimation('idle', raw)
  assert.equal(evaluateClip(clip, 0).get('Head').rotation[0], 1)
  assert.equal(evaluateClip(clip, 1.8).get('Head').rotation[0], 3)
  assert.equal(evaluateClip(clip, 2.5).get('Head').rotation[0], 1)
  assert.equal(evaluateClip(parseAnimation('idle', { ...raw, loop: 'hold_on_last_frame' }), 5).get('Head').rotation[0], 5)
})
test('mirrored fractional box UVs and arbitrary cube rotation retain finite mesh data', () => {
  assert.deepEqual(computeBoneLocalPos({x:2,y:5,z:3}, {x:1,y:2,z:3}), [-1,3,0])
  const cube = { origin:[-1,0,-1], size:[2.5,3,2], uv:[0,0], rotation:[12,30,-7], pivot:[0,1,0], pivotSet:true, inflate:0, mirror:true, faceUV:'', texSlot:0, cubeTexW:0, cubeTexH:0 }
  const mesh = buildCubeMeshData(cube, {x:0,y:0,z:0}, 64, 64, 'Body', 0)
  assert.ok(mesh)
  assert.equal(mesh.positions.length, 72)
  assert.equal(mesh.indices.length, 36)
  for (const values of [mesh.positions, mesh.normals, mesh.uvs, mesh.localPosition, mesh.localRotation]) assert.ok(values.every(Number.isFinite))
  assert.ok(mesh.uvs.some(v => Math.abs(v - 4.5/64) < 1e-6))
})

function coverBundle(rotation = [0, 0, 0]) {
  return { schema: 1, name: 'cube', bones: [{ name: 'root', pivot: [0, 0, 0], rotation }, { name: 'child', parent: 'root', pivot: [0, 3, 0] }],
    parts: [{ bone: 'child', cubes: [{ origin: [-2, 0, -2], size: [4, 12, 4], uv: [0, 0] }] }], animations: {}, textures: [{ name: 'base', width: 64, height: 64, png: '' }] }
}
function pixelTexture(alpha) {
  const pixels = new Uint8ClampedArray(64 * 64 * 4)
  for (let i = 0; i < pixels.length; i += 4) { pixels[i] = 255; pixels[i + 3] = alpha }
  return { width: 64, height: 64, pixels }
}
function bounds(image, pane) {
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity, count = 0
  for (let y = 0; y < image.height; y++) for (let x = pane * image.width / 2; x < (pane + 1) * image.width / 2; x++) {
    if (!image.pixels[(y * image.width + x) * 4 + 3]) continue
    minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); count++
  }
  return { width: maxX - minX, height: maxY - minY, count }
}
test('CPU cover frames both views and respects parent bone rotation', () => {
  const normal = rasterizeCover(coverBundle(), pixelTexture(255), 320, 240)
  for (const pane of [0, 1]) {
    const b = bounds(normal, pane)
    assert.ok(b.count > 1000)
    assert.ok(b.height > b.width * 2)
    assert.ok(b.height < 220)
  }
  const rotated = bounds(rasterizeCover(coverBundle([0, 0, 90]), pixelTexture(255), 320, 240), 0)
  assert.ok(rotated.width > rotated.height * 2)
})
test('transparent overlays preserve the underlying opaque texture and empty texels', () => {
  const bundle = coverBundle(), texture = pixelTexture(255)
  for (let y = 0; y < 64; y++) for (let x = 32; x < 64; x++) texture.pixels[(y * 64 + x) * 4 + 3] = 0
  bundle.parts[0].cubes.push({ origin: [-2, 0, -2], size: [4, 12, 4], uv: [32, 0], inflate: 1 })
  const image = rasterizeCover(bundle, texture, 320, 240)
  assert.ok(bounds(image, 0).count > 1000)
  assert.ok(bounds(image, 1).count > 1000)
  assert.equal(rasterizeCover(bundle, pixelTexture(0), 320, 240).pixels.some(Boolean), false)
})

test('schema 2 expressions animate safely with the same degree units and layer defaults as Java', () => {
  const expr = { expr: ['*', ['n', 16], ['call', 'math.sin', ['*', ['v', 'query.anim_time'], ['n', 90]]]] }
  const bundle = { ...coverBundle(), schema: 2, layers: ['parallel0'], animations: {
    idle: { animation_length: 2, bones: { child: { position: [0, expr, 0] } } },
    parallel0: { animation_length: 2, bones: { child: { scale: { expr: ['?', ['==', ['v', 'v.roaming.cloth'], ['n', 0]], ['n', 1], ['n', 0]] } } } },
  } }
  const poses = evaluatePose(bundle, parseAnimations(bundle), 'idle', 1)
  assert.ok(Math.abs(poses.get('child').position[1] - 16) < 1e-6)
  assert.deepEqual(poses.get('child').scale, [1, 1, 1])
  assert.throws(() => parseAnimation('bad', { animation_length: 1, bones: { child: { scale: { expr: ['call', 'java.exec', ['n', 1]] } } } }))
})
test('per-face UV omits absent faces and preserves negative intervals and rotation', () => {
  const cube = { origin:[-1,0,-1], size:[2,3,2], uv:[0,0], rotation:[0,0,0], pivot:[0,0,0], pivotSet:true, inflate:0, mirror:false,
    faceUV:JSON.stringify({ north: { uv:[4,4], uv_size:[-4,8], uv_rotation:90 } }), texSlot:0, cubeTexW:0, cubeTexH:0 }
  const mesh = buildCubeMeshData(cube, {x:0,y:0,z:0}, 16, 16, 'child', 0)
  assert.equal(mesh.positions.length, 12)
  assert.equal(mesh.indices.length, 6)
  assert.deepEqual([...new Set(mesh.uvs)].sort(), [0,.25,.75])
  const plain = buildCubeMeshData({...cube,faceUV:JSON.stringify({north:{uv:[4,4],uv_size:[-4,8]}})}, {x:0,y:0,z:0},16,16,'child',0)
  assert.notDeepEqual(mesh.uvs, plain.uvs)
})
