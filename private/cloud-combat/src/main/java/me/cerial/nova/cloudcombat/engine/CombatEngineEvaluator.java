package me.cerial.nova.cloudcombat.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class CombatEngineEvaluator {

    private static final double LEGACY_PIPELINE_WEIGHT = 0.76D;
    private static final double MODERN_PIPELINE_WEIGHT = 1.10D;
    private static final double SUSPICIOUS_STREAK_SCORE_FACTOR = 0.58D;
    private static final double SUSPICIOUS_STREAK_MIN_WINDOW_RAW = 3.2D;
    private static final double TEMPO_DOMINANCE_RATIO = 0.72D;
    private static final double TEMPO_ONLY_LEGACY_DAMPEN = 0.78D;

    public CombatEngineVerdict evaluateState(UUID uuid,
                                             CombatEngineState state,
                                             CombatEngineConfig config,
                                             long now,
                                             int pingMs,
                                             double tps) {
        if (uuid == null || state == null || config == null || !config.isEnabled()) {
            return null;
        }
        state.prune(now - config.getLongWindowMs());
        if (!state.isCombatActive(now, config.getCombatWindowMs())) {
            state.setLegacyPipelineScore(state.getLegacyPipelineScore() * config.getScoreDecayFactor());
            state.setModernPipelineScore(state.getModernPipelineScore() * config.getScoreDecayFactor());
            state.setScore(state.getScore() * config.getScoreDecayFactor());
            state.setSuspiciousWindowStreak(Math.max(0, state.getSuspiciousWindowStreak() - 1));
            return null;
        }

        CombatEngineScorer.CombatSnapshot snapshot = CombatEngineScorer.snapshot(state, config, now);
        double touchpadCompatibilityScore = updateCompatibilityScore(
                state.getTouchpadCompatibilityScore(),
                touchpadKinematicEvidence(snapshot),
                0.985D,
                1.0D,
                0.12D
        );
        double erraticMouseCompatibilityScore = updateCompatibilityScore(
                state.getErraticMouseCompatibilityScore(),
                erraticMouseKinematicEvidence(snapshot) || highErrorMouseKinematicEvidence(snapshot),
                0.985D,
                1.0D,
                0.16D
        );
        double cheatKinematicCompatibilityScore = updateCompatibilityScore(
                state.getCheatKinematicCompatibilityScore(),
                cheatKinematicEvidence(snapshot),
                0.988D,
                1.2D,
                0.10D
        );
        double awayBackSpoofCompatibilityScore = updateCompatibilityScore(
                state.getAwayBackSpoofCompatibilityScore(),
                awayBackSpoofKinematicEvidence(snapshot),
                0.985D,
                1.35D,
                0.30D
        );
        state.setTouchpadCompatibilityScore(touchpadCompatibilityScore);
        state.setErraticMouseCompatibilityScore(erraticMouseCompatibilityScore);
        state.setCheatKinematicCompatibilityScore(cheatKinematicCompatibilityScore);
        state.setAwayBackSpoofCompatibilityScore(awayBackSpoofCompatibilityScore);
        EvaluationUpdate update = evaluatePlayer(new EvaluationInput(
                uuid,
                now,
                snapshot,
                state.getLegacyPipelineScore(),
                state.getModernPipelineScore(),
                state.getSuspiciousWindowStreak(),
                pingMs >= config.getPingHighThresholdMs(),
                pingMs >= config.getPingExtremeThresholdMs(),
                false,
                tps,
                touchpadCompatibilityScore,
                erraticMouseCompatibilityScore,
                cheatKinematicCompatibilityScore,
                awayBackSpoofCompatibilityScore
        ), config);
        update.apply(state);
        KinematicDebugLogger.record(uuid, now, snapshot, update.verdict);
        return update.verdict;
    }

    private EvaluationUpdate evaluatePlayer(EvaluationInput input,
                                            CombatEngineConfig config) {
        long now = input.now;
        CombatEngineScorer.CombatSnapshot snapshot = input.snapshot;
        CombatEngineScorer.ScoreOutput legacy = CombatEngineScorer.scoreLegacy(snapshot, config);
        CombatEngineScorer.ScoreOutput modern = CombatEngineScorer.scoreModern(snapshot, config);
        CombatEngineProfileLayer.ProfileFlags profileFlags = snapshot.profileFlags;

        int deltaEvents = snapshot.deltaEventsMedium;
        int distinctFamilies = snapshot.distinctFamiliesMedium;
        Map<String, Integer> familyTotals = snapshot.familyTotalsMedium;
        double weightedDeltaSum = weightedFamilyDeltaSum(familyTotals);
        boolean singleFamilyAimOnly = distinctFamilies == 1
                && familyTotals.getOrDefault("aim", 0) > 0;

        Map<String, Double> crossContributions = new HashMap<>();
        double deltaScore = 0.0D;
        if (deltaEvents > 0) {
            double deltaFactor = clamp01(weightedDeltaSum / 5.0D);
            deltaScore += addContribution(
                    crossContributions,
                    "cross-check-delta",
                    deltaFactor * config.getWeightCheckDelta()
            );
        }
        if (distinctFamilies > 0) {
            double diversityFactor = clamp01((distinctFamilies - 1.0D) / 3.0D);
            deltaScore += addContribution(
                    crossContributions,
                    "cross-family-diversity",
                    diversityFactor * config.getWeightFamilyDiversity()
            );
        }

        double engineWindowRawNoDelta = (legacy.getRawScore() * LEGACY_PIPELINE_WEIGHT)
                + (modern.getRawScore() * MODERN_PIPELINE_WEIGHT);
        boolean isolatedAimDelta = distinctFamilies == 1
                && familyTotals.getOrDefault("aim", 0) > 0
                && deltaEvents <= 2
                && weightedDeltaSum <= (familyRisk("aim") * 2.0D)
                && engineWindowRawNoDelta < config.getAimSingleFamilyMinRawScore();
        if (isolatedAimDelta) {
            double deltaMultiplier = clamp(config.getAimSingleFamilyDeltaMultiplier(), 0.10D, 1.0D);
            deltaScore *= deltaMultiplier;
            weightedDeltaSum *= deltaMultiplier;
            scaleContributions(crossContributions, deltaMultiplier);
        }
        if (profileFlags.lowPrecisionLowHitHighRotation) {
            // Reduce cross-check reinforcement in a low-precision, low-hit, high-rotation window.
            double lowPrecisionDeltaMultiplier = 0.86D;
            deltaScore *= lowPrecisionDeltaMultiplier;
            weightedDeltaSum *= lowPrecisionDeltaMultiplier;
            scaleContributions(crossContributions, lowPrecisionDeltaMultiplier);
        }
        if (profileFlags.lowTempoNoAimHighHitRotation) {
            // Clamp single-family delta amplification in low-tempo/no-aim windows.
            double lowTempoNoAimDeltaMultiplier = 0.58D;
            deltaScore *= lowTempoNoAimDeltaMultiplier;
            weightedDeltaSum *= lowTempoNoAimDeltaMultiplier;
            scaleContributions(crossContributions, lowTempoNoAimDeltaMultiplier);
        }
        if (profileFlags.cooldownNoAimPerfectHitRotation) {
            // Prevent single-family aim deltas from dominating low-tempo/no-aim cooldown windows.
            double cooldownNoAimDeltaMultiplier = singleFamilyAimOnly ? 0.46D : 0.72D;
            deltaScore *= cooldownNoAimDeltaMultiplier;
            weightedDeltaSum *= cooldownNoAimDeltaMultiplier;
            scaleContributions(crossContributions, cooldownNoAimDeltaMultiplier);
        }
        if (profileFlags.cooldownNoAimFastReacquire) {
            // Strongly clamp feedback loops from single-family aim deltas in fast reacquire no-aim windows.
            double cooldownNoAimFastDeltaMultiplier = singleFamilyAimOnly ? 0.34D : 0.62D;
            deltaScore *= cooldownNoAimFastDeltaMultiplier;
            weightedDeltaSum *= cooldownNoAimFastDeltaMultiplier;
            scaleContributions(crossContributions, cooldownNoAimFastDeltaMultiplier);
        }
        if (profileFlags.cooldownNoAimNoReacquire) {
            // Slightly damp single-family aim feedback when no reacquire samples exist.
            double cooldownNoReacqDeltaMultiplier = singleFamilyAimOnly ? 0.62D : 0.82D;
            deltaScore *= cooldownNoReacqDeltaMultiplier;
            weightedDeltaSum *= cooldownNoReacqDeltaMultiplier;
            scaleContributions(crossContributions, cooldownNoReacqDeltaMultiplier);
        }
        if (profileFlags.rhythmicLowConversionCooldown) {
            // Consistent cooldown spam with weak conversion should not let rhythm + light aim deltas dominate.
            double rhythmicLowConversionDeltaMultiplier = 0.72D;
            deltaScore *= rhythmicLowConversionDeltaMultiplier;
            weightedDeltaSum *= rhythmicLowConversionDeltaMultiplier;
            scaleContributions(crossContributions, rhythmicLowConversionDeltaMultiplier);
        }
        if (profileFlags.sampleStarvedPerfectCooldown) {
            // Perfect-hit cooldown windows with no real aim/fov samples need much stronger delta skepticism.
            double sampleStarvedDeltaMultiplier = singleFamilyAimOnly ? 0.32D : 0.56D;
            deltaScore *= sampleStarvedDeltaMultiplier;
            weightedDeltaSum *= sampleStarvedDeltaMultiplier;
            scaleContributions(crossContributions, sampleStarvedDeltaMultiplier);
        }
        if (profileFlags.midHitInstantReacquireRotation) {
            double midHitInstantReacquireDeltaMultiplier = singleFamilyAimOnly ? 0.26D : 0.46D;
            deltaScore *= midHitInstantReacquireDeltaMultiplier;
            weightedDeltaSum *= midHitInstantReacquireDeltaMultiplier;
            scaleContributions(crossContributions, midHitInstantReacquireDeltaMultiplier);
        }
        if (profileFlags.midTempoCooldownFastReacquire) {
            // Mid-tempo cooldown windows with weak aim context should not let cross-check noise reinforce fast reacquire pressure.
            double midTempoCooldownFastReacquireDeltaMultiplier = singleFamilyAimOnly ? 0.30D : 0.44D;
            deltaScore *= midTempoCooldownFastReacquireDeltaMultiplier;
            weightedDeltaSum *= midTempoCooldownFastReacquireDeltaMultiplier;
            scaleContributions(crossContributions, midTempoCooldownFastReacquireDeltaMultiplier);
        }
        if (profileFlags.lowRotationNoReacquireCooldown) {
            // Low-rotation no-reacquire cooldown windows should not snowball from cooldown-core, micro-correction, and light delta support.
            double lowRotationNoReacquireCooldownDeltaMultiplier = singleFamilyAimOnly ? 0.34D : 0.50D;
            deltaScore *= lowRotationNoReacquireCooldownDeltaMultiplier;
            weightedDeltaSum *= lowRotationNoReacquireCooldownDeltaMultiplier;
            scaleContributions(crossContributions, lowRotationNoReacquireCooldownDeltaMultiplier);
        }
        if (profileFlags.softCooldownFastReacquire) {
            // Low-kinematics cooldown/reacquire windows should not let weak cross-check noise reinforce a human-looking reacquire path.
            double softCooldownFastReacquireDeltaMultiplier = singleFamilyAimOnly ? 0.28D : 0.46D;
            deltaScore *= softCooldownFastReacquireDeltaMultiplier;
            weightedDeltaSum *= softCooldownFastReacquireDeltaMultiplier;
            scaleContributions(crossContributions, softCooldownFastReacquireDeltaMultiplier);
        }
        if (profileFlags.compactInstantReacquireSoftKinematics) {
            // Compact instant-reacquire windows with no aim context should be highly skeptical of light delta reinforcement.
            double compactInstantReacquireSoftKinematicsDeltaMultiplier = singleFamilyAimOnly ? 0.24D : 0.40D;
            deltaScore *= compactInstantReacquireSoftKinematicsDeltaMultiplier;
            weightedDeltaSum *= compactInstantReacquireSoftKinematicsDeltaMultiplier;
            scaleContributions(crossContributions, compactInstantReacquireSoftKinematicsDeltaMultiplier);
        }
        if (profileFlags.instantReacquireSkirmish) {
            // Sparse flick/reacquire skirmishes should not let light delta noise reinforce a pure rotation window.
            double instantReacquireSkirmishDeltaMultiplier = singleFamilyAimOnly ? 0.28D : 0.48D;
            deltaScore *= instantReacquireSkirmishDeltaMultiplier;
            weightedDeltaSum *= instantReacquireSkirmishDeltaMultiplier;
            scaleContributions(crossContributions, instantReacquireSkirmishDeltaMultiplier);
        }
        if (profileFlags.tightCooldownFastReacquire) {
            // Tight cooldown flick windows need even stronger protection against pure fov/rotation reinforcement.
            double tightCooldownFastReacquireDeltaMultiplier = singleFamilyAimOnly ? 0.24D : 0.42D;
            deltaScore *= tightCooldownFastReacquireDeltaMultiplier;
            weightedDeltaSum *= tightCooldownFastReacquireDeltaMultiplier;
            scaleContributions(crossContributions, tightCooldownFastReacquireDeltaMultiplier);
        }
        if (profileFlags.compactCooldownStickiness) {
            // Compact cooldown stickiness windows should not snowball from tiny aim deltas or cooldown pressure alone.
            double compactCooldownStickinessDeltaMultiplier = singleFamilyAimOnly ? 0.22D : 0.38D;
            deltaScore *= compactCooldownStickinessDeltaMultiplier;
            weightedDeltaSum *= compactCooldownStickinessDeltaMultiplier;
            scaleContributions(crossContributions, compactCooldownStickinessDeltaMultiplier);
        }
        if (profileFlags.tempoBlockOverlapReacquire) {
            // Mixed overlap/reacquire windows should not let light external deltas reinforce a weak-aim pressure pattern.
            double tempoBlockOverlapDeltaMultiplier = singleFamilyAimOnly ? 0.34D : 0.68D;
            deltaScore *= tempoBlockOverlapDeltaMultiplier;
            weightedDeltaSum *= tempoBlockOverlapDeltaMultiplier;
            scaleContributions(crossContributions, tempoBlockOverlapDeltaMultiplier);
        }
        if (profileFlags.tempoBlockOverlapSlowReacquire) {
            // Slower overlap/reacquire windows should be even more skeptical of cooldown-core plus light cross-check support.
            double tempoBlockOverlapSlowDeltaMultiplier = singleFamilyAimOnly ? 0.30D : 0.56D;
            deltaScore *= tempoBlockOverlapSlowDeltaMultiplier;
            weightedDeltaSum *= tempoBlockOverlapSlowDeltaMultiplier;
            scaleContributions(crossContributions, tempoBlockOverlapSlowDeltaMultiplier);
        }
        boolean sparseAimFovEvidence = snapshot.aimSampleCount <= Math.max(2, config.getMinAttacksShort())
                && snapshot.fovReacquireSampleCount <= 1;
        boolean lowTempoNoAimCooldownWindow = snapshot.attacksMedium >= Math.max(4, config.getMinAttacksMedium())
                && snapshot.attacksMedium <= Math.max(6, config.getMinAttacksMedium() + 3)
                && snapshot.apsShort >= 0.60D
                && snapshot.apsShort <= Math.min(2.4D, config.getModernCooldownApsMax())
                && snapshot.hitRatio >= 0.92D
                && snapshot.preciseAimShare <= 0.05D
                && snapshot.snapPreciseAimShare <= 0.03D
                && snapshot.rotationsMedium >= Math.max(48, config.getMinRotationsMedium() * 8)
                && snapshot.rotationDeltasMedium >= Math.max(96, config.getMinRotationDeltasMedium() * 12);
        boolean singleFamilyLowEvidenceCooldownWindow = singleFamilyAimOnly
                && sparseAimFovEvidence
                && lowTempoNoAimCooldownWindow;
        if (singleFamilyLowEvidenceCooldownWindow) {
            // Keep one-family deltas from dominating when aim/fov evidence quality is poor.
            double lowEvidenceDeltaMultiplier = 0.55D;
            deltaScore *= lowEvidenceDeltaMultiplier;
            weightedDeltaSum *= lowEvidenceDeltaMultiplier;
            scaleContributions(crossContributions, lowEvidenceDeltaMultiplier);
        }
        boolean singleFamilyAimNoTempoWindow = singleFamilyAimOnly
                && snapshot.attacksMedium >= Math.max(4, config.getMinAttacksMedium() + 2)
                && snapshot.attacksMedium <= Math.max(6, config.getMinAttacksMedium() + 4)
                && snapshot.confirmedHitsMedium >= Math.max(4, snapshot.attacksMedium - 1)
                && snapshot.hitRatio >= 0.96D
                && snapshot.aimSampleCount <= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare <= 0.02D
                && snapshot.snapPreciseAimShare <= 0.01D
                && snapshot.apsShort <= 0.35D
                && snapshot.fovMissRatio <= 0.08D
                && snapshot.fovReacquireSampleCount <= 0
                && Double.isNaN(snapshot.meanFovReacquireMs)
                && snapshot.rotationsMedium >= Math.max(48, config.getMinRotationsMedium() * 8)
                && snapshot.rotationDeltasMedium >= Math.max(96, config.getMinRotationDeltasMedium() * 12);
        if (singleFamilyAimNoTempoWindow) {
            double singleFamilyAimNoTempoDeltaMultiplier = 0.18D;
            deltaScore *= singleFamilyAimNoTempoDeltaMultiplier;
            weightedDeltaSum *= singleFamilyAimNoTempoDeltaMultiplier;
            scaleContributions(crossContributions, singleFamilyAimNoTempoDeltaMultiplier);
        }

        boolean hasCrossCheckSupport = deltaEvents >= Math.max(1, config.getMinCheckDeltaEvents())
                && weightedDeltaSum >= 2.0D;
        boolean strongCrossCheckSupport = hasCrossCheckSupport
                && weightedDeltaSum >= 3.8D
                && distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1);
        boolean noCrossCheckNoAimCooldownWindow = !hasCrossCheckSupport
                && weightedDeltaSum <= 1.0D
                && snapshot.preciseAimShare <= 0.03D
                && snapshot.snapPreciseAimShare <= 0.02D;
        double instantRatio = snapshot.attacksMedium <= 0
                ? 0.0D
                : (snapshot.instantRotationAttacksMedium / (double) Math.max(1, snapshot.attacksMedium));
        double effectiveModernRaw = modern.getRawScore();
        double modernCooldownRotationCore = contributionValue(modern.getContributions(), "modern-cooldown-rotation-core");
        double modernCooldownCluster = contributionValue(modern.getContributions(), "modern-nonlinear-cooldown-cluster");
        double modernNonLinearKinematics = contributionValue(modern.getContributions(), "modern-nonlinear-aim-kinematics");
        double modernNonLinearTotal = modernCooldownCluster + modernNonLinearKinematics;
        double modernCooldownLock = contributionValue(modern.getContributions(), "modern-cooldown-lock")
                + contributionValue(modern.getContributions(), "modern-cooldown-pressure")
                + modernCooldownRotationCore
                + modernCooldownCluster;
        double modernInstantRotation = contributionValue(modern.getContributions(), "modern-instant-rotation");
        double modernFovReacquire = contributionValue(modern.getContributions(), "modern-fov-reacquire");
        double modernFovStickiness = contributionValue(modern.getContributions(), "modern-fov-stickiness");
        double modernRotationCadence = contributionValue(modern.getContributions(), "modern-rotation-cadence");
        double modernRotationDuplicate = contributionValue(modern.getContributions(), "modern-rotation-duplicate");
        double modernRotationQuantization = contributionValue(modern.getContributions(), "modern-rotation-quantization");
        double modernMicroCorrection = contributionValue(modern.getContributions(), "modern-micro-correction-deficit");
        double modernLowAcceleration = contributionValue(modern.getContributions(), "modern-low-acceleration");
        double modernLowJerk = contributionValue(modern.getContributions(), "modern-low-jerk");
        double legacyTargetSwitchBurst = contributionValue(legacy.getContributions(), "legacy-target-switch-burst");
        double legacyNoSwingRatio = contributionValue(legacy.getContributions(), "legacy-no-swing-ratio");
        double legacyBlockHitOverlap = contributionValue(legacy.getContributions(), "legacy-block-hit-overlap");
        double duplicateKinematicTotal = modernLowAcceleration
                + modernLowJerk
                + modernMicroCorrection
                + modernRotationQuantization;
        boolean duplicateKinematicsHardSupport = snapshot.attacksMedium >= Math.max(5, config.getMinAttacksMedium() + 1)
                && snapshot.confirmedHitsMedium >= Math.max(2, (int) Math.ceil(snapshot.attacksMedium * 0.25D))
                && snapshot.hitRatio <= 0.86D
                && snapshot.apsShort >= 1.40D
                && snapshot.apsShort <= 8.50D
                && snapshot.rotationsMedium >= Math.max(24, config.getMinRotationsMedium() * 5)
                && snapshot.rotationDeltasMedium >= Math.max(48, config.getMinRotationDeltasMedium() * 7)
                && modernRotationDuplicate >= (config.getScoreFlagThreshold() * 0.16D)
                && duplicateKinematicTotal >= (config.getScoreFlagThreshold() * 0.35D)
                && (modernRotationDuplicate + modernLowAcceleration + modernLowJerk) >= (config.getScoreFlagThreshold() * 0.42D);
        boolean weakAimContext = snapshot.aimSampleCount <= 0
                || (snapshot.aimSampleCount >= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare <= Math.max(0.03D, config.getModernAimContextPreciseMin() * 0.40D)
                && snapshot.snapPreciseAimShare <= Math.max(0.01D, config.getModernAimContextSnapMin() * 0.50D));
        boolean borderlineAimContext = snapshot.aimSampleCount >= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare < 0.18D
                && snapshot.snapPreciseAimShare < 0.18D;
        boolean movementOnlyModernWindow = !strongCrossCheckSupport
                && !duplicateKinematicsHardSupport
                && weakAimContext
                && snapshot.attacksMedium >= Math.max(5, config.getMinAttacksMedium())
                && snapshot.hitRatio <= 0.72D
                && snapshot.rotationsMedium >= Math.max(12, config.getMinRotationsMedium() * 4);
        boolean rotationDominantModernWindow = !strongCrossCheckSupport
                && borderlineAimContext
                && snapshot.attacksMedium >= Math.max(6, config.getMinAttacksMedium() + 2)
                && snapshot.hitRatio <= 0.75D
                && snapshot.rotationsMedium >= Math.max(24, config.getMinRotationsMedium() * 6)
                && snapshot.rotationDeltasMedium >= Math.max(60, config.getMinRotationDeltasMedium() * 8);
        boolean fastHeadFalseWindow = !strongCrossCheckSupport
                && snapshot.aimSampleCount >= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare <= 0.10D
                && snapshot.snapPreciseAimShare <= 0.05D
                && snapshot.attacksMedium >= Math.max(8, config.getMinAttacksMedium() + 4)
                && snapshot.rotationsMedium >= Math.max(50, config.getMinRotationsMedium() * 10)
                && snapshot.rotationDeltasMedium >= Math.max(100, config.getMinRotationDeltasMedium() * 12)
                && snapshot.apsShort >= 2.6D
                && snapshot.apsShort <= 5.8D
                && snapshot.hitRatio <= 0.68D;
        boolean noAimHighHitFalseWindow = !strongCrossCheckSupport
                && weakAimContext
                && snapshot.hitRatio >= 0.74D
                && snapshot.attacksMedium >= Math.max(6, config.getMinAttacksMedium() + 1)
                && snapshot.attacksMedium <= Math.max(16, config.getMinAttacksMedium() * 5)
                && snapshot.rotationsMedium >= Math.max(40, config.getMinRotationsMedium() * 8)
                && snapshot.rotationDeltasMedium >= Math.max(90, config.getMinRotationDeltasMedium() * 12);
        boolean noAimLowVolumeHighHitFalseWindow = !strongCrossCheckSupport
                && weakAimContext
                && snapshot.hitRatio >= 0.88D
                && snapshot.attacksMedium >= config.getMinAttacksMedium()
                && snapshot.attacksMedium <= Math.max(5, config.getMinAttacksMedium() + 1)
                && snapshot.rotationsMedium >= Math.max(16, config.getMinRotationsMedium() * 4)
                && snapshot.rotationsMedium <= Math.max(42, config.getMinRotationsMedium() * 10)
                && snapshot.rotationDeltasMedium >= Math.max(36, config.getMinRotationDeltasMedium() * 6)
                && snapshot.rotationDeltasMedium <= Math.max(70, config.getMinRotationDeltasMedium() * 11)
                && snapshot.apsShort <= 4.6D
                && instantRatio <= 0.10D;
        boolean noAimLowVolumeHighRotationMissFalseWindow = !strongCrossCheckSupport
                && weakAimContext
                && snapshot.hitRatio >= 0.90D
                && snapshot.attacksMedium >= Math.max(3, config.getMinAttacksMedium())
                && snapshot.attacksMedium <= Math.max(6, config.getMinAttacksMedium() + 4)
                && snapshot.rotationsMedium >= Math.max(48, config.getMinRotationsMedium() * 9)
                && snapshot.rotationDeltasMedium >= Math.max(110, config.getMinRotationDeltasMedium() * 14)
                && snapshot.apsShort <= 2.8D
                && (snapshot.fovMissRatio >= 0.40D
                || (!Double.isNaN(snapshot.meanFovReacquireMs) && snapshot.meanFovReacquireMs >= 300.0D));
        boolean sparsePrecisionHighRotationFalseWindow = !strongCrossCheckSupport
                && profileFlags.sparsePrecisionHighRotation;
        boolean lowSnapCooldownPrecisionFalseWindow = !strongCrossCheckSupport
                && profileFlags.lowSnapCooldownPrecision;
        boolean highHitLowSnapCooldownFalseWindow = !strongCrossCheckSupport
                && profileFlags.highHitLowSnapCooldown;
        boolean cooldownCadenceSaturationFalseWindow = !strongCrossCheckSupport
                && profileFlags.cooldownCadenceSaturation;
        boolean lowPrecisionLowHitHighRotationFalseWindow = profileFlags.lowPrecisionLowHitHighRotation;
        boolean lowApsInstantNoAimFalseWindow = !strongCrossCheckSupport
                && profileFlags.lowApsInstantNoAim;
        boolean lowTempoNoAimHighHitRotationFalseWindow = profileFlags.lowTempoNoAimHighHitRotation;
        boolean cooldownNoAimPerfectHitRotationFalseWindow = !strongCrossCheckSupport
                && profileFlags.cooldownNoAimPerfectHitRotation;
        boolean cooldownNoAimFastReacquireFalseWindow = !strongCrossCheckSupport
                && profileFlags.cooldownNoAimFastReacquire;
        boolean cooldownNoAimNoReacquireFalseWindow = !strongCrossCheckSupport
                && profileFlags.cooldownNoAimNoReacquire;
        boolean rhythmicLowConversionCooldownFalseWindow = profileFlags.rhythmicLowConversionCooldown;
        boolean sampleStarvedPerfectCooldownFalseWindow = !strongCrossCheckSupport
                && profileFlags.sampleStarvedPerfectCooldown;
        boolean lowVolumeInstantReacquireHighHitFalseWindow = !strongCrossCheckSupport
                && profileFlags.lowVolumeInstantReacquireHighHit;
        boolean midHitInstantReacquireRotationFalseWindow = !strongCrossCheckSupport
                && profileFlags.midHitInstantReacquireRotation;
        boolean midTempoCooldownFastReacquireFalseWindow = !strongCrossCheckSupport
                && profileFlags.midTempoCooldownFastReacquire;
        boolean lowRotationNoReacquireCooldownFalseWindow = !strongCrossCheckSupport
                && profileFlags.lowRotationNoReacquireCooldown;
        boolean softCooldownFastReacquireFalseWindow = !strongCrossCheckSupport
                && profileFlags.softCooldownFastReacquire;
        boolean compactInstantReacquireSoftKinematicsFalseWindow = !strongCrossCheckSupport
                && profileFlags.compactInstantReacquireSoftKinematics;
        boolean instantReacquireSkirmishFalseWindow = !strongCrossCheckSupport
                && profileFlags.instantReacquireSkirmish;
        boolean tightCooldownFastReacquireFalseWindow = !strongCrossCheckSupport
                && profileFlags.tightCooldownFastReacquire;
        boolean compactCooldownStickinessFalseWindow = !strongCrossCheckSupport
                && profileFlags.compactCooldownStickiness;
        boolean tempoBlockOverlapReacquireFalseWindow = !strongCrossCheckSupport
                && profileFlags.tempoBlockOverlapReacquire;
        boolean tempoBlockOverlapSlowReacquireFalseWindow = !strongCrossCheckSupport
                && profileFlags.tempoBlockOverlapSlowReacquire;
        double modernFovAndCooldownShare = effectiveModernRaw <= 0.01D
                ? 0.0D
                : (modernFovReacquire + modernFovStickiness + modernCooldownLock + modernInstantRotation) / effectiveModernRaw;
        boolean staleLowHitTailFalseWindow = !strongCrossCheckSupport
                && snapshot.attacksMedium >= Math.max(9, config.getMinAttacksMedium() + 5)
                && snapshot.confirmedHitsMedium <= Math.max(3, (int) Math.ceil(snapshot.attacksMedium * 0.24D))
                && snapshot.hitRatio <= 0.36D
                && snapshot.preciseAimShare <= 0.20D
                && snapshot.snapPreciseAimShare <= 0.08D
                && modernFovAndCooldownShare >= 0.42D;
        boolean lowApsNoAimTailFalseWindow = !strongCrossCheckSupport
                && snapshot.apsShort <= 1.10D
                && snapshot.attacksMedium <= Math.max(12, config.getMinAttacksMedium() + 8)
                && weakAimContext
                && snapshot.preciseAimShare <= 0.05D
                && snapshot.snapPreciseAimShare <= 0.03D
                && (modernInstantRotation + modernFovReacquire) >= (config.getScoreFlagThreshold() * 0.18D);
        boolean compactNoAimRotationProbeWindow = !strongCrossCheckSupport
                && weakAimContext
                && snapshot.attacksMedium <= Math.max(6, config.getMinAttacksMedium() + 4)
                && snapshot.confirmedHitsMedium <= Math.max(4, snapshot.attacksMedium)
                && snapshot.preciseAimShare <= 0.03D
                && snapshot.snapPreciseAimShare <= 0.02D
                && snapshot.apsShort >= 0.45D
                && snapshot.apsShort <= 2.40D
                && snapshot.rotationsMedium >= Math.max(40, config.getMinRotationsMedium() * 8)
                && snapshot.rotationDeltasMedium >= Math.max(72, config.getMinRotationDeltasMedium() * 12)
                && (modernInstantRotation + modernCooldownLock + modernFovReacquire) >= (config.getScoreFlagThreshold() * 0.24D);
        boolean legacyTempoDriftProfileWindow = !strongCrossCheckSupport
                && profileFlags.legacyTempoDrift;
        boolean compatibilityTuningEnabled = CombatEngineRuntimeConfig.touchpadCompatibility();
        boolean compatibilitySuppressionAllowed = compatibilityTuningEnabled
                && (!hasCrossCheckSupport || touchpadCrossCheckCompatibilityEvidence(snapshot));
        boolean compatibilityAwayBackSpoof = input.awayBackSpoofCompatibilityScore >= 8.0D
                && awayBackSpoofKinematicEvidence(snapshot);
        boolean sustainedAuraPressureKinematics = sustainedAuraPressureKinematicEvidence(snapshot);
        boolean compatibilityCheatKinematics = (input.cheatKinematicCompatibilityScore >= 18.0D
                && cheatKinematicEvidence(snapshot))
                || sustainedAuraPressureKinematics
                || compatibilityAwayBackSpoof;
        boolean compatibilityHardOverride = compatibilityAwayBackSpoof
                || duplicateKinematicsHardSupport
                || sustainedAuraPressureKinematics;
        boolean touchpadStrictCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityCheatKinematics
                && touchpadStrictKinematicEvidence(snapshot);
        boolean touchpadWarmupCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityCheatKinematics
                && touchpadWarmupKinematicEvidence(snapshot, legacyTargetSwitchBurst, legacyNoSwingRatio);
        boolean touchpadProfileCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityCheatKinematics
                && input.touchpadCompatibilityScore >= 26.0D
                && input.touchpadCompatibilityScore > (input.cheatKinematicCompatibilityScore * 1.15D)
                && touchpadKinematicEvidence(snapshot);
        boolean erraticMouseStrictCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityCheatKinematics
                && erraticMouseStrictKinematicEvidence(snapshot);
        boolean erraticMouseHighErrorCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityCheatKinematics
                && highErrorMouseKinematicEvidence(snapshot);
        boolean erraticMouseProfileCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityCheatKinematics
                && input.erraticMouseCompatibilityScore >= 12.0D
                && input.erraticMouseCompatibilityScore > (input.cheatKinematicCompatibilityScore * 1.05D)
                && erraticMouseKinematicEvidence(snapshot);
        boolean touchpadStopCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityHardOverride
                && touchpadStopKinematicEvidence(snapshot);
        boolean touchpadSwitchCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityHardOverride
                && touchpadSwitchKinematicEvidence(snapshot);
        boolean legitFastChaosCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityHardOverride
                && legitFastChaosKinematicEvidence(snapshot);
        boolean lowVolumeFlickCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityHardOverride
                && lowVolumeFlickKinematicEvidence(snapshot);
        boolean blockOverlapChaosCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityHardOverride
                && blockOverlapChaosKinematicEvidence(snapshot, legacyBlockHitOverlap, modernFovReacquire);
        boolean blockOverlapHighErrorCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityHardOverride
                && blockOverlapHighErrorKinematicEvidence(snapshot, legacyBlockHitOverlap);
        boolean smoothTouchpadCooldownCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityHardOverride
                && smoothTouchpadCooldownKinematicEvidence(snapshot);
        boolean touchpadLowDeltaSwitchCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityHardOverride
                && touchpadLowDeltaSwitchKinematicEvidence(snapshot, legacyTargetSwitchBurst);
        boolean touchpadLowVolumeCadenceCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityHardOverride
                && touchpadLowVolumeCadenceKinematicEvidence(snapshot, modernRotationCadence);
        boolean lowVolumeBlockOverlapFlickCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityHardOverride
                && lowVolumeBlockOverlapFlickKinematicEvidence(snapshot, legacyBlockHitOverlap);
        boolean lowHitErraticMissCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityHardOverride
                && lowHitErraticMissKinematicEvidence(snapshot, modernCooldownLock);
        boolean midHitFovBlockCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityHardOverride
                && midHitFovBlockKinematicEvidence(snapshot, legacyBlockHitOverlap, modernFovReacquire);
        boolean midHitFovNoBlockCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityHardOverride
                && midHitFovNoBlockKinematicEvidence(snapshot, legacyBlockHitOverlap, modernFovReacquire);
        boolean cooldownMidChaosCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityHardOverride
                && cooldownMidChaosKinematicEvidence(snapshot, modernCooldownRotationCore);
        boolean highFastLowErrorTouchpadCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityHardOverride
                && highFastLowErrorTouchpadKinematicEvidence(snapshot);
        boolean compactFovInstantLowErrorCompatibilityWindow = compatibilitySuppressionAllowed
                && !compatibilityHardOverride
                && compactFovInstantLowErrorKinematicEvidence(snapshot, modernFovReacquire, modernInstantRotation);
        boolean touchpadCrossCheckCompatibilityWindow = compatibilityTuningEnabled
                && !compatibilityHardOverride
                && hasCrossCheckSupport
                && touchpadCrossCheckCompatibilityEvidence(snapshot);
        boolean compatibilityFalseWindow = touchpadStrictCompatibilityWindow
                || touchpadWarmupCompatibilityWindow
                || touchpadProfileCompatibilityWindow
                || erraticMouseStrictCompatibilityWindow
                || erraticMouseHighErrorCompatibilityWindow
                || erraticMouseProfileCompatibilityWindow
                || touchpadStopCompatibilityWindow
                || touchpadSwitchCompatibilityWindow
                || legitFastChaosCompatibilityWindow
                || lowVolumeFlickCompatibilityWindow
                || blockOverlapChaosCompatibilityWindow
                || blockOverlapHighErrorCompatibilityWindow
                || smoothTouchpadCooldownCompatibilityWindow
                || touchpadLowDeltaSwitchCompatibilityWindow
                || touchpadLowVolumeCadenceCompatibilityWindow
                || lowVolumeBlockOverlapFlickCompatibilityWindow
                || lowHitErraticMissCompatibilityWindow
                || midHitFovBlockCompatibilityWindow
                || midHitFovNoBlockCompatibilityWindow
                || cooldownMidChaosCompatibilityWindow
                || highFastLowErrorTouchpadCompatibilityWindow
                || compactFovInstantLowErrorCompatibilityWindow
                || touchpadCrossCheckCompatibilityWindow;
        boolean guardedFalseWindow = movementOnlyModernWindow
                || rotationDominantModernWindow
                || fastHeadFalseWindow
                || noAimHighHitFalseWindow
                || noAimLowVolumeHighRotationMissFalseWindow
                || sparsePrecisionHighRotationFalseWindow
                || lowSnapCooldownPrecisionFalseWindow
                || highHitLowSnapCooldownFalseWindow
                || cooldownCadenceSaturationFalseWindow
                || lowPrecisionLowHitHighRotationFalseWindow
                || lowApsInstantNoAimFalseWindow
                || lowTempoNoAimHighHitRotationFalseWindow
                || cooldownNoAimPerfectHitRotationFalseWindow
                || cooldownNoAimFastReacquireFalseWindow
                || cooldownNoAimNoReacquireFalseWindow
                || rhythmicLowConversionCooldownFalseWindow
                || sampleStarvedPerfectCooldownFalseWindow
                || lowVolumeInstantReacquireHighHitFalseWindow
                || midHitInstantReacquireRotationFalseWindow
                || midTempoCooldownFastReacquireFalseWindow
                || lowRotationNoReacquireCooldownFalseWindow
                || softCooldownFastReacquireFalseWindow
                || compactInstantReacquireSoftKinematicsFalseWindow
                || instantReacquireSkirmishFalseWindow
                || tightCooldownFastReacquireFalseWindow
                || compactCooldownStickinessFalseWindow
                || tempoBlockOverlapReacquireFalseWindow
                || tempoBlockOverlapSlowReacquireFalseWindow
                || staleLowHitTailFalseWindow
                || lowApsNoAimTailFalseWindow
                || compactNoAimRotationProbeWindow
                || legacyTempoDriftProfileWindow
                || compatibilityFalseWindow;
        if (movementOnlyModernWindow) {
            effectiveModernRaw *= 0.60D;
            modernCooldownRotationCore *= 0.78D;
            modernCooldownLock *= 0.74D;
            modernNonLinearTotal *= 0.84D;
        } else if (rotationDominantModernWindow) {
            effectiveModernRaw *= 0.84D;
            modernCooldownRotationCore *= 0.90D;
            modernCooldownLock *= 0.87D;
            modernNonLinearTotal *= 0.92D;
        }
        if (fastHeadFalseWindow) {
            effectiveModernRaw *= 0.82D;
            modernCooldownRotationCore *= 0.91D;
            modernCooldownLock *= 0.88D;
            modernNonLinearTotal *= 0.94D;
        }
        if (noAimHighHitFalseWindow) {
            effectiveModernRaw *= 0.90D;
            modernCooldownRotationCore *= 0.91D;
            modernCooldownLock *= 0.88D;
            modernNonLinearTotal *= 0.94D;
        }
        if (noAimLowVolumeHighHitFalseWindow) {
            effectiveModernRaw *= 0.96D;
            modernCooldownRotationCore *= 0.96D;
            modernCooldownLock *= 0.96D;
            modernNonLinearTotal *= 0.97D;
        }
        if (noAimLowVolumeHighRotationMissFalseWindow) {
            effectiveModernRaw *= 0.72D;
            modernCooldownRotationCore *= 0.78D;
            modernCooldownLock *= 0.72D;
            modernNonLinearTotal *= 0.80D;
        }
        if (sparsePrecisionHighRotationFalseWindow) {
            effectiveModernRaw *= 0.66D;
            modernCooldownRotationCore *= 0.79D;
            modernCooldownLock *= 0.72D;
            modernNonLinearTotal *= 0.82D;
        }
        if (lowSnapCooldownPrecisionFalseWindow) {
            effectiveModernRaw *= 0.60D;
            modernCooldownRotationCore *= 0.72D;
            modernCooldownLock *= 0.62D;
            modernNonLinearTotal *= 0.79D;
        }
        if (highHitLowSnapCooldownFalseWindow) {
            effectiveModernRaw *= 0.88D;
            modernCooldownRotationCore *= 0.93D;
            modernCooldownLock *= 0.84D;
            modernNonLinearTotal *= 0.93D;
        }
        if (cooldownCadenceSaturationFalseWindow) {
            effectiveModernRaw *= 0.91D;
            modernCooldownRotationCore *= 0.94D;
            modernCooldownLock *= 0.90D;
            modernNonLinearTotal *= 0.94D;
        }
        if (lowPrecisionLowHitHighRotationFalseWindow) {
            effectiveModernRaw *= 0.88D;
            modernCooldownRotationCore *= 0.90D;
            modernCooldownLock *= 0.88D;
            modernNonLinearTotal *= 0.92D;
        }
        if (lowApsInstantNoAimFalseWindow) {
            effectiveModernRaw *= 0.86D;
            modernCooldownRotationCore *= 0.93D;
            modernCooldownLock *= 0.90D;
            modernNonLinearTotal *= 0.93D;
        }
        if (lowTempoNoAimHighHitRotationFalseWindow) {
            effectiveModernRaw *= 0.78D;
            modernCooldownRotationCore *= 0.86D;
            modernCooldownLock *= 0.80D;
            modernNonLinearTotal *= 0.86D;
        }
        if (cooldownNoAimPerfectHitRotationFalseWindow) {
            effectiveModernRaw *= 0.70D;
            modernCooldownRotationCore *= 0.78D;
            modernCooldownLock *= 0.70D;
            modernNonLinearTotal *= 0.80D;
        }
        if (cooldownNoAimFastReacquireFalseWindow) {
            effectiveModernRaw *= 0.64D;
            modernCooldownRotationCore *= 0.76D;
            modernCooldownLock *= 0.62D;
            modernNonLinearTotal *= 0.76D;
        }
        if (cooldownNoAimNoReacquireFalseWindow) {
            effectiveModernRaw *= 0.82D;
            modernCooldownRotationCore *= 0.88D;
            modernCooldownLock *= 0.84D;
            modernNonLinearTotal *= 0.90D;
        }
        if (singleFamilyLowEvidenceCooldownWindow) {
            effectiveModernRaw *= 0.90D;
            modernCooldownRotationCore *= 0.92D;
            modernCooldownLock *= 0.84D;
            modernNonLinearTotal *= 0.90D;
        }
        if (rhythmicLowConversionCooldownFalseWindow) {
            effectiveModernRaw *= 0.68D;
            modernCooldownRotationCore *= 0.72D;
            modernCooldownLock *= 0.66D;
            modernNonLinearTotal *= 0.78D;
        }
        if (sampleStarvedPerfectCooldownFalseWindow) {
            effectiveModernRaw *= 0.58D;
            modernCooldownRotationCore *= 0.68D;
            modernCooldownLock *= 0.54D;
            modernNonLinearTotal *= 0.72D;
        }
        if (lowVolumeInstantReacquireHighHitFalseWindow) {
            effectiveModernRaw *= 0.30D;
            modernCooldownRotationCore *= 0.48D;
            modernCooldownLock *= 0.36D;
            modernNonLinearTotal *= 0.48D;
        }
        if (midHitInstantReacquireRotationFalseWindow) {
            effectiveModernRaw *= 0.40D;
            modernCooldownRotationCore *= 0.68D;
            modernCooldownLock *= 0.52D;
            modernNonLinearTotal *= 0.60D;
        }
        if (midTempoCooldownFastReacquireFalseWindow) {
            effectiveModernRaw *= 0.34D;
            modernCooldownRotationCore *= 0.56D;
            modernCooldownLock *= 0.46D;
            modernNonLinearTotal *= 0.58D;
        }
        if (lowRotationNoReacquireCooldownFalseWindow) {
            effectiveModernRaw *= 0.30D;
            modernCooldownRotationCore *= 0.46D;
            modernCooldownLock *= 0.40D;
            modernNonLinearTotal *= 0.56D;
        }
        if (softCooldownFastReacquireFalseWindow) {
            effectiveModernRaw *= 0.32D;
            modernCooldownRotationCore *= 0.46D;
            modernCooldownLock *= 0.38D;
            modernNonLinearTotal *= 0.54D;
        }
        if (compactInstantReacquireSoftKinematicsFalseWindow) {
            effectiveModernRaw *= 0.28D;
            modernCooldownRotationCore *= 0.42D;
            modernCooldownLock *= 0.34D;
            modernNonLinearTotal *= 0.50D;
        }
        if (instantReacquireSkirmishFalseWindow) {
            effectiveModernRaw *= 0.44D;
            modernCooldownRotationCore *= 0.78D;
            modernCooldownLock *= 0.52D;
            modernNonLinearTotal *= 0.62D;
        }
        if (tightCooldownFastReacquireFalseWindow) {
            effectiveModernRaw *= 0.36D;
            modernCooldownRotationCore *= 0.64D;
            modernCooldownLock *= 0.46D;
            modernNonLinearTotal *= 0.58D;
        }
        if (compactCooldownStickinessFalseWindow) {
            effectiveModernRaw *= 0.32D;
            modernCooldownRotationCore *= 0.52D;
            modernCooldownLock *= 0.42D;
            modernNonLinearTotal *= 0.54D;
        }
        if (tempoBlockOverlapReacquireFalseWindow) {
            effectiveModernRaw *= 0.72D;
            modernCooldownRotationCore *= 0.84D;
            modernCooldownLock *= 0.78D;
            modernNonLinearTotal *= 0.86D;
        }
        if (tempoBlockOverlapSlowReacquireFalseWindow) {
            effectiveModernRaw *= 0.28D;
            modernCooldownRotationCore *= 0.42D;
            modernCooldownLock *= 0.36D;
            modernNonLinearTotal *= 0.54D;
        }
        if (staleLowHitTailFalseWindow) {
            effectiveModernRaw *= 0.38D;
            modernCooldownRotationCore *= 0.50D;
            modernCooldownLock *= 0.42D;
            modernNonLinearTotal *= 0.56D;
            modernFovReacquire *= 0.30D;
        }
        if (lowApsNoAimTailFalseWindow) {
            effectiveModernRaw *= 0.44D;
            modernCooldownRotationCore *= 0.56D;
            modernCooldownLock *= 0.50D;
            modernNonLinearTotal *= 0.58D;
            modernInstantRotation *= 0.34D;
            modernFovReacquire *= 0.32D;
        }
        if (compactNoAimRotationProbeWindow) {
            effectiveModernRaw *= 0.54D;
            modernCooldownRotationCore *= 0.62D;
            modernCooldownLock *= 0.58D;
            modernNonLinearTotal *= 0.64D;
            modernInstantRotation *= 0.46D;
            modernFovReacquire *= 0.44D;
        }
        if (compatibilityFalseWindow) {
            effectiveModernRaw *= 0.18D;
            modernCooldownRotationCore *= 0.18D;
            modernCooldownLock *= 0.16D;
            modernNonLinearTotal *= 0.24D;
            modernInstantRotation *= 0.18D;
            modernFovReacquire *= 0.16D;
            modernFovStickiness *= 0.20D;
        }
        boolean cooldownProfile = snapshot.apsShort >= config.getModernCooldownApsMin()
                && snapshot.apsShort <= config.getModernCooldownApsMax()
                && snapshot.intervalSampleCount >= config.getModernCooldownMinIntervalSamples()
                && snapshot.meanIntervalMs >= config.getModernCooldownIntervalMinMs()
                && snapshot.meanIntervalMs <= config.getModernCooldownIntervalMaxMs();
        boolean cooldownAimContext = snapshot.aimSampleCount >= Math.max(1, config.getMinAttacksShort() - 1)
                && (snapshot.preciseAimShare >= config.getModernAimContextPreciseMin()
                || snapshot.snapPreciseAimShare >= config.getModernAimContextSnapMin());
        double cooldownCoreStrongContribution = config.getWeightRotationQuantization()
                * 1.25D
                * config.getModernCooldownRotationCoreStrongMin();
        boolean cooldownRotationContext = modernCooldownRotationCore >= (cooldownCoreStrongContribution * 0.85D);
        double cooldownLockStrongFactor = guardedFalseWindow ? 0.05D : 0.035D;
        double modernRawStrongFactor = guardedFalseWindow ? 0.22D : 0.18D;
        double cooldownClusterStrongFactor = guardedFalseWindow ? 0.07D : 0.05D;
        boolean modernCooldownStrong = modern.hasEvidence()
                && cooldownProfile
                && (cooldownAimContext || cooldownRotationContext)
                && (modernCooldownLock >= (config.getScoreFlagThreshold() * cooldownLockStrongFactor)
                || effectiveModernRaw >= (config.getScoreFlagThreshold() * modernRawStrongFactor)
                || modernCooldownCluster >= (config.getScoreFlagThreshold() * cooldownClusterStrongFactor));
        double modernContextRawFactor = guardedFalseWindow ? 0.26D : 0.20D;
        double modernContextNonLinearFactor = guardedFalseWindow ? 0.08D : 0.06D;
        boolean modernContextStrong = modern.hasEvidence()
                && (snapshot.preciseAimShare >= config.getModernAimContextPreciseMin()
                || snapshot.snapPreciseAimShare >= Math.max(0.05D, config.getModernAimContextSnapMin())
                || (snapshot.instantRotationAttacksMedium >= 1
                && (snapshot.preciseAimShare >= 0.06D || snapshot.snapPreciseAimShare >= 0.02D))
                || (cooldownProfile && cooldownRotationContext)
                || effectiveModernRaw >= (config.getScoreFlagThreshold() * modernContextRawFactor)
                || modernNonLinearTotal >= (config.getScoreFlagThreshold() * modernContextNonLinearFactor)
                || modernCooldownStrong);
        double modernAggressiveNonLinearFactor = guardedFalseWindow ? 0.09D : 0.07D;
        double modernAggressiveRawFactor = guardedFalseWindow ? 0.24D : 0.20D;
        boolean modernAggressiveStrong = modernCooldownStrong
                || modernNonLinearTotal >= (config.getScoreFlagThreshold() * modernAggressiveNonLinearFactor)
                || effectiveModernRaw >= (config.getScoreFlagThreshold() * modernAggressiveRawFactor);
        boolean lowAttackWindow = snapshot.attacksMedium < config.getMinAttacksMedium();
        boolean lowPrecisionSkirmishWindow = snapshot.attacksMedium <= Math.max(6, config.getMinAttacksMedium() + 2)
                && snapshot.hitRatio <= 0.66D
                && snapshot.preciseAimShare <= (config.getModernAimContextPreciseMin() * 0.45D)
                && snapshot.snapPreciseAimShare <= Math.max(0.01D, config.getModernAimContextSnapMin() * 0.50D);
        boolean lowAttackSupport = hasCrossCheckSupport
                || (snapshot.aimSampleCount >= Math.max(2, config.getMinAttacksShort())
                && (snapshot.preciseAimShare >= config.getModernAimContextPreciseMin()
                || snapshot.snapPreciseAimShare >= Math.max(0.05D, config.getModernAimContextSnapMin())));
        boolean modernAggressiveForFlag = modernAggressiveStrong && (!lowAttackWindow || lowAttackSupport);
        if (lowPrecisionSkirmishWindow && !hasCrossCheckSupport) {
            boolean nonLinearSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.10D);
            modernContextStrong = modernContextStrong && nonLinearSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && nonLinearSupport;
        }
        boolean noAimHighHitHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.07D);
        if (noAimHighHitFalseWindow) {
            modernContextStrong = modernContextStrong && noAimHighHitHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && noAimHighHitHardSupport;
        }
        boolean noAimLowVolumeHighRotationHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.15D)
                && hasCrossCheckSupport
                && (distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1) || weightedDeltaSum >= 4.4D);
        if (noAimLowVolumeHighRotationMissFalseWindow) {
            modernContextStrong = modernContextStrong && noAimLowVolumeHighRotationHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && noAimLowVolumeHighRotationHardSupport;
        }
        boolean lowPrecisionLowHitHardSupport = duplicateKinematicsHardSupport
                || modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.12D)
                || modernCooldownLock >= (config.getScoreFlagThreshold() * 0.18D);
        if (lowPrecisionLowHitHighRotationFalseWindow) {
            modernContextStrong = modernContextStrong && lowPrecisionLowHitHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && lowPrecisionLowHitHardSupport;
        }
        boolean lowApsInstantNoAimHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.11D)
                || modernCooldownLock >= (config.getScoreFlagThreshold() * 0.14D)
                || (hasCrossCheckSupport && weightedDeltaSum >= 3.4D);
        if (lowApsInstantNoAimFalseWindow) {
            modernContextStrong = modernContextStrong && lowApsInstantNoAimHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && lowApsInstantNoAimHardSupport;
        }
        boolean lowTempoNoAimHighHitHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.16D)
                && hasCrossCheckSupport
                && (distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1) || weightedDeltaSum >= 4.8D);
        if (lowTempoNoAimHighHitRotationFalseWindow) {
            modernContextStrong = modernContextStrong && lowTempoNoAimHighHitHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && lowTempoNoAimHighHitHardSupport;
        }
        boolean cooldownNoAimPerfectHitRotationHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.17D)
                && (distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1)
                || (hasCrossCheckSupport
                && weightedDeltaSum >= 4.8D
                && !singleFamilyAimOnly)
                || (singleFamilyAimOnly
                && modernCooldownLock >= (config.getScoreFlagThreshold() * 0.34D)
                && effectiveModernRaw >= (config.getScoreFlagThreshold() * 0.95D)));
        if (cooldownNoAimPerfectHitRotationFalseWindow) {
            modernContextStrong = modernContextStrong && cooldownNoAimPerfectHitRotationHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && cooldownNoAimPerfectHitRotationHardSupport;
        }
        boolean cooldownNoAimFastReacquireHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.18D)
                && (distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1)
                || (hasCrossCheckSupport
                && weightedDeltaSum >= 5.2D
                && !singleFamilyAimOnly)
                || (singleFamilyAimOnly
                && modernCooldownLock >= (config.getScoreFlagThreshold() * 0.38D)
                && effectiveModernRaw >= (config.getScoreFlagThreshold() * 1.02D)));
        if (cooldownNoAimFastReacquireFalseWindow) {
            modernContextStrong = modernContextStrong && cooldownNoAimFastReacquireHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && cooldownNoAimFastReacquireHardSupport;
        }
        boolean cooldownNoAimNoReacquireHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.12D)
                || (hasCrossCheckSupport
                && !singleFamilyAimOnly
                && weightedDeltaSum >= 3.8D)
                || (singleFamilyAimOnly
                && modernCooldownLock >= (config.getScoreFlagThreshold() * 0.24D)
                && effectiveModernRaw >= (config.getScoreFlagThreshold() * 0.58D));
        if (cooldownNoAimNoReacquireFalseWindow) {
            modernContextStrong = modernContextStrong && cooldownNoAimNoReacquireHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && cooldownNoAimNoReacquireHardSupport;
        }
        boolean singleFamilyLowEvidenceCooldownHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.11D)
                && (effectiveModernRaw >= (config.getScoreFlagThreshold() * 0.58D)
                || modernCooldownLock >= (config.getScoreFlagThreshold() * 0.20D));
        if (singleFamilyLowEvidenceCooldownWindow) {
            modernContextStrong = modernContextStrong && singleFamilyLowEvidenceCooldownHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && singleFamilyLowEvidenceCooldownHardSupport;
        }
        boolean sampleStarvedPerfectCooldownHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.18D)
                && (distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1)
                || (hasCrossCheckSupport && !singleFamilyAimOnly && weightedDeltaSum >= 5.0D)
                || (singleFamilyAimOnly
                && modernCooldownLock >= (config.getScoreFlagThreshold() * 0.40D)
                && effectiveModernRaw >= (config.getScoreFlagThreshold() * 1.00D)
                && snapshot.rotationDeltasMedium >= Math.max(110, config.getMinRotationDeltasMedium() * 14)));
        if (profileFlags.sampleStarvedPerfectCooldown) {
            modernContextStrong = modernContextStrong && sampleStarvedPerfectCooldownHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && sampleStarvedPerfectCooldownHardSupport;
        }
        boolean lowVolumeInstantReacquireHighHitHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.26D)
                && (distinctFamilies >= Math.max(3, config.getMinDistinctFamilies() + 2)
                || (hasCrossCheckSupport
                && !singleFamilyAimOnly
                && weightedDeltaSum >= 6.4D));
        if (profileFlags.lowVolumeInstantReacquireHighHit) {
            modernContextStrong = modernContextStrong && lowVolumeInstantReacquireHighHitHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && lowVolumeInstantReacquireHighHitHardSupport;
        }
        boolean midHitInstantReacquireRotationHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.19D)
                && (distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1)
                || (hasCrossCheckSupport
                && !singleFamilyAimOnly
                && weightedDeltaSum >= 5.4D));
        if (profileFlags.midHitInstantReacquireRotation) {
            modernContextStrong = modernContextStrong && midHitInstantReacquireRotationHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && midHitInstantReacquireRotationHardSupport;
        }
        boolean midTempoCooldownFastReacquireHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.20D)
                && ((distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1)
                && weightedDeltaSum >= 4.4D)
                || (hasCrossCheckSupport
                && !singleFamilyAimOnly
                && weightedDeltaSum >= 7.0D
                && snapshot.hitRatio >= 0.84D
                && effectiveModernRaw >= (config.getScoreFlagThreshold() * 0.90D)));
        if (profileFlags.midTempoCooldownFastReacquire) {
            modernContextStrong = modernContextStrong && midTempoCooldownFastReacquireHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && midTempoCooldownFastReacquireHardSupport;
        }
        boolean lowRotationNoReacquireCooldownHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.18D)
                && ((distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1)
                && weightedDeltaSum >= 4.2D
                && effectiveModernRaw >= (config.getScoreFlagThreshold() * 0.58D))
                || (hasCrossCheckSupport
                && !singleFamilyAimOnly
                && weightedDeltaSum >= 6.2D
                && snapshot.hitRatio >= 0.84D));
        if (profileFlags.lowRotationNoReacquireCooldown) {
            modernContextStrong = modernContextStrong && lowRotationNoReacquireCooldownHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && lowRotationNoReacquireCooldownHardSupport;
        }
        boolean softCooldownFastReacquireHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.18D)
                && (distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1)
                || (hasCrossCheckSupport
                && !singleFamilyAimOnly
                && weightedDeltaSum >= 5.0D
                && effectiveModernRaw >= (config.getScoreFlagThreshold() * 0.70D)));
        if (profileFlags.softCooldownFastReacquire) {
            modernContextStrong = modernContextStrong && softCooldownFastReacquireHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && softCooldownFastReacquireHardSupport;
        }
        boolean compactInstantReacquireSoftKinematicsHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.22D)
                && ((distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1)
                && weightedDeltaSum >= 4.8D)
                || (hasCrossCheckSupport
                && !singleFamilyAimOnly
                && weightedDeltaSum >= 5.8D
                && snapshot.hitRatio >= 0.92D
                && effectiveModernRaw >= (config.getScoreFlagThreshold() * 0.76D)));
        if (profileFlags.compactInstantReacquireSoftKinematics) {
            modernContextStrong = modernContextStrong && compactInstantReacquireSoftKinematicsHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && compactInstantReacquireSoftKinematicsHardSupport;
        }
        boolean instantReacquireSkirmishHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.20D)
                && (distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1)
                || (hasCrossCheckSupport
                && !singleFamilyAimOnly
                && weightedDeltaSum >= 5.2D));
        if (profileFlags.instantReacquireSkirmish) {
            modernContextStrong = modernContextStrong && instantReacquireSkirmishHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && instantReacquireSkirmishHardSupport;
        }
        boolean tightCooldownFastReacquireHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.22D)
                && (distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1)
                || (hasCrossCheckSupport
                && !singleFamilyAimOnly
                && weightedDeltaSum >= 5.6D));
        if (profileFlags.tightCooldownFastReacquire) {
            modernContextStrong = modernContextStrong && tightCooldownFastReacquireHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && tightCooldownFastReacquireHardSupport;
        }
        boolean compactCooldownStickinessHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.24D)
                && (distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1)
                || (hasCrossCheckSupport
                && !singleFamilyAimOnly
                && weightedDeltaSum >= 5.8D));
        if (profileFlags.compactCooldownStickiness) {
            modernContextStrong = modernContextStrong && compactCooldownStickinessHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && compactCooldownStickinessHardSupport;
        }
        boolean tempoBlockOverlapReacquireHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.15D)
                && (distinctFamilies >= Math.max(3, config.getMinDistinctFamilies() + 2)
                || (hasCrossCheckSupport
                && weightedDeltaSum >= 6.0D
                && snapshot.hitRatio >= 0.78D));
        if (profileFlags.tempoBlockOverlapReacquire) {
            modernContextStrong = modernContextStrong && tempoBlockOverlapReacquireHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && tempoBlockOverlapReacquireHardSupport;
        }
        boolean tempoBlockOverlapSlowReacquireHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.18D)
                && ((distinctFamilies >= Math.max(3, config.getMinDistinctFamilies() + 2)
                && weightedDeltaSum >= 5.4D)
                || (hasCrossCheckSupport
                && weightedDeltaSum >= 7.2D
                && snapshot.hitRatio >= 0.86D
                && effectiveModernRaw >= (config.getScoreFlagThreshold() * 0.60D)));
        if (profileFlags.tempoBlockOverlapSlowReacquire) {
            modernContextStrong = modernContextStrong && tempoBlockOverlapSlowReacquireHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && tempoBlockOverlapSlowReacquireHardSupport;
        }
        boolean staleLowHitTailHardSupport = duplicateKinematicsHardSupport
                || modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.26D)
                && ((distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1)
                && weightedDeltaSum >= 5.6D
                && !singleFamilyAimOnly)
                || (snapshot.preciseAimShare >= 0.24D
                && snapshot.snapPreciseAimShare >= 0.10D
                && effectiveModernRaw >= (config.getScoreFlagThreshold() * 0.90D)));
        if (staleLowHitTailFalseWindow) {
            modernContextStrong = modernContextStrong && staleLowHitTailHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && staleLowHitTailHardSupport;
        }
        boolean lowApsNoAimTailHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.28D)
                && ((distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1)
                && weightedDeltaSum >= 5.4D
                && !singleFamilyAimOnly)
                || (snapshot.hitRatio >= 0.92D
                && snapshot.confirmedHitsMedium >= 6
                && effectiveModernRaw >= (config.getScoreFlagThreshold() * 1.10D)));
        if (lowApsNoAimTailFalseWindow) {
            modernContextStrong = modernContextStrong && lowApsNoAimTailHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && lowApsNoAimTailHardSupport;
        }
        boolean compactNoAimRotationProbeHardSupport = modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.16D)
                && ((modernCooldownLock >= (config.getScoreFlagThreshold() * 0.58D)
                && effectiveModernRaw >= (config.getScoreFlagThreshold() * 3.30D))
                || (hasCrossCheckSupport
                && !singleFamilyAimOnly
                && weightedDeltaSum >= 5.0D
                && distinctFamilies >= Math.max(2, config.getMinDistinctFamilies() + 1)));
        if (compactNoAimRotationProbeWindow) {
            modernContextStrong = modernContextStrong && compactNoAimRotationProbeHardSupport;
            modernAggressiveForFlag = modernAggressiveForFlag && compactNoAimRotationProbeHardSupport;
        }
        if (duplicateKinematicsHardSupport) {
            modernContextStrong = true;
            modernAggressiveForFlag = true;
        }

        double legacyTempoRaw = contributionValue(legacy.getContributions(), "legacy-attack-rate")
                + contributionValue(legacy.getContributions(), "legacy-short-burst")
                + contributionValue(legacy.getContributions(), "legacy-interval-consistency")
                + contributionValue(legacy.getContributions(), "legacy-interval-entropy");
        double legacyTempoDominance = legacy.getRawScore() <= 0.01D ? 0.0D : (legacyTempoRaw / legacy.getRawScore());
        boolean legacyTempoDriftFalseWindow = legacyTempoDriftProfileWindow
                && legacyTempoDominance >= 0.82D
                && effectiveModernRaw <= (config.getScoreFlagThreshold() * 0.14D)
                && modernNonLinearTotal <= (config.getScoreFlagThreshold() * 0.07D)
                && weightedDeltaSum <= 1.50D;
        boolean tempoOnlyLegacy = legacyTempoDominance >= TEMPO_DOMINANCE_RATIO
                && snapshot.apsShort < 5.6D
                && snapshot.switchesShort <= 1
                && snapshot.multiInteractsShort <= 1
                && !modernContextStrong
                && !hasCrossCheckSupport;

        double legacyBlended = blendPipelineScore(input.previousLegacyPipelineScore, legacy.getRawScore(), config);
        double modernBlended = blendPipelineScore(input.previousModernPipelineScore, effectiveModernRaw, config);
        if (tempoOnlyLegacy) {
            legacyBlended *= TEMPO_ONLY_LEGACY_DAMPEN;
            modernBlended *= 0.92D;
        }
        if (legacyTempoDriftFalseWindow) {
            legacyBlended *= 0.82D;
            modernBlended *= 0.92D;
        }
        if (staleLowHitTailFalseWindow && !staleLowHitTailHardSupport) {
            legacyBlended *= 0.62D;
            modernBlended *= 0.34D;
            deltaScore *= 0.58D;
        }
        if (lowApsNoAimTailFalseWindow && !lowApsNoAimTailHardSupport) {
            legacyBlended *= 0.70D;
            modernBlended *= 0.38D;
            deltaScore *= 0.62D;
        }
        if (compactNoAimRotationProbeWindow && !compactNoAimRotationProbeHardSupport) {
            legacyBlended *= 0.66D;
            modernBlended *= 0.42D;
            deltaScore *= 0.60D;
        }

        double combinedWindowRaw = (legacy.getRawScore() * LEGACY_PIPELINE_WEIGHT)
                + (effectiveModernRaw * MODERN_PIPELINE_WEIGHT)
                + deltaScore;
        if (tempoOnlyLegacy) {
            combinedWindowRaw *= TEMPO_ONLY_LEGACY_DAMPEN;
        }
        if (legacyTempoDriftFalseWindow) {
            combinedWindowRaw *= 0.82D;
        }
        if (staleLowHitTailFalseWindow && !staleLowHitTailHardSupport) {
            combinedWindowRaw *= 0.46D;
        }
        if (lowApsNoAimTailFalseWindow && !lowApsNoAimTailHardSupport) {
            combinedWindowRaw *= 0.50D;
        }
        if (compactNoAimRotationProbeWindow && !compactNoAimRotationProbeHardSupport) {
            combinedWindowRaw *= 0.54D;
        }
        double combinedScore = clamp(
                (legacyBlended * LEGACY_PIPELINE_WEIGHT) + (modernBlended * MODERN_PIPELINE_WEIGHT) + deltaScore,
                0.0D,
                config.getMaxScore()
        );
        if (legacyTempoDriftFalseWindow) {
            combinedScore = clamp(combinedScore * 0.84D, 0.0D, config.getMaxScore());
        }
        if (staleLowHitTailFalseWindow && !staleLowHitTailHardSupport) {
            combinedScore = clamp(combinedScore * 0.48D, 0.0D, config.getMaxScore());
        }
        if (lowApsNoAimTailFalseWindow && !lowApsNoAimTailHardSupport) {
            combinedScore = clamp(combinedScore * 0.52D, 0.0D, config.getMaxScore());
        }
        if (compactNoAimRotationProbeWindow && !compactNoAimRotationProbeHardSupport) {
            combinedScore = clamp(combinedScore * 0.56D, 0.0D, config.getMaxScore());
        }

        double confidence = 1.0D;
        if (input.highPing) {
            confidence -= config.getPingHighPenalty();
        }
        if (input.extremePing) {
            confidence -= config.getPingExtremePenalty();
        }
        if (input.hadRecentPingSpike) {
            confidence -= config.getPingSpikePenalty();
        }

        double tps = input.currentTps;
        if (tps < config.getTpsLowThreshold()) {
            confidence -= config.getTpsLowPenalty();
        }
        if (tps < config.getTpsCriticalThreshold()) {
            confidence -= config.getTpsCriticalPenalty();
        }

        if (snapshot.attacksMedium < config.getMinAttacksMedium()) {
            confidence -= modernAggressiveForFlag ? 0.01D : 0.08D;
        }
        if (snapshot.rotationsMedium < config.getMinRotationsMedium()) {
            confidence -= modernAggressiveForFlag ? 0.02D : 0.06D;
        }
        if (snapshot.rotationDeltasMedium < config.getMinRotationDeltasMedium()) {
            confidence -= modernAggressiveForFlag ? 0.03D : 0.08D;
        }
        if (snapshot.aimSampleCount < Math.max(2, config.getMinAttacksShort())) {
            confidence -= modernAggressiveForFlag ? 0.02D : 0.04D;
        }
        if (deltaEvents < config.getMinCheckDeltaEvents()) {
            confidence -= 0.04D;
        }
        confidence = clamp(confidence, 0.05D, 1.0D);
        boolean lowDeltaLowConfidenceCompatibilityWindow = compatibilityTuningEnabled
                && !compatibilityHardOverride
                && lowDeltaLowConfidenceKinematicEvidence(snapshot, confidence);
        int suspiciousWindowStreak = input.previousSuspiciousWindowStreak;

        boolean legacyEvidence = legacy.hasEvidence();
        boolean modernEvidence = modern.hasEvidence();
        boolean evidenceSufficient = legacyEvidence || modernEvidence || hasCrossCheckSupport;
        boolean strictEvidenceSufficient = hasCrossCheckSupport
                || modernAggressiveForFlag
                || modernContextStrong
                || (legacyEvidence && !tempoOnlyLegacy && snapshot.apsShort >= 5.6D);

        double suspiciousRawGate = modernAggressiveForFlag
                ? (guardedFalseWindow ? 2.4D : 1.8D)
                : SUSPICIOUS_STREAK_MIN_WINDOW_RAW;
        double suspiciousScoreFactor = modernAggressiveForFlag
                ? (guardedFalseWindow ? 0.50D : 0.42D)
                : SUSPICIOUS_STREAK_SCORE_FACTOR;
        double suspiciousConfidenceGate = modernAggressiveForFlag && !guardedFalseWindow
                ? Math.max(0.24D, config.getMinConfidenceToFlag() - 0.18D)
                : Math.max(0.28D, config.getMinConfidenceToFlag() - 0.15D);
        boolean suspiciousWindow = combinedWindowRaw >= suspiciousRawGate
                && combinedScore >= (config.getScoreFlagThreshold() * suspiciousScoreFactor)
                && confidence >= suspiciousConfidenceGate
                && strictEvidenceSufficient
                && evidenceSufficient;
        if (cooldownNoAimFastReacquireFalseWindow && singleFamilyAimOnly && !cooldownNoAimFastReacquireHardSupport) {
            suspiciousWindow = false;
        }
        if (cooldownNoAimPerfectHitRotationFalseWindow
                && noCrossCheckNoAimCooldownWindow
                && !cooldownNoAimPerfectHitRotationHardSupport) {
            suspiciousWindow = false;
        }
        if (cooldownNoAimFastReacquireFalseWindow
                && noCrossCheckNoAimCooldownWindow
                && !cooldownNoAimFastReacquireHardSupport) {
            suspiciousWindow = false;
        }
        if (cooldownNoAimNoReacquireFalseWindow && singleFamilyAimOnly && !cooldownNoAimNoReacquireHardSupport) {
            suspiciousWindow = false;
        }
        if (singleFamilyLowEvidenceCooldownWindow && !singleFamilyLowEvidenceCooldownHardSupport) {
            suspiciousWindow = false;
        }
        if (sampleStarvedPerfectCooldownFalseWindow && singleFamilyAimOnly && !sampleStarvedPerfectCooldownHardSupport) {
            suspiciousWindow = false;
        }
        if (lowVolumeInstantReacquireHighHitFalseWindow && !lowVolumeInstantReacquireHighHitHardSupport) {
            suspiciousWindow = false;
        }
        if (midHitInstantReacquireRotationFalseWindow && !midHitInstantReacquireRotationHardSupport) {
            suspiciousWindow = false;
        }
        if (midTempoCooldownFastReacquireFalseWindow && !midTempoCooldownFastReacquireHardSupport) {
            suspiciousWindow = false;
        }
        if (lowRotationNoReacquireCooldownFalseWindow && !lowRotationNoReacquireCooldownHardSupport) {
            suspiciousWindow = false;
        }
        if (softCooldownFastReacquireFalseWindow && !softCooldownFastReacquireHardSupport) {
            suspiciousWindow = false;
        }
        if (compactInstantReacquireSoftKinematicsFalseWindow && !compactInstantReacquireSoftKinematicsHardSupport) {
            suspiciousWindow = false;
        }
        if (singleFamilyAimNoTempoWindow) {
            suspiciousWindow = false;
        }
        if (instantReacquireSkirmishFalseWindow && !instantReacquireSkirmishHardSupport) {
            suspiciousWindow = false;
        }
        if (tightCooldownFastReacquireFalseWindow && !tightCooldownFastReacquireHardSupport) {
            suspiciousWindow = false;
        }
        if (compactCooldownStickinessFalseWindow && !compactCooldownStickinessHardSupport) {
            suspiciousWindow = false;
        }
        if (tempoBlockOverlapReacquireFalseWindow && !tempoBlockOverlapReacquireHardSupport) {
            suspiciousWindow = false;
        }
        if (tempoBlockOverlapSlowReacquireFalseWindow && !tempoBlockOverlapSlowReacquireHardSupport) {
            suspiciousWindow = false;
        }
        if (staleLowHitTailFalseWindow && !staleLowHitTailHardSupport) {
            suspiciousWindow = false;
        }
        if (lowApsNoAimTailFalseWindow && !lowApsNoAimTailHardSupport) {
            suspiciousWindow = false;
        }
        if (compactNoAimRotationProbeWindow && !compactNoAimRotationProbeHardSupport) {
            suspiciousWindow = false;
        }
        if (suspiciousWindow) {
            suspiciousWindowStreak++;
        } else {
            suspiciousWindowStreak = Math.max(0, suspiciousWindowStreak - 1);
        }

        int requiredAttacksForFlag = modernAggressiveForFlag
                ? Math.max(2, config.getMinAttacksMedium() - 2)
                : config.getMinAttacksMedium();
        int requiredRotationsForFlag = modernAggressiveForFlag
                ? Math.max(2, config.getMinRotationsMedium() - 2)
                : config.getMinRotationsMedium();
        int requiredRotationDeltasForFlag = modernAggressiveForFlag
                ? Math.max(2, config.getMinRotationDeltasMedium() - 4)
                : config.getMinRotationDeltasMedium();
        double requiredConfidenceForFlag = modernAggressiveForFlag
                ? Math.max(0.26D, config.getMinConfidenceToFlag() - 0.12D)
                : config.getMinConfidenceToFlag();
        int requiredStreakForFlag = config.getMinSuspiciousStreak();
        if (modernAggressiveForFlag && !guardedFalseWindow) {
            requiredRotationsForFlag = Math.max(2, config.getMinRotationsMedium() - 3);
            requiredRotationDeltasForFlag = Math.max(1, config.getMinRotationDeltasMedium() - 5);
            requiredConfidenceForFlag = Math.max(0.20D, config.getMinConfidenceToFlag() - 0.16D);
            requiredStreakForFlag = Math.max(1, config.getMinSuspiciousStreak() - 1);
        }
        int requiredConfirmedHitsForFlag = 0;
        double requiredHitRatioForFlag = 0.0D;
        if (rotationDominantModernWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, config.getMinAttacksMedium() + 1);
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(16, config.getMinRotationsMedium() * 4));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(40, config.getMinRotationDeltasMedium() * 6));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.84D, config.getMinConfidenceToFlag() + 0.04D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 2);
        }
        if (fastHeadFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, config.getMinAttacksMedium() + 3);
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(36, config.getMinRotationsMedium() * 7));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(75, config.getMinRotationDeltasMedium() * 9));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.88D, config.getMinConfidenceToFlag() + 0.07D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 4);
            requiredConfirmedHitsForFlag = Math.max(6, (int) Math.ceil(snapshot.attacksMedium * 0.53D));
            requiredHitRatioForFlag = 0.57D;
        }
        if (noAimHighHitFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, config.getMinAttacksMedium() + 1);
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(28, config.getMinRotationsMedium() * 6));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(56, config.getMinRotationDeltasMedium() * 7));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.83D, config.getMinConfidenceToFlag() + 0.03D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 2);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(4, (int) Math.ceil(snapshot.attacksMedium * 0.55D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.67D);
        }
        if (noAimLowVolumeHighHitFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, config.getMinAttacksMedium());
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(14, config.getMinRotationsMedium() * 3));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(30, config.getMinRotationDeltasMedium() * 5));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.82D, config.getMinConfidenceToFlag() + 0.02D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 2);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(3, (int) Math.ceil(snapshot.attacksMedium * 0.70D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.82D);
        }
        if (noAimLowVolumeHighRotationMissFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(6, config.getMinAttacksMedium() + 4));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(72, config.getMinRotationsMedium() * 12));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(140, config.getMinRotationDeltasMedium() * 20));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.93D, config.getMinConfidenceToFlag() + 0.10D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 5);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(6, (int) Math.ceil(snapshot.attacksMedium * 0.80D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.94D);
        }
        if (sparsePrecisionHighRotationFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, config.getMinAttacksMedium() + 1);
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(24, config.getMinRotationsMedium() * 5));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(56, config.getMinRotationDeltasMedium() * 8));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.88D, config.getMinConfidenceToFlag() + 0.05D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 4);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(3, (int) Math.ceil(snapshot.attacksMedium * 0.65D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.66D);
        }
        if (lowSnapCooldownPrecisionFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, config.getMinAttacksMedium() + 1);
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(28, config.getMinRotationsMedium() * 6));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(64, config.getMinRotationDeltasMedium() * 9));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.89D, config.getMinConfidenceToFlag() + 0.06D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 4);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(4, (int) Math.ceil(snapshot.attacksMedium * 0.64D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.62D);
        }
        if (highHitLowSnapCooldownFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, config.getMinAttacksMedium() + 1);
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(20, config.getMinRotationsMedium() * 4));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(48, config.getMinRotationDeltasMedium() * 7));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.86D, config.getMinConfidenceToFlag() + 0.04D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 2);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(4, (int) Math.ceil(snapshot.attacksMedium * 0.70D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.90D);
        }
        if (cooldownCadenceSaturationFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, config.getMinAttacksMedium() + 1);
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(18, config.getMinRotationsMedium() * 4));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(44, config.getMinRotationDeltasMedium() * 7));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.84D, config.getMinConfidenceToFlag() + 0.02D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 2);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(3, (int) Math.ceil(snapshot.attacksMedium * 0.60D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.68D);
        }
        if (lowPrecisionLowHitHighRotationFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(8, config.getMinAttacksMedium() + 4));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(26, config.getMinRotationsMedium() * 6));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(60, config.getMinRotationDeltasMedium() * 9));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.90D, config.getMinConfidenceToFlag() + 0.06D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 3);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(5, (int) Math.ceil(snapshot.attacksMedium * 0.45D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.50D);
        }
        if (lowApsInstantNoAimFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(6, config.getMinAttacksMedium() + 3));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(32, config.getMinRotationsMedium() * 8));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(72, config.getMinRotationDeltasMedium() * 12));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.92D, config.getMinConfidenceToFlag() + 0.08D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 4);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(5, (int) Math.ceil(snapshot.attacksMedium * 0.60D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.86D);
        }
        if (lowTempoNoAimHighHitRotationFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(6, config.getMinAttacksMedium() + 4));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(90, config.getMinRotationsMedium() * 16));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(170, config.getMinRotationDeltasMedium() * 24));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.93D, config.getMinConfidenceToFlag() + 0.09D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 6);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(6, (int) Math.ceil(snapshot.attacksMedium * 0.75D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.95D);
        }
        if (cooldownNoAimPerfectHitRotationFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(6, config.getMinAttacksMedium() + 4));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(64, config.getMinRotationsMedium() * 11));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(120, config.getMinRotationDeltasMedium() * 16));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.92D, config.getMinConfidenceToFlag() + 0.08D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 5);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(5, (int) Math.ceil(snapshot.attacksMedium * 0.74D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.90D);
        }
        if (cooldownNoAimFastReacquireFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(6, config.getMinAttacksMedium() + 4));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(72, config.getMinRotationsMedium() * 12));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(132, config.getMinRotationDeltasMedium() * 18));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.94D, config.getMinConfidenceToFlag() + 0.10D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 6);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(6, (int) Math.ceil(snapshot.attacksMedium * 0.80D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.95D);
        }
        if (cooldownNoAimNoReacquireFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(6, config.getMinAttacksMedium() + 3));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(42, config.getMinRotationsMedium() * 7));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(86, config.getMinRotationDeltasMedium() * 11));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.90D, config.getMinConfidenceToFlag() + 0.05D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 3);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(5, (int) Math.ceil(snapshot.attacksMedium * 0.72D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.90D);
        }
        if (singleFamilyLowEvidenceCooldownWindow) {
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 4);
        }
        if (legacyTempoDriftFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(10, config.getMinAttacksMedium() + 6));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(8, config.getMinRotationsMedium() * 2));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(20, config.getMinRotationDeltasMedium() * 4));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.90D, config.getMinConfidenceToFlag() + 0.08D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 3);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(6, (int) Math.ceil(snapshot.attacksMedium * 0.40D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.40D);
        }
        if (rhythmicLowConversionCooldownFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(14, config.getMinAttacksMedium() + 10));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(42, config.getMinRotationsMedium() * 10));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(42, config.getMinRotationDeltasMedium() * 8));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.92D, config.getMinConfidenceToFlag() + 0.08D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 4);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(9, (int) Math.ceil(snapshot.attacksMedium * 0.52D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.52D);
        }
        if (sampleStarvedPerfectCooldownFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(6, config.getMinAttacksMedium() + 4));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(72, config.getMinRotationsMedium() * 12));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(120, config.getMinRotationDeltasMedium() * 16));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.94D, config.getMinConfidenceToFlag() + 0.10D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 6);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(6, (int) Math.ceil(snapshot.attacksMedium * 0.95D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.98D);
        }
        if (lowVolumeInstantReacquireHighHitFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(7, config.getMinAttacksMedium() + 5));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(84, config.getMinRotationsMedium() * 14));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(152, config.getMinRotationDeltasMedium() * 22));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.96D, config.getMinConfidenceToFlag() + 0.12D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 6);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, 6);
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.98D);
        }
        if (midHitInstantReacquireRotationFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(7, config.getMinAttacksMedium() + 5));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(80, config.getMinRotationsMedium() * 13));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(148, config.getMinRotationDeltasMedium() * 21));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.95D, config.getMinConfidenceToFlag() + 0.11D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 6);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, 6);
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.84D);
        }
        if (midTempoCooldownFastReacquireFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(13, config.getMinAttacksMedium() + 9));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(84, config.getMinRotationsMedium() * 14));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(154, config.getMinRotationDeltasMedium() * 22));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.96D, config.getMinConfidenceToFlag() + 0.12D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 6);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(9, (int) Math.ceil(snapshot.attacksMedium * 0.76D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.80D);
        }
        if (lowRotationNoReacquireCooldownFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(9, config.getMinAttacksMedium() + 6));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(56, config.getMinRotationsMedium() * 9));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(102, config.getMinRotationDeltasMedium() * 14));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.94D, config.getMinConfidenceToFlag() + 0.10D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 5);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(7, (int) Math.ceil(snapshot.attacksMedium * 0.72D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.80D);
        }
        if (softCooldownFastReacquireFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(8, config.getMinAttacksMedium() + 6));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(72, config.getMinRotationsMedium() * 12));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(128, config.getMinRotationDeltasMedium() * 18));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.95D, config.getMinConfidenceToFlag() + 0.11D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 6);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, 6);
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.96D);
        }
        if (compactInstantReacquireSoftKinematicsFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(7, config.getMinAttacksMedium() + 5));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(78, config.getMinRotationsMedium() * 12));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(146, config.getMinRotationDeltasMedium() * 20));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.95D, config.getMinConfidenceToFlag() + 0.11D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 6);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, 5);
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.92D);
        }
        if (instantReacquireSkirmishFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(6, config.getMinAttacksMedium() + 4));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(80, config.getMinRotationsMedium() * 13));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(150, config.getMinRotationDeltasMedium() * 21));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.95D, config.getMinConfidenceToFlag() + 0.11D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 6);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, 5);
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.90D);
        }
        if (tightCooldownFastReacquireFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(6, config.getMinAttacksMedium() + 4));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(82, config.getMinRotationsMedium() * 13));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(150, config.getMinRotationDeltasMedium() * 21));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.95D, config.getMinConfidenceToFlag() + 0.11D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 6);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, 5);
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.98D);
        }
        if (compactCooldownStickinessFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(6, config.getMinAttacksMedium() + 4));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(76, config.getMinRotationsMedium() * 12));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(116, config.getMinRotationDeltasMedium() * 16));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.95D, config.getMinConfidenceToFlag() + 0.11D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 6);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, 5);
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.98D);
        }
        if (tempoBlockOverlapReacquireFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(12, config.getMinAttacksMedium() + 8));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(70, config.getMinRotationsMedium() * 11));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(132, config.getMinRotationDeltasMedium() * 18));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.92D, config.getMinConfidenceToFlag() + 0.08D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 5);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(10, (int) Math.ceil(snapshot.attacksMedium * 0.66D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.76D);
        }
        if (tempoBlockOverlapSlowReacquireFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(16, config.getMinAttacksMedium() + 12));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(82, config.getMinRotationsMedium() * 13));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(148, config.getMinRotationDeltasMedium() * 20));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.96D, config.getMinConfidenceToFlag() + 0.12D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 6);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, Math.max(12, (int) Math.ceil(snapshot.attacksMedium * 0.74D)));
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.84D);
        }
        if (staleLowHitTailFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(14, config.getMinAttacksMedium() + 10));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(64, config.getMinRotationsMedium() * 10));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(120, config.getMinRotationDeltasMedium() * 16));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.94D, config.getMinConfidenceToFlag() + 0.10D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 5);
        }
        if (lowApsNoAimTailFalseWindow) {
            requiredAttacksForFlag = Math.max(requiredAttacksForFlag, Math.max(8, config.getMinAttacksMedium() + 5));
            requiredRotationsForFlag = Math.max(requiredRotationsForFlag, Math.max(70, config.getMinRotationsMedium() * 12));
            requiredRotationDeltasForFlag = Math.max(requiredRotationDeltasForFlag, Math.max(132, config.getMinRotationDeltasMedium() * 18));
            requiredConfidenceForFlag = Math.max(requiredConfidenceForFlag, Math.min(0.94D, config.getMinConfidenceToFlag() + 0.10D));
            requiredStreakForFlag = Math.max(requiredStreakForFlag, 5);
            requiredConfirmedHitsForFlag = Math.max(requiredConfirmedHitsForFlag, 5);
            requiredHitRatioForFlag = Math.max(requiredHitRatioForFlag, 0.86D);
        }

        double requiredScoreForFlag = config.getScoreFlagThreshold();
        if (modernAggressiveForFlag && !guardedFalseWindow) {
            requiredScoreForFlag = config.getScoreFlagThreshold() * 0.80D;
        }
        if (noAimLowVolumeHighHitFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.03D);
        }
        if (noAimLowVolumeHighRotationMissFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.28D);
        }
        if (sparsePrecisionHighRotationFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.08D);
        }
        if (lowSnapCooldownPrecisionFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.12D);
        }
        if (highHitLowSnapCooldownFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.03D);
        }
        if (cooldownCadenceSaturationFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.04D);
        }
        if (lowPrecisionLowHitHighRotationFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.10D);
        }
        if (lowApsInstantNoAimFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.16D);
        }
        if (lowTempoNoAimHighHitRotationFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.24D);
        }
        if (cooldownNoAimPerfectHitRotationFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.20D);
        }
        if (cooldownNoAimFastReacquireFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.28D);
        }
        if (cooldownNoAimNoReacquireFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.10D);
        }
        if (legacyTempoDriftFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.14D);
        }
        if (singleFamilyLowEvidenceCooldownWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.08D);
        }
        if (rhythmicLowConversionCooldownFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.20D);
        }
        if (sampleStarvedPerfectCooldownFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.30D);
        }
        if (lowVolumeInstantReacquireHighHitFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.50D);
        }
        if (midHitInstantReacquireRotationFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.42D);
        }
        if (midTempoCooldownFastReacquireFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.48D);
        }
        if (lowRotationNoReacquireCooldownFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.44D);
        }
        if (softCooldownFastReacquireFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.48D);
        }
        if (compactInstantReacquireSoftKinematicsFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.46D);
        }
        if (instantReacquireSkirmishFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.36D);
        }
        if (tightCooldownFastReacquireFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.40D);
        }
        if (compactCooldownStickinessFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.44D);
        }
        if (tempoBlockOverlapReacquireFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.22D);
        }
        if (tempoBlockOverlapSlowReacquireFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.52D);
        }
        if (staleLowHitTailFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.38D);
        }
        if (lowApsNoAimTailFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.36D);
        }
        if (compactNoAimRotationProbeWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 1.34D);
        }
        if (compatibilityFalseWindow) {
            requiredScoreForFlag = Math.max(requiredScoreForFlag, config.getScoreFlagThreshold() * 2.40D);
            requiredStreakForFlag = Math.max(requiredStreakForFlag, config.getMinSuspiciousStreak() * 8);
        }
        if (duplicateKinematicsHardSupport) {
            requiredAttacksForFlag = Math.min(requiredAttacksForFlag, Math.max(5, config.getMinAttacksMedium() + 1));
            requiredRotationsForFlag = Math.min(requiredRotationsForFlag, Math.max(24, config.getMinRotationsMedium() * 5));
            requiredRotationDeltasForFlag = Math.min(requiredRotationDeltasForFlag, Math.max(48, config.getMinRotationDeltasMedium() * 7));
            requiredConfidenceForFlag = Math.min(requiredConfidenceForFlag, Math.max(0.72D, config.getMinConfidenceToFlag() - 0.04D));
            requiredStreakForFlag = Math.min(requiredStreakForFlag, Math.max(2, config.getMinSuspiciousStreak() - 1));
            requiredConfirmedHitsForFlag = Math.min(
                    Math.max(requiredConfirmedHitsForFlag, 0),
                    Math.max(2, (int) Math.ceil(snapshot.attacksMedium * 0.25D))
            );
            requiredHitRatioForFlag = Math.min(requiredHitRatioForFlag, 0.25D);
            requiredScoreForFlag = Math.min(requiredScoreForFlag, config.getScoreFlagThreshold() * 0.96D);
        }

        boolean shouldFlag = combinedScore >= requiredScoreForFlag
                && confidence >= requiredConfidenceForFlag
                && snapshot.attacksMedium >= requiredAttacksForFlag
                && snapshot.confirmedHitsMedium >= requiredConfirmedHitsForFlag
                && snapshot.hitRatio >= requiredHitRatioForFlag
                && snapshot.rotationsMedium >= requiredRotationsForFlag
                && snapshot.rotationDeltasMedium >= requiredRotationDeltasForFlag
                && suspiciousWindowStreak >= requiredStreakForFlag
                && strictEvidenceSufficient
                && evidenceSufficient;
        if (movementOnlyModernWindow) {
            shouldFlag = false;
        }
        if (noAimHighHitFalseWindow && !noAimHighHitHardSupport) {
            shouldFlag = false;
        }
        if (noAimLowVolumeHighRotationMissFalseWindow
                && (snapshot.fovMissRatio >= 0.40D || snapshot.meanFovReacquireMs >= 320.0D)
                && !noAimLowVolumeHighRotationHardSupport) {
            shouldFlag = false;
        }
        if (lowSnapCooldownPrecisionFalseWindow
                && snapshot.attacksMedium <= config.getMinAttacksMedium()
                && !hasCrossCheckSupport) {
            shouldFlag = false;
        }
        if (highHitLowSnapCooldownFalseWindow
                && snapshot.attacksMedium <= config.getMinAttacksMedium()
                && !hasCrossCheckSupport) {
            shouldFlag = false;
        }
        if (legacyTempoDriftFalseWindow
                && snapshot.hitRatio <= 0.36D
                && modernNonLinearTotal < (config.getScoreFlagThreshold() * 0.06D)
                && !hasCrossCheckSupport) {
            shouldFlag = false;
        }
        if (lowPrecisionLowHitHighRotationFalseWindow
                && snapshot.hitRatio <= 0.42D
                && !lowPrecisionLowHitHardSupport) {
            shouldFlag = false;
        }
        if (lowApsInstantNoAimFalseWindow
                && modernInstantRotation >= Math.max(7.5D, config.getScoreFlagThreshold() * 0.28D)
                && snapshot.preciseAimShare <= 0.05D
                && snapshot.snapPreciseAimShare <= 0.03D
                && !lowApsInstantNoAimHardSupport) {
            shouldFlag = false;
        }
        if (lowTempoNoAimHighHitRotationFalseWindow
                && (snapshot.fovMissRatio >= 0.40D || snapshot.meanFovReacquireMs >= 320.0D)
                && !lowTempoNoAimHighHitHardSupport) {
            shouldFlag = false;
        }
        if (cooldownNoAimPerfectHitRotationFalseWindow
                && singleFamilyAimOnly
                && snapshot.preciseAimShare <= 0.03D
                && snapshot.snapPreciseAimShare <= 0.02D
                && !cooldownNoAimPerfectHitRotationHardSupport) {
            shouldFlag = false;
        }
        if (cooldownNoAimPerfectHitRotationFalseWindow
                && noCrossCheckNoAimCooldownWindow
                && !cooldownNoAimPerfectHitRotationHardSupport) {
            shouldFlag = false;
        }
        if (cooldownNoAimFastReacquireFalseWindow
                && singleFamilyAimOnly
                && snapshot.fovMissRatio <= 0.12D
                && (Double.isNaN(snapshot.meanFovReacquireMs) || snapshot.meanFovReacquireMs <= 130.0D)
                && !cooldownNoAimFastReacquireHardSupport) {
            shouldFlag = false;
        }
        if (cooldownNoAimFastReacquireFalseWindow
                && noCrossCheckNoAimCooldownWindow
                && !cooldownNoAimFastReacquireHardSupport) {
            shouldFlag = false;
        }
        if (cooldownNoAimNoReacquireFalseWindow
                && singleFamilyAimOnly
                && snapshot.fovReacquireSampleCount <= 0
                && snapshot.fovMissRatio <= 0.10D
                && !cooldownNoAimNoReacquireHardSupport) {
            shouldFlag = false;
        }
        if (singleFamilyLowEvidenceCooldownWindow && !singleFamilyLowEvidenceCooldownHardSupport) {
            shouldFlag = false;
        }
        if (sampleStarvedPerfectCooldownFalseWindow
                && singleFamilyAimOnly
                && !sampleStarvedPerfectCooldownHardSupport) {
            shouldFlag = false;
        }
        if (lowVolumeInstantReacquireHighHitFalseWindow && !lowVolumeInstantReacquireHighHitHardSupport) {
            shouldFlag = false;
        }
        if (midHitInstantReacquireRotationFalseWindow && !midHitInstantReacquireRotationHardSupport) {
            shouldFlag = false;
        }
        if (midTempoCooldownFastReacquireFalseWindow && !midTempoCooldownFastReacquireHardSupport) {
            shouldFlag = false;
        }
        if (lowRotationNoReacquireCooldownFalseWindow && !lowRotationNoReacquireCooldownHardSupport) {
            shouldFlag = false;
        }
        if (softCooldownFastReacquireFalseWindow && !softCooldownFastReacquireHardSupport) {
            shouldFlag = false;
        }
        if (compactInstantReacquireSoftKinematicsFalseWindow && !compactInstantReacquireSoftKinematicsHardSupport) {
            shouldFlag = false;
        }
        if (singleFamilyAimNoTempoWindow) {
            shouldFlag = false;
        }
        if (instantReacquireSkirmishFalseWindow && !instantReacquireSkirmishHardSupport) {
            shouldFlag = false;
        }
        if (tightCooldownFastReacquireFalseWindow && !tightCooldownFastReacquireHardSupport) {
            shouldFlag = false;
        }
        if (compactCooldownStickinessFalseWindow && !compactCooldownStickinessHardSupport) {
            shouldFlag = false;
        }
        if (tempoBlockOverlapReacquireFalseWindow && !tempoBlockOverlapReacquireHardSupport) {
            shouldFlag = false;
        }
        if (tempoBlockOverlapSlowReacquireFalseWindow && !tempoBlockOverlapSlowReacquireHardSupport) {
            shouldFlag = false;
        }
        if (staleLowHitTailFalseWindow && !staleLowHitTailHardSupport) {
            shouldFlag = false;
        }
        if (lowApsNoAimTailFalseWindow && !lowApsNoAimTailHardSupport) {
            shouldFlag = false;
        }
        if (compactNoAimRotationProbeWindow && !compactNoAimRotationProbeHardSupport) {
            shouldFlag = false;
        }
        if (compatibilityFalseWindow) {
            shouldFlag = false;
        }
        if (lowDeltaLowConfidenceCompatibilityWindow) {
            shouldFlag = false;
        }
        boolean sustainedModernPatternSignal = modernCooldownLock >= (config.getScoreFlagThreshold() * 0.12D)
                || modernInstantRotation >= (config.getScoreFlagThreshold() * 0.16D)
                || modernNonLinearTotal >= (config.getScoreFlagThreshold() * 0.20D)
                || duplicateKinematicTotal >= (config.getScoreFlagThreshold() * 0.30D);
        boolean sustainedModernPattern = !shouldFlag
                && sustainedModernPatternSignal
                && suspiciousWindowStreak >= Math.max(80, config.getMinSuspiciousStreak() * 80)
                && combinedScore >= (config.getScoreFlagThreshold() * 1.12D)
                && confidence >= Math.max(0.82D, config.getMinConfidenceToFlag())
                && snapshot.attacksMedium >= Math.max(8, config.getMinAttacksMedium() + 6)
                && snapshot.confirmedHitsMedium >= Math.max(4, (int) Math.ceil(snapshot.attacksMedium * 0.36D))
                && snapshot.hitRatio >= 0.34D
                && snapshot.rotationsMedium >= Math.max(48, config.getMinRotationsMedium() * 12)
                && snapshot.rotationDeltasMedium >= Math.max(88, config.getMinRotationDeltasMedium() * 20)
                && snapshot.apsShort >= 1.20D
                && snapshot.apsShort <= 8.50D
                && !singleFamilyAimNoTempoWindow
                && !(movementOnlyModernWindow && !duplicateKinematicsHardSupport)
                && !(compactNoAimRotationProbeWindow && !compactNoAimRotationProbeHardSupport)
                && !(staleLowHitTailFalseWindow && !staleLowHitTailHardSupport)
                && !(lowApsNoAimTailFalseWindow && !lowApsNoAimTailHardSupport)
                && !(sampleStarvedPerfectCooldownFalseWindow && singleFamilyAimOnly && !sampleStarvedPerfectCooldownHardSupport)
                && !(legacyTempoDriftFalseWindow && !modernContextStrong && !hasCrossCheckSupport)
                && !compatibilityFalseWindow
                && !lowDeltaLowConfidenceCompatibilityWindow;
        boolean sustainedAuraPressurePattern = !shouldFlag
                && sustainedAuraPressureKinematics
                && combinedScore >= Math.max(95.0D, config.getScoreFlagThreshold() * 0.95D)
                && confidence >= Math.max(0.90D, config.getMinConfidenceToFlag())
                && suspiciousWindowStreak >= Math.max(6, config.getMinSuspiciousStreak() + 2)
                && !compatibilityFalseWindow
                && !lowDeltaLowConfidenceCompatibilityWindow;
        if (sustainedModernPattern) {
            shouldFlag = true;
        }
        if (sustainedAuraPressurePattern) {
            shouldFlag = true;
        }
        String reasonSummary = "";
        List<String> topSignals = Collections.emptyList();
        if (shouldFlag) {
            reasonSummary = String.format(Locale.US,
                    "score=%.1f conf=%.2f raw=%.1f legacy=%.1f modern=%.1f delta=%.1f cd=%.1f streak=%d atk=%d hit=%d rot=%d dRot=%d aps=%.1f hr=%.2f aim=%.2f snapAim=%.2f react=%.0fms acc=%.2f jerk=%.2f td=%.2f fMiss=%.2f reacq=%.0fms tps=%.2f",
                    combinedScore, confidence, combinedWindowRaw, legacyBlended, modernBlended, deltaScore,
                    modernCooldownLock,
                    suspiciousWindowStreak, snapshot.attacksMedium, snapshot.confirmedHitsMedium,
                    snapshot.rotationsMedium, snapshot.rotationDeltasMedium, snapshot.apsShort, snapshot.hitRatio,
                    snapshot.preciseAimShare, snapshot.snapPreciseAimShare, defaultIfNaN(snapshot.meanSwitchReactionMs, -1.0D),
                    defaultIfNaN(snapshot.meanYawAcceleration, -1.0D), defaultIfNaN(snapshot.meanYawJerk, -1.0D),
                    legacyTempoDominance, snapshot.fovMissRatio, defaultIfNaN(snapshot.meanFovReacquireMs, -1.0D), tps);

            topSignals = buildTopSignals(
                    legacy.getContributions(),
                    modern.getContributions(),
                    crossContributions,
                    familyTotals
            );
            if (sustainedModernPattern) {
                List<String> sustainedSignals = new ArrayList<>(topSignals);
                sustainedSignals.add(0, "modern-sustained-pattern");
                topSignals = sustainedSignals;
            }
            if (sustainedAuraPressurePattern) {
                List<String> sustainedSignals = new ArrayList<>(topSignals);
                sustainedSignals.add(0, "modern-sustained-aura-pressure");
                topSignals = sustainedSignals;
            }
        }

        return new EvaluationUpdate(
                input.playerId,
                legacyBlended,
                modernBlended,
                combinedScore,
                confidence,
                suspiciousWindowStreak,
                now,
                new CombatEngineVerdict(
                        combinedScore,
                        confidence,
                        reasonSummary,
                        topSignals,
                        shouldFlag,
                        compatibilityFalseWindow
                                || lowDeltaLowConfidenceCompatibilityWindow
                                || mxLegitNoiseKinematicEvidence(snapshot),
                        now
                )
        );
    }

    private static double updateCompatibilityScore(double previous,
                                                   boolean evidence,
                                                   double decay,
                                                   double gain,
                                                   double missPenalty) {
        return Math.max(0.0D, (previous * decay) + (evidence ? gain : -missPenalty));
    }

    private static boolean touchpadStrictKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot) {
        return snapshot != null
                && snapshot.apsShort <= 2.6D
                && snapshot.hitRatio >= 0.78D
                && snapshot.instantRotationAttacksMedium <= 1
                && snapshot.rotationDeltasMedium <= 72
                && snapshot.rotationsMedium >= 22
                && snapshot.rotationsMedium <= 84
                && snapshot.meanAimYawError >= 2.0D
                && snapshot.fastRotationShare >= 0.10D
                && snapshot.rotationMaxBucketShare <= 0.18D
                && snapshot.rotationUniqueRatio >= 0.62D;
    }

    private static boolean touchpadWarmupKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot,
                                                           double legacyTargetSwitchBurst,
                                                           double legacyNoSwingRatio) {
        return snapshot != null
                && snapshot.apsShort <= 2.6D
                && snapshot.hitRatio >= 0.65D
                && snapshot.instantRotationAttacksMedium == 0
                && snapshot.rotationDeltasMedium <= 45
                && snapshot.rotationsMedium >= 20
                && snapshot.rotationsMedium <= 65
                && snapshot.meanAimYawError >= 6.0D
                && snapshot.fastRotationShare >= 0.14D
                && snapshot.rotationMaxBucketShare <= 0.16D
                && snapshot.rotationUniqueRatio >= 0.88D
                && (legacyTargetSwitchBurst > 0.0D
                || legacyNoSwingRatio > 0.0D
                || snapshot.microCorrectionShare <= 0.18D);
    }

    private static boolean touchpadKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot) {
        return snapshot != null
                && snapshot.apsShort <= 2.6D
                && snapshot.hitRatio >= 0.60D
                && snapshot.instantRotationAttacksMedium <= 1
                && snapshot.rotationDeltasMedium <= 80
                && snapshot.rotationsMedium >= 20
                && snapshot.rotationsMedium <= 85
                && snapshot.meanAimYawError >= 2.0D
                && snapshot.fastRotationShare >= 0.10D
                && snapshot.rotationMaxBucketShare <= 0.18D
                && snapshot.rotationUniqueRatio >= 0.62D;
    }

    private static boolean erraticMouseStrictKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot) {
        return snapshot != null
                && snapshot.rotationDeltasMedium >= 100
                && snapshot.fastRotationShare >= 0.11D
                && snapshot.meanYawAcceleration >= 4.2D
                && snapshot.meanYawJerk >= 3.0D
                && snapshot.rotationMaxBucketShare <= 0.09D
                && snapshot.rotationUniqueRatio >= 0.55D
                && snapshot.meanAimYawError >= 7.5D
                && snapshot.hitRatio <= 0.92D
                && snapshot.apsShort <= 5.2D;
    }

    private static boolean erraticMouseKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot) {
        return snapshot != null
                && snapshot.rotationDeltasMedium >= 68
                && snapshot.fastRotationShare >= 0.06D
                && snapshot.meanYawAcceleration >= 4.2D
                && snapshot.meanYawJerk >= 3.0D
                && snapshot.rotationMaxBucketShare <= 0.09D
                && snapshot.rotationUniqueRatio >= 0.55D
                && snapshot.meanAimYawError >= 7.5D
                && snapshot.hitRatio <= 0.92D
                && snapshot.apsShort <= 5.2D
                && snapshot.rotationsMedium >= 34;
    }

    private static boolean highErrorMouseKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot) {
        return snapshot != null
                && snapshot.rotationDeltasMedium >= 120
                && snapshot.fastRotationShare >= 0.14D
                && snapshot.meanYawAcceleration >= 5.0D
                && snapshot.meanYawJerk >= 5.0D
                && snapshot.rotationMaxBucketShare <= 0.08D
                && snapshot.rotationUniqueRatio >= 0.55D
                && snapshot.meanAimYawError >= 12.0D
                && snapshot.rotationsMedium >= 70
                && snapshot.apsShort <= 3.6D;
    }

    private static boolean touchpadStopKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot) {
        return snapshot != null
                && snapshot.rotationDeltasMedium <= 10
                && snapshot.hitRatio >= 0.70D
                && snapshot.rotationUniqueRatio >= 0.80D
                && snapshot.microCorrectionShare <= 0.02D
                && snapshot.fastRotationShare >= 0.12D
                && snapshot.meanYawAcceleration >= 2.0D;
    }

    private static boolean touchpadSwitchKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot) {
        return snapshot != null
                && snapshot.rotationDeltasMedium <= 60
                && snapshot.hitRatio >= 0.70D
                && snapshot.rotationUniqueRatio >= 0.75D
                && snapshot.fastRotationShare >= 0.16D
                && snapshot.rotationMaxBucketShare <= 0.22D
                && snapshot.meanYawAcceleration >= 3.5D
                && snapshot.meanYawJerk >= 2.5D;
    }

    private static boolean legitFastChaosKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot) {
        return snapshot != null
                && snapshot.rotationMaxBucketShare <= 0.12D
                && snapshot.rotationUniqueRatio >= 0.58D
                && snapshot.fastRotationShare >= 0.18D
                && snapshot.meanYawAcceleration >= 4.0D
                && snapshot.meanYawJerk >= 3.2D
                && snapshot.meanAimYawError >= 6.0D
                && snapshot.hitRatio >= 0.70D
                && snapshot.instantRotationAttacksMedium <= 2;
    }

    private static boolean lowVolumeFlickKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot) {
        return snapshot != null
                && snapshot.attacksMedium <= 4
                && snapshot.hitRatio >= 0.70D
                && snapshot.instantRotationAttacksMedium >= 1
                && snapshot.fastRotationShare >= 0.28D
                && snapshot.meanYawAcceleration >= 12.0D
                && snapshot.meanYawJerk >= 12.0D
                && snapshot.meanAimYawError >= 24.0D
                && snapshot.rotationMaxBucketShare <= 0.07D
                && snapshot.rotationUniqueRatio >= 0.74D;
    }

    private static boolean blockOverlapChaosKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot,
                                                              double legacyBlockHitOverlap,
                                                              double modernFovReacquire) {
        return snapshot != null
                && legacyBlockHitOverlap > 0.0D
                && snapshot.rotationMaxBucketShare <= 0.11D
                && snapshot.rotationUniqueRatio >= 0.48D
                && snapshot.fastRotationShare >= 0.08D
                && snapshot.meanYawAcceleration >= 4.0D
                && snapshot.meanYawJerk >= 3.4D
                && snapshot.meanAimYawError >= 7.0D
                && snapshot.hitRatio <= 0.75D;
    }

    private static boolean blockOverlapHighErrorKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot,
                                                                  double legacyBlockHitOverlap) {
        return snapshot != null
                && legacyBlockHitOverlap > 0.0D
                && snapshot.meanAimYawError >= 10.0D
                && snapshot.hitRatio >= 0.70D
                && snapshot.rotationMaxBucketShare <= 0.07D;
    }

    private static boolean lowDeltaLowConfidenceKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot,
                                                                  double confidence) {
        return snapshot != null
                && snapshot.rotationDeltasMedium <= 45
                && confidence <= 0.85D;
    }

    private static boolean smoothTouchpadCooldownKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot) {
        return snapshot != null
                && snapshot.hitRatio >= 0.83D
                && snapshot.rotationDeltasMedium <= 70
                && snapshot.meanYawJerk <= 3.5D
                && snapshot.rotationMaxBucketShare <= 0.14D;
    }

    private static boolean touchpadLowDeltaSwitchKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot,
                                                                    double legacyTargetSwitchBurst) {
        return snapshot != null
                && legacyTargetSwitchBurst > 0.0D
                && snapshot.attacksMedium >= 8
                && snapshot.hitRatio >= 0.70D
                && snapshot.rotationDeltasMedium <= 25
                && snapshot.meanYawAcceleration <= 1.6D
                && snapshot.meanYawJerk <= 1.0D
                && snapshot.rotationUniqueRatio >= 0.75D;
    }

    private static boolean touchpadLowVolumeCadenceKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot,
                                                                      double modernRotationCadence) {
        return snapshot != null
                && modernRotationCadence > 0.0D
                && snapshot.attacksMedium <= 3
                && snapshot.hitRatio >= 0.65D
                && snapshot.rotationDeltasMedium <= 72
                && snapshot.meanYawAcceleration >= 3.5D
                && snapshot.meanYawAcceleration <= 5.1D
                && snapshot.meanYawJerk <= 2.6D;
    }

    private static boolean lowVolumeBlockOverlapFlickKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot,
                                                                       double legacyBlockHitOverlap) {
        return snapshot != null
                && legacyBlockHitOverlap > 0.0D
                && snapshot.attacksMedium <= 3
                && snapshot.rotationDeltasMedium <= 16
                && snapshot.meanYawAcceleration >= 7.0D
                && snapshot.meanYawJerk >= 8.0D;
    }

    private static boolean lowHitErraticMissKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot,
                                                              double modernCooldownLock) {
        return snapshot != null
                && snapshot.attacksMedium >= 8
                && snapshot.hitRatio <= 0.45D
                && snapshot.rotationDeltasMedium >= 90
                && modernCooldownLock <= 3.2D
                && snapshot.meanYawAcceleration >= 2.5D
                && snapshot.meanYawAcceleration <= 3.5D
                && snapshot.meanYawJerk >= 2.3D;
    }

    private static boolean midHitFovBlockKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot,
                                                           double legacyBlockHitOverlap,
                                                           double modernFovReacquire) {
        return snapshot != null
                && legacyBlockHitOverlap > 0.0D
                && modernFovReacquire > 0.0D
                && snapshot.hitRatio <= 0.65D
                && snapshot.rotationDeltasMedium >= 120
                && snapshot.meanYawJerk >= 2.8D
                && snapshot.meanAimYawError >= 8.0D;
    }

    private static boolean midHitFovNoBlockKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot,
                                                             double legacyBlockHitOverlap,
                                                             double modernFovReacquire) {
        return snapshot != null
                && legacyBlockHitOverlap <= 0.0D
                && modernFovReacquire > 0.0D
                && snapshot.attacksMedium >= 10
                && snapshot.hitRatio <= 0.56D
                && snapshot.rotationDeltasMedium >= 100
                && snapshot.meanYawAcceleration >= 3.4D
                && snapshot.meanYawAcceleration <= 4.0D
                && snapshot.meanYawJerk >= 3.0D
                && snapshot.meanYawJerk <= 3.4D
                && snapshot.meanAimYawError >= 5.0D
                && snapshot.meanAimYawError <= 7.0D
                && snapshot.rotationMaxBucketShare <= 0.08D
                && snapshot.rotationUniqueRatio >= 0.60D;
    }

    private static boolean cooldownMidChaosKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot,
                                                             double modernCooldownRotationCore) {
        return snapshot != null
                && modernCooldownRotationCore > 0.0D
                && snapshot.attacksMedium >= 10
                && snapshot.hitRatio >= 0.50D
                && snapshot.hitRatio <= 0.75D
                && snapshot.rotationDeltasMedium >= 100
                && snapshot.meanYawAcceleration >= 4.0D
                && snapshot.meanYawJerk >= 3.3D
                && snapshot.rotationMaxBucketShare <= 0.09D;
    }

    private static boolean highFastLowErrorTouchpadKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot) {
        return snapshot != null
                && snapshot.attacksMedium <= 4
                && snapshot.hitRatio >= 0.70D
                && snapshot.fastRotationShare >= 0.30D
                && snapshot.rotationUniqueRatio >= 0.75D
                && snapshot.meanYawAcceleration >= 8.5D
                && snapshot.meanYawJerk >= 5.5D
                && snapshot.meanAimYawError <= 8.0D;
    }

    private static boolean compactFovInstantLowErrorKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot,
                                                                      double modernFovReacquire,
                                                                      double modernInstantRotation) {
        return snapshot != null
                && modernFovReacquire > 0.0D
                && modernInstantRotation > 0.0D
                && snapshot.attacksMedium <= 3
                && snapshot.hitRatio >= 0.60D
                && snapshot.rotationDeltasMedium >= 55
                && snapshot.rotationDeltasMedium <= 80
                && snapshot.instantRotationAttacksMedium >= 1
                && snapshot.meanYawAcceleration >= 3.8D
                && snapshot.meanYawAcceleration <= 5.0D
                && snapshot.meanYawJerk >= 2.8D
                && snapshot.meanYawJerk <= 3.6D
                && snapshot.meanAimYawError <= 22.0D;
    }

    private static boolean mxLegitNoiseKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        boolean noisyLowConversion = snapshot.hitRatio <= 0.58D
                && snapshot.rotationDeltasMedium >= 50
                && snapshot.fastRotationShare >= 0.08D
                && snapshot.meanYawAcceleration >= 6.5D
                && snapshot.meanYawJerk >= 5.5D;
        boolean broadErraticMouse = snapshot.hitRatio >= 0.60D
                && snapshot.rotationDeltasMedium >= 140
                && snapshot.rotationMaxBucketShare <= 0.04D
                && snapshot.rotationUniqueRatio >= 0.62D
                && snapshot.microCorrectionShare <= 0.22D
                && snapshot.meanYawAcceleration >= 4.0D
                && snapshot.meanYawJerk >= 3.0D
                && snapshot.meanAimYawError >= 8.0D;
        boolean fastErraticMouse = snapshot.hitRatio >= 0.65D
                && snapshot.fastRotationShare >= 0.30D
                && snapshot.meanYawAcceleration >= 10.0D
                && snapshot.meanYawJerk >= 7.0D
                && snapshot.rotationMaxBucketShare <= 0.04D
                && snapshot.rotationUniqueRatio >= 0.65D;
        return noisyLowConversion || broadErraticMouse || fastErraticMouse;
    }

    private static boolean touchpadCrossCheckCompatibilityEvidence(CombatEngineScorer.CombatSnapshot snapshot) {
        return snapshot != null
                && snapshot.attacksMedium >= 4
                && snapshot.hitRatio >= 0.75D
                && snapshot.apsShort <= 2.6D
                && snapshot.rotationDeltasMedium <= 48
                && snapshot.preciseAimShare <= 0.02D
                && snapshot.snapPreciseAimShare <= 0.01D
                && snapshot.meanYawAcceleration <= 8.6D
                && snapshot.meanYawJerk <= 6.7D;
    }

    private static boolean sustainedAuraPressureKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot) {
        return snapshot != null
                && snapshot.attacksMedium >= 7
                && snapshot.rotationsMedium >= 40
                && snapshot.rotationDeltasMedium >= 68
                && snapshot.apsShort >= 1.20D
                && snapshot.apsShort <= 8.50D
                && snapshot.hitRatio >= 0.22D
                && snapshot.hitRatio <= 0.62D
                && snapshot.microCorrectionShare >= 0.36D
                && snapshot.microCorrectionShare <= 0.58D
                && snapshot.fastRotationShare <= 0.13D
                && snapshot.meanYawAcceleration <= 3.0D
                && snapshot.meanYawJerk <= 2.8D
                && snapshot.rotationUniqueRatio <= 0.64D
                && snapshot.rotationMaxBucketShare >= 0.09D;
    }

    private static boolean cheatKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        return (snapshot.rotationMaxBucketShare >= 0.10D
                && snapshot.rotationUniqueRatio <= 0.58D
                && snapshot.meanYawJerk <= 3.2D)
                || (snapshot.microCorrectionShare >= 0.38D
                && snapshot.fastRotationShare <= 0.075D
                && snapshot.meanYawJerk <= 3.1D)
                || (snapshot.meanYawAcceleration <= 1.5D
                && snapshot.meanYawJerk <= 1.2D
                && snapshot.microCorrectionShare >= 0.25D);
    }

    private static boolean awayBackSpoofKinematicEvidence(CombatEngineScorer.CombatSnapshot snapshot) {
        return snapshot != null
                && snapshot.attacksMedium >= 6
                && snapshot.hitRatio >= 0.94D
                && snapshot.instantRotationAttacksMedium >= 2
                && snapshot.rotationDeltasMedium >= 96
                && snapshot.fastRotationShare >= 0.12D
                && snapshot.meanYawAcceleration >= 4.8D
                && snapshot.meanYawJerk >= 4.6D
                && snapshot.rotationMaxBucketShare <= 0.10D
                && snapshot.rotationUniqueRatio >= 0.55D
                && snapshot.meanAimYawError >= 10.0D
                && snapshot.apsShort <= 6.0D;
    }

    private static final class EvaluationInput {
        private final UUID playerId;
        private final long now;
        private final CombatEngineScorer.CombatSnapshot snapshot;
        private final double previousLegacyPipelineScore;
        private final double previousModernPipelineScore;
        private final int previousSuspiciousWindowStreak;
        private final boolean highPing;
        private final boolean extremePing;
        private final boolean hadRecentPingSpike;
        private final double currentTps;
        private final double touchpadCompatibilityScore;
        private final double erraticMouseCompatibilityScore;
        private final double cheatKinematicCompatibilityScore;
        private final double awayBackSpoofCompatibilityScore;

        private EvaluationInput(UUID playerId,
                                long now,
                                CombatEngineScorer.CombatSnapshot snapshot,
                                double previousLegacyPipelineScore,
                                double previousModernPipelineScore,
                                int previousSuspiciousWindowStreak,
                                boolean highPing,
                                boolean extremePing,
                                boolean hadRecentPingSpike,
                                double currentTps,
                                double touchpadCompatibilityScore,
                                double erraticMouseCompatibilityScore,
                                double cheatKinematicCompatibilityScore,
                                double awayBackSpoofCompatibilityScore) {
            this.playerId = playerId;
            this.now = now;
            this.snapshot = snapshot;
            this.previousLegacyPipelineScore = previousLegacyPipelineScore;
            this.previousModernPipelineScore = previousModernPipelineScore;
            this.previousSuspiciousWindowStreak = previousSuspiciousWindowStreak;
            this.highPing = highPing;
            this.extremePing = extremePing;
            this.hadRecentPingSpike = hadRecentPingSpike;
            this.currentTps = currentTps;
            this.touchpadCompatibilityScore = touchpadCompatibilityScore;
            this.erraticMouseCompatibilityScore = erraticMouseCompatibilityScore;
            this.cheatKinematicCompatibilityScore = cheatKinematicCompatibilityScore;
            this.awayBackSpoofCompatibilityScore = awayBackSpoofCompatibilityScore;
        }
    }

    private static final class EvaluationUpdate {
        private final UUID playerId;
        private final double legacyPipelineScore;
        private final double modernPipelineScore;
        private final double score;
        private final double confidence;
        private final int suspiciousWindowStreak;
        private final long lastEvaluationMs;
        private final CombatEngineVerdict verdict;

        private EvaluationUpdate(UUID playerId,
                                 double legacyPipelineScore,
                                 double modernPipelineScore,
                                 double score,
                                 double confidence,
                                 int suspiciousWindowStreak,
                                 long lastEvaluationMs,
                                 CombatEngineVerdict verdict) {
            this.playerId = playerId;
            this.legacyPipelineScore = legacyPipelineScore;
            this.modernPipelineScore = modernPipelineScore;
            this.score = score;
            this.confidence = confidence;
            this.suspiciousWindowStreak = suspiciousWindowStreak;
            this.lastEvaluationMs = lastEvaluationMs;
            this.verdict = verdict;
        }

        private void apply(CombatEngineState state) {
            if (state == null) {
                return;
            }

            state.setLegacyPipelineScore(legacyPipelineScore);
            state.setModernPipelineScore(modernPipelineScore);
            state.setScore(score);
            state.setConfidence(confidence);
            state.setSuspiciousWindowStreak(suspiciousWindowStreak);
            state.setLastEvaluationMs(lastEvaluationMs);
        }
    }

    private double weightedFamilyDeltaSum(Map<String, Integer> familyTotals) {
        double weighted = 0.0D;
        for (Map.Entry<String, Integer> entry : familyTotals.entrySet()) {
            String family = entry.getKey() == null ? "other" : entry.getKey().toLowerCase(Locale.ROOT);
            int value = Math.max(0, entry.getValue());
            if (value == 0) {
                continue;
            }
            weighted += value * familyRisk(family);
        }
        return weighted;
    }

    private double familyRisk(String family) {
        switch (family) {
            case "reach":
                return 1.35D;
            case "killaura":
                return 1.30D;
            case "aim":
                return 1.20D;
            case "backtrack":
                return 1.15D;
            case "autoblock":
                return 1.12D;
            case "autoclicker":
                return 1.10D;
            default:
                return 1.0D;
        }
    }

    private List<String> buildTopSignals(Map<String, Double> legacySignals,
                                         Map<String, Double> modernSignals,
                                         Map<String, Double> crossSignals,
                                         Map<String, Integer> familyTotals) {
        Map<String, Double> merged = new HashMap<>();
        mergeContributions(merged, legacySignals);
        mergeContributions(merged, modernSignals);
        mergeContributions(merged, crossSignals);

        List<Map.Entry<String, Double>> ordered = new ArrayList<>(merged.entrySet());
        ordered.sort(Comparator.comparingDouble((Map.Entry<String, Double> e) -> e.getValue()).reversed());

        List<String> top = new ArrayList<>();
        for (Map.Entry<String, Double> entry : ordered) {
            if (entry.getValue() <= 0.0D) {
                continue;
            }
            top.add(entry.getKey() + "=" + String.format(Locale.US, "%.2f", entry.getValue()));
            if (top.size() >= 4) {
                break;
            }
        }

        if (!familyTotals.isEmpty()) {
            List<Map.Entry<String, Integer>> families = new ArrayList<>(familyTotals.entrySet());
            families.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
            Map.Entry<String, Integer> strongest = families.get(0);
            top.add("family-" + strongest.getKey() + "=" + strongest.getValue());
        }

        return top;
    }

    private void mergeContributions(Map<String, Double> target, Map<String, Double> source) {
        if (target == null || source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Double> entry : source.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            double value = entry.getValue() == null ? 0.0D : entry.getValue();
            if (value <= 0.0D) {
                continue;
            }
            target.merge(entry.getKey(), value, Double::sum);
        }
    }

    private double addContribution(Map<String, Double> contributions, String key, double value) {
        if (contributions == null || key == null || key.isEmpty() || value <= 0.0D) {
            return 0.0D;
        }
        contributions.merge(key, value, Double::sum);
        return value;
    }

    private double contributionValue(Map<String, Double> contributions, String key) {
        if (contributions == null || key == null || key.isEmpty()) {
            return 0.0D;
        }
        Double value = contributions.get(key);
        return value == null ? 0.0D : Math.max(0.0D, value);
    }

    private void scaleContributions(Map<String, Double> contributions, double multiplier) {
        if (contributions == null || contributions.isEmpty() || multiplier >= 0.999D) {
            return;
        }
        for (Map.Entry<String, Double> entry : contributions.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            double value = entry.getValue() == null ? 0.0D : entry.getValue();
            if (value <= 0.0D) {
                continue;
            }
            entry.setValue(value * multiplier);
        }
    }

    private double blendPipelineScore(double previousScore, double currentRaw, CombatEngineConfig config) {
        double blended = (previousScore * config.getScoreCarryOver()) + (currentRaw * config.getScoreStrictMultiplier());
        if (currentRaw <= 0.01D) {
            blended *= config.getScoreDecayFactor();
        }
        return clamp(blended, 0.0D, config.getMaxScore());
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0D, 1.0D);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double defaultIfNaN(double value, double fallback) {
        return Double.isNaN(value) ? fallback : value;
    }
}
