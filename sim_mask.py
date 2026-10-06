#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
模拟 OreBiomePatchMask 的圆斑遮罩，量出各档位实际切出来的片区直径、覆盖率和
最大的「空隙」（被圆斑漏掉、又不在窗口边上的那一片普通地形有多大）。

纯标准库，逐步复刻 Java 版的 64 位哈希与噪声，保证和游戏里一致。

用法：python3 sim_mask.py [L[:概率:最小半径:最大半径] ...]
      不传参数时按 OreBiomeSettings 里四档的实际参数各量一遍。
"""
import math
import sys
from collections import deque

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
    (184.0, 0.22, 0.40, 0.53, "小，标称 160"),
    (368.0, 0.25, 0.40, 0.53, "中，标称 320"),
    (736.0, 0.45, 0.44, 0.58, "大，标称 640"),
    (2944.0, 1.0, 0.75, 0.90, "超大，几乎铺满"),
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
                      probability=0.22, r_min=0.40, r_max=0.53):
    """枚举窗口及其外圈一格内的所有圆斑（圆心、半径）。"""
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


def build_mask(lattice, seed, size, step,
               probability=0.22, r_min=0.40, r_max=0.53):
    """采样一张 size×size（世界坐标 [0,size)²，步长 step）的斑内/斑外图。"""
    n = size // step
    grid = bytearray(n * n)
    cell_a = lattice * WOBBLE_CELL_A
    cell_b = lattice * WOBBLE_CELL_B
    for cx, cz, r in patches_in_window(lattice, seed, 0, size, 0, size,
                                       probability, r_min, r_max):
        pad = r * 1.35  # 含起伏的最大外扩，取 1.25 再加余量
        gx0 = max(0, int(math.floor((cx - pad - OFFSET_X) / step)))
        gx1 = min(n - 1, int(math.ceil((cx + pad - OFFSET_X) / step)))
        gz0 = max(0, int(math.floor((cz - pad - OFFSET_Z) / step)))
        gz1 = min(n - 1, int(math.ceil((cz + pad - OFFSET_Z) / step)))
        for gi in range(gx0, gx1 + 1):
            wx = gi * step + step / 2
            for gj in range(gz0, gz1 + 1):
                wz = gj * step + step / 2
                dx = wx - cx
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


def components(grid, n):
    """4 邻域连通分量；返回 (面积像素数, 是否贴边) 列表。"""
    seen = bytearray(n * n)
    comps = []
    for start in range(n * n):
        if grid[start] == 0 or seen[start]:
            continue
        seen[start] = 1
        q = deque([start])
        area = 0
        touches = False
        while q:
            idx = q.popleft()
            area += 1
            gi = idx % n
            gj = idx // n
            if gi == 0 or gj == 0 or gi == n - 1 or gj == n - 1:
                touches = True
            for nidx in (idx - 1, idx + 1, idx - n, idx + n):
                if nidx < 0 or nidx >= n * n:
                    continue
                # 禁止跨行环绕
                if abs(nidx % n - gi) > 1 and abs(nidx - idx) == 1:
                    continue
                if grid[nidx] and not seen[nidx]:
                    seen[nidx] = 1
                    q.append(nidx)
        comps.append((area, touches))
    return comps


def holes(grid, n):
    """圆斑漏掉的那些连通空地；把斑内/斑外反过来再找连通分量。"""
    inv = bytearray(1 - v for v in grid)
    return components(inv, n)


def diameter(area_cells, pixel_area):
    return 2.0 * math.sqrt(area_cells * pixel_area / math.pi)


def run(lattice, seed=12345, size=16384, step=16, label="",
        probability=0.22, r_min=0.40, r_max=0.53, show_all=False):
    grid, n = build_mask(lattice, seed, size, step, probability, r_min, r_max)
    comps = components(grid, n)
    pixel_area = step * step
    diameters = sorted(diameter(a, pixel_area)
                       for a, touches in comps if not touches and a * pixel_area > pixel_area)
    covered = sum(grid) * pixel_area
    total = size * size

    hole_list = sorted(diameter(a, pixel_area) for a, touches in holes(grid, n) if not touches)
    biggest_hole = hole_list[-1] if hole_list else 0.0

    print(f"格距 L={lattice:.0f} {label}: 出斑率 {probability:.2f} 半径 {r_min:.2f}~{r_max:.2f}L，"
          f"覆盖率 {100.0 * covered / total:.1f}%，完整片区 {len(diameters)} 个，"
          f"最大空隙直径 {biggest_hole:.0f} 格")
    if diameters:
        med = diameters[len(diameters) // 2]
        print(f"  片区等效直径（格）: 最小 {diameters[0]:.0f} / 中位 {med:.0f} / 最大 {diameters[-1]:.0f}")
        if show_all:
            print("  全部: " + ", ".join(f"{d:.0f}" for d in diameters))
    return diameters


if __name__ == "__main__":
    if len(sys.argv) > 1:
        for raw in sys.argv[1:]:
            parts = raw.split(":")
            lat = float(parts[0])
            prob = float(parts[1]) if len(parts) > 1 else 0.22
            rmin = float(parts[2]) if len(parts) > 2 else 0.40
            rmax = float(parts[3]) if len(parts) > 3 else 0.53
            run(lat, probability=prob, r_min=rmin, r_max=rmax, show_all=True)
    else:
        for lat, prob, rmin, rmax, label in FOUR_TIERS:
            step = 8 if lat <= 1500 else 24
            size = 16384 if lat <= 1500 else 32768
            run(lat, size=size, step=step, label=label,
                probability=prob, r_min=rmin, r_max=rmax)
