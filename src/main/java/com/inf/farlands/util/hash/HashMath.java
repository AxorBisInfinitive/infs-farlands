package com.inf.farlands.util.hash;

/**
 * HashMath
 *
 * @see <a href="https://github.com/AxorBisInfinitive/hash-proof">证明</a>
 */
public final class HashMath {
    private HashMath() {
    }

    public static long hash(long x, long y, long z) {
        long xb = ((y & 0xFFFFFFFFL) << 32) | (z & 0xFFFFFFFFL);
        long ha = x & 0xFFFFFFFFL;
        ha ^= ha >>> 33;
        ha *= 0xFF51AFD7ED558CCDL;
        ha ^= ha >>> 33;
        ha *= 0xC4CEB9FE1A85EC53L;
        ha ^= ha >>> 33;
        // 外层 finalizer 即 fmix 双射包层，保持 P(y,z)^fmix(x) 无仿射恒等碰撞；
        // 证明的关键是碰撞方程不变，完整 key 高位充分混合 -> CHM 桶分布均匀
        long h = xb ^ ha;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }
}
