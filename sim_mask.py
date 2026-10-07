#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
模拟 OreBiomePatchMask 的圆斑遮罩，量出各档位切出来的片区直径、覆盖率和
「平均走多远能遇到一片」。

纯标准库，逐步复刻 Java 版的 64 位哈希与噪声，保证和游戏里一致。

用法：python3 sim_mask.py [L[:概率:最小半径:最大半径] ...]
      不传参数时按 OreBiomeSettings 里四档的实际参数各量一遍。

注意：FOUR_TIERS 必须和 OreBiomeSettings.BiomeSize 里那四行保持一致，
改档位参数时这里要跟着改，否则量出来的数字就骗人了。

早先这版脚本有个坐标换算 bug：算每片圆斑占用的格子范围时多减了一次
OFFSET_X/OFFSET_Z，填充区域整体偏了 170 多格，只覆盖到圆的一半，
把中档 15% 的覆盖率报成了 0.2%。现在统一用世界坐标算，不再减偏移。
"""
import math
import sys

M64 = (1 << 64) - 1

OFFSET_X = 173.0
OFFSET_Z = -421.0
SALT_PRESENT = 0
SALT_JITTER_X = 0x5DEECE66D
SALT_JITTER_Z = 0x0E52B9D7
SALT_RADIUS = 0x2545F491
WOBBLE_CELL_A = 0.9
WOBBLE_CELL_B = 2.3
WOBBLE_AMP_A = 0.10
WOBBLE_AMP_B = 0.06
NOISE_SALT = 0xB16B00B5

# 四档的真实参数，必须和 OreBiomeSettings.BiomeSize 保持一致
FOUR_TIERS = [
    (184.0, 0.00050, 0.40, 0.53, "小，标称 170"),
    (368.0, 0.00198, 0.40, 0.53, "中，标称 340"),
    (736.0, 0.00790, 0.40, 0.53, "大，标称 690"),
    (1472.0, 0.01750, 0.55, 0.70, "超大，标称 1840"),
]

# 旧参数，留着做对照（改惨了可以看着这个数往回找）
LEGACY_TIERS = [
    (184.0, 0.22, 0.40, 0.53, "小（旧）"),
    (368.0, 0.22, 0.40, 0.53, "中（旧）"),
    (736.0, 0.22, 0.40, 0.53, "大（旧）"),
    (2944.0, 1.00, 0.75, 0.90, "超大（旧，铺满）"),
]


def hash64(x, z, seed):
    v = (((x * 0x9E3779B97F4A7C15) ^ (z * 0xC2B2AE3D27D4EB4F) ^ (seed * 0xD1B54A32D192ED03)) & M64)
    v ^= v >> 30
    v = (v * 0xBF58476D1CE4E5B9) & M64
    v ^= v >> 27
    v = (v * 0x94D049BB133111EB) & M64
    v ^= v >> 31
    return (v >> 11) / (1 << 53)


def smooth(t):
    return t * t * (3.0 - 2.0 * t)


def value_noise(x, z, cell, seed):
    cx = math.floor(x / cell)
    cz = math.floor(z / cell)
    tx = smooth(x / cell - cx)
    tz = smooth(z / cell - cz)
    s = seed ^ NOISE_SALT
    a = hash64(cx, cz, s)
    b = hash64(cx + 1, cz, s)
    c = hash64(cx, cz + 1, s)
    d = hash64(cx + 1, cz + 1, s)
    top = a + (b - a) * tx
    bottom = c + (d - c) * tx
    return top + (bottom - top) * tz


def patches_in_window(lattice, seed, x0, x1, z0, z1,
                      probability, r_min, r_max):
    """枚举窗口及其外圈一格内的所有圆斑（世界坐标圆心、半径）。"""
    ci0 = math.floor((x0 + OFFSET_X) / lattice) - 1
    ci1 = math.floor((x1 + OFFSET_X) / lattice) + 1
    cj0 = math.floor((z0 + OFFSET_Z) / lattice) - 1
    cj1 = math.floor((z1 + OFFSET_Z) / lattice) + 1
    out = []
    for ci in range(ci0, ci1 + 1):
        for cj in range(cj0, cj1 + 1):
            if hash64(ci, cj, seed ^ SALT_PRESENT) >= probability:
                continue
            jx = hash64(ci, cj, seed ^ SALT_JITTER_X)
            jz = hash64(ci, cj, seed ^ SALT_JITTER_Z)
            rh = hash64(ci, cj, seed ^ SALT_RADIUS)
            cx = (ci + 0.5 + (jx - 0.5) * 0.55) * lattice + OFFSET_X
            cz = (cj + 0.5 + (jz - 0.5) * 0.55) * lattice + OFFSET_Z
            r = (r_min + (r_max - r_min) * rh) * lattice
            out.append((cx, cz, r))
    return out


def build_mask(lattice, seed, size, step, probability, r_min, r_max):
    """采样一张 size×size 的斑内/斑外图；网格点按世界坐标对齐，单位是 step 格。"""
    n = size // step
    grid = bytearray(n * n)
    cell_a = lattice * WOBBLE_CELL_A
    cell_b = lattice * WOBBLE_CELL_B
    for cx, cz, r in patches_in_window(lattice, seed, 0, size, 0, size,
                                       probability, r_min, r_max):
        # 圆斑含起伏后的最大外扩：半径上限 1.25 倍再加一点余量
        pad = r * 1.30
        gx0 = max(0, int(math.floor((cx - pad) / step)))
        gx1 = min(n - 1, int(math.ceil((cx + pad) / step)))
        gz0 = max(0, int(math.floor((cz - pad) / step)))
        gz1 = min(n - 1, int(math.ceil((cz + pad) / step)))
        for gi in range(gx0, gx1 + 1):
            wx = gi * step + step / 2
            dx = wx - cx
            for gj in range(gz0, gz1 + 1):
                wz = gj * step + step / 2
                dz = wz - cz
                d2 = dx * dx + dz * dz
                max_r = r * 1.25
                if d2 > max_r * max_r:
                    continue
                wob = (WOBBLE_AMP_A * (value_noise(wx, wz, cell_a, seed) - 0.5) * 2.0
                       + WOBBLE_AMP_B * (value_noise(wx, wz, cell_b, seed) - 0.5) * 2.0)
                edge = r * (1.0 + wob)
                if d2 <= edge * edge:
                    grid[gj * n + gi] = 1
    return grid, n


def run(lattice, seed=12345, size=None, step=None, label="",
        probability=0.22, r_min=0.40, r_max=0.53):
    # 采样格点数固定在一千六百万上下：L 越大，窗口和步长一起放大，
    # 保证小档也能抽到足够多的圆斑来估计覆盖率。
    if step is None:
        step = max(4, round(lattice / 9.2))
    if size is None:
        size = step * 1600
    grid, n = build_mask(lattice, seed, size, step,
                         probability, r_min, r_max)
    sampled = n * n
    covered = sum(grid)
    coverage = covered / sampled
    pixel_area = step * step
    patches = patches_in_window(lattice, seed, 0, size, 0, size,
                                probability, r_min, r_max)
    inside = [p for p in patches if 0 <= p[0] < size and 0 <= p[1] < size]
    diameters = sorted(2.0 * r for _, _, r in inside)

    print(f"格距 L={lattice:.0f} {label}: 出斑率 {probability:.5f} "
          f"半径 {r_min:.2f}~{r_max:.2f}L")
    print(f"  覆盖率 {100.0 * coverage:.3f}%（窗口 {size}×{size}，采样 {sampled} 点，"
          f"窗口内 {len(inside)} 片）")
    if coverage > 0:
        spacing = lattice / math.sqrt(coverage)
        print(f"  平均间距 ≈ {spacing:.0f} 格（走这么远会遇到下一片）")
    if diameters:
        med = diameters[len(diameters) // 2]
        print(f"  片区直径（格）: 中位 {med:.0f} / 最大 {diameters[-1]:.0f}"
              f"（标称 2×半径 = {2 * r_min * lattice:.0f}~{2 * r_max * lattice:.0f}）")
    return coverage


if __name__ == "__main__":
    if len(sys.argv) > 1:
        for raw in sys.argv[1:]:
            parts = raw.split(":")
            lat = float(parts[0])
            prob = float(parts[1]) if len(parts) > 1 else 0.002
            rmin = float(parts[2]) if len(parts) > 2 else 0.40
            rmax = float(parts[3]) if len(parts) > 3 else 0.53
            run(lat, probability=prob, r_min=rmin, r_max=rmax)
    else:
        print("=== 当前四档（稀有化后）===")
        for lat, prob, rmin, rmax, label in FOUR_TIERS:
            run(lat, probability=prob, r_min=rmin, r_max=rmax, label=label)
        print()
        print("=== 旧参数对照 ===")
        # 旧的超大档（2944 格距 + 出斑率 1.0）几乎铺满整片地面，跑起来很慢也没参考价值，
        # 这里只对照前三档。
        for lat, prob, rmin, rmax, label in LEGACY_TIERS[:3]:
            run(lat, probability=prob, r_min=rmin, r_max=rmax, label=label)
