package cn.blockforge.generated.orebiomereborn.worldgen;

import java.util.concurrent.atomic.AtomicLong;

/** 记录群系注入每一步的实际计数，方便在游戏内确认卡在哪一道判断。 */
public final class OreBiomeDiagnostics {
    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong PROTECTED = new AtomicLong();
    private static final AtomicLong MASK_REJECTED = new AtomicLong();
    private static final AtomicLong NON_LAND = new AtomicLong();
    private static final AtomicLong HOLDER_MISSING = new AtomicLong();
    private static final AtomicLong REPLACED = new AtomicLong();

    private OreBiomeDiagnostics() {
    }

    public static void reset() {
        CALLS.set(0L);
        PROTECTED.set(0L);
        MASK_REJECTED.set(0L);
        NON_LAND.set(0L);
        HOLDER_MISSING.set(0L);
        REPLACED.set(0L);
    }

    public static void call() {
        CALLS.incrementAndGet();
    }

    public static void protectedArea() {
        PROTECTED.incrementAndGet();
    }

    public static void maskRejected() {
        MASK_REJECTED.incrementAndGet();
    }

    public static void nonLand() {
        NON_LAND.incrementAndGet();
    }

    public static void holderMissing() {
        HOLDER_MISSING.incrementAndGet();
    }

    public static void replaced() {
        REPLACED.incrementAndGet();
    }

    public static long calls() {
        return CALLS.get();
    }

    public static long protectedAreaCount() {
        return PROTECTED.get();
    }

    public static long maskRejectedCount() {
        return MASK_REJECTED.get();
    }

    public static long nonLandCount() {
        return NON_LAND.get();
    }

    public static long holderMissingCount() {
        return HOLDER_MISSING.get();
    }

    public static long replacedCount() {
        return REPLACED.get();
    }
}
