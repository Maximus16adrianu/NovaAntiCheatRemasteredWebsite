package me.cerial.nova.cloudcombat.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CombatEngineState {

    private static final int MAX_SIGNAL_HISTORY = 320;
    private static final int MAX_ROTATION_SAMPLE_HISTORY = 280;
    private static final int MAX_RAW_TIME_HISTORY = 4096;
    private static final int MAX_CHECK_DELTA_HISTORY = 640;
    private static final long BLOCK_ATTACK_OVERLAP_MS = 150L;
    private static final long INVENTORY_ATTACK_OVERLAP_MS = 220L;
    private static final long SWING_ATTACK_SYNC_MS = 320L;
    private static final long ATTACK_TICK_BUCKET_MS = 50L;
    private static final long ATTACK_TICK_BUCKET_MAX_MS = 170L;
    private static final double ATTACK_TICK_BUCKET_ADAPT_TPS = 19.5D;
    private static final double ATTACK_TICK_BUCKET_TPS_FLOOR = 6.0D;
    private static final long ROTATION_ATTACK_OVERLAP_MS = 12L;
    private static final int MAX_FOV_TRACKING_HISTORY = 560;
    private static final long MAX_SWITCH_REACTION_SAMPLE_MS = 1500L;
    private static final long FAST_SWITCH_SIGNAL_MS = 120L;
    private static final double INSTANT_ROTATION_SIGNAL_YAW = 75.0D;
    private static final double DUPLICATE_ROTATION_EPSILON = 1.0E-3D;
    private static volatile double currentTps = 20.0D;

    private final UUID playerId;

    private final ArrayDeque<CombatSignal> signals = new ArrayDeque<>();
    private final ArrayDeque<Long> attackTimes = new ArrayDeque<>();
    private final ArrayDeque<Long> confirmedHitTimes = new ArrayDeque<>();
    private final ArrayDeque<Long> rotationTimes = new ArrayDeque<>();
    private final ArrayDeque<Long> swingTimes = new ArrayDeque<>();
    private final ArrayDeque<Long> targetSwitchTimes = new ArrayDeque<>();
    private final ArrayDeque<Long> blockUseTimes = new ArrayDeque<>();
    private final ArrayDeque<Long> inventoryActionTimes = new ArrayDeque<>();
    private final ArrayDeque<Long> attackAfterBlockUseTimes = new ArrayDeque<>();
    private final ArrayDeque<Long> attackAfterInventoryTimes = new ArrayDeque<>();
    private final ArrayDeque<Long> tightRotationAttackTimes = new ArrayDeque<>();
    private final ArrayDeque<Long> noSwingAttackTimes = new ArrayDeque<>();
    private final ArrayDeque<Long> multiInteractTimes = new ArrayDeque<>();
    private final ArrayDeque<Long> duplicateRotationTimes = new ArrayDeque<>();
    private final ArrayDeque<DistanceSample> confirmedHitDistanceSamples = new ArrayDeque<>();
    private final ArrayDeque<AttackRotationSample> attackRotationSamples = new ArrayDeque<>();
    private final ArrayDeque<AimSample> attackAimSamples = new ArrayDeque<>();
    private final ArrayDeque<FovTrackingSample> fovTrackingSamples = new ArrayDeque<>();
    private final ArrayDeque<SwitchReactionSample> switchReactionSamples = new ArrayDeque<>();
    private final ArrayDeque<RotationDeltaSample> yawDeltaSamples = new ArrayDeque<>();
    private final ArrayDeque<RotationDeltaSample> pitchDeltaSamples = new ArrayDeque<>();
    private final ArrayDeque<RotationDeltaSample> yawAccelerationSamples = new ArrayDeque<>();
    private final ArrayDeque<RotationDeltaSample> yawJerkSamples = new ArrayDeque<>();
    private final ArrayDeque<CheckDeltaEvent> checkDeltaEvents = new ArrayDeque<>();
    private final Map<String, Integer> lastCheckViolationCounts = new HashMap<>();

    private long lastAttackMs = 0L;
    private long lastConfirmedHitMs = 0L;
    private long lastRotationMs = 0L;
    private long lastAnimationMs = 0L;
    private long lastBlockUseMs = 0L;
    private long lastInventoryActionMs = 0L;
    private long lastEvaluationMs = 0L;
    private long lastActivityMs = 0L;
    private long lastAttackTickBucket = Long.MIN_VALUE;
    private long pendingTargetSwitchMs = 0L;
    private int attacksInCurrentTickBucket = 0;
    private int lastAttackEntityInTickBucket = Integer.MIN_VALUE;
    private double lastYawDelta = Double.NaN;
    private double lastPitchDelta = Double.NaN;
    private double lastYawAcceleration = Double.NaN;

    private int lastTargetEntityId = Integer.MIN_VALUE;

    private double score = 0.0D;
    private double legacyPipelineScore = 0.0D;
    private double modernPipelineScore = 0.0D;
    private double confidence = 1.0D;
    private int suspiciousWindowStreak = 0;
    private double touchpadCompatibilityScore = 0.0D;
    private double erraticMouseCompatibilityScore = 0.0D;
    private double cheatKinematicCompatibilityScore = 0.0D;
    private double awayBackSpoofCompatibilityScore = 0.0D;

    public CombatEngineState(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public static void setCurrentTps(double tps) {
        currentTps = Double.isNaN(tps) || tps <= 0.0D ? 20.0D : tps;
    }

    public void recordAttackPacket(long now, int targetEntityId) {
        recordAttackPacket(now, targetEntityId, Double.NaN, Double.NaN, -1.0D);
    }

    public void recordAttackPacket(long now,
                                   int targetEntityId,
                                   double aimYawError,
                                   double aimPitchError,
                                   double targetDistance) {
        this.lastAttackMs = now;
        this.lastActivityMs = now;
        addTimestamp(this.attackTimes, now);
        addSignal(new CombatSignal(CombatSignalType.ATTACK_PACKET, now, 1.0D, "target=" + targetEntityId));

        // Consume pending switch timing first; the pending marker is set on a prior target switch.
        if (pendingTargetSwitchMs > 0L && now > pendingTargetSwitchMs) {
            long reactionMs = now - pendingTargetSwitchMs;
            if (reactionMs <= MAX_SWITCH_REACTION_SAMPLE_MS) {
                this.switchReactionSamples.addLast(new SwitchReactionSample(now, reactionMs));
                while (this.switchReactionSamples.size() > MAX_ROTATION_SAMPLE_HISTORY) {
                    this.switchReactionSamples.pollFirst();
                }
                if (reactionMs <= FAST_SWITCH_SIGNAL_MS) {
                    addSignal(new CombatSignal(
                            CombatSignalType.FAST_SWITCH_REACTION,
                            now,
                            reactionMs,
                            ""
                    ));
                }
            }
            pendingTargetSwitchMs = 0L;
        }

        registerTargetSwitchIfNeeded(now, targetEntityId);

        if (isRecent(lastBlockUseMs, now, BLOCK_ATTACK_OVERLAP_MS)) {
            addTimestamp(this.attackAfterBlockUseTimes, now);
        }
        if (isRecent(lastInventoryActionMs, now, INVENTORY_ATTACK_OVERLAP_MS)) {
            addTimestamp(this.attackAfterInventoryTimes, now);
        }
        if (isRecent(lastRotationMs, now, ROTATION_ATTACK_OVERLAP_MS)) {
            addTimestamp(this.tightRotationAttackTimes, now);
        }
        if (!isRecent(lastAnimationMs, now, SWING_ATTACK_SYNC_MS)) {
            addTimestamp(this.noSwingAttackTimes, now);
        }

        long tickBucket = now / resolveAttackTickBucketMs();
        if (tickBucket == lastAttackTickBucket) {
            attacksInCurrentTickBucket++;
            boolean switchedTargetInTick = targetEntityId != Integer.MIN_VALUE
                    && lastAttackEntityInTickBucket != Integer.MIN_VALUE
                    && targetEntityId != lastAttackEntityInTickBucket;
            if (attacksInCurrentTickBucket >= 2 && switchedTargetInTick) {
                addTimestamp(this.multiInteractTimes, now);
            }
            lastAttackEntityInTickBucket = targetEntityId;
        } else {
            lastAttackTickBucket = tickBucket;
            attacksInCurrentTickBucket = 1;
            lastAttackEntityInTickBucket = targetEntityId;
        }

        double yawDeltaAtAttack = Double.isNaN(lastYawDelta) ? 0.0D : lastYawDelta;
        long sinceRotationMs = lastRotationMs > 0L ? Math.max(0L, now - lastRotationMs) : Long.MAX_VALUE;
        this.attackRotationSamples.addLast(new AttackRotationSample(now, yawDeltaAtAttack, sinceRotationMs));
        while (this.attackRotationSamples.size() > MAX_ROTATION_SAMPLE_HISTORY) {
            this.attackRotationSamples.pollFirst();
        }
        if (!Double.isNaN(aimYawError) && !Double.isNaN(aimPitchError)) {
            this.attackAimSamples.addLast(new AimSample(
                    now,
                    Math.abs(aimYawError),
                    Math.abs(aimPitchError),
                    targetDistance,
                    yawDeltaAtAttack,
                    sinceRotationMs
            ));
            while (this.attackAimSamples.size() > MAX_ROTATION_SAMPLE_HISTORY) {
                this.attackAimSamples.pollFirst();
            }
        }
        if (yawDeltaAtAttack >= INSTANT_ROTATION_SIGNAL_YAW && sinceRotationMs <= 120L) {
            addSignal(new CombatSignal(
                    CombatSignalType.INSTANT_ROTATION_ATTACK,
                    now,
                    yawDeltaAtAttack,
                    "dt=" + sinceRotationMs
            ));
        }

    }

    public void recordConfirmedHit(long now, int targetEntityId) {
        recordConfirmedHit(now, targetEntityId, -1.0D);
    }

    public void recordConfirmedHit(long now, int targetEntityId, double targetDistance) {
        this.lastConfirmedHitMs = now;
        this.lastActivityMs = now;
        addTimestamp(this.confirmedHitTimes, now);
        addSignal(new CombatSignal(CombatSignalType.ATTACK_CONFIRMED, now, 1.0D, "target=" + targetEntityId));
        registerTargetSwitchIfNeeded(now, targetEntityId);
        if (targetDistance >= 0.0D) {
            this.confirmedHitDistanceSamples.addLast(new DistanceSample(now, targetDistance));
            while (this.confirmedHitDistanceSamples.size() > MAX_ROTATION_SAMPLE_HISTORY) {
                this.confirmedHitDistanceSamples.pollFirst();
            }
        }
    }

    public void recordRotationPacket(long now) {
        this.lastRotationMs = now;
        this.lastActivityMs = now;
        addTimestamp(this.rotationTimes, now);
        addSignal(new CombatSignal(CombatSignalType.ROTATION_PACKET, now, 1.0D, ""));
    }

    public void recordRotationDelta(long now, double yawDelta, double pitchDelta) {
        this.lastActivityMs = now;

        double absYaw = Math.abs(yawDelta);
        double absPitch = Math.abs(pitchDelta);

        if (absYaw > 0.0D) {
            if (!Double.isNaN(lastYawDelta)) {
                double yawAcceleration = Math.abs(absYaw - lastYawDelta);
                this.yawAccelerationSamples.addLast(new RotationDeltaSample(now, yawAcceleration));
                while (this.yawAccelerationSamples.size() > MAX_ROTATION_SAMPLE_HISTORY) {
                    this.yawAccelerationSamples.pollFirst();
                }
                if (!Double.isNaN(lastYawAcceleration)) {
                    double yawJerk = Math.abs(yawAcceleration - lastYawAcceleration);
                    this.yawJerkSamples.addLast(new RotationDeltaSample(now, yawJerk));
                    while (this.yawJerkSamples.size() > MAX_ROTATION_SAMPLE_HISTORY) {
                        this.yawJerkSamples.pollFirst();
                    }
                }
                this.lastYawAcceleration = yawAcceleration;
            }
            this.yawDeltaSamples.addLast(new RotationDeltaSample(now, absYaw));
            while (this.yawDeltaSamples.size() > MAX_ROTATION_SAMPLE_HISTORY) {
                this.yawDeltaSamples.pollFirst();
            }
        }
        if (absPitch > 0.0D) {
            this.pitchDeltaSamples.addLast(new RotationDeltaSample(now, absPitch));
            while (this.pitchDeltaSamples.size() > MAX_ROTATION_SAMPLE_HISTORY) {
                this.pitchDeltaSamples.pollFirst();
            }
        }

        if (absYaw > 0.0D && !Double.isNaN(lastYawDelta) && Math.abs(absYaw - lastYawDelta) <= DUPLICATE_ROTATION_EPSILON) {
            addTimestamp(this.duplicateRotationTimes, now);
        } else if (absPitch > 0.0D && !Double.isNaN(lastPitchDelta) && Math.abs(absPitch - lastPitchDelta) <= DUPLICATE_ROTATION_EPSILON) {
            addTimestamp(this.duplicateRotationTimes, now);
        }

        if (absYaw > 0.0D) {
            this.lastYawDelta = absYaw;
        }
        if (absPitch > 0.0D) {
            this.lastPitchDelta = absPitch;
        }
    }

    public void recordTrackingAimSample(long now, double yawError, double pitchError) {
        if (Double.isNaN(yawError) || Double.isNaN(pitchError)) {
            return;
        }

        this.lastActivityMs = now;
        this.fovTrackingSamples.addLast(new FovTrackingSample(now, Math.abs(yawError), Math.abs(pitchError)));
        while (this.fovTrackingSamples.size() > MAX_FOV_TRACKING_HISTORY) {
            this.fovTrackingSamples.pollFirst();
        }
    }

    public void recordSwingPacket(long now) {
        this.lastAnimationMs = now;
        this.lastActivityMs = now;
        addTimestamp(this.swingTimes, now);
        // Some clients send swing right after interact; remove a recent provisional no-swing mark.
        if (!this.noSwingAttackTimes.isEmpty()) {
            Long recentNoSwing = this.noSwingAttackTimes.peekLast();
            if (recentNoSwing != null && isRecent(recentNoSwing, now, SWING_ATTACK_SYNC_MS)) {
                this.noSwingAttackTimes.pollLast();
            }
        }
        addSignal(new CombatSignal(CombatSignalType.SWING_PACKET, now, 1.0D, ""));
    }

    public void recordBlockOrUsePacket(long now) {
        this.lastBlockUseMs = now;
        this.lastActivityMs = now;
        addTimestamp(this.blockUseTimes, now);
        addSignal(new CombatSignal(CombatSignalType.BLOCK_OR_USE_PACKET, now, 1.0D, ""));
    }

    public void recordDiggingPacket(long now, String detail) {
        this.lastBlockUseMs = now;
        this.lastActivityMs = now;
        addTimestamp(this.blockUseTimes, now);
        addSignal(new CombatSignal(CombatSignalType.DIGGING_PACKET, now, 1.0D, detail));
    }

    public void recordInventoryPacket(long now) {
        this.lastInventoryActionMs = now;
        this.lastActivityMs = now;
        addTimestamp(this.inventoryActionTimes, now);
        addSignal(new CombatSignal(CombatSignalType.INVENTORY_PACKET, now, 1.0D, ""));
    }

    public void recordCheckDelta(long now, String checkId, String family, int delta) {
        if (delta <= 0) {
            return;
        }
        this.lastActivityMs = now;
        this.checkDeltaEvents.addLast(new CheckDeltaEvent(now, checkId, family, delta));
        while (this.checkDeltaEvents.size() > MAX_CHECK_DELTA_HISTORY) {
            this.checkDeltaEvents.pollFirst();
        }
        addSignal(new CombatSignal(CombatSignalType.CHECK_DELTA, now, delta, family + ":" + checkId));
    }

    private void registerTargetSwitchIfNeeded(long now, int targetEntityId) {
        if (targetEntityId == Integer.MIN_VALUE) {
            return;
        }
        if (lastTargetEntityId != Integer.MIN_VALUE && lastTargetEntityId != targetEntityId) {
            addTimestamp(targetSwitchTimes, now);
            addSignal(new CombatSignal(CombatSignalType.TARGET_SWITCH, now, 1.0D,
                    "from=" + lastTargetEntityId + ",to=" + targetEntityId));
            pendingTargetSwitchMs = now;
        }
        lastTargetEntityId = targetEntityId;
    }

    public void prune(long cutoffTimeMs) {
        pruneTimes(attackTimes, cutoffTimeMs);
        pruneTimes(confirmedHitTimes, cutoffTimeMs);
        pruneTimes(rotationTimes, cutoffTimeMs);
        pruneTimes(swingTimes, cutoffTimeMs);
        pruneTimes(targetSwitchTimes, cutoffTimeMs);
        pruneTimes(blockUseTimes, cutoffTimeMs);
        pruneTimes(inventoryActionTimes, cutoffTimeMs);
        pruneTimes(attackAfterBlockUseTimes, cutoffTimeMs);
        pruneTimes(attackAfterInventoryTimes, cutoffTimeMs);
        pruneTimes(tightRotationAttackTimes, cutoffTimeMs);
        pruneTimes(noSwingAttackTimes, cutoffTimeMs);
        pruneTimes(multiInteractTimes, cutoffTimeMs);
        pruneTimes(duplicateRotationTimes, cutoffTimeMs);

        while (!attackRotationSamples.isEmpty() && attackRotationSamples.peekFirst().timestamp < cutoffTimeMs) {
            attackRotationSamples.pollFirst();
        }
        while (!attackAimSamples.isEmpty() && attackAimSamples.peekFirst().timestamp < cutoffTimeMs) {
            attackAimSamples.pollFirst();
        }
        while (!fovTrackingSamples.isEmpty() && fovTrackingSamples.peekFirst().timestamp < cutoffTimeMs) {
            fovTrackingSamples.pollFirst();
        }
        while (!switchReactionSamples.isEmpty() && switchReactionSamples.peekFirst().timestamp < cutoffTimeMs) {
            switchReactionSamples.pollFirst();
        }
        while (!confirmedHitDistanceSamples.isEmpty() && confirmedHitDistanceSamples.peekFirst().timestamp < cutoffTimeMs) {
            confirmedHitDistanceSamples.pollFirst();
        }
        while (!checkDeltaEvents.isEmpty() && checkDeltaEvents.peekFirst().timestamp < cutoffTimeMs) {
            checkDeltaEvents.pollFirst();
        }
        while (!yawDeltaSamples.isEmpty() && yawDeltaSamples.peekFirst().timestamp < cutoffTimeMs) {
            yawDeltaSamples.pollFirst();
        }
        while (!pitchDeltaSamples.isEmpty() && pitchDeltaSamples.peekFirst().timestamp < cutoffTimeMs) {
            pitchDeltaSamples.pollFirst();
        }
        while (!yawAccelerationSamples.isEmpty() && yawAccelerationSamples.peekFirst().timestamp < cutoffTimeMs) {
            yawAccelerationSamples.pollFirst();
        }
        while (!yawJerkSamples.isEmpty() && yawJerkSamples.peekFirst().timestamp < cutoffTimeMs) {
            yawJerkSamples.pollFirst();
        }
        while (!signals.isEmpty() && signals.peekFirst().getTimestamp() < cutoffTimeMs) {
            signals.pollFirst();
        }
    }

    public boolean isCombatActive(long now, long combatWindowMs) {
        long latestCombat = Math.max(lastAttackMs, lastConfirmedHitMs);
        return latestCombat > 0L && (now - latestCombat) <= combatWindowMs;
    }

    public int countAttacks(long now, long windowMs) {
        return countTimesWithin(attackTimes, now - windowMs);
    }

    public int countConfirmedHits(long now, long windowMs) {
        return countTimesWithin(confirmedHitTimes, now - windowMs);
    }

    public int countRotations(long now, long windowMs) {
        return countTimesWithin(rotationTimes, now - windowMs);
    }

    public int countTargetSwitches(long now, long windowMs) {
        return countTimesWithin(targetSwitchTimes, now - windowMs);
    }

    public int countSwings(long now, long windowMs) {
        return countTimesWithin(swingTimes, now - windowMs);
    }

    public int countAttackAfterBlockUse(long now, long windowMs) {
        return countTimesWithin(attackAfterBlockUseTimes, now - windowMs);
    }

    public int countAttackAfterInventory(long now, long windowMs) {
        return countTimesWithin(attackAfterInventoryTimes, now - windowMs);
    }

    public int countTightRotationAttacks(long now, long windowMs) {
        return countTimesWithin(tightRotationAttackTimes, now - windowMs);
    }

    public int countNoSwingAttacks(long now, long windowMs) {
        return countTimesWithin(noSwingAttackTimes, now - windowMs);
    }

    public int countMultiInteracts(long now, long windowMs) {
        return countTimesWithin(multiInteractTimes, now - windowMs);
    }

    public int countDuplicateRotations(long now, long windowMs) {
        return countTimesWithin(duplicateRotationTimes, now - windowMs);
    }

    public int countInstantRotationAttacks(long now, long windowMs, double yawThreshold, long maxRotateToHitMs) {
        long cutoff = now - windowMs;
        int count = 0;
        for (AttackRotationSample sample : attackRotationSamples) {
            if (sample.timestamp < cutoff) {
                continue;
            }
            if (sample.yawDelta >= yawThreshold && sample.sinceRotationMs <= maxRotateToHitMs) {
                count++;
            }
        }
        return count;
    }

    public List<Long> getSwitchReactionDelays(long now, long windowMs, long maxDelayMs) {
        long cutoff = now - windowMs;
        List<Long> delays = new ArrayList<>();
        for (SwitchReactionSample sample : switchReactionSamples) {
            if (sample.timestamp < cutoff) {
                continue;
            }
            if (sample.delayMs <= maxDelayMs) {
                delays.add(sample.delayMs);
            }
        }
        return delays;
    }

    public int countFastSwitchReactions(long now, long windowMs, long fastThresholdMs, long maxDelayMs) {
        long cutoff = now - windowMs;
        int count = 0;
        for (SwitchReactionSample sample : switchReactionSamples) {
            if (sample.timestamp < cutoff) {
                continue;
            }
            if (sample.delayMs <= maxDelayMs && sample.delayMs <= fastThresholdMs) {
                count++;
            }
        }
        return count;
    }

    public int countDistanceSamples(long now, long windowMs) {
        long cutoff = now - windowMs;
        int count = 0;
        for (DistanceSample sample : confirmedHitDistanceSamples) {
            if (sample.timestamp >= cutoff) {
                count++;
            }
        }
        return count;
    }

    public double meanConfirmedHitDistance(long now, long windowMs) {
        long cutoff = now - windowMs;
        double sum = 0.0D;
        int count = 0;
        for (DistanceSample sample : confirmedHitDistanceSamples) {
            if (sample.timestamp < cutoff) {
                continue;
            }
            sum += sample.distance;
            count++;
        }
        if (count <= 0) {
            return -1.0D;
        }
        return sum / count;
    }

    public double ratioConfirmedHitsBeyond(long now, long windowMs, double thresholdDistance) {
        if (thresholdDistance <= 0.0D) {
            return 0.0D;
        }
        long cutoff = now - windowMs;
        int total = 0;
        int beyond = 0;
        for (DistanceSample sample : confirmedHitDistanceSamples) {
            if (sample.timestamp < cutoff) {
                continue;
            }
            total++;
            if (sample.distance >= thresholdDistance) {
                beyond++;
            }
        }
        if (total <= 0) {
            return 0.0D;
        }
        return beyond / (double) total;
    }

    public int countAimSamples(long now, long windowMs) {
        long cutoff = now - windowMs;
        int count = 0;
        for (AimSample sample : attackAimSamples) {
            if (sample.timestamp >= cutoff) {
                count++;
            }
        }
        return count;
    }

    public double meanAimYawError(long now, long windowMs) {
        long cutoff = now - windowMs;
        double sum = 0.0D;
        int count = 0;
        for (AimSample sample : attackAimSamples) {
            if (sample.timestamp < cutoff) {
                continue;
            }
            sum += sample.yawError;
            count++;
        }
        if (count <= 0) {
            return Double.NaN;
        }
        return sum / count;
    }

    public double meanAimPitchError(long now, long windowMs) {
        long cutoff = now - windowMs;
        double sum = 0.0D;
        int count = 0;
        for (AimSample sample : attackAimSamples) {
            if (sample.timestamp < cutoff) {
                continue;
            }
            sum += sample.pitchError;
            count++;
        }
        if (count <= 0) {
            return Double.NaN;
        }
        return sum / count;
    }

    public double ratioPreciseAimSamples(long now, long windowMs, double maxYawError, double maxPitchError) {
        long cutoff = now - windowMs;
        int total = 0;
        int precise = 0;
        for (AimSample sample : attackAimSamples) {
            if (sample.timestamp < cutoff) {
                continue;
            }
            total++;
            if (sample.yawError <= maxYawError && sample.pitchError <= maxPitchError) {
                precise++;
            }
        }
        if (total <= 0) {
            return 0.0D;
        }
        return precise / (double) total;
    }

    public double ratioSnapPreciseAimSamples(long now,
                                             long windowMs,
                                             double minYawSnapDelta,
                                             long maxRotateToAttackMs,
                                             double maxYawError,
                                             double maxPitchError) {
        long cutoff = now - windowMs;
        int total = 0;
        int snapPrecise = 0;
        for (AimSample sample : attackAimSamples) {
            if (sample.timestamp < cutoff) {
                continue;
            }
            total++;
            boolean snap = sample.attackYawDelta >= minYawSnapDelta
                    && sample.sinceRotationMs <= maxRotateToAttackMs;
            boolean precise = sample.yawError <= maxYawError
                    && sample.pitchError <= maxPitchError;
            if (snap && precise) {
                snapPrecise++;
            }
        }
        if (total <= 0) {
            return 0.0D;
        }
        return snapPrecise / (double) total;
    }

    public FovTrackingStats computeFovTrackingStats(long now,
                                                    long windowMs,
                                                    double yawFovDegrees,
                                                    double pitchFovDegrees,
                                                    long continuityGapMs,
                                                    long fastReacquireMs,
                                                    long maxReacquireMs) {
        long cutoff = now - windowMs;
        double maxYaw = Math.max(1.0D, yawFovDegrees);
        double maxPitch = Math.max(1.0D, pitchFovDegrees);
        long maxGap = Math.max(50L, continuityGapMs);
        long fastGate = Math.max(1L, fastReacquireMs);
        long maxGate = Math.max(fastGate, maxReacquireMs);

        int samples = 0;
        int misses = 0;
        int reacquires = 0;
        int fastReacquires = 0;
        long reacquireSum = 0L;

        boolean outOfFov = false;
        long missStartMs = 0L;
        long lastTimestamp = -1L;

        for (FovTrackingSample sample : fovTrackingSamples) {
            if (sample.timestamp < cutoff) {
                continue;
            }

            if (lastTimestamp > 0L && sample.timestamp - lastTimestamp > maxGap) {
                outOfFov = false;
                missStartMs = 0L;
            }
            lastTimestamp = sample.timestamp;

            samples++;
            boolean inFov = sample.yawError <= maxYaw && sample.pitchError <= maxPitch;
            if (!inFov) {
                misses++;
                if (!outOfFov) {
                    outOfFov = true;
                    missStartMs = sample.timestamp;
                }
                continue;
            }

            if (outOfFov && missStartMs > 0L) {
                long delay = sample.timestamp - missStartMs;
                if (delay <= maxGate) {
                    reacquires++;
                    reacquireSum += delay;
                    if (delay <= fastGate) {
                        fastReacquires++;
                    }
                }
            }
            outOfFov = false;
            missStartMs = 0L;
        }

        double missRatio = samples <= 0 ? 0.0D : misses / (double) samples;
        double meanReacquireMs = reacquires <= 0 ? Double.NaN : reacquireSum / (double) reacquires;
        double fastReacquireRatio = reacquires <= 0 ? 0.0D : fastReacquires / (double) reacquires;

        return new FovTrackingStats(samples, missRatio, reacquires, meanReacquireMs, fastReacquireRatio);
    }

    public List<Long> getAttackIntervals(long now, long windowMs) {
        long cutoff = now - windowMs;
        List<Long> intervals = new ArrayList<>();
        long previous = -1L;
        for (Long attackTime : attackTimes) {
            if (attackTime < cutoff) {
                continue;
            }
            if (previous > 0L) {
                long interval = attackTime - previous;
                if (interval > 0L) {
                    intervals.add(interval);
                }
            }
            previous = attackTime;
        }
        return intervals;
    }

    public List<Double> getYawDeltaSamples(long now, long windowMs, double minDelta) {
        return collectDeltaSamples(yawDeltaSamples, now - windowMs, minDelta);
    }

    public List<Double> getPitchDeltaSamples(long now, long windowMs, double minDelta) {
        return collectDeltaSamples(pitchDeltaSamples, now - windowMs, minDelta);
    }

    public List<Double> getYawAccelerationSamples(long now, long windowMs, double minAcceleration) {
        return collectDeltaSamples(yawAccelerationSamples, now - windowMs, minAcceleration);
    }

    public List<Double> getYawJerkSamples(long now, long windowMs, double minJerk) {
        return collectDeltaSamples(yawJerkSamples, now - windowMs, minJerk);
    }

    public int maxAttacksInSlice(long now, long windowMs, long sliceMs) {
        if (sliceMs <= 0L) {
            return 0;
        }
        long cutoff = now - windowMs;
        List<Long> samples = new ArrayList<>();
        for (Long attackTime : attackTimes) {
            if (attackTime >= cutoff) {
                samples.add(attackTime);
            }
        }
        if (samples.isEmpty()) {
            return 0;
        }

        int left = 0;
        int max = 1;
        for (int right = 0; right < samples.size(); right++) {
            long rightTime = samples.get(right);
            while (left < right && rightTime - samples.get(left) > sliceMs) {
                left++;
            }
            int count = right - left + 1;
            if (count > max) {
                max = count;
            }
        }
        return max;
    }

    public int countCheckDeltaEvents(long now, long windowMs) {
        long cutoff = now - windowMs;
        int count = 0;
        for (CheckDeltaEvent event : checkDeltaEvents) {
            if (event.timestamp >= cutoff) {
                count++;
            }
        }
        return count;
    }

    public int sumCheckDeltaValues(long now, long windowMs) {
        long cutoff = now - windowMs;
        int sum = 0;
        for (CheckDeltaEvent event : checkDeltaEvents) {
            if (event.timestamp >= cutoff) {
                sum += event.delta;
            }
        }
        return sum;
    }

    public int distinctDeltaFamilies(long now, long windowMs) {
        long cutoff = now - windowMs;
        Set<String> families = new HashSet<>();
        for (CheckDeltaEvent event : checkDeltaEvents) {
            if (event.timestamp >= cutoff && !event.family.isEmpty()) {
                families.add(event.family);
            }
        }
        return families.size();
    }

    public Map<String, Integer> familyDeltaTotals(long now, long windowMs) {
        long cutoff = now - windowMs;
        Map<String, Integer> totals = new HashMap<>();
        for (CheckDeltaEvent event : checkDeltaEvents) {
            if (event.timestamp < cutoff) {
                continue;
            }
            totals.merge(event.family, event.delta, Integer::sum);
        }
        return totals;
    }

    CombatEngineScorer.CombatSnapshot buildSnapshot(CombatEngineConfig config, long now) {
        CombatEngineScorer.CombatSnapshot snapshot = new CombatEngineScorer.CombatSnapshot();
        if (config == null) {
            return snapshot;
        }

        long shortCutoff = now - config.getShortWindowMs();
        long mediumCutoff = now - config.getMediumWindowMs();
        long burstSliceMs = config.getBurstSliceMs();

        RunningStats intervalStats = new RunningStats();
        Map<Long, Integer> intervalHistogram = new HashMap<>();
        ArrayDeque<Long> burstWindow = new ArrayDeque<>();
        long previousAttackInWindow = -1L;

        for (Long attackTime : attackTimes) {
            if (attackTime == null) {
                continue;
            }

            if (attackTime >= shortCutoff) {
                snapshot.attacksShort++;
                burstWindow.addLast(attackTime);
                while (!burstWindow.isEmpty() && attackTime - burstWindow.peekFirst() > burstSliceMs) {
                    burstWindow.pollFirst();
                }
                if (burstWindow.size() > snapshot.maxBurst) {
                    snapshot.maxBurst = burstWindow.size();
                }
            }

            if (attackTime < mediumCutoff) {
                continue;
            }

            snapshot.attacksMedium++;
            if (previousAttackInWindow > 0L) {
                long interval = attackTime - previousAttackInWindow;
                if (interval > 0L) {
                    snapshot.intervalSampleCount++;
                    intervalStats.add(interval);
                    long bucket = Math.max(0L, interval / Math.max(1L, config.getIntervalEntropyBucketMs()));
                    intervalHistogram.merge(bucket, 1, Integer::sum);
                }
            }
            previousAttackInWindow = attackTime;
        }

        if (snapshot.intervalSampleCount > 0) {
            snapshot.meanIntervalMs = intervalStats.mean();
            snapshot.intervalStdMs = intervalStats.stddev();
            snapshot.intervalCv = snapshot.meanIntervalMs <= 0.0D
                    ? 1.0D
                    : (snapshot.intervalStdMs / snapshot.meanIntervalMs);
            snapshot.intervalEntropy = shannonEntropy(intervalHistogram, snapshot.intervalSampleCount);
        }

        snapshot.apsShort = snapshot.attacksShort * (1000.0D / Math.max(1.0D, config.getShortWindowMs()));
        snapshot.burstAps = snapshot.maxBurst * (1000.0D / Math.max(1.0D, burstSliceMs));

        for (Long confirmedHitTime : confirmedHitTimes) {
            if (confirmedHitTime != null && confirmedHitTime >= mediumCutoff) {
                snapshot.confirmedHitsMedium++;
            }
        }
        for (Long rotationTime : rotationTimes) {
            if (rotationTime != null && rotationTime >= mediumCutoff) {
                snapshot.rotationsMedium++;
            }
        }
        for (Long switchTime : targetSwitchTimes) {
            if (switchTime != null && switchTime >= shortCutoff) {
                snapshot.switchesShort++;
            }
        }
        for (Long blockTime : attackAfterBlockUseTimes) {
            if (blockTime != null && blockTime >= mediumCutoff) {
                snapshot.blockOverlapMedium++;
            }
        }
        for (Long inventoryTime : attackAfterInventoryTimes) {
            if (inventoryTime != null && inventoryTime >= mediumCutoff) {
                snapshot.inventoryOverlapMedium++;
            }
        }
        for (Long tightRotationTime : tightRotationAttackTimes) {
            if (tightRotationTime != null && tightRotationTime >= mediumCutoff) {
                snapshot.tightRotationAttacksMedium++;
            }
        }
        for (Long noSwingTime : noSwingAttackTimes) {
            if (noSwingTime != null && noSwingTime >= mediumCutoff) {
                snapshot.noSwingAttacksMedium++;
            }
        }
        for (Long swingTime : swingTimes) {
            if (swingTime != null && swingTime >= mediumCutoff) {
                snapshot.swingsMedium++;
            }
        }
        for (Long multiInteractTime : multiInteractTimes) {
            if (multiInteractTime != null && multiInteractTime >= shortCutoff) {
                snapshot.multiInteractsShort++;
            }
        }
        for (Long duplicateRotationTime : duplicateRotationTimes) {
            if (duplicateRotationTime != null && duplicateRotationTime >= mediumCutoff) {
                snapshot.duplicateRotationsMedium++;
            }
        }
        for (AttackRotationSample sample : attackRotationSamples) {
            if (sample.timestamp >= mediumCutoff
                    && sample.yawDelta >= config.getInstantRotationYawThreshold()
                    && sample.sinceRotationMs <= config.getInstantRotationMaxDelayMs()) {
                snapshot.instantRotationAttacksMedium++;
            }
        }

        snapshot.hitRatio = snapshot.attacksMedium <= 0
                ? 0.0D
                : snapshot.confirmedHitsMedium / (double) snapshot.attacksMedium;
        snapshot.rotationCadenceRatio = snapshot.attacksMedium <= 0
                ? 0.0D
                : snapshot.tightRotationAttacksMedium / (double) snapshot.attacksMedium;

        RotationStatsAccumulator rotationAccumulator = new RotationStatsAccumulator();
        accumulateRotationSamples(
                yawDeltaSamples,
                mediumCutoff,
                config.getRotationMinDelta(),
                0,
                config.getRotationMicroCorrectionUpperDelta(),
                config.getRotationFastDeltaThreshold(),
                config.getRotationSnapHighDeltaThreshold(),
                config.getRotationSnapSettleLowDeltaThreshold(),
                rotationAccumulator,
                true
        );
        accumulateRotationSamples(
                pitchDeltaSamples,
                mediumCutoff,
                config.getRotationMinDelta(),
                100_000,
                config.getRotationMicroCorrectionUpperDelta(),
                config.getRotationFastDeltaThreshold(),
                config.getRotationSnapHighDeltaThreshold(),
                config.getRotationSnapSettleLowDeltaThreshold(),
                rotationAccumulator,
                false
        );
        snapshot.yawDeltasCount = rotationAccumulator.yawCount;
        snapshot.pitchDeltasCount = rotationAccumulator.pitchCount;
        snapshot.rotationDeltasMedium = rotationAccumulator.totalCount;
        if (rotationAccumulator.totalCount > 0) {
            snapshot.rotationMaxBucketShare = rotationAccumulator.maxBucketShare();
            snapshot.rotationUniqueRatio = rotationAccumulator.uniqueRatio();
            snapshot.microCorrectionShare = rotationAccumulator.microCorrections / (double) rotationAccumulator.totalCount;
            snapshot.fastRotationShare = rotationAccumulator.fastRotations / (double) rotationAccumulator.totalCount;
            if (rotationAccumulator.snapOpportunities > 0) {
                snapshot.snapSettleRatio = rotationAccumulator.snapTransitions / (double) rotationAccumulator.snapOpportunities;
            }
        }

        RunningStats yawAccelerationStats = new RunningStats();
        for (RotationDeltaSample sample : yawAccelerationSamples) {
            if (sample.timestamp < mediumCutoff) {
                continue;
            }
            snapshot.yawAccelerationCount++;
            yawAccelerationStats.add(sample.value);
        }
        if (snapshot.yawAccelerationCount > 0) {
            snapshot.meanYawAcceleration = yawAccelerationStats.mean();
            snapshot.stdYawAcceleration = yawAccelerationStats.stddev();
        }

        RunningStats yawJerkStats = new RunningStats();
        for (RotationDeltaSample sample : yawJerkSamples) {
            if (sample.timestamp < mediumCutoff) {
                continue;
            }
            snapshot.yawJerkCount++;
            yawJerkStats.add(sample.value);
        }
        if (snapshot.yawJerkCount > 0) {
            snapshot.meanYawJerk = yawJerkStats.mean();
            snapshot.stdYawJerk = yawJerkStats.stddev();
        }

        RunningStats switchReactionStats = new RunningStats();
        int fastSwitchReactions = 0;
        for (SwitchReactionSample sample : switchReactionSamples) {
            if (sample.timestamp < mediumCutoff || sample.delayMs > config.getSwitchReactionSampleMaxMs()) {
                continue;
            }
            snapshot.switchReactionSampleCount++;
            switchReactionStats.add(sample.delayMs);
            if (sample.delayMs <= config.getSwitchReactionFastThresholdMs()) {
                fastSwitchReactions++;
            }
        }
        if (snapshot.switchReactionSampleCount > 0) {
            snapshot.meanSwitchReactionMs = switchReactionStats.mean();
            snapshot.fastSwitchReactionRatio = fastSwitchReactions / (double) snapshot.switchReactionSampleCount;
        }

        RunningStats aimYawStats = new RunningStats();
        RunningStats aimPitchStats = new RunningStats();
        int preciseAim = 0;
        int snapPreciseAim = 0;
        for (AimSample sample : attackAimSamples) {
            if (sample.timestamp < mediumCutoff) {
                continue;
            }
            snapshot.aimSampleCount++;
            aimYawStats.add(sample.yawError);
            aimPitchStats.add(sample.pitchError);
            boolean precise = sample.yawError <= CombatEngineScorer.MODERN_AIM_LOCK_YAW_MAX
                    && sample.pitchError <= CombatEngineScorer.MODERN_AIM_LOCK_PITCH_MAX;
            if (precise) {
                preciseAim++;
            }
            boolean snap = sample.attackYawDelta >= CombatEngineScorer.MODERN_SNAP_AIM_YAW_MIN
                    && sample.sinceRotationMs <= CombatEngineScorer.MODERN_SNAP_AIM_MAX_DELAY_MS;
            if (precise && snap) {
                snapPreciseAim++;
            }
        }
        if (snapshot.aimSampleCount > 0) {
            snapshot.meanAimYawError = aimYawStats.mean();
            snapshot.meanAimPitchError = aimPitchStats.mean();
            snapshot.preciseAimShare = preciseAim / (double) snapshot.aimSampleCount;
            snapshot.snapPreciseAimShare = snapPreciseAim / (double) snapshot.aimSampleCount;
        }

        accumulateFovStats(snapshot, mediumCutoff);

        Map<String, Integer> familyTotals = new HashMap<>();
        for (CheckDeltaEvent event : checkDeltaEvents) {
            if (event.timestamp < mediumCutoff) {
                continue;
            }
            snapshot.deltaEventsMedium++;
            String family = event.family == null || event.family.isEmpty() ? "other" : event.family;
            familyTotals.merge(family, event.delta, Integer::sum);
        }
        snapshot.distinctFamiliesMedium = familyTotals.size();
        snapshot.familyTotalsMedium = familyTotals.isEmpty() ? Collections.emptyMap() : familyTotals;

        return snapshot;
    }

    private void accumulateFovStats(CombatEngineScorer.CombatSnapshot snapshot, long cutoffTimeMs) {
        double maxYaw = Math.max(1.0D, CombatEngineScorer.MODERN_TRACK_FOV_YAW_MAX);
        double maxPitch = Math.max(1.0D, CombatEngineScorer.MODERN_TRACK_FOV_PITCH_MAX);
        long maxGap = Math.max(50L, CombatEngineScorer.MODERN_TRACK_CONTINUITY_GAP_MS);
        long fastGate = Math.max(1L, CombatEngineScorer.MODERN_FOV_REACQUIRE_FAST_MS);
        long maxGate = Math.max(fastGate, CombatEngineScorer.MODERN_FOV_REACQUIRE_MAX_MS);

        int misses = 0;
        int fastReacquires = 0;
        long reacquireSum = 0L;
        boolean outOfFov = false;
        long missStartMs = 0L;
        long lastTimestamp = -1L;

        for (FovTrackingSample sample : fovTrackingSamples) {
            if (sample.timestamp < cutoffTimeMs) {
                continue;
            }

            if (lastTimestamp > 0L && sample.timestamp - lastTimestamp > maxGap) {
                outOfFov = false;
                missStartMs = 0L;
            }
            lastTimestamp = sample.timestamp;

            snapshot.fovTrackingSampleCount++;
            boolean inFov = sample.yawError <= maxYaw && sample.pitchError <= maxPitch;
            if (!inFov) {
                misses++;
                if (!outOfFov) {
                    outOfFov = true;
                    missStartMs = sample.timestamp;
                }
                continue;
            }

            if (outOfFov && missStartMs > 0L) {
                long delay = sample.timestamp - missStartMs;
                if (delay <= maxGate) {
                    snapshot.fovReacquireSampleCount++;
                    reacquireSum += delay;
                    if (delay <= fastGate) {
                        fastReacquires++;
                    }
                }
            }
            outOfFov = false;
            missStartMs = 0L;
        }

        if (snapshot.fovTrackingSampleCount > 0) {
            snapshot.fovMissRatio = misses / (double) snapshot.fovTrackingSampleCount;
        }
        if (snapshot.fovReacquireSampleCount > 0) {
            snapshot.meanFovReacquireMs = reacquireSum / (double) snapshot.fovReacquireSampleCount;
            snapshot.fastFovReacquireRatio = fastReacquires / (double) snapshot.fovReacquireSampleCount;
        }
    }

    private static void accumulateRotationSamples(ArrayDeque<RotationDeltaSample> samples,
                                                  long cutoffTimeMs,
                                                  double minDelta,
                                                  int bucketOffset,
                                                  double microCorrectionUpperDelta,
                                                  double fastRotationThreshold,
                                                  double snapHighThreshold,
                                                  double settleThreshold,
                                                  RotationStatsAccumulator accumulator,
                                                  boolean yaw) {
        double previousValue = Double.NaN;
        for (RotationDeltaSample sample : samples) {
            if (sample.timestamp < cutoffTimeMs || sample.value < minDelta) {
                continue;
            }

            accumulator.totalCount++;
            if (yaw) {
                accumulator.yawCount++;
            } else {
                accumulator.pitchCount++;
            }
            if (sample.value <= microCorrectionUpperDelta) {
                accumulator.microCorrections++;
            }
            if (sample.value >= fastRotationThreshold) {
                accumulator.fastRotations++;
            }

            int bucket = bucketOffset + (int) Math.round(sample.value * 100.0D);
            accumulator.histogram.merge(bucket, 1, Integer::sum);

            if (!Double.isNaN(previousValue)) {
                accumulator.snapOpportunities++;
                if (previousValue >= snapHighThreshold && sample.value <= settleThreshold) {
                    accumulator.snapTransitions++;
                }
            }
            previousValue = sample.value;
        }
    }

    public int getLastCheckViolationCount(String checkId) {
        if (checkId == null || checkId.isEmpty()) {
            return 0;
        }
        return lastCheckViolationCounts.getOrDefault(checkId, 0);
    }

    public void setLastCheckViolationCount(String checkId, int value) {
        if (checkId == null || checkId.isEmpty()) {
            return;
        }
        if (value < 0) {
            value = 0;
        }
        lastCheckViolationCounts.put(checkId, value);
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = Math.max(0.0D, score);
    }

    public double getLegacyPipelineScore() {
        return legacyPipelineScore;
    }

    public void setLegacyPipelineScore(double legacyPipelineScore) {
        this.legacyPipelineScore = Math.max(0.0D, legacyPipelineScore);
    }

    public double getModernPipelineScore() {
        return modernPipelineScore;
    }

    public void setModernPipelineScore(double modernPipelineScore) {
        this.modernPipelineScore = Math.max(0.0D, modernPipelineScore);
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public int getSuspiciousWindowStreak() {
        return suspiciousWindowStreak;
    }

    public void setSuspiciousWindowStreak(int suspiciousWindowStreak) {
        this.suspiciousWindowStreak = Math.max(0, suspiciousWindowStreak);
    }

    public double getTouchpadCompatibilityScore() {
        return touchpadCompatibilityScore;
    }

    public void setTouchpadCompatibilityScore(double touchpadCompatibilityScore) {
        this.touchpadCompatibilityScore = Math.max(0.0D, touchpadCompatibilityScore);
    }

    public double getErraticMouseCompatibilityScore() {
        return erraticMouseCompatibilityScore;
    }

    public void setErraticMouseCompatibilityScore(double erraticMouseCompatibilityScore) {
        this.erraticMouseCompatibilityScore = Math.max(0.0D, erraticMouseCompatibilityScore);
    }

    public double getCheatKinematicCompatibilityScore() {
        return cheatKinematicCompatibilityScore;
    }

    public void setCheatKinematicCompatibilityScore(double cheatKinematicCompatibilityScore) {
        this.cheatKinematicCompatibilityScore = Math.max(0.0D, cheatKinematicCompatibilityScore);
    }

    public double getAwayBackSpoofCompatibilityScore() {
        return awayBackSpoofCompatibilityScore;
    }

    public void setAwayBackSpoofCompatibilityScore(double awayBackSpoofCompatibilityScore) {
        this.awayBackSpoofCompatibilityScore = Math.max(0.0D, awayBackSpoofCompatibilityScore);
    }

    public long getLastActivityMs() {
        return lastActivityMs;
    }

    public long getLastAttackMs() {
        return lastAttackMs;
    }

    public long getLastConfirmedHitMs() {
        return lastConfirmedHitMs;
    }

    public int getLastTargetEntityId() {
        return lastTargetEntityId;
    }

    public long getLastEvaluationMs() {
        return lastEvaluationMs;
    }

    public void setLastEvaluationMs(long lastEvaluationMs) {
        this.lastEvaluationMs = lastEvaluationMs;
    }

    private void addSignal(CombatSignal signal) {
        signals.addLast(signal);
        while (signals.size() > MAX_SIGNAL_HISTORY) {
            signals.pollFirst();
        }
    }

    private static void pruneTimes(ArrayDeque<Long> times, long cutoffTimeMs) {
        while (!times.isEmpty() && times.peekFirst() < cutoffTimeMs) {
            times.pollFirst();
        }
    }

    private static void addTimestamp(ArrayDeque<Long> times, long timestamp) {
        if (times == null) {
            return;
        }
        times.addLast(timestamp);
        while (times.size() > MAX_RAW_TIME_HISTORY) {
            times.pollFirst();
        }
    }

    private static long resolveAttackTickBucketMs() {
        double tps = currentTps;
        if (Double.isNaN(tps) || tps <= 0.0D || tps >= ATTACK_TICK_BUCKET_ADAPT_TPS) {
            return ATTACK_TICK_BUCKET_MS;
        }

        double clampedTps = Math.max(ATTACK_TICK_BUCKET_TPS_FLOOR, Math.min(20.0D, tps));
        long scaledBucketMs = Math.round(1000.0D / clampedTps);
        if (scaledBucketMs < ATTACK_TICK_BUCKET_MS) {
            return ATTACK_TICK_BUCKET_MS;
        }
        return Math.min(ATTACK_TICK_BUCKET_MAX_MS, scaledBucketMs);
    }

    private static int countTimesWithin(ArrayDeque<Long> times, long cutoffTimeMs) {
        int count = 0;
        for (Long time : times) {
            if (time >= cutoffTimeMs) {
                count++;
            }
        }
        return count;
    }

    private static boolean isRecent(long then, long now, long windowMs) {
        return then > 0L && now - then <= windowMs;
    }

    private static List<Double> collectDeltaSamples(ArrayDeque<RotationDeltaSample> samples,
                                                    long cutoffTimeMs,
                                                    double minDelta) {
        List<Double> result = new ArrayList<>();
        for (RotationDeltaSample sample : samples) {
            if (sample.timestamp < cutoffTimeMs) {
                continue;
            }
            if (sample.value >= minDelta) {
                result.add(sample.value);
            }
        }
        return result;
    }

    private static final class RotationDeltaSample {
        private final long timestamp;
        private final double value;

        private RotationDeltaSample(long timestamp, double value) {
            this.timestamp = timestamp;
            this.value = value;
        }
    }

    private static final class AttackRotationSample {
        private final long timestamp;
        private final double yawDelta;
        private final long sinceRotationMs;

        private AttackRotationSample(long timestamp, double yawDelta, long sinceRotationMs) {
            this.timestamp = timestamp;
            this.yawDelta = yawDelta;
            this.sinceRotationMs = sinceRotationMs;
        }
    }

    private static final class AimSample {
        private final long timestamp;
        private final double yawError;
        private final double pitchError;
        private final double targetDistance;
        private final double attackYawDelta;
        private final long sinceRotationMs;

        private AimSample(long timestamp,
                          double yawError,
                          double pitchError,
                          double targetDistance,
                          double attackYawDelta,
                          long sinceRotationMs) {
            this.timestamp = timestamp;
            this.yawError = yawError;
            this.pitchError = pitchError;
            this.targetDistance = targetDistance;
            this.attackYawDelta = attackYawDelta;
            this.sinceRotationMs = sinceRotationMs;
        }
    }

    static final class FovTrackingStats {
        final int sampleCount;
        final double missRatio;
        final int reacquireCount;
        final double meanReacquireMs;
        final double fastReacquireRatio;

        private FovTrackingStats(int sampleCount,
                                 double missRatio,
                                 int reacquireCount,
                                 double meanReacquireMs,
                                 double fastReacquireRatio) {
            this.sampleCount = sampleCount;
            this.missRatio = missRatio;
            this.reacquireCount = reacquireCount;
            this.meanReacquireMs = meanReacquireMs;
            this.fastReacquireRatio = fastReacquireRatio;
        }
    }

    private static final class FovTrackingSample {
        private final long timestamp;
        private final double yawError;
        private final double pitchError;

        private FovTrackingSample(long timestamp, double yawError, double pitchError) {
            this.timestamp = timestamp;
            this.yawError = yawError;
            this.pitchError = pitchError;
        }
    }

    private static final class SwitchReactionSample {
        private final long timestamp;
        private final long delayMs;

        private SwitchReactionSample(long timestamp, long delayMs) {
            this.timestamp = timestamp;
            this.delayMs = delayMs;
        }
    }

    private static final class DistanceSample {
        private final long timestamp;
        private final double distance;

        private DistanceSample(long timestamp, double distance) {
            this.timestamp = timestamp;
            this.distance = distance;
        }
    }

    private static final class CheckDeltaEvent {
        private final long timestamp;
        private final String checkId;
        private final String family;
        private final int delta;

        private CheckDeltaEvent(long timestamp, String checkId, String family, int delta) {
            this.timestamp = timestamp;
            this.checkId = checkId == null ? "" : checkId;
            this.family = family == null ? "" : family;
            this.delta = delta;
        }
    }

    private static final class RunningStats {
        private int count;
        private double mean;
        private double m2;

        void add(double value) {
            count++;
            double delta = value - mean;
            mean += delta / count;
            double delta2 = value - mean;
            m2 += delta * delta2;
        }

        double mean() {
            return count <= 0 ? 0.0D : mean;
        }

        double stddev() {
            return count <= 0 ? 0.0D : Math.sqrt(m2 / count);
        }
    }

    private static final class RotationStatsAccumulator {
        private final Map<Integer, Integer> histogram = new HashMap<>();
        private int totalCount;
        private int yawCount;
        private int pitchCount;
        private int microCorrections;
        private int fastRotations;
        private int snapTransitions;
        private int snapOpportunities;

        double maxBucketShare() {
            if (totalCount <= 0 || histogram.isEmpty()) {
                return 0.0D;
            }
            int maxBucketCount = 0;
            for (int count : histogram.values()) {
                if (count > maxBucketCount) {
                    maxBucketCount = count;
                }
            }
            return maxBucketCount / (double) totalCount;
        }

        double uniqueRatio() {
            if (totalCount <= 0) {
                return 1.0D;
            }
            return histogram.size() / (double) totalCount;
        }
    }

    private static double shannonEntropy(Map<Long, Integer> histogram, int totalSamples) {
        if (histogram.isEmpty() || totalSamples <= 0) {
            return 0.0D;
        }

        double total = totalSamples;
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
}
