package cn.blockforge.generated.orebiomereborn.worldgen;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记录「区块群系重写」每一步的实际计数，方便在游戏内确认卡在哪一道判断。
 *
 * <p>这些计数是给 {@code /orebiome status} 用的：只要 {@link #chunkSeen()} 涨过，
 * 就说明区块生成钩子确实在跑，问题只可能在后面的判断上；一次都没涨，才说明钩子整条没生效。</p>
 */
public final class OreBiomeDiagnostics {

    /** 被钩子处理过的区块数。 */
    private static final AtomicLong CHUNKS = new AtomicLong();
    /** 出生点保护圈内、没动的群系格数。 */
    private static final AtomicLong PROTECTED = new AtomicLong();
    /** 圆斑遮罩外、没动的群系格数。 */
    private static final AtomicLong MASK_REJECTED = new AtomicLong();
    /** 遮罩内但原版是水域/海岸、没动的群系格数。 */
    private static final AtomicLong NON_LAND = new AtomicLong();
    /** 群系表还没就绪（拿不到矿石群系句柄）而跳过的次数。 */
    private static final AtomicLong HOLDER_MISSING = new AtomicLong();
    /** 真正被换成矿石群系的群系格数。 */
    private static final AtomicLong REPLACED = new AtomicLong();

    /**
     * 区块生成钩子是否真的被调用过。
     *
     * <p>这是判断「替换功能到底有没有生效」的铁证：只要区块生成走过一次钩子就会被置为
     * true；整条注入没生效时它永远是 false，而游戏不会报任何错。</p>
     */
    private static final AtomicBoolean ALIVE = new AtomicBoolean();

    /** 当前世界实际用的群系源类名，排错时一眼就能看出是不是被别的模组换掉了。 */
    private static volatile String biomeSourceClass = "未知";

    private OreBiomeDiagnostics() {
    }

    /**
     * 进入新世界时把计数清零。
     *
     * <p>注意 {@code ALIVE} 不在这里清：它回答的是「这套注入在当前游戏进程里到底有没有
     * 生效」，是进程级的事实。如果每次进世界都清掉，玩家站在已经生成好的老区块里不动时，
     * 自检就会误报「没有生效」——那只是这个世界这次登录还没生成过新区块而已。</p>
     */
    public static void reset() {
        CHUNKS.set(0L);
        PROTECTED.set(0L);
        MASK_REJECTED.set(0L);
        NON_LAND.set(0L);
        HOLDER_MISSING.set(0L);
        REPLACED.set(0L);
        biomeSourceClass = "未知";
    }

    /** 区块生成钩子被调用时点亮。 */
    public static void chunkSeen() {
        CHUNKS.incrementAndGet();
        ALIVE.set(true);
    }

    /** 区块生成钩子是否被调用过（注入是否生效）。 */
    public static boolean injectionAlive() {
        return ALIVE.get();
    }

    /** 记下这次查询用到的群系源类名。 */
    public static void noteBiomeSource(String className) {
        if (className != null && !className.isEmpty()) {
            biomeSourceClass = className;
        }
    }

    /** 当前世界实际用的群系源类名。 */
    public static String biomeSourceClass() {
        return biomeSourceClass;
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

    public static long chunks() {
        return CHUNKS.get();
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
