import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import ts from 'typescript'
const temporary = mkdtempSync(join(tmpdir(), 'ysm-preview-'))
after(() => rmSync(temporary, { recursive: true, force: true }))
for (const [name, source] of Object.entries({ animation: 'preview/animation.ts', evaluator: 'vendor/ysm-model-manager/animation-evaluator.ts', quaternion: 'vendor/ysm-model-manager/quaternion.ts', cube: 'vendor/ysm-model-manager/cube-mesh.ts' })) {
  const code = ts.transpileModule(readFileSync(new URL(`../src/${source}`, import.meta.url), 'utf8'), { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText
    .replaceAll('../../preview/animation', './animation.mjs').replaceAll('./quaternion', './quaternion.mjs')
  writeFileSync(join(temporary, `${name}.mjs`), code)
}
const { parseAnimation } = await import(pathToFileURL(join(temporary, 'animation.mjs')))
const { evaluateClip } = await import(pathToFileURL(join(temporary, 'evaluator.mjs')))
const { buildCubeMeshData, computeBoneLocalPos } = await import(pathToFileURL(join(temporary, 'cube.mjs')))
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
