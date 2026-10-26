// SPDX-License-Identifier: Apache-2.0
// Types adapted from YSM Model Manager's spec-builder.ts, commit d74f850f.
export interface Vec3 { x: number; y: number; z: number }
export interface Cube2D {
  origin: [number, number, number]; size: [number, number, number];
  pivot: [number, number, number]; pivotSet: boolean;
  uv: [number, number]; faceUV: string; rotation: [number, number, number];
  texSlot: number; inflate: number; mirror: boolean; cubeTexW: number; cubeTexH: number;
}
export interface MeshData {
  id: string; boneId: string; localPosition: [number, number, number];
  localRotation: [number, number, number, number]; positions: number[];
  normals: number[]; uvs: number[]; indices: number[]; texIdx: number;
}
