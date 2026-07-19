import * as THREE from 'three'

const MAX_ATTRIBUTES = 16
const MAX_VALUES = 16_000_000
const MAX_INDICES = 48_000_000

type PrbmArrayType = {
  new (buffer: ArrayBuffer, byteOffset: number, length: number): ArrayBufferView
  readonly BYTES_PER_ELEMENT: number
  readonly name: string
}

const arrayTypes: Array<PrbmArrayType | undefined> = [
  undefined, Float32Array, undefined, Int8Array, Int16Array, undefined, Int32Array, Uint8Array, Uint16Array,
  undefined, Uint32Array,
]

const methodNames: Record<string, keyof DataView> = {
  Uint16Array: 'getUint16', Uint32Array: 'getUint32', Int16Array: 'getInt16', Int32Array: 'getInt32', Float32Array: 'getFloat32',
}

export function decodePrbm(buffer: ArrayBuffer): THREE.BufferGeometry {
  const bytes = new Uint8Array(buffer)
  if (bytes.byteLength < 8 || bytes[0] !== 1) {
    throw new Error('Unsupported PRBM tile format')
  }
  const flags = bytes[1]!
  const indexed = Boolean(flags & 0x80)
  const index32 = Boolean(flags & 0x40)
  const bigEndian = Boolean(flags & 0x20)
  const attributeCount = flags & 0x1f
  if (attributeCount > MAX_ATTRIBUTES) {
    throw new Error('PRBM tile has too many attributes')
  }
  const values = readThree(bytes, 2, bigEndian)
  const indices = readThree(bytes, 5, bigEndian)
  if (values > MAX_VALUES || indices > MAX_INDICES || (!indexed && indices !== 0)) {
    throw new Error('PRBM tile exceeds safety limits')
  }

  let offset = 8
  const geometry = new THREE.BufferGeometry()
  for (let attributeIndex = 0; attributeIndex < attributeCount; attributeIndex += 1) {
    const nameEnd = bytes.indexOf(0, offset)
    if (nameEnd < offset || nameEnd === -1) throw new Error('Malformed PRBM attribute name')
    const name = new TextDecoder('ascii').decode(bytes.subarray(offset, nameEnd))
    offset = align4(nameEnd + 2)
    if (offset > bytes.byteLength) throw new Error('Malformed PRBM attribute header')
    const attributeFlags = bytes[nameEnd + 1]!
    const cardinality = ((attributeFlags >> 4) & 0x03) + 1
    const encoding = attributeFlags & 0x0f
    const Type = arrayTypes[encoding]
    if (!Type) throw new Error(`Unsupported PRBM encoding ${encoding}`)
    const length = cardinality * values
    const typed = readArray(buffer, Type, offset, length, bigEndian)
    offset += typed.byteLength
    geometry.setAttribute(name, new THREE.BufferAttribute(typed as never, cardinality, Boolean(attributeFlags & 0x40)))
  }
  if (indexed) {
    offset = align4(offset)
    const Type: PrbmArrayType = index32 ? Uint32Array : Uint16Array
    const typed = readArray(buffer, Type, offset, indices, bigEndian)
    offset += typed.byteLength
    geometry.setIndex(new THREE.BufferAttribute(typed as never, 1))
  }
  offset = align4(offset)
  while (offset + 4 <= bytes.byteLength) {
    const material = new DataView(buffer, offset, 4).getInt32(0, true)
    offset += 4
    if (material === -1) break
    if (offset + 8 > bytes.byteLength) throw new Error('Malformed PRBM groups')
    const start = new DataView(buffer, offset, 4).getInt32(0, true)
    const count = new DataView(buffer, offset + 4, 4).getInt32(0, true)
    offset += 8
    if (start < 0 || count < 0) throw new Error('Malformed PRBM group range')
    geometry.addGroup(start, count, material)
  }
  if (!geometry.getAttribute('position') || !geometry.getAttribute('uv')) {
    throw new Error('PRBM tile is missing required geometry attributes')
  }
  geometry.computeBoundingSphere()
  return geometry
}

function align4(value: number): number { return Math.ceil(value / 4) * 4 }

function readThree(bytes: Uint8Array, offset: number, bigEndian: boolean): number {
  return bigEndian
    ? (bytes[offset]! << 16) + (bytes[offset + 1]! << 8) + bytes[offset + 2]!
    : bytes[offset]! + (bytes[offset + 1]! << 8) + (bytes[offset + 2]! << 16)
}

function readArray(buffer: ArrayBuffer, Type: PrbmArrayType,
  offset: number, length: number, bigEndian: boolean): ArrayBufferView {
  const byteLength = Type.BYTES_PER_ELEMENT * length
  if (offset < 0 || byteLength < 0 || offset + byteLength > buffer.byteLength) throw new Error('PRBM attribute exceeds tile bounds')
  if (bigEndian === isBigEndian() || Type.BYTES_PER_ELEMENT === 1) return new Type(buffer, offset, length)
  const view = new DataView(buffer, offset, byteLength)
  const result = new Type(new ArrayBuffer(byteLength), 0, length)
  const method = methodNames[Type.name]
  if (!method) throw new Error('Unsupported PRBM endian conversion')
  for (let index = 0; index < length; index += 1) {
    ;(result as unknown as Record<number, number>)[index] = (view[method] as (offset: number, littleEndian: boolean) => number)(index * Type.BYTES_PER_ELEMENT, !bigEndian)
  }
  return result
}

function isBigEndian(): boolean {
  return new Uint16Array(new Uint8Array([0xaa, 0xbb]).buffer)[0] === 0xaabb
}
