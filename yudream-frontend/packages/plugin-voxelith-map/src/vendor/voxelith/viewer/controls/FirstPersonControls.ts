// vendored from yudream/voxelith (MIT, Copyright (c) 2026 余梦) — 只读副本，勿在此处修改；同步见 src/vendor/voxelith/VENDOR.md
/**
 * 第一人称控制器：指针锁定视角 + 我的世界生存模式式移动。
 *
 * 没有体素数据，脚下地面与撞墙全部靠对瓦片几何做射线探测（options.probe，渲染空间）：
 * - 支撑面：从抬脚高度向下打一条竖直射线，取最近的上表面 —— 起点在脚上方，头顶的树冠/
 *   屋檐不会被当成"脚下的地面"；
 * - 撞墙：沿移动方向在脚踝 / 腰 / 头三个高度各打一条水平射线，命中即停；分轴推进，
 *   贴着墙走会自然沿墙滑动；
 * - 自动上台阶 ≤ 0.6 格（MC 同值），更高的坎必须跳；
 * - 重力 32 格/秒²、终端速度 78 格/秒、起跳 8.95 格/秒（≈1.25 格跳高）、步行 4.317 /
 *   疾跑 5.612 格/秒，均为 MC 数值换算；
 * - 探测不到地形（瓦片还没到 / 图外）时保持当前高度悬停，绝不掉进虚空；
 * - 水面（瓦片里的 translucent primitive）不是地面：身体浸在水里时重力降到 8 格/秒²、
 *   终端下沉速度 3 格/秒，按住 Space 以 ~3.2 格/秒上浮，浮出水面时按 Space 会像 MC 一样
 *   给一次起跳把人送上岸；水里水平速度减半。
 *
 * 未实现：攀爬（梯子/藤蔓）、摔落伤害（本项目没有血量系统）、水下视野染色。
 */
import * as THREE from "three";
import type { CameraControls, LookOptions } from "./CameraControls.js";

/** 探测介质：实体（地面/墙）或水面。 */
export type TerrainMedium = "solid" | "water";

/** 渲染空间地形探测：由 App 侧用瓦片几何实现（只打 hires 瓦片）。 */
export interface TerrainProbe {
  /** 该列从地图最高点往下找到的最近表面（渲染空间 Y）；medium 缺省 = 实体地面。 */
  topSurfaceY(x: number, z: number, medium?: TerrainMedium): number | null;
  /**
   * 该列自 fromY 往下的最近表面（渲染空间 Y）；maxDrop 省略 = 一直下探到地图最低点。
   * 返回 null 表示下方没有可用地形（视距外 / 图外 / 瓦片未到）。
   */
  groundBelow(
    x: number,
    z: number,
    fromY: number,
    maxDrop?: number,
    medium?: TerrainMedium,
  ): number | null;
  /** 从 (x, y, z) 沿单位方向 (dx, dy, dz) 投射 maxDistance，命中返回距离；null = 未命中。 */
  castRay(
    x: number,
    y: number,
    z: number,
    dx: number,
    dy: number,
    dz: number,
    maxDistance: number,
    medium?: TerrainMedium,
  ): number | null;
}

export interface FirstPersonOptions extends LookOptions {
  /** 地形探测；省略 = 以 groundY 为地面的平面世界（无碰撞） */
  probe?: TerrainProbe;
  /** 没有 probe 时的地面高度（渲染空间 Y），默认 4 */
  groundY?: number;
  /** 水平范围限制（就地修改传入的渲染空间坐标），省略则不限制。 */
  clampXZ?: (position: THREE.Vector3) => void;
  /** 视点离脚底高度，默认 1.62（MC 站姿眼高） */
  eyeHeight?: number;
  /** 步行速度（方块/秒），默认 4.317（MC 步行速度） */
  walkSpeed?: number;
}

/** 玩家碰撞盒：MC 生存模式 1.8 高 × 0.6 宽 */
const PLAYER_HEIGHT = 1.8;
const PLAYER_RADIUS = 0.3;
/** 自动上台阶高度（MC 同值）：一格高的坎必须跳 */
const STEP_HEIGHT = 0.6;
/** 重力加速度（MC 0.08 格/刻² × 400） */
const GRAVITY = 32;
/** 下落终端速度（MC ≈3.92 格/刻） */
const TERMINAL_VELOCITY = 78;
/** 起跳速度：8.95 格/秒 → 跳高约 1.25 格（MC 同手感） */
const JUMP_VELOCITY = 8.95;
/** 疾跑倍率（MC 5.612 / 4.317） */
const SPRINT_MULTIPLIER = 1.3;
/** 水中重力（MC 0.02 格/刻² × 400） */
const WATER_GRAVITY = 8;
/** 水中下沉终端速度（MC ≈0.15 格/刻） */
const WATER_SINK_SPEED = 3;
/** 按住 Space 的上浮速度 */
const WATER_SWIM_SPEED = 3.2;
/** 水中水平速度倍率（MC 游泳 ≈ 步行的一半） */
const WATER_SPEED_FACTOR = 0.5;
/** 身体浸没判定余量（方块）：水面高过脚底这么多才算"在水里" */
const SUBMERGE_MARGIN = 0.05;
/** 出水后仍能借力起跳的水面距离（方块）：MC 里能从水里跳上 1 格台阶 */
const WATER_EXIT_REACH = 0.6;
/** 身体横向探测高度（脚踝 / 腰 / 头） */
const BODY_PROBES = [0.1, 0.9, 1.7];
const EPSILON = 1e-4;

export class FirstPersonControls implements CameraControls {
  private readonly camera: THREE.PerspectiveCamera;
  private readonly canvas: HTMLCanvasElement;

  sensitivity: number;
  private readonly probe?: TerrainProbe;
  private readonly clampXZ?: (position: THREE.Vector3) => void;
  private readonly eyeHeight: number;
  private readonly walkSpeed: number;
  /** 没有 probe 时的平面地面高度（渲染空间 Y） */
  private readonly flatGroundY: number;

  private readonly keys = new Set<string>();
  private yaw = 0;
  private pitch = 0;
  private targetYaw = 0;
  private targetPitch = 0;
  /** 脚底高度（渲染空间 Y）；相机 = 脚底 + 眼高 */
  private feetY = 0;
  private verticalVelocity = 0;
  private grounded = true;
  private readonly euler = new THREE.Euler(0, 0, 0, "YXZ");
  private readonly onKeyDown = (e: KeyboardEvent) => {
    this.keys.add(e.code);
    if (e.code === "Space") {
      e.preventDefault();
    }
  };
  private readonly onKeyUp = (e: KeyboardEvent) => this.keys.delete(e.code);
  private readonly onMouseMove = (e: MouseEvent) => {
    if (document.pointerLockElement !== this.canvas) {
      return;
    }
    this.targetYaw -= e.movementX * this.sensitivity;
    this.targetPitch = THREE.MathUtils.clamp(
      this.targetPitch - e.movementY * this.sensitivity,
      -Math.PI / 2,
      Math.PI / 2,
    );
  };
  private readonly onClick = () => {
    if (document.pointerLockElement !== this.canvas) {
      void this.canvas.requestPointerLock();
    }
  };

  constructor(camera: THREE.PerspectiveCamera, canvas: HTMLCanvasElement, options: FirstPersonOptions = {}) {
    this.camera = camera;
    this.canvas = canvas;
    this.sensitivity = options.sensitivity ?? 0.0011;
    this.probe = options.probe;
    this.clampXZ = options.clampXZ;
    this.eyeHeight = options.eyeHeight ?? 1.62;
    this.walkSpeed = options.walkSpeed ?? 4.317;
    this.flatGroundY = options.groundY ?? 4;
    // 以相机当前朝向初始化，避免切换模式瞬间跳视角
    this.euler.setFromQuaternion(camera.quaternion, "YXZ");
    this.yaw = this.targetYaw = this.euler.y;
    this.pitch = this.targetPitch = this.euler.x;
    // 进场先把水平位置收进地图（自由飞行可能停在图外），再取脚下地表站好
    this.clampXZ?.(this.camera.position);
    const ground = this.surfaceAtSpawn();
    this.feetY = ground ?? this.camera.position.y - this.eyeHeight;
    this.verticalVelocity = 0;
    this.grounded = true;
    this.syncEye();
    window.addEventListener("keydown", this.onKeyDown);
    window.addEventListener("keyup", this.onKeyUp);
    window.addEventListener("mousemove", this.onMouseMove);
    canvas.addEventListener("click", this.onClick);
  }

  /**
   * 进场落点：该列最靠上的表面（自由飞行可以在图外、甚至地形内部切进来）。
   * 水比水底高，所以落在水面上开始游泳，而不是直接沉到水底。
   */
  private surfaceAtSpawn(): number | null {
    if (!this.probe) {
      return this.flatGroundY;
    }
    const x = this.camera.position.x;
    const z = this.camera.position.z;
    const solid = this.probe.topSurfaceY(x, z);
    const water = this.probe.topSurfaceY(x, z, "water");
    if (solid === null) {
      return water;
    }
    return water === null ? solid : Math.max(solid, water);
  }

  private syncEye(): void {
    this.camera.position.y = this.feetY + this.eyeHeight;
  }

  /** 当前脚下的支撑面（渲染空间 Y）；null = 探测不到地形（此时悬停，不往下掉）。 */
  private supportSurface(): number | null {
    if (!this.probe) {
      return this.flatGroundY;
    }
    // 起点抬高一档台阶：站着时挡住去路的是墙，而不是自己脚下的这层地面
    return this.probe.groundBelow(
      this.camera.position.x,
      this.camera.position.z,
      this.feetY + STEP_HEIGHT + 0.05,
    );
  }

  /**
   * 该列的水情：水面高度 + 身体是否浸在水里。
   *
   * 水面从"整列最上面"往下找（不能从头顶往下打：人沉在水下时水面在射线起点之上，
   * 单面材质从内部打不到它）。水面上方若还有实体（头顶有岩层/棚顶），说明那不是
   * 身边的水，只是同列更高处的水体，不算泡在水里。
   */
  private waterContext(): { waterTop: number | null; submerged: boolean } {
    if (!this.probe) {
      return { waterTop: null, submerged: false };
    }
    const x = this.camera.position.x;
    const z = this.camera.position.z;
    const waterTop = this.probe.topSurfaceY(x, z, "water");
    if (waterTop === null || waterTop <= this.feetY + SUBMERGE_MARGIN) {
      return { waterTop, submerged: false };
    }
    const solidTop = this.probe.topSurfaceY(x, z);
    return { waterTop, submerged: solidTop === null || solidTop <= waterTop };
  }

  /** 沿水平方向最多能推进多少（已扣掉玩家半径）；前方无碰撞则返回 maxDistance。 */
  private freeAhead(dirX: number, dirZ: number, feetY: number, maxDistance: number): number {
    if (!this.probe) {
      return maxDistance;
    }
    const reach = maxDistance + PLAYER_RADIUS;
    let free = maxDistance;
    for (const height of BODY_PROBES) {
      const hit = this.probe.castRay(
        this.camera.position.x,
        feetY + height,
        this.camera.position.z,
        dirX,
        0,
        dirZ,
        reach,
      );
      if (hit !== null) {
        free = Math.min(free, hit - PLAYER_RADIUS);
      }
    }
    return Math.max(0, free);
  }

  /**
   * 单轴推进：能走就走满，撞上东西先看能否自动上台阶（≤0.6 格），否则贴到墙边停下。
   * dx / dz 里只有一个非 0（分轴推进）。
   */
  private moveAxis(dx: number, dz: number): void {
    const length = Math.abs(dx) + Math.abs(dz);
    if (length <= EPSILON) {
      return;
    }
    const dirX = Math.sign(dx);
    const dirZ = Math.sign(dz);
    const free = this.freeAhead(dirX, dirZ, this.feetY, length);
    if (free >= length - EPSILON) {
      this.camera.position.x += dx;
      this.camera.position.z += dz;
      return;
    }
    if (this.probe) {
      // 贴住的位置再往前一列就是挡路的方块：顶面不超一档台阶就抬脚上去
      const touch = free + PLAYER_RADIUS;
      const probeX = this.camera.position.x + dirX * (touch + 0.1);
      const probeZ = this.camera.position.z + dirZ * (touch + 0.1);
      const stepGround = this.probe.groundBelow(probeX, probeZ, this.feetY + STEP_HEIGHT + 0.05);
      if (
        stepGround !== null
        && stepGround > this.feetY + EPSILON
        && stepGround <= this.feetY + STEP_HEIGHT + EPSILON
        && this.freeAhead(dirX, dirZ, stepGround, length) >= length - EPSILON
      ) {
        this.feetY = stepGround;
        this.camera.position.x += dx;
        this.camera.position.z += dz;
        return;
      }
    }
    const advance = Math.max(0, Math.min(length, free));
    this.camera.position.x += dirX * advance;
    this.camera.position.z += dirZ * advance;
  }

  private moveHorizontal(dx: number, dz: number): void {
    // 分轴推进：撞墙时另一个轴继续走 = 沿墙滑动（MC 手感）
    if (Math.abs(dx) > 0) {
      this.moveAxis(dx, 0);
    }
    if (Math.abs(dz) > 0) {
      this.moveAxis(0, dz);
    }
    this.clampXZ?.(this.camera.position);
  }

  /** 重力 / 跳跃 / 落地 / 撞头 / 浮沉。 */
  private moveVertical(dtSeconds: number, waterTop: number | null, submerged: boolean): void {
    const support = this.supportSurface();
    const before = this.feetY;
    const swimming = this.keys.has("Space");
    let velocity = this.verticalVelocity;
    let dy: number;
    if (submerged) {
      // 在水里：重力小、阻力大 —— 松手缓慢下沉，按住 Space 上浮（MC 手感）
      velocity -= WATER_GRAVITY * dtSeconds;
      if (swimming) {
        velocity = Math.max(velocity, WATER_SWIM_SPEED);
      }
      velocity = Math.max(velocity, -WATER_SINK_SPEED);
      dy = velocity * dtSeconds;
    } else {
      if (this.grounded && swimming) {
        velocity = JUMP_VELOCITY;
        this.grounded = false;
      } else if (
        !this.grounded
        && swimming
        && waterTop !== null
        && waterTop > this.feetY - WATER_EXIT_REACH
      ) {
        // 刚浮出水面还按着 Space：借力起跳，把人送上岸（MC 里能从水里跳上 1 格台阶）
        velocity = Math.max(velocity, JUMP_VELOCITY);
      }
      velocity = Math.max(velocity - GRAVITY * dtSeconds, -TERMINAL_VELOCITY);
      dy = velocity * dtSeconds;
      if (dy > 0 && this.probe) {
        // 上升撞头：头顶有方块就顶回去（树冠/屋檐下跳不起来）
        const hit = this.probe.castRay(
          this.camera.position.x,
          this.feetY + PLAYER_HEIGHT,
          this.camera.position.z,
          0,
          1,
          0,
          dy,
        );
        if (hit !== null) {
          dy = Math.max(0, hit - 0.01);
          velocity = 0;
        }
      }
    }
    this.feetY += dy;
    this.verticalVelocity = velocity;
    if (support === null) {
      // 地形未知：保持原高度悬停，等瓦片到了再落，绝不掉进虚空
      this.feetY = before;
      this.verticalVelocity = 0;
      this.grounded = true;
    } else if (this.feetY <= support) {
      this.feetY = support;
      this.verticalVelocity = 0;
      this.grounded = true;
    } else {
      this.grounded = false;
    }
    this.syncEye();
  }

  update(dtSeconds: number): void {
    // 指数平滑：k=18 时 60fps 下单帧收敛约 26%，既消抖又不拖沓
    const k = 1 - Math.exp(-18 * dtSeconds);
    this.yaw += (this.targetYaw - this.yaw) * k;
    this.pitch += (this.targetPitch - this.pitch) * k;
    this.euler.set(this.pitch, this.yaw, 0);
    this.camera.quaternion.setFromEuler(this.euler);

    // 水情：水面高过脚底一定余量才算"人在水里"（在地面/桥上不触发）
    const { waterTop, submerged } = this.waterContext();

    // 水平移动：仅取 yaw 朝向投影到地面（MC 无加速度，按下即给速度）
    const forward = new THREE.Vector3(-Math.sin(this.yaw), 0, -Math.cos(this.yaw));
    const right = new THREE.Vector3(-forward.z, 0, forward.x);
    const move = new THREE.Vector3();
    if (this.keys.has("KeyW")) move.add(forward);
    if (this.keys.has("KeyS")) move.sub(forward);
    if (this.keys.has("KeyD")) move.add(right);
    if (this.keys.has("KeyA")) move.sub(right);
    if (move.lengthSq() > 0) {
      const sprinting = this.keys.has("ShiftLeft") || this.keys.has("ControlLeft");
      const speedFactor = submerged ? WATER_SPEED_FACTOR : 1;
      move
        .normalize()
        .multiplyScalar(
          this.walkSpeed * (sprinting ? SPRINT_MULTIPLIER : 1) * speedFactor * dtSeconds,
        );
      this.moveHorizontal(move.x, move.z);
    }

    this.moveVertical(dtSeconds, waterTop, submerged);
  }

  dispose(): void {
    window.removeEventListener("keydown", this.onKeyDown);
    window.removeEventListener("keyup", this.onKeyUp);
    window.removeEventListener("mousemove", this.onMouseMove);
    this.canvas.removeEventListener("click", this.onClick);
    if (document.pointerLockElement === this.canvas) {
      document.exitPointerLock();
    }
  }
}
