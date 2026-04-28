package me.cerial.nova.cloudcombat.engine;

public final class CombatEngineConfig {

    private final boolean enabled;

    private final long evaluationIntervalMs;
    private final long combatWindowMs;
    private final long shortWindowMs;
    private final long mediumWindowMs;
    private final long longWindowMs;

    private final int minAttacksShort;
    private final int minAttacksMedium;
    private final int minRotationsMedium;
    private final int minRotationDeltasMedium;
    private final int minSwitchReactionSamples;
    private final int minDistanceSamples;
    private final int minCheckDeltaEvents;
    private final int minDistinctFamilies;
    private final int minSuspiciousStreak;

    private final double weightAttackRate;
    private final double weightIntervalConsistency;
    private final double weightTargetSwitchBurst;
    private final double weightBlockHitOverlap;
    private final double weightInventoryHitOverlap;
    private final double weightRotationHitCadence;
    private final double weightNoSwingRatio;
    private final double weightMultiInteractBurst;
    private final double weightIntervalEntropy;
    private final double weightRotationDuplicate;
    private final double weightRotationQuantization;
    private final double weightInstantRotation;
    private final double weightSwitchReaction;
    private final double weightLowAcceleration;
    private final double weightSpeedTrackingCoupling;
    private final double weightShortBurst;
    private final double weightHitAccuracyPressure;
    private final double weightCheckDelta;
    private final double weightFamilyDiversity;
    private final double weightMicroCorrectionDeficit;
    private final double weightLowJerk;
    private final double weightAxisImbalance;
    private final double weightSnapSettlePattern;
    private final double weightSnapHitPressure;

    private final double scoreFlagThreshold;
    private final double scoreCarryOver;
    private final double scoreDecayFactor;
    private final double scoreStrictMultiplier;
    private final double maxScore;

    private final int pingHighThresholdMs;
    private final int pingExtremeThresholdMs;
    private final long pingSpikeLookbackMs;
    private final double pingHighPenalty;
    private final double pingExtremePenalty;
    private final double pingSpikePenalty;

    private final double tpsLowThreshold;
    private final double tpsCriticalThreshold;
    private final double tpsLowPenalty;
    private final double tpsCriticalPenalty;

    private final long intervalEntropyBucketMs;
    private final double intervalEntropyLowCutoff;
    private final double distanceFarThreshold;
    private final double distanceVeryFarThreshold;
    private final double distanceDampeningMax;

    private final double rotationDuplicateRateBaseline;
    private final double rotationQuantizationShareBaseline;
    private final double rotationQuantizationUniqueRatioMax;
    private final double rotationMinDelta;
    private final double instantRotationYawThreshold;
    private final long instantRotationMaxDelayMs;
    private final long switchReactionFastThresholdMs;
    private final long switchReactionSampleMaxMs;
    private final double switchReactionHumanFloorMs;
    private final double lowAccelerationThreshold;
    private final double lowAccelerationMaxStd;
    private final double rotationMicroCorrectionUpperDelta;
    private final double rotationFastDeltaThreshold;
    private final double rotationMicroCorrectionBaselineShare;
    private final double rotationLowJerkThreshold;
    private final double rotationLowJerkMaxStd;
    private final double rotationAxisImbalanceBaseline;
    private final double rotationSnapHighDeltaThreshold;
    private final double rotationSnapSettleLowDeltaThreshold;
    private final double rotationSnapSettleRatioBaseline;
    private final double rotationSnapShareBaseline;

    private final long burstSliceMs;
    private final double burstApsBase;
    private final double burstApsRange;
    private final double speedTrackingApsBase;
    private final double aimSingleFamilyDeltaMultiplier;
    private final double aimSingleFamilyMinRawScore;

    private final double minConfidenceToFlag;

    private final double modernCooldownApsMin;
    private final double modernCooldownApsMax;
    private final int modernCooldownMinIntervalSamples;
    private final double modernCooldownIntervalMinMs;
    private final double modernCooldownIntervalMaxMs;
    private final double modernAimContextPreciseMin;
    private final double modernAimContextSnapMin;
    private final double modernAimContextInstantMin;
    private final double modernEvidenceThresholdLowTempo;
    private final double modernEvidenceThresholdHighTempo;
    private final double modernProfileApsPivotLow;
    private final double modernProfileApsPivotHigh;
    private final double modernProfileCooldownMultiplier;
    private final double modernProfileHighTempoMultiplier;
    private final double modernNonLinearSignalMin;
    private final double modernNonLinearWeightScale;
    private final double modernCooldownClusterSignalMin;
    private final double modernCooldownClusterWeightScale;
    private final double modernCooldownRotationCoreStrongMin;

    private CombatEngineConfig(boolean enabled,
                               long evaluationIntervalMs,
                               long combatWindowMs,
                               long shortWindowMs,
                               long mediumWindowMs,
                               long longWindowMs,
                               int minAttacksShort,
                               int minAttacksMedium,
                               int minRotationsMedium,
                               int minRotationDeltasMedium,
                               int minSwitchReactionSamples,
                               int minDistanceSamples,
                               int minCheckDeltaEvents,
                               int minDistinctFamilies,
                               int minSuspiciousStreak,
                               double weightAttackRate,
                               double weightIntervalConsistency,
                               double weightTargetSwitchBurst,
                               double weightBlockHitOverlap,
                               double weightInventoryHitOverlap,
                               double weightRotationHitCadence,
                               double weightNoSwingRatio,
                               double weightMultiInteractBurst,
                               double weightIntervalEntropy,
                               double weightRotationDuplicate,
                               double weightRotationQuantization,
                               double weightInstantRotation,
                               double weightSwitchReaction,
                               double weightLowAcceleration,
                               double weightSpeedTrackingCoupling,
                               double weightShortBurst,
                               double weightHitAccuracyPressure,
                               double weightCheckDelta,
                               double weightFamilyDiversity,
                               double weightMicroCorrectionDeficit,
                               double weightLowJerk,
                               double weightAxisImbalance,
                               double weightSnapSettlePattern,
                               double weightSnapHitPressure,
                               double scoreFlagThreshold,
                               double scoreCarryOver,
                               double scoreDecayFactor,
                               double scoreStrictMultiplier,
                               double maxScore,
                               int pingHighThresholdMs,
                               int pingExtremeThresholdMs,
                               long pingSpikeLookbackMs,
                               double pingHighPenalty,
                               double pingExtremePenalty,
                               double pingSpikePenalty,
                               double tpsLowThreshold,
                               double tpsCriticalThreshold,
                               double tpsLowPenalty,
                               double tpsCriticalPenalty,
                               long intervalEntropyBucketMs,
                               double intervalEntropyLowCutoff,
                               double distanceFarThreshold,
                               double distanceVeryFarThreshold,
                               double distanceDampeningMax,
                               double rotationDuplicateRateBaseline,
                               double rotationQuantizationShareBaseline,
                               double rotationQuantizationUniqueRatioMax,
                               double rotationMinDelta,
                               double instantRotationYawThreshold,
                               long instantRotationMaxDelayMs,
                               long switchReactionFastThresholdMs,
                               long switchReactionSampleMaxMs,
                               double switchReactionHumanFloorMs,
                               double lowAccelerationThreshold,
                               double lowAccelerationMaxStd,
                               double rotationMicroCorrectionUpperDelta,
                               double rotationFastDeltaThreshold,
                               double rotationMicroCorrectionBaselineShare,
                               double rotationLowJerkThreshold,
                               double rotationLowJerkMaxStd,
                               double rotationAxisImbalanceBaseline,
                               double rotationSnapHighDeltaThreshold,
                               double rotationSnapSettleLowDeltaThreshold,
                               double rotationSnapSettleRatioBaseline,
                               double rotationSnapShareBaseline,
                               long burstSliceMs,
                               double burstApsBase,
                               double burstApsRange,
                               double speedTrackingApsBase,
                               double aimSingleFamilyDeltaMultiplier,
                               double aimSingleFamilyMinRawScore,
                               double minConfidenceToFlag,
                               double modernCooldownApsMin,
                               double modernCooldownApsMax,
                               int modernCooldownMinIntervalSamples,
                               double modernCooldownIntervalMinMs,
                               double modernCooldownIntervalMaxMs,
                               double modernAimContextPreciseMin,
                               double modernAimContextSnapMin,
                               double modernAimContextInstantMin,
                               double modernEvidenceThresholdLowTempo,
                               double modernEvidenceThresholdHighTempo,
                               double modernProfileApsPivotLow,
                               double modernProfileApsPivotHigh,
                               double modernProfileCooldownMultiplier,
                               double modernProfileHighTempoMultiplier,
                               double modernNonLinearSignalMin,
                               double modernNonLinearWeightScale,
                               double modernCooldownClusterSignalMin,
                               double modernCooldownClusterWeightScale,
                               double modernCooldownRotationCoreStrongMin) {
        this.enabled = enabled;
        this.evaluationIntervalMs = evaluationIntervalMs;
        this.combatWindowMs = combatWindowMs;
        this.shortWindowMs = shortWindowMs;
        this.mediumWindowMs = mediumWindowMs;
        this.longWindowMs = longWindowMs;
        this.minAttacksShort = minAttacksShort;
        this.minAttacksMedium = minAttacksMedium;
        this.minRotationsMedium = minRotationsMedium;
        this.minRotationDeltasMedium = minRotationDeltasMedium;
        this.minSwitchReactionSamples = minSwitchReactionSamples;
        this.minDistanceSamples = minDistanceSamples;
        this.minCheckDeltaEvents = minCheckDeltaEvents;
        this.minDistinctFamilies = minDistinctFamilies;
        this.minSuspiciousStreak = minSuspiciousStreak;
        this.weightAttackRate = weightAttackRate;
        this.weightIntervalConsistency = weightIntervalConsistency;
        this.weightTargetSwitchBurst = weightTargetSwitchBurst;
        this.weightBlockHitOverlap = weightBlockHitOverlap;
        this.weightInventoryHitOverlap = weightInventoryHitOverlap;
        this.weightRotationHitCadence = weightRotationHitCadence;
        this.weightNoSwingRatio = weightNoSwingRatio;
        this.weightMultiInteractBurst = weightMultiInteractBurst;
        this.weightIntervalEntropy = weightIntervalEntropy;
        this.weightRotationDuplicate = weightRotationDuplicate;
        this.weightRotationQuantization = weightRotationQuantization;
        this.weightInstantRotation = weightInstantRotation;
        this.weightSwitchReaction = weightSwitchReaction;
        this.weightLowAcceleration = weightLowAcceleration;
        this.weightSpeedTrackingCoupling = weightSpeedTrackingCoupling;
        this.weightShortBurst = weightShortBurst;
        this.weightHitAccuracyPressure = weightHitAccuracyPressure;
        this.weightCheckDelta = weightCheckDelta;
        this.weightFamilyDiversity = weightFamilyDiversity;
        this.weightMicroCorrectionDeficit = weightMicroCorrectionDeficit;
        this.weightLowJerk = weightLowJerk;
        this.weightAxisImbalance = weightAxisImbalance;
        this.weightSnapSettlePattern = weightSnapSettlePattern;
        this.weightSnapHitPressure = weightSnapHitPressure;
        this.scoreFlagThreshold = scoreFlagThreshold;
        this.scoreCarryOver = scoreCarryOver;
        this.scoreDecayFactor = scoreDecayFactor;
        this.scoreStrictMultiplier = scoreStrictMultiplier;
        this.maxScore = maxScore;
        this.pingHighThresholdMs = pingHighThresholdMs;
        this.pingExtremeThresholdMs = pingExtremeThresholdMs;
        this.pingSpikeLookbackMs = pingSpikeLookbackMs;
        this.pingHighPenalty = pingHighPenalty;
        this.pingExtremePenalty = pingExtremePenalty;
        this.pingSpikePenalty = pingSpikePenalty;
        this.tpsLowThreshold = tpsLowThreshold;
        this.tpsCriticalThreshold = tpsCriticalThreshold;
        this.tpsLowPenalty = tpsLowPenalty;
        this.tpsCriticalPenalty = tpsCriticalPenalty;
        this.intervalEntropyBucketMs = intervalEntropyBucketMs;
        this.intervalEntropyLowCutoff = intervalEntropyLowCutoff;
        this.distanceFarThreshold = distanceFarThreshold;
        this.distanceVeryFarThreshold = distanceVeryFarThreshold;
        this.distanceDampeningMax = distanceDampeningMax;
        this.rotationDuplicateRateBaseline = rotationDuplicateRateBaseline;
        this.rotationQuantizationShareBaseline = rotationQuantizationShareBaseline;
        this.rotationQuantizationUniqueRatioMax = rotationQuantizationUniqueRatioMax;
        this.rotationMinDelta = rotationMinDelta;
        this.instantRotationYawThreshold = instantRotationYawThreshold;
        this.instantRotationMaxDelayMs = instantRotationMaxDelayMs;
        this.switchReactionFastThresholdMs = switchReactionFastThresholdMs;
        this.switchReactionSampleMaxMs = switchReactionSampleMaxMs;
        this.switchReactionHumanFloorMs = switchReactionHumanFloorMs;
        this.lowAccelerationThreshold = lowAccelerationThreshold;
        this.lowAccelerationMaxStd = lowAccelerationMaxStd;
        this.rotationMicroCorrectionUpperDelta = rotationMicroCorrectionUpperDelta;
        this.rotationFastDeltaThreshold = rotationFastDeltaThreshold;
        this.rotationMicroCorrectionBaselineShare = rotationMicroCorrectionBaselineShare;
        this.rotationLowJerkThreshold = rotationLowJerkThreshold;
        this.rotationLowJerkMaxStd = rotationLowJerkMaxStd;
        this.rotationAxisImbalanceBaseline = rotationAxisImbalanceBaseline;
        this.rotationSnapHighDeltaThreshold = rotationSnapHighDeltaThreshold;
        this.rotationSnapSettleLowDeltaThreshold = rotationSnapSettleLowDeltaThreshold;
        this.rotationSnapSettleRatioBaseline = rotationSnapSettleRatioBaseline;
        this.rotationSnapShareBaseline = rotationSnapShareBaseline;
        this.burstSliceMs = burstSliceMs;
        this.burstApsBase = burstApsBase;
        this.burstApsRange = burstApsRange;
        this.speedTrackingApsBase = speedTrackingApsBase;
        this.aimSingleFamilyDeltaMultiplier = aimSingleFamilyDeltaMultiplier;
        this.aimSingleFamilyMinRawScore = aimSingleFamilyMinRawScore;
        this.minConfidenceToFlag = minConfidenceToFlag;
        this.modernCooldownApsMin = modernCooldownApsMin;
        this.modernCooldownApsMax = modernCooldownApsMax;
        this.modernCooldownMinIntervalSamples = modernCooldownMinIntervalSamples;
        this.modernCooldownIntervalMinMs = modernCooldownIntervalMinMs;
        this.modernCooldownIntervalMaxMs = modernCooldownIntervalMaxMs;
        this.modernAimContextPreciseMin = modernAimContextPreciseMin;
        this.modernAimContextSnapMin = modernAimContextSnapMin;
        this.modernAimContextInstantMin = modernAimContextInstantMin;
        this.modernEvidenceThresholdLowTempo = modernEvidenceThresholdLowTempo;
        this.modernEvidenceThresholdHighTempo = modernEvidenceThresholdHighTempo;
        this.modernProfileApsPivotLow = modernProfileApsPivotLow;
        this.modernProfileApsPivotHigh = modernProfileApsPivotHigh;
        this.modernProfileCooldownMultiplier = modernProfileCooldownMultiplier;
        this.modernProfileHighTempoMultiplier = modernProfileHighTempoMultiplier;
        this.modernNonLinearSignalMin = modernNonLinearSignalMin;
        this.modernNonLinearWeightScale = modernNonLinearWeightScale;
        this.modernCooldownClusterSignalMin = modernCooldownClusterSignalMin;
        this.modernCooldownClusterWeightScale = modernCooldownClusterWeightScale;
        this.modernCooldownRotationCoreStrongMin = modernCooldownRotationCoreStrongMin;
    }

    private static long longSetting(String property, String env, long fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static CombatEngineConfig defaults() {
        boolean enabled = true;

        long evaluationIntervalMs = Math.max(1L, longSetting(
                "nova.combatcloud.evaluationIntervalMs",
                "NOVA_COMBAT_CLOUD_EVALUATION_INTERVAL_MS",
                50L
        ));
        long combatWindowMs = 10000L;
        long shortWindowMs = 1200L;
        long mediumWindowMs = 4000L;
        long longWindowMs = 10000L;

        int minAttacksShort = 2;
        int minAttacksMedium = 2;
        int minRotationsMedium = 2;
        int minRotationDeltasMedium = 3;
        int minSwitchReactionSamples = 2;
        int minDistanceSamples = 4;
        int minCheckDeltaEvents = 1;
        int minDistinctFamilies = 1;
        int minSuspiciousStreak = 1;

        double weightAttackRate = 19.5D;
        double weightIntervalConsistency = 22.5D;
        double weightTargetSwitchBurst = 22.0D;
        double weightBlockHitOverlap = 15.0D;
        double weightInventoryHitOverlap = 13.5D;
        double weightRotationHitCadence = 28.0D;
        double weightNoSwingRatio = 18.0D;
        double weightMultiInteractBurst = 24.0D;
        double weightIntervalEntropy = 15.0D;
        double weightRotationDuplicate = 22.0D;
        double weightRotationQuantization = 34.0D;
        double weightInstantRotation = 36.0D;
        double weightSwitchReaction = 28.0D;
        double weightLowAcceleration = 28.0D;
        double weightSpeedTrackingCoupling = 38.0D;
        double weightShortBurst = 18.5D;
        double weightHitAccuracyPressure = 12.0D;
        double weightCheckDelta = 13.5D;
        double weightFamilyDiversity = 14.5D;
        double weightMicroCorrectionDeficit = 26.0D;
        double weightLowJerk = 30.0D;
        double weightAxisImbalance = 12.0D;
        double weightSnapSettlePattern = 27.0D;
        double weightSnapHitPressure = 40.0D;

        double scoreFlagThreshold = 26.0D;
        double scoreCarryOver = 0.86D;
        double scoreDecayFactor = 0.98D;
        double scoreStrictMultiplier = 1.52D;
        double maxScore = 180.0D;

        int pingHighThresholdMs = 200;
        int pingExtremeThresholdMs = 400;
        long pingSpikeLookbackMs = 3000L;
        double pingHighPenalty = 0.12D;
        double pingExtremePenalty = 0.30D;
        double pingSpikePenalty = 0.16D;

        double tpsLowThreshold = 19.2D;
        double tpsCriticalThreshold = 18.3D;
        double tpsLowPenalty = 0.07D;
        double tpsCriticalPenalty = 0.16D;

        long intervalEntropyBucketMs = 25L;
        double intervalEntropyLowCutoff = 1.25D;
        double distanceFarThreshold = 3.35D;
        double distanceVeryFarThreshold = 4.25D;
        double distanceDampeningMax = 0.48D;

        double rotationDuplicateRateBaseline = 0.18D;
        double rotationQuantizationShareBaseline = 0.40D;
        double rotationQuantizationUniqueRatioMax = 0.22D;
        double rotationMinDelta = 0.01D;
        double instantRotationYawThreshold = 26.0D;
        long instantRotationMaxDelayMs = 140L;
        long switchReactionFastThresholdMs = 170L;
        long switchReactionSampleMaxMs = 750L;
        double switchReactionHumanFloorMs = 155.0D;
        double lowAccelerationThreshold = 2.1D;
        double lowAccelerationMaxStd = 1.50D;
        double rotationMicroCorrectionUpperDelta = 1.25D;
        double rotationFastDeltaThreshold = 11.0D;
        double rotationMicroCorrectionBaselineShare = 0.28D;
        double rotationLowJerkThreshold = 0.72D;
        double rotationLowJerkMaxStd = 0.98D;
        double rotationAxisImbalanceBaseline = 3.4D;
        double rotationSnapHighDeltaThreshold = 20.0D;
        double rotationSnapSettleLowDeltaThreshold = 1.00D;
        double rotationSnapSettleRatioBaseline = 0.16D;
        double rotationSnapShareBaseline = 0.24D;

        long burstSliceMs = 200L;
        double burstApsBase = 9.0D;
        double burstApsRange = 7.0D;
        double speedTrackingApsBase = 1.8D;
        double aimSingleFamilyDeltaMultiplier = 0.82D;
        double aimSingleFamilyMinRawScore = 36.0D;

        double minConfidenceToFlag = 0.20D;

        double modernCooldownApsMin = 1.0D;
        double modernCooldownApsMax = 6.6D;
        int modernCooldownMinIntervalSamples = 2;
        double modernCooldownIntervalMinMs = 120.0D;
        double modernCooldownIntervalMaxMs = 1450.0D;
        double modernAimContextPreciseMin = 0.08D;
        double modernAimContextSnapMin = 0.015D;
        double modernAimContextInstantMin = 0.02D;
        double modernEvidenceThresholdLowTempo = 0.055D;
        double modernEvidenceThresholdHighTempo = 0.09D;
        double modernProfileApsPivotLow = 2.0D;
        double modernProfileApsPivotHigh = 5.6D;
        double modernProfileCooldownMultiplier = 1.34D;
        double modernProfileHighTempoMultiplier = 1.00D;
        double modernNonLinearSignalMin = 0.30D;
        double modernNonLinearWeightScale = 1.28D;
        double modernCooldownClusterSignalMin = 0.28D;
        double modernCooldownClusterWeightScale = 1.44D;
        double modernCooldownRotationCoreStrongMin = 0.28D;

        return new CombatEngineConfig(
                enabled,
                evaluationIntervalMs,
                combatWindowMs,
                shortWindowMs,
                mediumWindowMs,
                longWindowMs,
                minAttacksShort,
                minAttacksMedium,
                minRotationsMedium,
                minRotationDeltasMedium,
                minSwitchReactionSamples,
                minDistanceSamples,
                minCheckDeltaEvents,
                minDistinctFamilies,
                minSuspiciousStreak,
                weightAttackRate,
                weightIntervalConsistency,
                weightTargetSwitchBurst,
                weightBlockHitOverlap,
                weightInventoryHitOverlap,
                weightRotationHitCadence,
                weightNoSwingRatio,
                weightMultiInteractBurst,
                weightIntervalEntropy,
                weightRotationDuplicate,
                weightRotationQuantization,
                weightInstantRotation,
                weightSwitchReaction,
                weightLowAcceleration,
                weightSpeedTrackingCoupling,
                weightShortBurst,
                weightHitAccuracyPressure,
                weightCheckDelta,
                weightFamilyDiversity,
                weightMicroCorrectionDeficit,
                weightLowJerk,
                weightAxisImbalance,
                weightSnapSettlePattern,
                weightSnapHitPressure,
                scoreFlagThreshold,
                scoreCarryOver,
                scoreDecayFactor,
                scoreStrictMultiplier,
                maxScore,
                pingHighThresholdMs,
                pingExtremeThresholdMs,
                pingSpikeLookbackMs,
                pingHighPenalty,
                pingExtremePenalty,
                pingSpikePenalty,
                tpsLowThreshold,
                tpsCriticalThreshold,
                tpsLowPenalty,
                tpsCriticalPenalty,
                intervalEntropyBucketMs,
                intervalEntropyLowCutoff,
                distanceFarThreshold,
                distanceVeryFarThreshold,
                distanceDampeningMax,
                rotationDuplicateRateBaseline,
                rotationQuantizationShareBaseline,
                rotationQuantizationUniqueRatioMax,
                rotationMinDelta,
                instantRotationYawThreshold,
                instantRotationMaxDelayMs,
                switchReactionFastThresholdMs,
                switchReactionSampleMaxMs,
                switchReactionHumanFloorMs,
                lowAccelerationThreshold,
                lowAccelerationMaxStd,
                rotationMicroCorrectionUpperDelta,
                rotationFastDeltaThreshold,
                rotationMicroCorrectionBaselineShare,
                rotationLowJerkThreshold,
                rotationLowJerkMaxStd,
                rotationAxisImbalanceBaseline,
                rotationSnapHighDeltaThreshold,
                rotationSnapSettleLowDeltaThreshold,
                rotationSnapSettleRatioBaseline,
                rotationSnapShareBaseline,
                burstSliceMs,
                burstApsBase,
                burstApsRange,
                speedTrackingApsBase,
                aimSingleFamilyDeltaMultiplier,
                aimSingleFamilyMinRawScore,
                minConfidenceToFlag,
                modernCooldownApsMin,
                modernCooldownApsMax,
                modernCooldownMinIntervalSamples,
                modernCooldownIntervalMinMs,
                modernCooldownIntervalMaxMs,
                modernAimContextPreciseMin,
                modernAimContextSnapMin,
                modernAimContextInstantMin,
                modernEvidenceThresholdLowTempo,
                modernEvidenceThresholdHighTempo,
                modernProfileApsPivotLow,
                modernProfileApsPivotHigh,
                modernProfileCooldownMultiplier,
                modernProfileHighTempoMultiplier,
                modernNonLinearSignalMin,
                modernNonLinearWeightScale,
                modernCooldownClusterSignalMin,
                modernCooldownClusterWeightScale,
                modernCooldownRotationCoreStrongMin
        );
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getEvaluationIntervalMs() {
        return evaluationIntervalMs;
    }

    public long getEvaluationIntervalTicks() {
        return Math.max(1L, Math.round(evaluationIntervalMs / 50.0D));
    }

    public long getCombatWindowMs() {
        return combatWindowMs;
    }

    public long getShortWindowMs() {
        return shortWindowMs;
    }

    public long getMediumWindowMs() {
        return mediumWindowMs;
    }

    public long getLongWindowMs() {
        return longWindowMs;
    }

    public int getMinAttacksShort() {
        return minAttacksShort;
    }

    public int getMinAttacksMedium() {
        return minAttacksMedium;
    }

    public int getMinRotationsMedium() {
        return minRotationsMedium;
    }

    public int getMinRotationDeltasMedium() {
        return minRotationDeltasMedium;
    }

    public int getMinSwitchReactionSamples() {
        return minSwitchReactionSamples;
    }

    public int getMinDistanceSamples() {
        return minDistanceSamples;
    }

    public int getMinCheckDeltaEvents() {
        return minCheckDeltaEvents;
    }

    public int getMinDistinctFamilies() {
        return minDistinctFamilies;
    }

    public int getMinSuspiciousStreak() {
        return minSuspiciousStreak;
    }

    public double getWeightAttackRate() {
        return weightAttackRate;
    }

    public double getWeightIntervalConsistency() {
        return weightIntervalConsistency;
    }

    public double getWeightTargetSwitchBurst() {
        return weightTargetSwitchBurst;
    }

    public double getWeightBlockHitOverlap() {
        return weightBlockHitOverlap;
    }

    public double getWeightInventoryHitOverlap() {
        return weightInventoryHitOverlap;
    }

    public double getWeightRotationHitCadence() {
        return weightRotationHitCadence;
    }

    public double getWeightNoSwingRatio() {
        return weightNoSwingRatio;
    }

    public double getWeightMultiInteractBurst() {
        return weightMultiInteractBurst;
    }

    public double getWeightIntervalEntropy() {
        return weightIntervalEntropy;
    }

    public double getWeightRotationDuplicate() {
        return weightRotationDuplicate;
    }

    public double getWeightRotationQuantization() {
        return weightRotationQuantization;
    }

    public double getWeightInstantRotation() {
        return weightInstantRotation;
    }

    public double getWeightSwitchReaction() {
        return weightSwitchReaction;
    }

    public double getWeightLowAcceleration() {
        return weightLowAcceleration;
    }

    public double getWeightSpeedTrackingCoupling() {
        return weightSpeedTrackingCoupling;
    }

    public double getWeightShortBurst() {
        return weightShortBurst;
    }

    public double getWeightHitAccuracyPressure() {
        return weightHitAccuracyPressure;
    }

    public double getWeightCheckDelta() {
        return weightCheckDelta;
    }

    public double getWeightFamilyDiversity() {
        return weightFamilyDiversity;
    }

    public double getWeightMicroCorrectionDeficit() {
        return weightMicroCorrectionDeficit;
    }

    public double getWeightLowJerk() {
        return weightLowJerk;
    }

    public double getWeightAxisImbalance() {
        return weightAxisImbalance;
    }

    public double getWeightSnapSettlePattern() {
        return weightSnapSettlePattern;
    }

    public double getWeightSnapHitPressure() {
        return weightSnapHitPressure;
    }

    public double getScoreFlagThreshold() {
        return scoreFlagThreshold;
    }

    public double getScoreCarryOver() {
        return scoreCarryOver;
    }

    public double getScoreDecayFactor() {
        return scoreDecayFactor;
    }

    public double getScoreStrictMultiplier() {
        return scoreStrictMultiplier;
    }

    public double getMaxScore() {
        return maxScore;
    }

    public int getPingHighThresholdMs() {
        return pingHighThresholdMs;
    }

    public int getPingExtremeThresholdMs() {
        return pingExtremeThresholdMs;
    }

    public long getPingSpikeLookbackMs() {
        return pingSpikeLookbackMs;
    }

    public double getPingHighPenalty() {
        return pingHighPenalty;
    }

    public double getPingExtremePenalty() {
        return pingExtremePenalty;
    }

    public double getPingSpikePenalty() {
        return pingSpikePenalty;
    }

    public double getTpsLowThreshold() {
        return tpsLowThreshold;
    }

    public double getTpsCriticalThreshold() {
        return tpsCriticalThreshold;
    }

    public double getTpsLowPenalty() {
        return tpsLowPenalty;
    }

    public double getTpsCriticalPenalty() {
        return tpsCriticalPenalty;
    }

    public long getIntervalEntropyBucketMs() {
        return intervalEntropyBucketMs;
    }

    public double getIntervalEntropyLowCutoff() {
        return intervalEntropyLowCutoff;
    }

    public double getDistanceFarThreshold() {
        return distanceFarThreshold;
    }

    public double getDistanceVeryFarThreshold() {
        return distanceVeryFarThreshold;
    }

    public double getDistanceDampeningMax() {
        return distanceDampeningMax;
    }

    public double getRotationDuplicateRateBaseline() {
        return rotationDuplicateRateBaseline;
    }

    public double getRotationQuantizationShareBaseline() {
        return rotationQuantizationShareBaseline;
    }

    public double getRotationQuantizationUniqueRatioMax() {
        return rotationQuantizationUniqueRatioMax;
    }

    public double getRotationMinDelta() {
        return rotationMinDelta;
    }

    public double getInstantRotationYawThreshold() {
        return instantRotationYawThreshold;
    }

    public long getInstantRotationMaxDelayMs() {
        return instantRotationMaxDelayMs;
    }

    public long getSwitchReactionFastThresholdMs() {
        return switchReactionFastThresholdMs;
    }

    public long getSwitchReactionSampleMaxMs() {
        return switchReactionSampleMaxMs;
    }

    public double getSwitchReactionHumanFloorMs() {
        return switchReactionHumanFloorMs;
    }

    public double getLowAccelerationThreshold() {
        return lowAccelerationThreshold;
    }

    public double getLowAccelerationMaxStd() {
        return lowAccelerationMaxStd;
    }

    public double getRotationMicroCorrectionUpperDelta() {
        return rotationMicroCorrectionUpperDelta;
    }

    public double getRotationFastDeltaThreshold() {
        return rotationFastDeltaThreshold;
    }

    public double getRotationMicroCorrectionBaselineShare() {
        return rotationMicroCorrectionBaselineShare;
    }

    public double getRotationLowJerkThreshold() {
        return rotationLowJerkThreshold;
    }

    public double getRotationLowJerkMaxStd() {
        return rotationLowJerkMaxStd;
    }

    public double getRotationAxisImbalanceBaseline() {
        return rotationAxisImbalanceBaseline;
    }

    public double getRotationSnapHighDeltaThreshold() {
        return rotationSnapHighDeltaThreshold;
    }

    public double getRotationSnapSettleLowDeltaThreshold() {
        return rotationSnapSettleLowDeltaThreshold;
    }

    public double getRotationSnapSettleRatioBaseline() {
        return rotationSnapSettleRatioBaseline;
    }

    public double getRotationSnapShareBaseline() {
        return rotationSnapShareBaseline;
    }

    public long getBurstSliceMs() {
        return burstSliceMs;
    }

    public double getBurstApsBase() {
        return burstApsBase;
    }

    public double getBurstApsRange() {
        return burstApsRange;
    }

    public double getSpeedTrackingApsBase() {
        return speedTrackingApsBase;
    }

    public double getAimSingleFamilyDeltaMultiplier() {
        return aimSingleFamilyDeltaMultiplier;
    }

    public double getAimSingleFamilyMinRawScore() {
        return aimSingleFamilyMinRawScore;
    }

    public double getMinConfidenceToFlag() {
        return minConfidenceToFlag;
    }

    public double getModernCooldownApsMin() {
        return modernCooldownApsMin;
    }

    public double getModernCooldownApsMax() {
        return modernCooldownApsMax;
    }

    public int getModernCooldownMinIntervalSamples() {
        return modernCooldownMinIntervalSamples;
    }

    public double getModernCooldownIntervalMinMs() {
        return modernCooldownIntervalMinMs;
    }

    public double getModernCooldownIntervalMaxMs() {
        return modernCooldownIntervalMaxMs;
    }

    public double getModernAimContextPreciseMin() {
        return modernAimContextPreciseMin;
    }

    public double getModernAimContextSnapMin() {
        return modernAimContextSnapMin;
    }

    public double getModernAimContextInstantMin() {
        return modernAimContextInstantMin;
    }

    public double getModernEvidenceThresholdLowTempo() {
        return modernEvidenceThresholdLowTempo;
    }

    public double getModernEvidenceThresholdHighTempo() {
        return modernEvidenceThresholdHighTempo;
    }

    public double getModernProfileApsPivotLow() {
        return modernProfileApsPivotLow;
    }

    public double getModernProfileApsPivotHigh() {
        return modernProfileApsPivotHigh;
    }

    public double getModernProfileCooldownMultiplier() {
        return modernProfileCooldownMultiplier;
    }

    public double getModernProfileHighTempoMultiplier() {
        return modernProfileHighTempoMultiplier;
    }

    public double getModernNonLinearSignalMin() {
        return modernNonLinearSignalMin;
    }

    public double getModernNonLinearWeightScale() {
        return modernNonLinearWeightScale;
    }

    public double getModernCooldownClusterSignalMin() {
        return modernCooldownClusterSignalMin;
    }

    public double getModernCooldownClusterWeightScale() {
        return modernCooldownClusterWeightScale;
    }

    public double getModernCooldownRotationCoreStrongMin() {
        return modernCooldownRotationCoreStrongMin;
    }

    public long getStaleStateMs() {
        return Math.max(60000L, longWindowMs * 3L);
    }
}
