package com.inf.farlands.terrain.system.terrain.noise.overworld.Vanilla263;

import it.unimi.dsi.fastutil.floats.Float2FloatFunction;
import net.minecraft.util.Mth;

/**
 * 26.3 的 TerrainProvider float 版，主世界的三条地形样条都在这里。
 *
 * <p>这一份就是地形的形状：偏移、因子、锯齿度三条样条按大陆度、侵蚀度、异常度与山脊度查表。数值与
 * 26.3 逐位对齐的难点不在算法，而在这几十个字面量，任何一个写成 double 都会让整片地形偏一点。
 *
 * <p>原版对「放大化」预设用三个值变换把海岸与被侵蚀的地形一起拉伸，本类保留同样三个变换。
 */
final class TerrainProvider263 {

    /** 放大化的偏移变换：负值不动，正值加倍。 */
    private static final Float2FloatFunction AMPLIFIED_OFFSET = offset -> offset < 0.0F ? offset
            : offset * 2.0F;

    /** 放大化的因子变换。 */
    private static final Float2FloatFunction AMPLIFIED_FACTOR = factor -> 1.25F - 6.25F / (factor + 5.0F);

    /** 放大化的锯齿变换。 */
    private static final Float2FloatFunction AMPLIFIED_JAGGEDNESS = jaggedness -> jaggedness * 2.0F;

    private TerrainProvider263() {
    }

    static CubicSpline263 overworldOffset(FloatNode263 continents, FloatNode263 erosion, FloatNode263 ridges,
            boolean amplified) {
        Float2FloatFunction transformer = amplified ? AMPLIFIED_OFFSET : Float2FloatFunction.identity();
        CubicSpline263 beach = buildErosionOffsetSpline(erosion, ridges, -0.15F, 0.0F, 0.0F, 0.1F, 0.0F,
                -0.03F, false, false, transformer);
        CubicSpline263 low = buildErosionOffsetSpline(erosion, ridges, -0.1F, 0.03F, 0.1F, 0.1F, 0.01F, -0.03F,
                false, false, transformer);
        CubicSpline263 mid = buildErosionOffsetSpline(erosion, ridges, -0.1F, 0.03F, 0.1F, 0.7F, 0.01F, -0.03F,
                true, true, transformer);
        CubicSpline263 high = buildErosionOffsetSpline(erosion, ridges, -0.05F, 0.03F, 0.1F, 1.0F, 0.01F, 0.01F,
                true, true, transformer);
        return CubicSpline263.builder(continents, transformer)
                .addPoint(-1.1F, 0.044F)
                .addPoint(-1.02F, -0.2222F)
                .addPoint(-0.51F, -0.2222F)
                .addPoint(-0.44F, -0.12F)
                .addPoint(-0.18F, -0.12F)
                .addPoint(-0.16F, beach)
                .addPoint(-0.15F, beach)
                .addPoint(-0.1F, low)
                .addPoint(0.25F, mid)
                .addPoint(1.0F, high)
                .build();
    }

    static CubicSpline263 overworldFactor(FloatNode263 continents, FloatNode263 erosion, FloatNode263 weirdness,
            FloatNode263 ridges, boolean amplified) {
        Float2FloatFunction transformer = amplified ? AMPLIFIED_FACTOR : Float2FloatFunction.identity();
        return CubicSpline263.builder(continents, Float2FloatFunction.identity())
                .addPoint(-0.19F, 3.95F)
                .addPoint(-0.15F,
                        getErosionFactor(erosion, weirdness, ridges, 6.25F, true, Float2FloatFunction.identity()))
                .addPoint(-0.1F, getErosionFactor(erosion, weirdness, ridges, 5.47F, true, transformer))
                .addPoint(0.03F, getErosionFactor(erosion, weirdness, ridges, 5.08F, true, transformer))
                .addPoint(0.06F, getErosionFactor(erosion, weirdness, ridges, 4.69F, false, transformer))
                .build();
    }

    static CubicSpline263 overworldJaggedness(FloatNode263 continents, FloatNode263 erosion,
            FloatNode263 weirdness, FloatNode263 ridges, boolean amplified) {
        Float2FloatFunction transformer = amplified ? AMPLIFIED_JAGGEDNESS : Float2FloatFunction.identity();
        return CubicSpline263.builder(continents, transformer)
                .addPoint(-0.11F, 0.0F)
                .addPoint(0.03F,
                        buildErosionJaggednessSpline(erosion, weirdness, ridges, 1.0F, 0.5F, 0.0F, 0.0F,
                                transformer))
                .addPoint(0.65F,
                        buildErosionJaggednessSpline(erosion, weirdness, ridges, 1.0F, 1.0F, 1.0F, 0.0F,
                                transformer))
                .build();
    }

    private static CubicSpline263 buildErosionJaggednessSpline(FloatNode263 erosion, FloatNode263 weirdness,
            FloatNode263 ridges, float atPeakErosion0, float atPeakErosion1, float atHighErosion0,
            float atHighErosion1, Float2FloatFunction transformer) {
        CubicSpline263 atErosion0 = buildRidgeJaggednessSpline(weirdness, ridges, atPeakErosion0, atHighErosion0,
                transformer);
        CubicSpline263 atErosion1 = buildRidgeJaggednessSpline(weirdness, ridges, atPeakErosion1, atHighErosion1,
                transformer);
        return CubicSpline263.builder(erosion, transformer)
                .addPoint(-1.0F, atErosion0)
                .addPoint(-0.78F, atErosion1)
                .addPoint(-0.5775F, atErosion1)
                .addPoint(-0.375F, 0.0F)
                .build();
    }

    /** 山脊折叠，与节点版的算式等价，只是这里按标量用。 */
    static float peaksAndValleys(float weirdness) {
        return -(Math.abs(Math.abs(weirdness) - 0.6666667F) - 0.33333334F) * 3.0F;
    }

    private static CubicSpline263 buildRidgeJaggednessSpline(FloatNode263 weirdness, FloatNode263 ridges,
            float atPeakRidge, float atHighRidge, Float2FloatFunction transformer) {
        float highStart = peaksAndValleys(0.4F);
        float highEnd = peaksAndValleys(0.56666666F);
        float highMiddle = (highStart + highEnd) / 2.0F;
        CubicSpline263.Builder263 ridgeSpline = CubicSpline263.builder(ridges, transformer);
        ridgeSpline.addPoint(highStart, 0.0F);
        if (atHighRidge > 0.0F) {
            ridgeSpline.addPoint(highMiddle, buildWeirdnessJaggednessSpline(weirdness, atHighRidge, transformer));
        } else {
            ridgeSpline.addPoint(highMiddle, 0.0F);
        }
        if (atPeakRidge > 0.0F) {
            ridgeSpline.addPoint(1.0F, buildWeirdnessJaggednessSpline(weirdness, atPeakRidge, transformer));
        } else {
            ridgeSpline.addPoint(1.0F, 0.0F);
        }
        return ridgeSpline.build();
    }

    private static CubicSpline263 buildWeirdnessJaggednessSpline(FloatNode263 weirdness, float factor,
            Float2FloatFunction transformer) {
        return CubicSpline263.builder(weirdness, transformer)
                .addPoint(-0.01F, 0.63F * factor)
                .addPoint(0.01F, 0.3F * factor)
                .build();
    }

    private static CubicSpline263 getErosionFactor(FloatNode263 erosion, FloatNode263 weirdness,
            FloatNode263 ridges, float baseValue, boolean shatteredTerrain, Float2FloatFunction transformer) {
        CubicSpline263 base = CubicSpline263.builder(weirdness, transformer)
                .addPoint(-0.2F, 6.3F)
                .addPoint(0.2F, baseValue)
                .build();
        CubicSpline263.Builder263 erosionPoints = CubicSpline263.builder(erosion, transformer)
                .addPoint(-0.6F, base)
                .addPoint(-0.5F, CubicSpline263.builder(weirdness, transformer)
                        .addPoint(-0.05F, 6.3F)
                        .addPoint(0.05F, 2.67F)
                        .build())
                .addPoint(-0.35F, base)
                .addPoint(-0.25F, base)
                .addPoint(-0.1F, CubicSpline263.builder(weirdness, transformer)
                        .addPoint(-0.05F, 2.67F)
                        .addPoint(0.05F, 6.3F)
                        .build())
                .addPoint(0.03F, base);
        if (shatteredTerrain) {
            CubicSpline263 weirdnessShattered = CubicSpline263.builder(weirdness, transformer)
                    .addPoint(0.0F, baseValue)
                    .addPoint(0.1F, 0.625F)
                    .build();
            CubicSpline263 ridgesShattered = CubicSpline263.builder(ridges, transformer)
                    .addPoint(-0.9F, baseValue)
                    .addPoint(-0.69F, weirdnessShattered)
                    .build();
            erosionPoints.addPoint(0.35F, baseValue)
                    .addPoint(0.45F, ridgesShattered)
                    .addPoint(0.55F, ridgesShattered)
                    .addPoint(0.62F, baseValue);
        } else {
            CubicSpline263 extremeHillsFromMidSliceAndUp = CubicSpline263.builder(ridges, transformer)
                    .addPoint(-0.7F, base)
                    .addPoint(-0.15F, 1.37F)
                    .build();
            CubicSpline263 extra3dNoiseOnPeaksOnly = CubicSpline263.builder(ridges, transformer)
                    .addPoint(0.45F, base)
                    .addPoint(0.7F, 1.56F)
                    .build();
            erosionPoints.addPoint(0.05F, extra3dNoiseOnPeaksOnly)
                    .addPoint(0.4F, extra3dNoiseOnPeaksOnly)
                    .addPoint(0.45F, extremeHillsFromMidSliceAndUp)
                    .addPoint(0.55F, extremeHillsFromMidSliceAndUp)
                    .addPoint(0.58F, baseValue);
        }
        return erosionPoints.build();
    }

    private static float calculateSlope(float y1, float y2, float x1, float x2) {
        return (y2 - y1) / (x2 - x1);
    }

    private static CubicSpline263 buildMountainRidgeSplineWithPoints(FloatNode263 ridges, float modulation,
            boolean saddle, Float2FloatFunction transformer) {
        CubicSpline263.Builder263 build = CubicSpline263.builder(ridges, transformer);
        float minContinentalness = mountainContinentalness(-1.0F, modulation);
        float maxContinentalness = mountainContinentalness(1.0F, modulation);
        float ridgeZeroPoint = calculateMountainRidgeZeroContinentalnessPoint(modulation);
        if (-0.65F < ridgeZeroPoint && ridgeZeroPoint < 1.0F) {
            float afterRiverContinentalness = mountainContinentalness(-0.65F, modulation);
            float beforeRiverContinentalness = mountainContinentalness(-0.75F, modulation);
            float minDerivative = calculateSlope(minContinentalness, beforeRiverContinentalness, -1.0F, -0.75F);
            build.addPoint(-1.0F, minContinentalness, minDerivative);
            build.addPoint(-0.75F, beforeRiverContinentalness);
            build.addPoint(-0.65F, afterRiverContinentalness);
            float ridgeZeroContinentalness = mountainContinentalness(ridgeZeroPoint, modulation);
            float maxDerivative = calculateSlope(ridgeZeroContinentalness, maxContinentalness, ridgeZeroPoint,
                    1.0F);
            build.addPoint(ridgeZeroPoint - 0.01F, ridgeZeroContinentalness);
            build.addPoint(ridgeZeroPoint, ridgeZeroContinentalness, maxDerivative);
            build.addPoint(1.0F, maxContinentalness, maxDerivative);
        } else {
            float simpleDerivative = calculateSlope(minContinentalness, maxContinentalness, -1.0F, 1.0F);
            if (saddle) {
                build.addPoint(-1.0F, Math.max(0.2F, minContinentalness));
                build.addPoint(0.0F, Mth.lerp(0.5F, minContinentalness, maxContinentalness), simpleDerivative);
            } else {
                build.addPoint(-1.0F, minContinentalness, simpleDerivative);
            }
            build.addPoint(1.0F, maxContinentalness, simpleDerivative);
        }
        return build.build();
    }

    /** 把山脊度换算成大陆度。河流阈值以下有一条下限，这条下限就是「河流切谷」的成形处。 */
    private static float mountainContinentalness(float ridge, float modulation) {
        float ridgeSlope = 1.0F - (1.0F - modulation) * 0.5F;
        float ridgeIntersect = 0.5F * (1.0F - modulation);
        float adjustedRidgeHeight = (ridge + 1.17F) * 0.46082947F;
        float continentalness = adjustedRidgeHeight * ridgeSlope - ridgeIntersect;
        return ridge < -0.7F ? Math.max(continentalness, -0.2222F) : Math.max(continentalness, 0.0F);
    }

    private static float calculateMountainRidgeZeroContinentalnessPoint(float modulation) {
        float ridgeSlope = 1.0F - (1.0F - modulation) * 0.5F;
        float ridgeIntersect = 0.5F * (1.0F - modulation);
        return ridgeIntersect / (0.46082947F * ridgeSlope) - 1.17F;
    }

    private static CubicSpline263 buildErosionOffsetSpline(FloatNode263 erosion, FloatNode263 ridges,
            float lowValley, float hill, float tallHill, float mountainFactor, float plain, float swamp,
            boolean includeExtremeHills, boolean saddle, Float2FloatFunction transformer) {
        CubicSpline263 veryLowErosionMountains = buildMountainRidgeSplineWithPoints(ridges,
                Mth.lerp(mountainFactor, 0.6F, 1.5F), saddle, transformer);
        CubicSpline263 lowErosionMountains = buildMountainRidgeSplineWithPoints(ridges,
                Mth.lerp(mountainFactor, 0.6F, 1.0F), saddle, transformer);
        CubicSpline263 mountains = buildMountainRidgeSplineWithPoints(ridges, mountainFactor, saddle, transformer);
        CubicSpline263 widePlateau = ridgeSpline(ridges, lowValley - 0.15F, 0.5F * mountainFactor,
                Mth.lerp(0.5F, 0.5F, 0.5F) * mountainFactor, 0.5F * mountainFactor, 0.6F * mountainFactor, 0.5F,
                transformer);
        CubicSpline263 narrowPlateau = ridgeSpline(ridges, lowValley, plain * mountainFactor,
                hill * mountainFactor, 0.5F * mountainFactor, 0.6F * mountainFactor, 0.5F, transformer);
        CubicSpline263 plains = ridgeSpline(ridges, lowValley, plain, plain, hill, tallHill, 0.5F, transformer);
        CubicSpline263 plainsFarInland = ridgeSpline(ridges, lowValley, plain, plain, hill, tallHill, 0.5F,
                transformer);
        CubicSpline263 extremeHills = CubicSpline263.builder(ridges, transformer)
                .addPoint(-1.0F, lowValley)
                .addPoint(-0.4F, plains)
                .addPoint(0.0F, tallHill + 0.07F)
                .build();
        CubicSpline263 swamps = ridgeSpline(ridges, -0.02F, swamp, swamp, hill, tallHill, 0.0F, transformer);
        CubicSpline263.Builder263 builder = CubicSpline263.builder(erosion, transformer)
                .addPoint(-0.85F, veryLowErosionMountains)
                .addPoint(-0.7F, lowErosionMountains)
                .addPoint(-0.4F, mountains)
                .addPoint(-0.35F, widePlateau)
                .addPoint(-0.1F, narrowPlateau)
                .addPoint(0.2F, plains);
        if (includeExtremeHills) {
            builder.addPoint(0.4F, plainsFarInland)
                    .addPoint(0.45F, extremeHills)
                    .addPoint(0.55F, extremeHills)
                    .addPoint(0.58F, plainsFarInland);
        }
        builder.addPoint(0.7F, swamps);
        return builder.build();
    }

    private static CubicSpline263 ridgeSpline(FloatNode263 ridges, float valley, float low, float mid, float high,
            float peaks, float minValleySteepness, Float2FloatFunction transformer) {
        float d1 = Math.max(0.5F * (low - valley), minValleySteepness);
        float d2 = 5.0F * (mid - low);
        return CubicSpline263.builder(ridges, transformer)
                .addPoint(-1.0F, valley, d1)
                .addPoint(-0.4F, low, Math.min(d1, d2))
                .addPoint(0.0F, mid, d2)
                .addPoint(0.4F, high, 2.0F * (high - mid))
                .addPoint(1.0F, peaks, 0.7F * (peaks - high))
                .build();
    }
}
