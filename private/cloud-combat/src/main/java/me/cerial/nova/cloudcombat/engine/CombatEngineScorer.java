package me.cerial.nova.cloudcombat.engine;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class CombatEngineScorer {

    static final double MODERN_AIM_LOCK_YAW_MAX = 1.20D;
    static final double MODERN_AIM_LOCK_PITCH_MAX = 1.05D;
    static final double MODERN_SNAP_AIM_YAW_MIN = 20.0D;
    static final long MODERN_SNAP_AIM_MAX_DELAY_MS = 150L;
    static final double MODERN_TRACK_FOV_YAW_MAX = 45.0D;
    static final double MODERN_TRACK_FOV_PITCH_MAX = 35.0D;
    static final long MODERN_TRACK_CONTINUITY_GAP_MS = 420L;
    static final long MODERN_FOV_REACQUIRE_FAST_MS = 140L;
    static final long MODERN_FOV_REACQUIRE_MAX_MS = 1200L;
    static final double MODERN_FOV_REACQUIRE_HUMAN_FLOOR_MS = 190.0D;

    private CombatEngineScorer() {
    }

    static CombatSnapshot snapshot(CombatEngineState state, CombatEngineConfig config, long now) {
        CombatSnapshot snapshot = state == null || config == null
                ? new CombatSnapshot()
                : state.buildSnapshot(config, now);
        snapshot.profileFlags = CombatEngineProfileLayer.evaluate(snapshot, config);
        return snapshot;
    }

    static ScoreOutput scoreLegacy(CombatSnapshot snapshot, CombatEngineConfig config) {
        ScoreOutput output = new ScoreOutput();
        if (snapshot == null || config == null) {
            return output;
        }

        CombatEngineProfileLayer.ProfileFlags profileFlags = snapshot.profileFlags;
        double tempoFactor = clamp01((snapshot.apsShort - 4.6D) / 2.6D);

        if (snapshot.attacksShort >= config.getMinAttacksShort()) {
            double attackRateFactor = clamp01((snapshot.apsShort - 6.6D) / 6.0D);
            add(output, "legacy-attack-rate", attackRateFactor * config.getWeightAttackRate());

            if (snapshot.maxBurst >= 3) {
                double burstFactor = clamp01((snapshot.burstAps - config.getBurstApsBase())
                        / Math.max(0.50D, config.getBurstApsRange()));
                double sampleStrength = clamp01((snapshot.attacksShort - config.getMinAttacksShort()) / 5.0D);
                add(output, "legacy-short-burst", burstFactor * sampleStrength * tempoFactor * config.getWeightShortBurst());
            }
        }

        if (snapshot.attacksMedium >= Math.max(config.getMinAttacksMedium(), 6)
                && snapshot.intervalSampleCount >= 4
                && snapshot.apsShort >= 4.8D) {
            double intervalFactor = clamp01((0.40D - snapshot.intervalCv) / 0.30D);
            add(output, "legacy-interval-consistency", intervalFactor * config.getWeightIntervalConsistency());

            double entropyFactor = clamp01((config.getIntervalEntropyLowCutoff() - snapshot.intervalEntropy)
                    / Math.max(0.05D, config.getIntervalEntropyLowCutoff()));
            add(output, "legacy-interval-entropy", entropyFactor * tempoFactor * config.getWeightIntervalEntropy());
        }

        if (snapshot.switchesShort > 0) {
            double switchFactor = clamp01((snapshot.switchesShort - 1.0D) / 3.0D);
            add(output, "legacy-target-switch-burst", switchFactor * config.getWeightTargetSwitchBurst());
        }

        if (snapshot.attacksMedium > 0) {
            double blockFactor = clamp01(snapshot.blockOverlapMedium / (double) Math.max(1, snapshot.attacksMedium));
            add(output, "legacy-block-hit-overlap", blockFactor * config.getWeightBlockHitOverlap());

            double inventoryFactor = clamp01(snapshot.inventoryOverlapMedium / (double) Math.max(1, snapshot.attacksMedium));
            add(output, "legacy-inventory-hit-overlap", inventoryFactor * config.getWeightInventoryHitOverlap());

            if (snapshot.swingsMedium >= 2) {
                double noSwingRatio = snapshot.noSwingAttacksMedium / (double) Math.max(1, snapshot.attacksMedium);
                double noSwingFactor = clamp01((noSwingRatio - 0.18D) / 0.45D);
                add(output, "legacy-no-swing-ratio", noSwingFactor * config.getWeightNoSwingRatio());
            }

            double multiInteractRatio = snapshot.multiInteractsShort / (double) Math.max(1, snapshot.attacksShort);
            double multiInteractFactor = clamp01((multiInteractRatio - 0.05D) / 0.32D);
            add(output, "legacy-multi-interact-burst", multiInteractFactor * config.getWeightMultiInteractBurst());
        }

        if (profileFlags.legacyTempoDrift) {
            scaleContribution(output, "legacy-attack-rate", 0.48D);
            scaleContribution(output, "legacy-short-burst", 0.42D);
            scaleContribution(output, "legacy-interval-consistency", 0.64D);
            scaleContribution(output, "legacy-interval-entropy", 0.70D);
            scaleContribution(output, "legacy-target-switch-burst", 0.88D);
        }
        if (profileFlags.rhythmicLowConversionCooldown) {
            scaleContribution(output, "legacy-attack-rate", 0.74D);
            scaleContribution(output, "legacy-short-burst", 0.70D);
            scaleContribution(output, "legacy-interval-consistency", 0.44D);
            scaleContribution(output, "legacy-interval-entropy", 0.58D);
            scaleContribution(output, "legacy-target-switch-burst", 0.92D);
        }
        if (profileFlags.tempoBlockOverlapReacquire) {
            scaleContribution(output, "legacy-block-hit-overlap", 0.42D);
            scaleContribution(output, "legacy-attack-rate", 0.88D);
            scaleContribution(output, "legacy-short-burst", 0.90D);
            scaleContribution(output, "legacy-interval-consistency", 0.90D);
            scaleContribution(output, "legacy-interval-entropy", 0.92D);
            scaleContribution(output, "legacy-target-switch-burst", 0.90D);
        }
        if (profileFlags.tempoBlockOverlapSlowReacquire) {
            scaleContribution(output, "legacy-block-hit-overlap", 0.28D);
            scaleContribution(output, "legacy-attack-rate", 0.80D);
            scaleContribution(output, "legacy-short-burst", 0.82D);
            scaleContribution(output, "legacy-interval-consistency", 0.86D);
            scaleContribution(output, "legacy-interval-entropy", 0.88D);
            scaleContribution(output, "legacy-target-switch-burst", 0.84D);
        }

        boolean tempoReady = snapshot.apsShort >= 5.0D || snapshot.maxBurst >= 3;
        double legacyEvidenceThreshold = profileFlags.legacyTempoDrift ? 0.32D : 0.26D;
        boolean legacyHitEvidence = !profileFlags.legacyTempoDrift
                || snapshot.hitRatio >= 0.42D
                || snapshot.confirmedHitsMedium >= Math.max(10, (int) Math.ceil(snapshot.attacksMedium * 0.54D));
        output.evidence = snapshot.attacksMedium >= config.getMinAttacksMedium()
                && tempoReady
                && legacyHitEvidence
                && output.rawScore >= (config.getScoreFlagThreshold() * legacyEvidenceThreshold);
        return output;
    }

    static ScoreOutput scoreModern(CombatSnapshot snapshot, CombatEngineConfig config) {
        ScoreOutput output = new ScoreOutput();
        if (snapshot == null || config == null) {
            return output;
        }

        double instantRatio = snapshot.attacksMedium <= 0
                ? 0.0D
                : (snapshot.instantRotationAttacksMedium / (double) Math.max(1, snapshot.attacksMedium));
        int aimSampleGate = Math.max(1, config.getMinAttacksShort() - 1);
        boolean cooldownAimPrecisionContext = snapshot.aimSampleCount >= aimSampleGate
                && (snapshot.preciseAimShare >= config.getModernAimContextPreciseMin()
                || snapshot.snapPreciseAimShare >= config.getModernAimContextSnapMin());
        boolean strongCooldownBiasContext = snapshot.aimSampleCount >= aimSampleGate
                && (snapshot.preciseAimShare >= Math.max(0.13D, config.getModernAimContextPreciseMin() + 0.03D)
                || snapshot.snapPreciseAimShare >= Math.max(0.08D, config.getModernAimContextSnapMin() + 0.04D));
        boolean noAimPrecisionContext = snapshot.aimSampleCount <= 0
                || (snapshot.aimSampleCount >= aimSampleGate
                && snapshot.preciseAimShare < Math.max(0.04D, config.getModernAimContextPreciseMin() * 0.55D)
                && snapshot.snapPreciseAimShare < Math.max(0.01D, config.getModernAimContextSnapMin() * 0.55D));
        boolean cooldownProfile = snapshot.apsShort >= config.getModernCooldownApsMin()
                && snapshot.apsShort <= config.getModernCooldownApsMax()
                && snapshot.intervalSampleCount >= config.getModernCooldownMinIntervalSamples()
                && snapshot.meanIntervalMs >= config.getModernCooldownIntervalMinMs()
                && snapshot.meanIntervalMs <= config.getModernCooldownIntervalMaxMs();
        boolean cooldownAimContext = cooldownAimPrecisionContext;
        boolean cooldownRotationCoreStrong = false;
        double cooldownRotationCoreSignal = 0.0D;
        double cooldownLockSignal = 0.0D;
        double cooldownPressureSignal = 0.0D;
        double quantizationSignal = 0.0D;
        double lowAccelerationSignal = 0.0D;
        double lowJerkSignal = 0.0D;
        double aimLockSignal = 0.0D;
        double speedTrackingSignal = 0.0D;
        double profileSpan = Math.max(0.10D, config.getModernProfileApsPivotHigh() - config.getModernProfileApsPivotLow());
        double profileBlend = clamp01((snapshot.apsShort - config.getModernProfileApsPivotLow()) / profileSpan);
        double modernProfileMultiplier = ((1.0D - profileBlend) * config.getModernProfileCooldownMultiplier())
                + (profileBlend * config.getModernProfileHighTempoMultiplier());

        if (snapshot.attacksMedium > 0) {
            double cadenceFactor = clamp01((snapshot.rotationCadenceRatio - 0.12D) / 0.28D);
            add(output, "modern-rotation-cadence", cadenceFactor * config.getWeightRotationHitCadence());

            double instantFactor = clamp01((instantRatio - 0.01D) / 0.16D);
            if (noAimPrecisionContext) {
                instantFactor *= 0.45D;
            }
            add(output, "modern-instant-rotation", instantFactor * config.getWeightInstantRotation());

            double speedFactor = clamp01((snapshot.apsShort - config.getSpeedTrackingApsBase()) / 2.2D);
            double trackingFactor = clamp01((snapshot.rotationCadenceRatio - 0.18D) / 0.22D);
            double coupling = speedFactor * clamp01((trackingFactor * 0.72D) + (snapshot.preciseAimShare * 0.28D));
            speedTrackingSignal = coupling;
            add(output, "modern-speed-tracking-coupling", coupling * config.getWeightSpeedTrackingCoupling());

            if (cooldownProfile && snapshot.rotationDeltasMedium >= Math.max(3, config.getMinRotationDeltasMedium() - 3)) {
                double cooldownQuantShareFactor = clamp01((snapshot.rotationMaxBucketShare
                        - Math.max(0.16D, config.getRotationQuantizationShareBaseline() - 0.18D))
                        / Math.max(0.01D, 1.0D - Math.max(0.16D, config.getRotationQuantizationShareBaseline() - 0.18D)));
                double cooldownUniqueFactor = clamp01(((config.getRotationQuantizationUniqueRatioMax() + 0.14D)
                        - snapshot.rotationUniqueRatio)
                        / Math.max(0.01D, config.getRotationQuantizationUniqueRatioMax() + 0.14D));
                double cooldownFastFactor = clamp01((snapshot.fastRotationShare - 0.03D) / 0.24D);
                double cooldownConsistencyFactor = clamp01((0.42D - snapshot.intervalCv) / 0.34D);
                double cooldownRotationCoreFactor = clamp01((cooldownQuantShareFactor * 0.36D)
                        + (cooldownUniqueFactor * 0.22D)
                        + (cooldownFastFactor * 0.24D)
                        + (cooldownConsistencyFactor * 0.18D));
                cooldownRotationCoreSignal = cooldownRotationCoreFactor;
                cooldownRotationCoreStrong = cooldownRotationCoreFactor >= config.getModernCooldownRotationCoreStrongMin();
                add(output, "modern-cooldown-rotation-core",
                        cooldownRotationCoreFactor * (config.getWeightRotationQuantization() * 1.25D));
            }

            if (cooldownProfile && cooldownAimContext) {
                double cooldownConsistency = clamp01((0.34D - snapshot.intervalCv) / 0.28D);
                double cooldownPrecision = clamp01((snapshot.preciseAimShare - 0.12D) / 0.56D);
                double cooldownSnap = clamp01((snapshot.snapPreciseAimShare - 0.02D) / 0.24D);
                double cooldownInstant = clamp01((instantRatio - 0.03D) / 0.20D);
                double cooldownLockFactor = clamp01((cooldownConsistency * 0.30D)
                        + (cooldownPrecision * 0.35D)
                        + (cooldownSnap * 0.20D)
                        + (cooldownInstant * 0.15D));
                cooldownLockSignal = Math.max(cooldownLockSignal, cooldownLockFactor);
                add(output, "modern-cooldown-lock", cooldownLockFactor * (config.getWeightSnapHitPressure() * 1.35D));

                double cooldownLowHitFactor = clamp01((0.98D - snapshot.hitRatio) / 0.70D);
                double cooldownTracking = clamp01((snapshot.rotationCadenceRatio - 0.14D) / 0.24D);
                double cooldownPressureFactor = cooldownLowHitFactor
                        * clamp01((snapshot.preciseAimShare * 0.40D) + (cooldownTracking * 0.60D));
                cooldownPressureSignal = Math.max(cooldownPressureSignal, cooldownPressureFactor);
                add(output, "modern-cooldown-pressure",
                        cooldownPressureFactor * (config.getWeightSpeedTrackingCoupling() * 1.20D));
            }
        }

        if (snapshot.rotationsMedium > 0) {
            double duplicateRate = snapshot.duplicateRotationsMedium / (double) snapshot.rotationsMedium;
            double duplicateFactor = clamp01((duplicateRate - config.getRotationDuplicateRateBaseline())
                    / Math.max(0.01D, 1.0D - config.getRotationDuplicateRateBaseline()));
            add(output, "modern-rotation-duplicate", duplicateFactor * config.getWeightRotationDuplicate());
        }

        boolean highTempoModernContext = snapshot.attacksMedium >= Math.max(4, config.getMinAttacksMedium() - 1)
                && snapshot.apsShort >= 3.3D
                && (snapshot.preciseAimShare >= 0.10D
                || snapshot.snapPreciseAimShare >= 0.03D
                || (snapshot.instantRotationAttacksMedium >= 1
                && (snapshot.preciseAimShare >= 0.06D || snapshot.snapPreciseAimShare >= 0.02D)));
        boolean lowTempoModernContext = snapshot.attacksMedium >= Math.max(2, config.getMinAttacksMedium() - 2)
                && cooldownProfile
                && (cooldownAimContext || cooldownRotationCoreStrong);
        boolean modernRotationContext = highTempoModernContext || lowTempoModernContext
                || (cooldownProfile && cooldownRotationCoreStrong)
                || (cooldownProfile && instantRatio >= 0.04D && cooldownAimPrecisionContext);
        int rotationDeltaGate = lowTempoModernContext
                ? Math.max(3, config.getMinRotationDeltasMedium() - 3)
                : config.getMinRotationDeltasMedium();
        if (snapshot.rotationDeltasMedium >= rotationDeltaGate) {
            double quantShareFactor = clamp01((snapshot.rotationMaxBucketShare - config.getRotationQuantizationShareBaseline())
                    / Math.max(0.01D, 1.0D - config.getRotationQuantizationShareBaseline()));
            double uniqueRatioFactor = clamp01((config.getRotationQuantizationUniqueRatioMax() - snapshot.rotationUniqueRatio)
                    / Math.max(0.01D, config.getRotationQuantizationUniqueRatioMax()));
            double quantizationFactor = clamp01((quantShareFactor * 0.65D) + (uniqueRatioFactor * 0.35D));
            quantizationSignal = quantizationFactor;
            add(output, "modern-rotation-quantization", quantizationFactor * config.getWeightRotationQuantization());

            if (modernRotationContext) {
                double microDeficitFactor = clamp01((config.getRotationMicroCorrectionBaselineShare() - snapshot.microCorrectionShare)
                        / Math.max(0.01D, config.getRotationMicroCorrectionBaselineShare()));
                double fastShareFactor = clamp01((snapshot.fastRotationShare - 0.10D) / 0.35D);
                double microFactor = clamp01((microDeficitFactor * 0.70D) + (fastShareFactor * 0.30D));
                add(output, "modern-micro-correction-deficit", microFactor * config.getWeightMicroCorrectionDeficit());
            }

            if (modernRotationContext && !Double.isNaN(snapshot.snapSettleRatio)) {
                double settleFactor = clamp01((snapshot.snapSettleRatio - config.getRotationSnapSettleRatioBaseline())
                        / Math.max(0.01D, 1.0D - config.getRotationSnapSettleRatioBaseline()));
                double snapShareFactor = clamp01((snapshot.fastRotationShare - config.getRotationSnapShareBaseline())
                        / Math.max(0.01D, 1.0D - config.getRotationSnapShareBaseline()));
                double snapPatternFactor = clamp01((settleFactor * 0.70D) + (snapShareFactor * 0.30D));
                add(output, "modern-snap-settle-pattern", snapPatternFactor * config.getWeightSnapSettlePattern());
            }
        }

        int derivativeDeltaGate = lowTempoModernContext
                ? Math.max(3, config.getMinRotationDeltasMedium() - 3)
                : config.getMinRotationDeltasMedium();
        if (modernRotationContext && snapshot.yawAccelerationCount >= derivativeDeltaGate) {
            double lowMeanFactor = clamp01((config.getLowAccelerationThreshold() - snapshot.meanYawAcceleration)
                    / Math.max(0.01D, config.getLowAccelerationThreshold()));
            double lowStdFactor = clamp01((config.getLowAccelerationMaxStd() - snapshot.stdYawAcceleration)
                    / Math.max(0.01D, config.getLowAccelerationMaxStd()));
            double lowAccelerationFactor = clamp01((lowMeanFactor * 0.70D) + (lowStdFactor * 0.30D));
            lowAccelerationSignal = lowAccelerationFactor;
            add(output, "modern-low-acceleration", lowAccelerationFactor * config.getWeightLowAcceleration());
        }
        if (modernRotationContext && snapshot.yawAccelerationCount >= derivativeDeltaGate) {
            double fastAccelerationBase = Math.max(0.45D, config.getLowAccelerationThreshold() * 1.10D);
            double fastAccelerationRange = Math.max(0.45D, config.getLowAccelerationThreshold() * 1.90D);
            double fastAccelerationFactor = clamp01((snapshot.meanYawAcceleration - fastAccelerationBase) / fastAccelerationRange);
            double cadenceTracking = clamp01((snapshot.rotationCadenceRatio - 0.22D) / 0.24D);
            double fastRotationTracking = clamp01((snapshot.fastRotationShare - 0.12D) / 0.30D);
            double trackingQuality = clamp01((speedTrackingSignal * 0.65D)
                    + (cadenceTracking * 0.20D)
                    + (fastRotationTracking * 0.15D));
            double fastAccelTrackingFactor = fastAccelerationFactor * trackingQuality;
            if (fastAccelerationFactor >= 0.25D && trackingQuality >= 0.45D && fastAccelTrackingFactor > 0.0D) {
                add(output, "modern-fast-accel-tracking",
                        fastAccelTrackingFactor * (config.getWeightSpeedTrackingCoupling() * 1.85D));
            }
        }

        if (modernRotationContext && snapshot.yawJerkCount >= derivativeDeltaGate) {
            double lowMeanFactor = clamp01((config.getRotationLowJerkThreshold() - snapshot.meanYawJerk)
                    / Math.max(0.01D, config.getRotationLowJerkThreshold()));
            double lowStdFactor = clamp01((config.getRotationLowJerkMaxStd() - snapshot.stdYawJerk)
                    / Math.max(0.01D, config.getRotationLowJerkMaxStd()));
            double lowJerkFactor = clamp01((lowMeanFactor * 0.70D) + (lowStdFactor * 0.30D));
            lowJerkSignal = lowJerkFactor;
            add(output, "modern-low-jerk", lowJerkFactor * config.getWeightLowJerk());
        }

        if (snapshot.switchReactionSampleCount >= config.getMinSwitchReactionSamples()) {
            double meanReactionFactor = clamp01((config.getSwitchReactionHumanFloorMs() - snapshot.meanSwitchReactionMs)
                    / Math.max(1.0D, config.getSwitchReactionHumanFloorMs()));
            double fastRatioFactor = clamp01((snapshot.fastSwitchReactionRatio - 0.08D) / 0.35D);
            double reactionFactor = clamp01((meanReactionFactor * 0.70D) + (fastRatioFactor * 0.30D));
            add(output, "modern-switch-reaction", reactionFactor * config.getWeightSwitchReaction());
        }
        if (snapshot.fovTrackingSampleCount >= Math.max(8, config.getMinRotationsMedium() * 3)) {
            double missFactor = clamp01((0.22D - snapshot.fovMissRatio) / 0.22D);
            double cadenceSupport = clamp01((snapshot.rotationCadenceRatio - 0.10D) / 0.30D);
            double kinematicSupport = clamp01((snapshot.fastRotationShare - 0.08D) / 0.34D);
            double fovStickinessFactor = missFactor * clamp01((cadenceSupport * 0.60D) + (kinematicSupport * 0.40D));
            add(output, "modern-fov-stickiness",
                    fovStickinessFactor * (config.getWeightSpeedTrackingCoupling() * 0.52D));
        }
        if (snapshot.fovReacquireSampleCount >= 2) {
            double reacquireMs = Double.isNaN(snapshot.meanFovReacquireMs)
                    ? MODERN_FOV_REACQUIRE_MAX_MS
                    : snapshot.meanFovReacquireMs;
            double meanReacquireFactor = clamp01((MODERN_FOV_REACQUIRE_HUMAN_FLOOR_MS - reacquireMs)
                    / Math.max(1.0D, MODERN_FOV_REACQUIRE_HUMAN_FLOOR_MS - MODERN_FOV_REACQUIRE_FAST_MS));
            double fastReacquireFactor = clamp01((snapshot.fastFovReacquireRatio - 0.14D) / 0.60D);
            double reacquireFactor = clamp01((meanReacquireFactor * 0.70D) + (fastReacquireFactor * 0.30D));
            if (snapshot.fovMissRatio >= 0.04D || snapshot.fovReacquireSampleCount >= 3) {
                add(output, "modern-fov-reacquire", reacquireFactor * (config.getWeightSwitchReaction() * 0.80D));
            }
        }

        if (snapshot.aimSampleCount >= Math.max(2, config.getMinAttacksShort())) {
            double preciseFactor = clamp01((snapshot.preciseAimShare - 0.12D) / 0.68D);
            double yawErrorFactor = Double.isNaN(snapshot.meanAimYawError)
                    ? 0.0D
                    : clamp01((2.8D - snapshot.meanAimYawError) / 2.8D);
            double pitchErrorFactor = Double.isNaN(snapshot.meanAimPitchError)
                    ? 0.0D
                    : clamp01((2.1D - snapshot.meanAimPitchError) / 2.1D);
            double meanErrorFactor = clamp01((yawErrorFactor * 0.60D) + (pitchErrorFactor * 0.40D));
            double aimLockFactor = clamp01((preciseFactor * 0.65D) + (meanErrorFactor * 0.35D));
            aimLockSignal = aimLockFactor;
            add(output, "modern-aim-lock-precision", aimLockFactor * (config.getWeightSnapSettlePattern() * 0.80D));

            double lowHitFactor = clamp01((0.98D - snapshot.hitRatio) / 0.74D);
            double snapPrecisionFactor = clamp01((snapshot.snapPreciseAimShare - 0.01D) / 0.24D);
            double lowHitRotationFactor = lowHitFactor
                    * clamp01((preciseFactor * 0.55D) + (snapPrecisionFactor * 0.45D));
            add(output, "modern-low-hit-rotation", lowHitRotationFactor * (config.getWeightSnapHitPressure() * 1.20D));
        }

        double kinematicComposite = Math.max(0.0D, quantizationSignal * Math.max(lowJerkSignal, lowAccelerationSignal) * aimLockSignal);
        double kinematicGeometric = Math.cbrt(kinematicComposite);
        double kinematicSynergy = clamp01((kinematicGeometric - config.getModernNonLinearSignalMin())
                / Math.max(0.01D, 1.0D - config.getModernNonLinearSignalMin()));
        if (modernRotationContext && kinematicSynergy > 0.0D) {
            add(output, "modern-nonlinear-aim-kinematics",
                    kinematicSynergy * config.getWeightRotationQuantization() * config.getModernNonLinearWeightScale());
        }

        double cooldownComposite = Math.max(0.0D, cooldownRotationCoreSignal * cooldownLockSignal * cooldownPressureSignal);
        double cooldownGeometric = Math.cbrt(cooldownComposite);
        double cooldownCluster = clamp01((cooldownGeometric - config.getModernCooldownClusterSignalMin())
                / Math.max(0.01D, 1.0D - config.getModernCooldownClusterSignalMin()));
        if (cooldownProfile && cooldownCluster > 0.0D) {
            add(output, "modern-nonlinear-cooldown-cluster",
                    cooldownCluster * config.getWeightSnapHitPressure() * config.getModernCooldownClusterWeightScale());
        }

        if (cooldownProfile && strongCooldownBiasContext && modernProfileMultiplier > 1.0D && output.rawScore > 0.0D) {
            double baseRaw = output.rawScore;
            add(output, "modern-profile-cooldown-bias", baseRaw * (modernProfileMultiplier - 1.0D));
        }

        boolean lowPrecisionSkirmishWindow = snapshot.attacksMedium <= Math.max(6, config.getMinAttacksMedium() + 2)
                && snapshot.hitRatio <= 0.66D
                && snapshot.preciseAimShare <= (config.getModernAimContextPreciseMin() * 0.45D)
                && snapshot.snapPreciseAimShare <= Math.max(0.01D, config.getModernAimContextSnapMin() * 0.50D);
        if (lowPrecisionSkirmishWindow) {
            scaleContribution(output, "modern-instant-rotation", 0.82D);
            scaleContribution(output, "modern-rotation-cadence", 0.90D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.86D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.80D);
            scaleContribution(output, "modern-cooldown-lock", 0.70D);
            scaleContribution(output, "modern-cooldown-pressure", 0.70D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.50D);
        }
        if (noAimPrecisionContext) {
            scaleContribution(output, "modern-cooldown-lock", 0.48D);
            scaleContribution(output, "modern-cooldown-pressure", 0.48D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.45D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.72D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.78D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.82D);
            scaleContribution(output, "modern-rotation-quantization", 0.86D);
        }
        boolean noAimHighHitWindow = noAimPrecisionContext
                && snapshot.hitRatio >= 0.74D
                && snapshot.attacksMedium >= Math.max(6, config.getMinAttacksMedium() + 1)
                && snapshot.attacksMedium <= Math.max(16, config.getMinAttacksMedium() * 5);
        if (noAimHighHitWindow) {
            scaleContribution(output, "modern-instant-rotation", 0.88D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.84D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.86D);
            scaleContribution(output, "modern-rotation-quantization", 0.90D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.89D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.88D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.90D);
        }
        boolean noAimLowVolumeHighHitWindow = noAimPrecisionContext
                && snapshot.hitRatio >= 0.88D
                && snapshot.attacksMedium >= config.getMinAttacksMedium()
                && snapshot.attacksMedium <= Math.max(5, config.getMinAttacksMedium() + 1)
                && snapshot.rotationsMedium >= Math.max(16, config.getMinRotationsMedium() * 4)
                && snapshot.rotationsMedium <= Math.max(42, config.getMinRotationsMedium() * 10)
                && snapshot.rotationDeltasMedium >= Math.max(36, config.getMinRotationDeltasMedium() * 6)
                && snapshot.rotationDeltasMedium <= Math.max(70, config.getMinRotationDeltasMedium() * 11)
                && snapshot.apsShort <= 4.6D
                && instantRatio <= 0.10D;
        if (noAimLowVolumeHighHitWindow) {
            scaleContribution(output, "modern-cooldown-rotation-core", 0.94D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.95D);
            scaleContribution(output, "modern-snap-settle-pattern", 0.94D);
            scaleContribution(output, "modern-rotation-quantization", 0.96D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.94D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.95D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.93D);
        }
        boolean noAimLowVolumeHighRotationMissWindow = noAimPrecisionContext
                && snapshot.hitRatio >= 0.90D
                && snapshot.attacksMedium >= Math.max(3, config.getMinAttacksMedium())
                && snapshot.attacksMedium <= Math.max(6, config.getMinAttacksMedium() + 4)
                && snapshot.rotationsMedium >= Math.max(48, config.getMinRotationsMedium() * 9)
                && snapshot.rotationDeltasMedium >= Math.max(110, config.getMinRotationDeltasMedium() * 14)
                && snapshot.apsShort <= 2.8D
                && (snapshot.fovMissRatio >= 0.40D
                || (!Double.isNaN(snapshot.meanFovReacquireMs) && snapshot.meanFovReacquireMs >= 300.0D));
        if (noAimLowVolumeHighRotationMissWindow) {
            scaleContribution(output, "modern-cooldown-lock", 0.76D);
            scaleContribution(output, "modern-cooldown-pressure", 0.78D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.74D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.82D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.72D);
            scaleContribution(output, "modern-snap-settle-pattern", 0.82D);
            scaleContribution(output, "modern-rotation-quantization", 0.86D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.78D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.80D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.70D);
        }
        CombatEngineProfileLayer.ProfileFlags profileFlags = snapshot.profileFlags;
        boolean sparsePrecisionHighRotationWindow = profileFlags.sparsePrecisionHighRotation;
        if (sparsePrecisionHighRotationWindow) {
            scaleContribution(output, "modern-instant-rotation", 0.66D);
            scaleContribution(output, "modern-aim-lock-precision", 0.56D);
            scaleContribution(output, "modern-low-hit-rotation", 0.54D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.74D);
            scaleContribution(output, "modern-cooldown-lock", 0.62D);
            scaleContribution(output, "modern-cooldown-pressure", 0.62D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.48D);
            scaleContribution(output, "modern-rotation-quantization", 0.88D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.83D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.80D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.78D);
        }
        boolean lowSnapCooldownPrecisionWindow = profileFlags.lowSnapCooldownPrecision;
        if (lowSnapCooldownPrecisionWindow) {
            scaleContribution(output, "modern-aim-lock-precision", 0.50D);
            scaleContribution(output, "modern-cooldown-lock", 0.44D);
            scaleContribution(output, "modern-cooldown-pressure", 0.50D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.34D);
            scaleContribution(output, "modern-low-hit-rotation", 0.60D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.68D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.76D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.72D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.78D);
            scaleContribution(output, "modern-instant-rotation", 0.78D);
        }
        boolean highHitLowSnapCooldownWindow = profileFlags.highHitLowSnapCooldown;
        if (highHitLowSnapCooldownWindow) {
            scaleContribution(output, "modern-cooldown-lock", 0.86D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.82D);
            scaleContribution(output, "modern-aim-lock-precision", 0.88D);
            scaleContribution(output, "modern-cooldown-pressure", 0.91D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.93D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.93D);
            scaleContribution(output, "modern-instant-rotation", 0.93D);
        }
        boolean cooldownCadenceSaturationWindow = profileFlags.cooldownCadenceSaturation;
        if (cooldownCadenceSaturationWindow) {
            scaleContribution(output, "modern-cooldown-lock", 0.88D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.85D);
            scaleContribution(output, "modern-rotation-cadence", 0.87D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.91D);
            scaleContribution(output, "modern-cooldown-pressure", 0.93D);
            scaleContribution(output, "modern-aim-lock-precision", 0.94D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.94D);
        }
        boolean cooldownNoAimPerfectHitRotationWindow = profileFlags.cooldownNoAimPerfectHitRotation;
        if (cooldownNoAimPerfectHitRotationWindow) {
            scaleContribution(output, "modern-cooldown-lock", 0.74D);
            scaleContribution(output, "modern-cooldown-pressure", 0.76D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.78D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.68D);
            scaleContribution(output, "modern-rotation-cadence", 0.88D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.84D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.84D);
            scaleContribution(output, "modern-rotation-quantization", 0.86D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.76D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.84D);
            scaleContribution(output, "modern-fov-stickiness", 0.80D);
            scaleContribution(output, "modern-fov-reacquire", 0.82D);
        }
        boolean cooldownNoAimFastReacquireWindow = profileFlags.cooldownNoAimFastReacquire;
        if (cooldownNoAimFastReacquireWindow) {
            scaleContribution(output, "modern-fov-reacquire", 0.38D);
            scaleContribution(output, "modern-fov-stickiness", 0.62D);
            scaleContribution(output, "modern-cooldown-lock", 0.72D);
            scaleContribution(output, "modern-cooldown-pressure", 0.74D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.80D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.64D);
            scaleContribution(output, "modern-rotation-cadence", 0.90D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.84D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.86D);
            scaleContribution(output, "modern-rotation-quantization", 0.88D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.70D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.82D);
        }
        boolean cooldownNoAimNoReacquireWindow = profileFlags.cooldownNoAimNoReacquire;
        if (cooldownNoAimNoReacquireWindow) {
            scaleContribution(output, "modern-cooldown-lock", 0.84D);
            scaleContribution(output, "modern-cooldown-pressure", 0.86D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.86D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.80D);
            scaleContribution(output, "modern-rotation-cadence", 0.94D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.92D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.92D);
            scaleContribution(output, "modern-rotation-quantization", 0.94D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.84D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.92D);
            scaleContribution(output, "modern-fov-stickiness", 0.90D);
            scaleContribution(output, "modern-fov-reacquire", 0.70D);
        }
        boolean rhythmicLowConversionCooldownWindow = profileFlags.rhythmicLowConversionCooldown;
        if (rhythmicLowConversionCooldownWindow) {
            scaleContribution(output, "modern-cooldown-lock", 0.60D);
            scaleContribution(output, "modern-cooldown-pressure", 0.64D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.66D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.46D);
            scaleContribution(output, "modern-rotation-cadence", 0.84D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.76D);
            scaleContribution(output, "modern-rotation-quantization", 0.76D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.78D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.62D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.70D);
        }
        boolean sampleStarvedPerfectCooldownWindow = profileFlags.sampleStarvedPerfectCooldown;
        if (sampleStarvedPerfectCooldownWindow) {
            scaleContribution(output, "modern-cooldown-lock", 0.54D);
            scaleContribution(output, "modern-cooldown-pressure", 0.58D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.62D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.42D);
            scaleContribution(output, "modern-rotation-cadence", 0.82D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.72D);
            scaleContribution(output, "modern-rotation-quantization", 0.80D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.82D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.56D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.66D);
            scaleContribution(output, "modern-fov-stickiness", 0.56D);
            scaleContribution(output, "modern-fov-reacquire", 0.42D);
        }
        boolean lowVolumeInstantReacquireHighHitWindow = profileFlags.lowVolumeInstantReacquireHighHit;
        if (lowVolumeInstantReacquireHighHitWindow) {
            scaleContribution(output, "modern-instant-rotation", 0.12D);
            scaleContribution(output, "modern-fov-reacquire", 0.18D);
            scaleContribution(output, "modern-fov-stickiness", 0.28D);
            scaleContribution(output, "modern-cooldown-lock", 0.42D);
            scaleContribution(output, "modern-cooldown-pressure", 0.46D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.40D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.34D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.64D);
            scaleContribution(output, "modern-rotation-cadence", 0.70D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.76D);
            scaleContribution(output, "modern-rotation-quantization", 0.78D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.50D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.48D);
        }
        boolean midHitInstantReacquireRotationWindow = profileFlags.midHitInstantReacquireRotation;
        if (midHitInstantReacquireRotationWindow) {
            scaleContribution(output, "modern-instant-rotation", 0.16D);
            scaleContribution(output, "modern-fov-reacquire", 0.24D);
            scaleContribution(output, "modern-fov-stickiness", 0.34D);
            scaleContribution(output, "modern-cooldown-lock", 0.58D);
            scaleContribution(output, "modern-cooldown-pressure", 0.62D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.56D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.50D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.74D);
            scaleContribution(output, "modern-rotation-cadence", 0.80D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.84D);
            scaleContribution(output, "modern-rotation-quantization", 0.86D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.62D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.60D);
        }
        boolean midTempoCooldownFastReacquireWindow = profileFlags.midTempoCooldownFastReacquire;
        if (midTempoCooldownFastReacquireWindow) {
            scaleContribution(output, "modern-instant-rotation", 0.56D);
            scaleContribution(output, "modern-fov-reacquire", 0.20D);
            scaleContribution(output, "modern-fov-stickiness", 0.26D);
            scaleContribution(output, "modern-cooldown-lock", 0.44D);
            scaleContribution(output, "modern-cooldown-pressure", 0.48D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.46D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.40D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.68D);
            scaleContribution(output, "modern-rotation-cadence", 0.72D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.78D);
            scaleContribution(output, "modern-rotation-quantization", 0.80D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.54D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.52D);
        }
        boolean lowRotationNoReacquireCooldownWindow = profileFlags.lowRotationNoReacquireCooldown;
        if (lowRotationNoReacquireCooldownWindow) {
            scaleContribution(output, "modern-instant-rotation", 0.72D);
            scaleContribution(output, "modern-fov-reacquire", 0.18D);
            scaleContribution(output, "modern-fov-stickiness", 0.24D);
            scaleContribution(output, "modern-cooldown-lock", 0.48D);
            scaleContribution(output, "modern-cooldown-pressure", 0.52D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.42D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.40D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.70D);
            scaleContribution(output, "modern-rotation-cadence", 0.74D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.46D);
            scaleContribution(output, "modern-rotation-quantization", 0.72D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.58D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.64D);
            scaleContribution(output, "modern-low-hit-rotation", 0.64D);
        }
        boolean softCooldownFastReacquireWindow = profileFlags.softCooldownFastReacquire;
        if (softCooldownFastReacquireWindow) {
            scaleContribution(output, "modern-instant-rotation", 0.28D);
            scaleContribution(output, "modern-fov-reacquire", 0.18D);
            scaleContribution(output, "modern-fov-stickiness", 0.28D);
            scaleContribution(output, "modern-cooldown-lock", 0.42D);
            scaleContribution(output, "modern-cooldown-pressure", 0.46D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.42D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.38D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.64D);
            scaleContribution(output, "modern-rotation-cadence", 0.70D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.74D);
            scaleContribution(output, "modern-rotation-quantization", 0.76D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.52D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.56D);
        }
        boolean compactInstantReacquireSoftKinematicsWindow = profileFlags.compactInstantReacquireSoftKinematics;
        if (compactInstantReacquireSoftKinematicsWindow) {
            scaleContribution(output, "modern-instant-rotation", 0.16D);
            scaleContribution(output, "modern-fov-reacquire", 0.18D);
            scaleContribution(output, "modern-fov-stickiness", 0.24D);
            scaleContribution(output, "modern-cooldown-lock", 0.40D);
            scaleContribution(output, "modern-cooldown-pressure", 0.42D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.34D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.34D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.60D);
            scaleContribution(output, "modern-rotation-cadence", 0.68D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.54D);
            scaleContribution(output, "modern-rotation-quantization", 0.70D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.50D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.54D);
        }
        boolean instantReacquireSkirmishWindow = profileFlags.instantReacquireSkirmish;
        if (instantReacquireSkirmishWindow) {
            scaleContribution(output, "modern-instant-rotation", 0.24D);
            scaleContribution(output, "modern-fov-reacquire", 0.30D);
            scaleContribution(output, "modern-fov-stickiness", 0.40D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.68D);
            scaleContribution(output, "modern-rotation-cadence", 0.76D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.80D);
            scaleContribution(output, "modern-rotation-quantization", 0.82D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.62D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.58D);
        }
        boolean tightCooldownFastReacquireWindow = profileFlags.tightCooldownFastReacquire;
        if (tightCooldownFastReacquireWindow) {
            scaleContribution(output, "modern-instant-rotation", 0.18D);
            scaleContribution(output, "modern-fov-reacquire", 0.22D);
            scaleContribution(output, "modern-fov-stickiness", 0.34D);
            scaleContribution(output, "modern-cooldown-lock", 0.54D);
            scaleContribution(output, "modern-cooldown-pressure", 0.58D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.54D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.46D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.70D);
            scaleContribution(output, "modern-rotation-cadence", 0.78D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.82D);
            scaleContribution(output, "modern-rotation-quantization", 0.84D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.60D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.56D);
        }
        boolean compactCooldownStickinessWindow = profileFlags.compactCooldownStickiness;
        if (compactCooldownStickinessWindow) {
            scaleContribution(output, "modern-instant-rotation", 0.22D);
            scaleContribution(output, "modern-fov-reacquire", 0.28D);
            scaleContribution(output, "modern-fov-stickiness", 0.24D);
            scaleContribution(output, "modern-cooldown-lock", 0.50D);
            scaleContribution(output, "modern-cooldown-pressure", 0.54D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.46D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.42D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.72D);
            scaleContribution(output, "modern-rotation-cadence", 0.80D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.54D);
            scaleContribution(output, "modern-rotation-quantization", 0.80D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.60D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.56D);
        }
        boolean tempoBlockOverlapReacquireWindow = profileFlags.tempoBlockOverlapReacquire;
        if (tempoBlockOverlapReacquireWindow) {
            scaleContribution(output, "modern-fov-reacquire", 0.34D);
            scaleContribution(output, "modern-fov-stickiness", 0.58D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.78D);
            scaleContribution(output, "modern-rotation-cadence", 0.84D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.86D);
            scaleContribution(output, "modern-rotation-quantization", 0.88D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.86D);
            scaleContribution(output, "modern-cooldown-lock", 0.88D);
            scaleContribution(output, "modern-cooldown-pressure", 0.90D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.80D);
        }
        boolean tempoBlockOverlapSlowReacquireWindow = profileFlags.tempoBlockOverlapSlowReacquire;
        if (tempoBlockOverlapSlowReacquireWindow) {
            scaleContribution(output, "modern-fov-reacquire", 0.16D);
            scaleContribution(output, "modern-fov-stickiness", 0.26D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.62D);
            scaleContribution(output, "modern-rotation-cadence", 0.68D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.72D);
            scaleContribution(output, "modern-rotation-quantization", 0.76D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.46D);
            scaleContribution(output, "modern-cooldown-lock", 0.40D);
            scaleContribution(output, "modern-cooldown-pressure", 0.42D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.36D);
            scaleContribution(output, "modern-nonlinear-cooldown-cluster", 0.54D);
            scaleContribution(output, "modern-nonlinear-aim-kinematics", 0.58D);
            scaleContribution(output, "modern-low-hit-rotation", 0.68D);
        }
        boolean rotationDominantLowAimWindow = snapshot.aimSampleCount >= aimSampleGate
                && snapshot.preciseAimShare < 0.18D
                && snapshot.snapPreciseAimShare < 0.18D
                && snapshot.attacksMedium >= Math.max(6, config.getMinAttacksMedium() + 2)
                && snapshot.rotationsMedium >= Math.max(24, config.getMinRotationsMedium() * 6)
                && snapshot.rotationDeltasMedium >= Math.max(60, config.getMinRotationDeltasMedium() * 8)
                && snapshot.hitRatio <= 0.75D;
        if (rotationDominantLowAimWindow) {
            scaleContribution(output, "modern-instant-rotation", 0.90D);
            scaleContribution(output, "modern-cooldown-rotation-core", 0.85D);
            scaleContribution(output, "modern-speed-tracking-coupling", 0.87D);
            scaleContribution(output, "modern-micro-correction-deficit", 0.82D);
            scaleContribution(output, "modern-aim-lock-precision", 0.83D);
            scaleContribution(output, "modern-low-hit-rotation", 0.85D);
            scaleContribution(output, "modern-profile-cooldown-bias", 0.59D);
        }

        double evidenceThreshold = lowTempoModernContext
                ? config.getModernEvidenceThresholdLowTempo()
                : config.getModernEvidenceThresholdHighTempo();
        if (noAimHighHitWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.15D);
        }
        if (noAimLowVolumeHighHitWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.14D);
        }
        if (noAimLowVolumeHighRotationMissWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.24D);
        }
        if (sparsePrecisionHighRotationWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.18D);
        }
        if (lowSnapCooldownPrecisionWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.21D);
        }
        if (highHitLowSnapCooldownWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.16D);
        }
        if (cooldownCadenceSaturationWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.15D);
        }
        if (cooldownNoAimPerfectHitRotationWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.23D);
        }
        if (cooldownNoAimFastReacquireWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.26D);
        }
        if (cooldownNoAimNoReacquireWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.18D);
        }
        if (rhythmicLowConversionCooldownWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.24D);
        }
        if (sampleStarvedPerfectCooldownWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.26D);
        }
        if (lowVolumeInstantReacquireHighHitWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.28D);
        }
        if (midHitInstantReacquireRotationWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.31D);
        }
        if (midTempoCooldownFastReacquireWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.34D);
        }
        if (lowRotationNoReacquireCooldownWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.38D);
        }
        if (softCooldownFastReacquireWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.36D);
        }
        if (compactInstantReacquireSoftKinematicsWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.39D);
        }
        if (instantReacquireSkirmishWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.30D);
        }
        if (tightCooldownFastReacquireWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.32D);
        }
        if (compactCooldownStickinessWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.34D);
        }
        if (tempoBlockOverlapReacquireWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.28D);
        }
        if (tempoBlockOverlapSlowReacquireWindow) {
            evidenceThreshold = Math.max(evidenceThreshold, 0.36D);
        }
        int minRotationsEvidence = lowTempoModernContext
                ? Math.max(2, config.getMinRotationsMedium() - 2)
                : config.getMinRotationsMedium();
        int minRotationDeltasEvidence = lowTempoModernContext
                ? Math.max(2, config.getMinRotationDeltasMedium() - 4)
                : config.getMinRotationDeltasMedium();
        if (noAimHighHitWindow) {
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(2, config.getMinRotationDeltasMedium() - 4));
        }
        if (sparsePrecisionHighRotationWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(7, config.getMinRotationsMedium() * 2));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(18, config.getMinRotationDeltasMedium() * 3));
        }
        if (lowSnapCooldownPrecisionWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(10, config.getMinRotationsMedium() * 3));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(26, config.getMinRotationDeltasMedium() * 5));
        }
        if (highHitLowSnapCooldownWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(9, config.getMinRotationsMedium() * 2));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(22, config.getMinRotationDeltasMedium() * 4));
        }
        if (cooldownCadenceSaturationWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(12, config.getMinRotationsMedium() * 3));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(34, config.getMinRotationDeltasMedium() * 6));
        }
        if (cooldownNoAimPerfectHitRotationWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(20, config.getMinRotationsMedium() * 5));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(52, config.getMinRotationDeltasMedium() * 8));
        }
        if (cooldownNoAimFastReacquireWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(24, config.getMinRotationsMedium() * 6));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(60, config.getMinRotationDeltasMedium() * 9));
        }
        if (cooldownNoAimNoReacquireWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(18, config.getMinRotationsMedium() * 4));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(46, config.getMinRotationDeltasMedium() * 7));
        }
        if (noAimLowVolumeHighRotationMissWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(16, config.getMinRotationsMedium() * 4));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(40, config.getMinRotationDeltasMedium() * 7));
        }
        if (lowVolumeInstantReacquireHighHitWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(22, config.getMinRotationsMedium() * 5));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(60, config.getMinRotationDeltasMedium() * 9));
        }
        if (midHitInstantReacquireRotationWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(24, config.getMinRotationsMedium() * 6));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(68, config.getMinRotationDeltasMedium() * 10));
        }
        if (midTempoCooldownFastReacquireWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(28, config.getMinRotationsMedium() * 7));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(74, config.getMinRotationDeltasMedium() * 11));
        }
        if (lowRotationNoReacquireCooldownWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(30, config.getMinRotationsMedium() * 7));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(76, config.getMinRotationDeltasMedium() * 12));
        }
        if (softCooldownFastReacquireWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(24, config.getMinRotationsMedium() * 6));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(68, config.getMinRotationDeltasMedium() * 10));
        }
        if (compactInstantReacquireSoftKinematicsWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(28, config.getMinRotationsMedium() * 7));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(76, config.getMinRotationDeltasMedium() * 12));
        }
        if (instantReacquireSkirmishWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(22, config.getMinRotationsMedium() * 5));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(64, config.getMinRotationDeltasMedium() * 10));
        }
        if (tightCooldownFastReacquireWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(24, config.getMinRotationsMedium() * 6));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(72, config.getMinRotationDeltasMedium() * 11));
        }
        if (compactCooldownStickinessWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(20, config.getMinRotationsMedium() * 5));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(56, config.getMinRotationDeltasMedium() * 9));
        }
        if (tempoBlockOverlapReacquireWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(22, config.getMinRotationsMedium() * 5));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(64, config.getMinRotationDeltasMedium() * 10));
        }
        if (tempoBlockOverlapSlowReacquireWindow) {
            minRotationsEvidence = Math.max(minRotationsEvidence, Math.max(28, config.getMinRotationsMedium() * 7));
            minRotationDeltasEvidence = Math.max(minRotationDeltasEvidence, Math.max(76, config.getMinRotationDeltasMedium() * 12));
        }
        output.evidence = snapshot.rotationsMedium >= minRotationsEvidence
                && snapshot.rotationDeltasMedium >= minRotationDeltasEvidence
                && (modernRotationContext || snapshot.instantRotationAttacksMedium >= 1)
                && output.rawScore >= (config.getScoreFlagThreshold() * evidenceThreshold);
        return output;
    }

    private static void add(ScoreOutput output, String key, double value) {
        if (output == null || key == null || key.isEmpty() || value <= 0.0D) {
            return;
        }
        output.rawScore += value;
        output.contributions.merge(key, value, Double::sum);
    }

    private static void scaleContribution(ScoreOutput output, String key, double factor) {
        if (output == null || key == null || key.isEmpty()) {
            return;
        }
        Double existing = output.contributions.get(key);
        if (existing == null || existing <= 0.0D) {
            return;
        }

        double clampedFactor = clamp01(factor);
        double scaled = existing * clampedFactor;
        if (scaled <= 0.0D) {
            output.contributions.remove(key);
        } else {
            output.contributions.put(key, scaled);
        }
        output.rawScore = Math.max(0.0D, output.rawScore - (existing - scaled));
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static double meanLong(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (Long value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static double meanDouble(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (Double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static double stddevLong(List<Long> values, double mean) {
        if (values == null || values.isEmpty()) {
            return 0.0D;
        }
        double variance = 0.0D;
        for (Long value : values) {
            double diff = value - mean;
            variance += diff * diff;
        }
        variance /= values.size();
        return Math.sqrt(variance);
    }

    private static double stddevDouble(List<Double> values, double mean) {
        if (values == null || values.isEmpty()) {
            return 0.0D;
        }
        double variance = 0.0D;
        for (Double value : values) {
            double diff = value - mean;
            variance += diff * diff;
        }
        variance /= values.size();
        return Math.sqrt(variance);
    }

    private static double shannonEntropy(List<Long> intervals, long bucketMs) {
        if (intervals == null || intervals.isEmpty() || bucketMs <= 0L) {
            return 0.0D;
        }

        Map<Long, Integer> histogram = new HashMap<>();
        for (Long interval : intervals) {
            long bucket = Math.max(0L, interval / bucketMs);
            histogram.merge(bucket, 1, Integer::sum);
        }

        double total = intervals.size();
        double entropy = 0.0D;
        for (Integer count : histogram.values()) {
            if (count == null || count <= 0) {
                continue;
            }
            double p = count / total;
            entropy -= p * (Math.log(p) / Math.log(2.0D));
        }
        return entropy;
    }

    private static RotationDistribution rotationDistribution(List<Double> yawDeltas, List<Double> pitchDeltas) {
        Map<Integer, Integer> histogram = new HashMap<>();
        int total = 0;

        if (yawDeltas != null) {
            for (double delta : yawDeltas) {
                int bucket = (int) Math.round(delta * 100.0D);
                histogram.merge(bucket, 1, Integer::sum);
                total++;
            }
        }
        if (pitchDeltas != null) {
            for (double delta : pitchDeltas) {
                int bucket = 100_000 + (int) Math.round(delta * 100.0D);
                histogram.merge(bucket, 1, Integer::sum);
                total++;
            }
        }

        if (total <= 0) {
            return new RotationDistribution(0.0D, 1.0D);
        }

        int maxCount = 0;
        for (int count : histogram.values()) {
            if (count > maxCount) {
                maxCount = count;
            }
        }

        return new RotationDistribution(
                maxCount / (double) total,
                histogram.size() / (double) total
        );
    }

    private static int countAtOrBelow(List<Double> values, double threshold) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (double value : values) {
            if (value <= threshold) {
                count++;
            }
        }
        return count;
    }

    private static int countAtOrAbove(List<Double> values, double threshold) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (double value : values) {
            if (value >= threshold) {
                count++;
            }
        }
        return count;
    }

    private static int countSnapSettleTransitions(List<Double> values, double highThreshold, double settleThreshold) {
        if (values == null || values.size() < 2) {
            return 0;
        }
        int transitions = 0;
        for (int i = 1; i < values.size(); i++) {
            double previous = values.get(i - 1);
            double current = values.get(i);
            if (previous >= highThreshold && current <= settleThreshold) {
                transitions++;
            }
        }
        return transitions;
    }

    static final class CombatSnapshot {
        int attacksShort = 0;
        int attacksMedium = 0;
        int confirmedHitsMedium = 0;
        int rotationsMedium = 0;
        int rotationDeltasMedium = 0;
        int switchesShort = 0;
        int blockOverlapMedium = 0;
        int inventoryOverlapMedium = 0;
        int tightRotationAttacksMedium = 0;
        int noSwingAttacksMedium = 0;
        int swingsMedium = 0;
        int multiInteractsShort = 0;
        int duplicateRotationsMedium = 0;
        int instantRotationAttacksMedium = 0;
        int maxBurst = 0;
        int intervalSampleCount = 0;
        int yawDeltasCount = 0;
        int pitchDeltasCount = 0;
        int yawAccelerationCount = 0;
        int yawJerkCount = 0;
        int switchReactionSampleCount = 0;
        int aimSampleCount = 0;
        int fovTrackingSampleCount = 0;
        int fovReacquireSampleCount = 0;

        double apsShort = 0.0D;
        double burstAps = 0.0D;
        double hitRatio = 0.0D;
        double rotationCadenceRatio = 0.0D;
        double meanIntervalMs = 0.0D;
        double intervalStdMs = 0.0D;
        double intervalCv = 1.0D;
        double intervalEntropy = 0.0D;
        double rotationMaxBucketShare = 0.0D;
        double rotationUniqueRatio = 1.0D;
        double microCorrectionShare = 0.0D;
        double fastRotationShare = 0.0D;
        double snapSettleRatio = Double.NaN;
        double meanYawAcceleration = Double.NaN;
        double stdYawAcceleration = Double.NaN;
        double meanYawJerk = Double.NaN;
        double stdYawJerk = Double.NaN;
        double meanSwitchReactionMs = Double.NaN;
        double fastSwitchReactionRatio = 0.0D;
        double meanAimYawError = Double.NaN;
        double meanAimPitchError = Double.NaN;
        double preciseAimShare = 0.0D;
        double snapPreciseAimShare = 0.0D;
        double fovMissRatio = 0.0D;
        double meanFovReacquireMs = Double.NaN;
        double fastFovReacquireRatio = 0.0D;
        int deltaEventsMedium = 0;
        int distinctFamiliesMedium = 0;
        Map<String, Integer> familyTotalsMedium = Collections.emptyMap();
        CombatEngineProfileLayer.ProfileFlags profileFlags = CombatEngineProfileLayer.ProfileFlags.EMPTY;
    }

    static final class ScoreOutput {
        private double rawScore = 0.0D;
        private boolean evidence = false;
        private final Map<String, Double> contributions = new HashMap<>();

        double getRawScore() {
            return rawScore;
        }

        boolean hasEvidence() {
            return evidence;
        }

        Map<String, Double> getContributions() {
            return contributions;
        }
    }

    private static final class RotationDistribution {
        private final double maxBucketShare;
        private final double uniqueRatio;

        private RotationDistribution(double maxBucketShare, double uniqueRatio) {
            this.maxBucketShare = maxBucketShare;
            this.uniqueRatio = uniqueRatio;
        }
    }
}
