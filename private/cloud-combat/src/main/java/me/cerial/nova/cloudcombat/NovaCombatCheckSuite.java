package me.cerial.nova.cloudcombat;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class NovaCombatCheckSuite {
    private static final long ATTACK_AIM_WINDOW_MS = 320L;
    private static final long CLICK_IDLE_RESET_MS = 1250L;
    private static final long LOW_CPS_MODEL_RESET_MS = 1750L;
    private static final double LEGACY_AUTOCLICKER_MIN_CPS = 5.8D;
    private static final double MODERN_AUTOCLICKER_MIN_CPS = 7.2D;

    List<CombatCheckFinding> accept(UUID playerId, State state, JsonObject event, long now) {
        decayBuffers(state, now);
        state.lastSeenMs = now;
        state.pingMs = intValue(event, "ping", state.pingMs);
        state.tps = doubleValue(event, "tps", state.tps);
        state.protocolVersion = intValue(event, "protocolVersion", state.protocolVersion);
        state.currentTick = now / 50L;

        String eventName = string(event, "event");
        List<CombatCheckFinding> findings = new ArrayList<>(4);
        finalizeWtap(playerId, state, now, false, findings);

        switch (eventName) {
            case "rotation_packet" -> handleRotationPacket(playerId, state, now, findings);
            case "rotation_delta" -> handleRotationDelta(playerId, state, event, now, findings);
            case "tracking_aim" -> handleTrackingAim(playerId, state, event, now, findings);
            case "attack" -> handleAttack(playerId, state, event, now, findings);
            case "hit" -> handleHit(playerId, state, event, now, findings);
            case "swing", "animation" -> handleSwing(playerId, state, now, findings);
            case "block_use" -> handleBlockUse(playerId, state, event, now, findings);
            case "item_consume" -> handleItemConsume(state, event, now);
            case "digging" -> handleDigging(playerId, state, event, now, findings);
            case "block_break" -> state.lastBlockBreakMs = now;
            case "drop_item" -> state.lastDropItemMs = now;
            case "projectile_throw" -> state.lastProjectileThrowMs = now;
            case "interact_entity" -> state.lastInteractEntityMs = now;
            case "inventory" -> handleInventory(playerId, state, event, now, findings);
            case "entity_action" -> handleEntityAction(playerId, state, event, now, findings);
            case "held_item" -> handleHeldItem(playerId, state, event, now, findings);
            case "client_status" -> handleClientStatus(playerId, state, event, now, findings);
            case "totem_pop" -> state.totemPop = new TotemPop(now, state.currentTick);
            case "container_open" -> handleContainerOpen(playerId, state, event, now, findings);
            case "bow_start" -> handleBowStart(state, now);
            case "bow_shoot" -> handleBowShoot(state);
            case "bow_target_sample" -> handleBowTargetSample(playerId, state, event, now, findings);
            case "check_delta" -> handleCheckDelta(playerId, state, event, now, findings);
            default -> {
            }
        }
        handleBacktrackPing(playerId, state, now, findings);
        return findings;
    }

    private void handleRotationPacket(UUID playerId, State state, long now, List<CombatCheckFinding> findings) {
        if (state.queuedAttackForKillAuraA) {
            long delay = now - state.queuedAttackMs;
            state.queuedAttackForKillAuraA = false;
            if (delay > 40L && delay < 100L) {
                add(findings, raise(playerId, state, now, "KillAura-A",
                        "attack timing (delay=" + delay + "ms, range=40-100ms)",
                        1.0D, 2.0D, 10, 0.78D));
            } else {
                decay(state, "KillAura-A", 0.35D);
            }
        }
        state.lastRotationPacketMs = now;
        state.attacksSinceFlying = 0;
    }

    private void handleRotationDelta(UUID playerId, State state, JsonObject event, long now, List<CombatCheckFinding> findings) {
        double yawDelta = Math.abs(doubleValue(event, "yawDelta", 0.0D));
        double pitchDelta = Math.abs(doubleValue(event, "pitchDelta", 0.0D));
        double yaw = doubleValue(event, "yaw", state.lastYaw);
        double pitch = doubleValue(event, "pitch", state.lastPitch);

        if (yawDelta <= 0.0D && pitchDelta <= 0.0D) {
            return;
        }

        RotationSample sample = new RotationSample(now, yaw, pitch, yawDelta, pitchDelta);
        state.rotations.addLast(sample);
        push(state.yawDeltas, yawDelta, 120);
        push(state.pitchDeltas, pitchDelta, 120);
        pruneRotations(state, now);

        if (!recent(now, state.lastAttackMs, 3000L) && !recent(now, state.lastHitMs, 3000L)) {
            state.lastYaw = yaw;
            state.lastPitch = pitch;
            return;
        }

        handleSimpleAimChecks(playerId, state, sample, now, findings);
        handleProfileAimChecks(playerId, state, sample, now, findings);
        handleStatisticalAimChecks(playerId, state, now, findings);

        state.lastYawDelta = yawDelta;
        state.lastPitchDelta = pitchDelta;
        state.lastYaw = yaw;
        state.lastPitch = pitch;
    }

    private void handleTrackingAim(UUID playerId, State state, JsonObject event, long now, List<CombatCheckFinding> findings) {
        double yawError = Math.abs(doubleValue(event, "aimYawError", Double.NaN));
        double pitchError = Math.abs(doubleValue(event, "aimPitchError", Double.NaN));
        if (!Double.isFinite(yawError) || !Double.isFinite(pitchError)) {
            return;
        }
        double distance = doubleValue(event, "distance", -1.0D);
        double targetSpeed = doubleValue(event, "targetSpeed", 0.0D);
        double combined = Math.hypot(yawError, pitchError);
        if (state.pingMs <= 200 && distance >= 2.2D && distance <= 6.0D && targetSpeed >= 0.08D) {
            push(state.aimErrors, combined, 24);
        }
        if (state.pingMs <= 200 && distance >= 2.5D && distance <= 6.0D && targetSpeed >= 0.12D) {
            push(state.trackingYawErrors, yawError, 24);
            push(state.trackingPitchErrors, pitchError, 24);
        }
        handleAimErrorWindows(playerId, state, now, findings);
    }

    private void handleAttack(UUID playerId, State state, JsonObject event, long now, List<CombatCheckFinding> findings) {
        int target = intValue(event, "target", Integer.MIN_VALUE);
        boolean crystal = boolValue(event, "crystal", false);
        String targetType = string(event, "targetType");
        double yawError = Math.abs(doubleValue(event, "aimYawError", Double.NaN));
        double pitchError = Math.abs(doubleValue(event, "aimPitchError", Double.NaN));
        double distance = doubleValue(event, "distance", -1.0D);
        double targetSpeed = doubleValue(event, "targetSpeed", 0.0D);

        if (state.lastAttackMs > 0L) {
            long interval = now - state.lastAttackMs;
            if (interval >= 5L && interval <= 5000L) {
                push(state.attackIntervals, (double) interval, 50);
                pushLong(state.attackIntervalWindow, interval, 20);
            }
        }
        state.attackTimes.addLast(now);
        while (!state.attackTimes.isEmpty() && now - state.attackTimes.peekFirst() > 1200L) {
            state.attackTimes.removeFirst();
        }
        beginBacktrackCombat(state, now);

        handleKillAuraAttackOrder(playerId, state, target, now, findings);
        handleKillAuraIntervals(playerId, state, now, findings);
        handleAutoBlockAttack(playerId, state, now, findings);
        beginWtap(state, now);

        if (crystal) {
            handleCrystalAttack(playerId, state, target, targetType, now, findings);
        } else if (distance > 0.0D) {
            handleReachLikeTelemetry(playerId, state, distance, yawError, now, findings);
        }

        if (Double.isFinite(yawError) && Double.isFinite(pitchError)) {
            if (state.pingMs <= 200 && distance >= 2.2D && distance <= 6.0D && targetSpeed >= 0.08D) {
                push(state.aimErrors, Math.hypot(yawError, pitchError), 24);
            }
            if (state.pingMs <= 200 && distance >= 2.5D && distance <= 6.0D && targetSpeed >= 0.12D) {
                push(state.trackingYawErrors, yawError, 24);
                push(state.trackingPitchErrors, pitchError, 24);
            }
            handleKillAuraView(playerId, state, yawError, distance, now, findings);
            handleAimErrorWindows(playerId, state, now, findings);
        }

        state.lastTargetId = target;
        state.lastAttackMs = now;
    }

    private void handleHit(UUID playerId, State state, JsonObject event, long now, List<CombatCheckFinding> findings) {
        state.lastHitMs = now;
        double distance = doubleValue(event, "distance", -1.0D);
        if (distance > 0.0D) {
            handleReachLikeTelemetry(playerId, state, distance, Double.NaN, now, findings);
        }
        evaluateTotem(playerId, state, now, findings);
    }

    private void handleSwing(UUID playerId, State state, long now, List<CombatCheckFinding> findings) {
        if (state.lastSwingMs > 0L) {
            long delay = now - state.lastSwingMs;
            if (delay > CLICK_IDLE_RESET_MS) {
                resetClickModel(state);
            } else if (delay > 0L) {
                state.lastClickDelay = delay;
                if (!autoClickerExempt(state, now)) {
                    pushLong(state.clickSamples, delay, 60);
                    evaluateAutoClicker(playerId, state, now, findings);
                }
            }
        }
        state.lastSwingMs = now;
    }

    private void handleBlockUse(UUID playerId, State state, JsonObject event, long now, List<CombatCheckFinding> findings) {
        boolean sword = boolValue(event, "sword", false);
        boolean shield = boolValue(event, "shield", false);
        boolean blocking = boolValue(event, "blocking", false);
        int protocolVersion = intValue(event, "protocolVersion", state.protocolVersion);
        state.lastBlockUseMs = now;
        state.lastBlockUseSword = sword;
        state.lastBlockUseShield = shield;
        state.lastBlockUseBlocking = blocking;
        state.lastBlockUseItemType = string(event, "itemType");
        state.lastBlockUseProtocolVersion = protocolVersion;
        handleCrystalEngineBlockUse(state, now, state.lastBlockUseItemType);
        if (sword) {
            if (protocolVersion > 47 && blocking && !shield) {
                add(findings, raise(playerId, state, now, "AutoBlock-F",
                        "impossible sword block (protocol=" + protocolVersion + ", shield=false, blocking=true)",
                        1.0D, 1.0D, 10, 0.92D));
            }
            state.blockToggleTimes.addLast(now);
            while (!state.blockToggleTimes.isEmpty() && now - state.blockToggleTimes.peekFirst() > 1000L) {
                state.blockToggleTimes.removeFirst();
            }
            if (recent(now, state.lastAttackMs, 2000L) && state.blockToggleTimes.size() >= 4) {
                add(findings, raise(playerId, state, now, "AutoBlock-B",
                        "autoblock toggles (toggles=" + state.blockToggleTimes.size() + " >= 4, window=1000ms)",
                        1.0D, 2.0D, 10, 0.75D));
                state.blockToggleTimes.clear();
            }
        }
    }

    private void handleItemConsume(State state, JsonObject event, long now) {
        state.lastConsumeMs = now;
        state.lastConsumeItemType = string(event, "itemType");
    }

    private void handleDigging(UUID playerId, State state, JsonObject event, long now, List<CombatCheckFinding> findings) {
        String action = string(event, "action");
        state.lastDiggingMs = now;
        state.lastDiggingAction = action;
        switch (action) {
            case "START_DIGGING" -> state.lastDigStartMs = now;
            case "CANCELLED_DIGGING" -> state.lastDigCancelMs = now;
            case "FINISHED_DIGGING" -> state.lastDigFinishMs = now;
            case "DROP_ITEM", "DROP_ITEM_STACK" -> state.lastDropItemMs = now;
            default -> {
            }
        }
        if ("SWAP_ITEM_WITH_OFFHAND".equals(action)) {
            recordTotemAction(state, now, "swapOffhand", false, false, true, false, 0, 0);
            evaluateTotem(playerId, state, now, findings);
        }
    }

    private void handleInventory(UUID playerId, State state, JsonObject event, long now, List<CombatCheckFinding> findings) {
        String clickType = string(event, "clickType");
        int slot = intValue(event, "slot", -999);
        int button = intValue(event, "button", -1);
        state.lastInventoryMs = now;

        boolean swapClickToOffhand = "SWAP".equals(clickType) && button == 40;
        boolean pickupIntoOffhand = ("PICKUP".equals(clickType) || "PICKUP_ALL".equals(clickType)) && slot == 45;
        boolean hotbarSwapToOffhand = "SWAP".equals(clickType) && slot == 45 && button >= 0 && button <= 8;
        recordTotemAction(state, now, "clickWindow", false, swapClickToOffhand, false, hotbarSwapToOffhand, 0, 1);
        if (pickupIntoOffhand) {
            state.totemPickupIntoOffhand = true;
        }
        evaluateTotem(playerId, state, now, findings);
    }

    private void handleEntityAction(UUID playerId, State state, JsonObject event, long now, List<CombatCheckFinding> findings) {
        String action = string(event, "action");
        if (!"START_SPRINTING".equals(action) && !"STOP_SPRINTING".equals(action)) {
            return;
        }
        SprintAction sample = new SprintAction(now, action);
        state.recentSprintActions.addLast(sample);
        while (!state.recentSprintActions.isEmpty() && now - state.recentSprintActions.peekFirst().timeMs > 400L) {
            state.recentSprintActions.removeFirst();
        }
        if (state.wtapTracking && state.wtapAttackMs > 0L) {
            long dt = now - state.wtapAttackMs;
            if (dt >= -160L && dt <= 160L) {
                state.wtapWindowActions.add(sample);
            }
        }
        finalizeWtap(playerId, state, now, false, findings);
    }

    private void handleHeldItem(UUID playerId, State state, JsonObject event, long now, List<CombatCheckFinding> findings) {
        int slot = intValue(event, "slot", -1);
        state.lastHeldSlot = slot;
        state.lastHeldChangeMs = now;
        recordTotemAction(state, now, "heldChange", false, false, false, false, 1, 0);
        evaluateTotem(playerId, state, now, findings);
    }

    private void handleClientStatus(UUID playerId, State state, JsonObject event, long now, List<CombatCheckFinding> findings) {
        String action = string(event, "action");
        if ("OPEN_INVENTORY_ACHIEVEMENT".equals(action)) {
            recordTotemAction(state, now, "openInventory", true, false, false, false, 0, 0);
            evaluateTotem(playerId, state, now, findings);
        }
    }

    private void handleContainerOpen(UUID playerId, State state, JsonObject event, long now, List<CombatCheckFinding> findings) {
        double distance = doubleValue(event, "distance", -1.0D);
        if (distance > 5.0D) {
            add(findings, raise(playerId, state, now, "ContainerReach-A",
                    format("illegal packets (inv=%s, reach=%.2f > %.2f)", string(event, "inventoryType"), distance, 5.0D),
                    1.0D, 1.0D, 10, 0.95D));
        }
    }

    private void handleBowStart(State state, long now) {
        state.bowDrawStartMs = now;
        state.bowLastEvaluateMs = 0L;
        state.bowAimSamples.clear();
    }

    private void handleBowShoot(State state) {
        state.bowDrawStartMs = 0L;
        state.bowLastEvaluateMs = 0L;
        state.bowAimSamples.clear();
    }

    private void handleBowTargetSample(UUID playerId,
                                       State state,
                                       JsonObject event,
                                       long now,
                                       List<CombatCheckFinding> findings) {
        long drawMs = intValue(event, "drawMs", 0);
        if (state.bowDrawStartMs <= 0L) {
            state.bowDrawStartMs = now - drawMs;
        }
        if (drawMs <= 0L || now - state.bowDrawStartMs > 7000L || state.pingMs > 220) {
            return;
        }

        double force = bowForce(drawMs);
        if (force < 0.10D) {
            return;
        }

        BowAimSample sample = solveBowSample(event, force, now);
        if (sample == null) {
            return;
        }
        state.bowAimSamples.addLast(sample);
        while (!state.bowAimSamples.isEmpty() && now - state.bowAimSamples.peekFirst().timeMs > 4500L) {
            state.bowAimSamples.removeFirst();
        }
        while (state.bowAimSamples.size() > 80) {
            state.bowAimSamples.removeFirst();
        }

        if (now - state.bowLastEvaluateMs >= 220L) {
            evaluateBowAim(playerId, state, now, findings);
            state.bowLastEvaluateMs = now;
        }
    }

    private BowAimSample solveBowSample(JsonObject event, double force, long now) {
        int target = intValue(event, "target", Integer.MIN_VALUE);
        double yaw = doubleValue(event, "yaw", Double.NaN);
        double pitch = doubleValue(event, "pitch", Double.NaN);
        double relX = doubleValue(event, "relativeX", Double.NaN);
        double relY = doubleValue(event, "relativeY", Double.NaN);
        double relZ = doubleValue(event, "relativeZ", Double.NaN);
        double velX = doubleValue(event, "targetVelocityX", 0.0D);
        double velZ = doubleValue(event, "targetVelocityZ", 0.0D);
        double targetWidth = doubleValue(event, "targetWidth", 0.6D);
        double targetHeight = doubleValue(event, "targetHeight", 1.8D);
        if (!Double.isFinite(yaw)
                || !Double.isFinite(pitch)
                || !Double.isFinite(relX)
                || !Double.isFinite(relY)
                || !Double.isFinite(relZ)) {
            return null;
        }

        double speed = Math.max(0.2D, force * 3.0D);
        double v2 = speed * speed;
        double predictedX = relX;
        double predictedY = relY;
        double predictedZ = relZ;
        double theta = Double.NaN;
        double flightTicks = -1.0D;
        for (int i = 0; i < 3; i++) {
            double distXZ = Math.hypot(predictedX, predictedZ);
            if (distXZ <= 0.001D) {
                return null;
            }
            double discriminant = (v2 * v2) - 0.05D * ((0.05D * distXZ * distXZ) + (2.0D * predictedY * v2));
            if (discriminant <= 0.0D) {
                return null;
            }
            theta = Math.atan((v2 - Math.sqrt(discriminant)) / (0.05D * distXZ));
            double cos = Math.cos(theta);
            if (Math.abs(cos) < 0.0001D) {
                return null;
            }
            flightTicks = distXZ / (speed * cos);
            if (flightTicks <= 0.0D || flightTicks > 95.0D) {
                return null;
            }
            predictedX = relX + (velX * flightTicks);
            predictedZ = relZ + (velZ * flightTicks);
        }
        if (!Double.isFinite(theta) || flightTicks <= 0.0D) {
            return null;
        }
        double distXZ = Math.hypot(predictedX, predictedZ);
        double distance = Math.sqrt((predictedX * predictedX) + (predictedY * predictedY) + (predictedZ * predictedZ));
        if (distance < 8.0D || distance > 72.0D || distXZ <= 0.001D) {
            return null;
        }
        double expectedYaw = Math.toDegrees(Math.atan2(-predictedX, predictedZ));
        double expectedPitch = -Math.toDegrees(theta);
        double yawError = angleDiffDegrees(yaw, expectedYaw);
        double pitchError = Math.abs(pitch - expectedPitch);
        double yawTolerance = horizontalAngularTolerance(targetWidth, distXZ);
        double pitchTolerance = verticalAngularTolerance(targetHeight, distXZ);
        double normalizedError = normalizedError(yawError, pitchError, yawTolerance, pitchTolerance);
        double candidateAngle = Math.hypot(yawError, pitchError);
        if (candidateAngle > 28.0D) {
            return null;
        }
        return new BowAimSample(
                now,
                target,
                distance,
                Math.hypot(velX, velZ),
                expectedYaw,
                expectedPitch,
                yaw,
                pitch,
                normalizedError,
                normalizedError <= 1.0D
        );
    }

    private void evaluateBowAim(UUID playerId,
                                State state,
                                long now,
                                List<CombatCheckFinding> findings) {
        if (state.bowAimSamples.size() < 4) {
            return;
        }
        int target = dominantBowTarget(state.bowAimSamples);
        if (target == Integer.MIN_VALUE) {
            return;
        }
        ArrayDeque<BowAimSample> tracked = new ArrayDeque<>();
        for (BowAimSample sample : state.bowAimSamples) {
            if (sample.targetId == target) {
                tracked.addLast(sample);
            }
        }
        if (tracked.size() < 4) {
            return;
        }

        int total = 0;
        int hits = 0;
        int followTransitions = 0;
        int followGood = 0;
        double distanceSum = 0.0D;
        double normErrorSum = 0.0D;
        double speedSum = 0.0D;
        double followDeltaErrorSum = 0.0D;
        double expectedMotionSum = 0.0D;

        BowAimSample previous = null;
        for (BowAimSample sample : tracked) {
            total++;
            distanceSum += sample.distance;
            normErrorSum += sample.normalizedError;
            speedSum += sample.targetSpeed;
            if (sample.wouldHit) {
                hits++;
            }
            if (previous != null) {
                double expectedYawDelta = angleDiffDegrees(sample.expectedYaw, previous.expectedYaw);
                double expectedPitchDelta = Math.abs(sample.expectedPitch - previous.expectedPitch);
                double expectedMag = Math.hypot(expectedYawDelta, expectedPitchDelta);
                if (expectedMag >= 0.18D) {
                    followTransitions++;
                    expectedMotionSum += expectedMag;
                    double playerYawDelta = angleDiffDegrees(sample.playerYaw, previous.playerYaw);
                    double playerPitchDelta = Math.abs(sample.playerPitch - previous.playerPitch);
                    double followDeltaError = Math.hypot(
                            playerYawDelta - expectedYawDelta,
                            playerPitchDelta - expectedPitchDelta
                    );
                    followDeltaErrorSum += followDeltaError;
                    if (followDeltaError <= 0.50D) {
                        followGood++;
                    }
                }
            }
            previous = sample;
        }

        double hitRatio = hits / (double) total;
        double avgDistance = distanceSum / total;
        double avgTargetSpeed = speedSum / total;
        double meanNormalizedError = normErrorSum / total;
        double stdNormalizedError = bowStddev(tracked, meanNormalizedError);
        double followRatio = followTransitions <= 0 ? 1.0D : followGood / (double) followTransitions;
        double meanFollowDeltaError = followTransitions <= 0 ? 0.0D : followDeltaErrorSum / followTransitions;
        double expectedMotionAvg = followTransitions <= 0 ? 0.0D : expectedMotionSum / followTransitions;
        boolean movingScenario = avgTargetSpeed >= 0.03D || expectedMotionAvg >= 0.40D;
        boolean followPass = !movingScenario
                || (followTransitions >= 2 && followRatio >= 0.72D && meanFollowDeltaError <= 0.50D);

        boolean suspicious = avgDistance >= 10.0D
                && hitRatio >= 0.82D
                && meanNormalizedError <= 0.90D
                && stdNormalizedError <= 0.36D
                && followPass;
        if (!suspicious) {
            state.bowAimStreak = Math.max(0, state.bowAimStreak - 1);
            decay(state, "BowAim-A", 0.35D);
            return;
        }
        state.bowAimStreak++;
        if (state.bowAimStreak >= 2) {
            add(findings, raise(playerId, state, now, "BowAim-A",
                    format("bow packet-track (hit=%.2f err=%.2f/%.2f follow=%.2f dErr=%.2f move=%s dist=%.1f n=%d)",
                            hitRatio, meanNormalizedError, stdNormalizedError,
                            followRatio, meanFollowDeltaError, movingScenario, avgDistance, total),
                    1.0D, 2.0D, 10, 0.82D));
        }
    }

    private void handleCheckDelta(UUID playerId, State state, JsonObject event, long now, List<CombatCheckFinding> findings) {
        int delta = intValue(event, "delta", 0);
        if (delta <= 0) {
            return;
        }
        String family = string(event, "family");
        String check = string(event, "check");
        String normalizedFamily = family.toLowerCase(Locale.ROOT);
        String checkKey = normalizedFamily + ":" + check.toLowerCase(Locale.ROOT);
        int recentSignals = state.recordPluginCombatSignal(checkKey, now);
        if (("reach".equals(normalizedFamily) || "hitbox".equals(normalizedFamily) || "backtrack".equals(normalizedFamily))
                && delta < 2
                && recentSignals < 2) {
            return;
        }
        String id = switch (family.toLowerCase(Locale.ROOT)) {
            case "reach" -> "Reach-A";
            case "hitbox" -> "HitBox-A";
            case "backtrack" -> "Backtrack-A";
            case "autoclicker" -> "AutoClicker-telemetry";
            default -> "CombatTelemetry";
        };
        add(findings, raise(playerId, state, now, id,
                "plugin combat signal (check=" + check + ", family=" + family + ", delta=" + delta + ")",
                Math.min(4.0D, delta), Math.max(3.0D, Math.min(8.0D, delta)), 10, 0.70D));
    }

    private void handleSimpleAimChecks(UUID playerId,
                                       State state,
                                       RotationSample sample,
                                       long now,
                                       List<CombatCheckFinding> findings) {
        double yaw = sample.yawDelta;
        double pitch = sample.pitchDelta;
        double absPitch = Math.abs(sample.pitch);

        if (recent(now, state.lastHitMs, 150L) || recent(now, state.lastAttackMs, 150L)) {
            double effectiveStep = effectiveRotationStep(state, 0.0D);
            boolean reliableClientStep = reliableRotationStep(state, effectiveStep);
            boolean fitsClientStep = reliableClientStep
                    && fitsStep(yaw, effectiveStep)
                    && (pitch <= 1.0E-5D || fitsStep(pitch, effectiveStep));
            if (reliableClientStep
                    && yaw > 0.0D
                    && !fitsClientStep
                    && (nearModulo(yaw, 0.25D) || nearModulo(yaw, 0.1D))) {
                add(findings, raise(playerId, state, now, "Aim-B",
                        format("modulo aim (deltaYaw=%.4f)", yaw), 1.0D, 3.0D, 8, 0.62D));
            }
            if (reliableClientStep
                    && state.lastYawDelta > 1.25D
                    && yaw > 1.25D
                    && almostEqual(state.lastYawDelta, yaw, 1.0E-5D)
                    && !fitsStep(yaw, effectiveStep)) {
                add(findings, raise(playerId, state, now, "Aim-C",
                        format("repeated yaw (delta=%.4f)", yaw), 1.0D, 2.0D, 8, 0.68D));
            }
            if (reliableClientStep
                    && pitch <= 1.0E-5D
                    && yaw > 1.825D
                    && absPitch < 60.0D
                    && !fitsStep(yaw, effectiveStep)) {
                add(findings, raise(playerId, state, now, "Aim-F",
                        format("straight line aim (dyaw=%.4f, dpitch=%.4f)", yaw, pitch), 1.0D, 2.0D, 8, 0.72D));
            }
        }

        if (recent(now, state.lastAttackMs, 2000L)
                && yaw >= 15.0D
                && pitch <= 0.1D
                && absPitch <= 65.0D) {
            state.largeYawSmallPitchStreak = recent(now, state.lastLargeYawSmallPitchMs, 2500L)
                    ? state.largeYawSmallPitchStreak + 1
                    : 1;
            state.lastLargeYawSmallPitchMs = now;
            if (yaw >= 55.0D || (yaw >= 25.0D && state.largeYawSmallPitchStreak >= 3)) {
                add(findings, raise(playerId, state, now, "Aim-H",
                        format("large yaw small pitch (dyaw=%.2f, dpitch=%.4f, streak=%d)",
                                yaw, pitch, state.largeYawSmallPitchStreak),
                        1.0D, 2.0D, 8, 0.72D));
            }
        }

        if (recent(now, state.lastAttackMs, 3000L) && state.lastYawDelta > 0.0D) {
            double yawAccel = Math.abs(yaw - state.lastYawDelta);
            double pitchAccel = Math.abs(pitch - state.lastPitchDelta);
            if (yawAccel >= 20.0D && pitchAccel <= 0.05D && absPitch <= 60.0D) {
                add(findings, raise(playerId, state, now, "Aim-I",
                        format("yaw acceleration (yawAccel=%.2f, pitchAccel=%.4f)", yawAccel, pitchAccel),
                        1.0D, 2.0D, 8, 0.72D));
            }
        }

        handleSnapOvershoot(playerId, state, sample, now, findings);
    }

    private void handleProfileAimChecks(UUID playerId,
                                        State state,
                                        RotationSample sample,
                                        long now,
                                        List<CombatCheckFinding> findings) {
        double yaw = sample.yawDelta;
        double pitch = sample.pitchDelta;
        if (yaw <= 0.001D && pitch <= 0.001D) {
            return;
        }

        double step = gcdLike(yaw, state.lastYawDelta);
        if (step >= 0.0028D && step <= 0.65D) {
            push(state.gcdSteps, step, 96);
        }
        double stableStep = mode(state.gcdSteps, 100_000.0D);
        if (stableStep > 0.0D) {
            state.learnedStep = stableStep;
        }

        boolean recentAttack = recent(now, state.lastAttackMs, ATTACK_AIM_WINDOW_MS);
        recordAimEProfile(state, yaw, state.lastYawDelta, step, recentAttack);

        if (!recentAttack || state.learnedStep <= 0.0D) {
            return;
        }

        double analysisStep = effectiveRotationStep(state, step);

        handleAimJ(playerId, state, yaw, pitch, analysisStep, now, findings);
        handleAimE(playerId, state, now, findings);

        double residualYaw = residualNorm(yaw, analysisStep);
        double residualPitch = residualNorm(pitch, analysisStep);
        if (yaw >= 0.70D && pitch >= 0.25D && residualYaw > 0.32D && residualPitch > 0.32D) {
            add(findings, raise(playerId, state, now, "Aim-L",
                    format("step residual (dyaw=%.3f, dpitch=%.3f, ry=%.3f, rp=%.3f, step=%.5f)",
                            yaw, pitch, residualYaw, residualPitch, analysisStep),
                    1.0D, 2.0D, 10, 0.78D));
        }

        if (yaw >= 0.60D && pitch >= 0.20D) {
            double tolerance = Math.max(analysisStep * 0.20D, 0.0035D);
            boolean badYaw = residual(yaw, analysisStep) > tolerance;
            boolean badPitch = residual(pitch, analysisStep) > tolerance;
            if (badYaw && badPitch) {
                add(findings, raise(playerId, state, now, "Aim-K",
                        format("gcd mismatch (dyaw=%.3f, dpitch=%.3f, step=%.5f)", yaw, pitch, analysisStep),
                        1.0D, 2.0D, 10, 0.75D));
            }
        }

        double yawResidual = residual(yaw, analysisStep);
        double pitchResidual = residual(pitch, analysisStep);
        boolean exactYaw = yawResidual <= 1.0E-5D;
        boolean exactPitch = pitchResidual <= 1.0E-5D;
        if ((exactYaw ^ exactPitch)
                && Math.max(residualYaw, residualPitch) > 0.32D
                && yaw + pitch > 1.25D) {
            add(findings, raise(playerId, state, now, "Aim-M",
                    format("gcd bypass (dyaw=%.3f, dpitch=%.3f, step=%.5f)", yaw, pitch, analysisStep),
                    1.0D, 2.0D, 8, 0.70D));
        }

        double floorDivisorX = floorDistance(yaw / analysisStep);
        double floorDivisorY = floorDistance(pitch / analysisStep);
        if (floorDivisorY > 0.03D && floorDivisorX < 1.0E-4D) {
            add(findings, raise(playerId, state, now, "Aim-N",
                    format("divisor y (x=%.5f, y=%.5f)", floorDivisorX, floorDivisorY),
                    1.0D, 2.0D, 8, 0.70D));
        }

        double sensitivity = estimateSensitivity(analysisStep);
        if (sensitivity < -10.0D && yaw + pitch > 1.25D) {
            add(findings, raise(playerId, state, now, "Aim-O",
                    format("negative sensitivity (sens=%.1f, dyaw=%.2f, dpitch=%.2f)", sensitivity, yaw, pitch),
                    1.0D, 1.0D, 10, 0.85D));
        } else if (sensitivity > 205.0D && yaw + pitch > 1.25D) {
            add(findings, raise(playerId, state, now, "Aim-P",
                    format("high sensitivity (sens=%.1f, dyaw=%.2f, dpitch=%.2f)", sensitivity, yaw, pitch),
                    1.0D, 1.0D, 10, 0.85D));
        }
    }

    private void recordAimEProfile(State state,
                                   double yaw,
                                   double lastYaw,
                                   double step,
                                   boolean inCombatWindow) {
        if (step >= 0.0096D
                && step <= 0.65D
                && yaw >= 0.2D
                && yaw <= 18.0D
                && lastYaw >= 0.2D
                && lastYaw <= 18.0D) {
            int bucket = (int) Math.round(step * 10_000.0D);
            pushInt(state.aimEBaselineBuckets, bucket, 80);
            if (inCombatWindow) {
                pushInt(state.aimECombatBuckets, bucket, 28);
            }
        }
        if (inCombatWindow) {
            push(state.aimECombatDeltas, yaw, 28);
        } else {
            state.aimECombatBuckets.clear();
            state.aimECombatDeltas.clear();
            if (state.aimEStreak > 0) {
                state.aimEStreak--;
            }
        }
    }

    private void handleAimE(UUID playerId,
                            State state,
                            long now,
                            List<CombatCheckFinding> findings) {
        BucketProfile baseline = bucketProfile(state.aimEBaselineBuckets, 35, 10_000.0D);
        BucketProfile combat = bucketProfile(state.aimECombatBuckets, 16, 10_000.0D);
        if (baseline.step <= 0.0D
                || combat.step <= 0.0D
                || baseline.confidence < 0.45D
                || combat.confidence < 0.60D
                || state.aimECombatDeltas.size() < 16) {
            return;
        }
        double meanDeltaYaw = mean(state.aimECombatDeltas);
        if (meanDeltaYaw < 0.8D || meanDeltaYaw > 12.0D) {
            state.aimEStreak = Math.max(0, state.aimEStreak - 1);
            return;
        }
        double driftRatio = Math.abs(combat.step - baseline.step) / Math.max(baseline.step, 1.0E-7D);
        if (driftRatio >= 0.55D) {
            state.aimEStreak++;
            if (state.aimEStreak >= 2) {
                add(findings, raise(playerId, state, now, "Aim-E",
                        format("profile drift (ratio=%.3f, bStep=%.5f, cStep=%.5f, bConf=%.2f, cConf=%.2f, meanDYaw=%.3f)",
                                driftRatio, baseline.step, combat.step, baseline.confidence, combat.confidence, meanDeltaYaw),
                        1.0D, 2.0D, 10, 0.78D));
            }
        } else {
            state.aimEStreak = Math.max(0, state.aimEStreak - 1);
            decay(state, "Aim-E", 0.35D);
        }
    }

    private void handleAimJ(UUID playerId,
                            State state,
                            double yaw,
                            double pitch,
                            double analysisStep,
                            long now,
                            List<CombatCheckFinding> findings) {
        if (!recent(now, state.lastAttackMs, 150L)
                || yaw <= 0.1D
                || pitch <= 0.1D
                || yaw >= 20.0D
                || pitch >= 20.0D
                || state.lastYawDelta <= 0.1D
                || state.lastPitchDelta <= 0.1D) {
            return;
        }
        if (fitsStep(yaw, analysisStep) && fitsStep(pitch, analysisStep)) {
            return;
        }
        double constantYaw = gcdLike(yaw, state.lastYawDelta);
        double constantPitch = gcdLike(pitch, state.lastPitchDelta);
        if (constantYaw <= 0.0D || constantPitch <= 0.0D) {
            return;
        }
        double currentX = yaw / constantYaw;
        double currentY = pitch / constantPitch;
        double previousX = state.lastYawDelta / constantYaw;
        double previousY = state.lastPitchDelta / constantPitch;
        if (Math.abs(previousX) <= 1.0E-9D || Math.abs(previousY) <= 1.0E-9D) {
            return;
        }
        double moduloX = currentX % previousX;
        double moduloY = currentY % previousY;
        double floorModuloX = Math.abs(Math.floor(moduloX) - moduloX);
        double floorModuloY = Math.abs(Math.floor(moduloY) - moduloY);
        boolean invalidX = moduloX > 60.0D && floorModuloX > 0.1D;
        boolean invalidY = moduloY > 60.0D && floorModuloY > 0.1D;
        if (invalidX && invalidY) {
            add(findings, raise(playerId, state, now, "Aim-J",
                    format("invalid gcd (dyaw=%.2f, dpitch=%.2f, mod=%.2f/%.2f)",
                            yaw, pitch, moduloX, moduloY),
                    1.0D, 2.0D, 10, 0.78D));
        }
    }

    private void handleStatisticalAimChecks(UUID playerId, State state, long now, List<CombatCheckFinding> findings) {
        if (!recent(now, state.lastAttackMs, 320L) && !recent(now, state.lastHitMs, 320L)) {
            return;
        }
        List<Double> yaw = tail(state.yawDeltas, 20);
        List<Double> pitch = tail(state.pitchDeltas, 20);
        if (yaw.size() < 20 || pitch.size() < 20) {
            return;
        }

        double yawVar = variance(yaw);
        double pitchVar = variance(pitch);
        double entropy = (entropy(yaw) + entropy(pitch)) * 0.5D;
        double kurtosis = kurtosis(yaw);
        if (yawVar < 0.5D && pitchVar < 0.3D && entropy < 2.0D && kurtosis > 3.0D) {
            add(findings, raise(playerId, state, now, "Aim-Q",
                    format("aim consistency (yawVar=%.3f, pitchVar=%.3f, entropy=%.2f, kurt=%.2f)",
                            yawVar, pitchVar, entropy, kurtosis),
                    1.0D, 2.0D, 10, 0.78D));
        }

        List<Double> tenYaw = tail(state.yawDeltas, 10);
        if (tenYaw.size() >= 10 && mean(tenYaw) >= 2.0D) {
            double jerk = jerk(tenYaw);
            double smoothing = smoothingCoefficient(tenYaw);
            double second = secondDerivative(tenYaw);
            double r2 = linearRSquared(tenYaw);
            if (jerk < 0.5D && smoothing > 0.85D && second < 0.2D && r2 > 0.9D) {
                add(findings, raise(playerId, state, now, "Aim-S",
                        format("aim smoothing (jerk=%.3f, smooth=%.2f, second=%.3f, r2=%.2f)",
                                jerk, smoothing, second, r2),
                        1.0D, 2.0D, 10, 0.78D));
            }
        }

        List<Double> fifteenYaw = tail(state.yawDeltas, 15);
        if (fifteenYaw.size() >= 15) {
            double periodicity = periodicity(fifteenYaw);
            double autocorr = autocorrelation(fifteenYaw, 2);
            double amplitude = Math.sqrt(variance(fifteenYaw));
            if (periodicity > 0.70D && autocorr > 0.60D && amplitude >= 0.10D && amplitude <= 0.50D) {
                add(findings, raise(playerId, state, now, "Aim-T",
                        format("noise injection (period=%.2f, amp=%.3f, auto=%.2f)", periodicity, amplitude, autocorr),
                        1.0D, 2.0D, 10, 0.76D));
            }
        }

        List<Double> fourteenYaw = tail(state.yawDeltas, 14);
        List<Double> fourteenPitch = tail(state.pitchDeltas, 14);
        if (fourteenYaw.size() >= 10 && fourteenPitch.size() >= 10) {
            double meanYaw = mean(fourteenYaw);
            double stdYaw = stddev(fourteenYaw, meanYaw);
            double meanPitch = mean(fourteenPitch);
            double stdPitch = stddev(fourteenPitch, meanPitch);
            double cvYaw = meanYaw > 0.0D ? stdYaw / meanYaw : 1.0D;
            if (meanYaw > 1.75D && cvYaw < 0.045D && stdPitch < 0.020D && meanPitch > 0.040D) {
                add(findings, raise(playerId, state, now, "Aim-U",
                        format("low-jitter lock (meanYaw=%.2f, cvYaw=%.4f, stdPitch=%.4f)",
                                meanYaw, cvYaw, stdPitch),
                        1.0D, 2.0D, 10, 0.80D));
            }
        }

        List<Double> sixteenYaw = tail(state.yawDeltas, 16);
        if (sixteenYaw.size() >= 10 && mean(sixteenYaw) >= 0.90D) {
            DominantBucket dominant = dominantBucket(sixteenYaw, 10000.0D);
            double range = max(sixteenYaw) - min(sixteenYaw);
            if (dominant.ratio >= 0.80D && range <= 0.150D) {
                add(findings, raise(playerId, state, now, "Aim-V",
                        format("quantized yaw (ratio=%.2f, range=%.4f, mean=%.2f)",
                                dominant.ratio, range, mean(sixteenYaw)),
                        1.0D, 2.0D, 10, 0.78D));
            }
        }

        List<Double> ratioYaw = tail(state.yawDeltas, 12);
        List<Double> ratioPitch = tail(state.pitchDeltas, 12);
        if (ratioYaw.size() >= 8 && ratioPitch.size() >= 8) {
            ArrayDeque<Double> ratios = new ArrayDeque<>();
            for (int i = 0; i < ratioYaw.size(); i++) {
                double y = ratioYaw.get(i);
                double p = ratioPitch.get(i);
                if (y >= 0.35D && p >= 0.06D) {
                    ratios.addLast(p / y);
                }
            }
            if (ratios.size() >= 8) {
                double meanYaw = mean(ratioYaw);
                double meanRatio = mean(ratios);
                double stdRatio = stddev(ratios, meanRatio);
                if (meanYaw >= 1.10D && stdRatio <= 0.015D && meanRatio >= 0.12D && meanRatio <= 0.90D) {
                    add(findings, raise(playerId, state, now, "Aim-X",
                            format("pitch/yaw ratio (meanYaw=%.2f, ratio=%.3f, std=%.4f)",
                                    meanYaw, meanRatio, stdRatio),
                            1.0D, 2.0D, 10, 0.78D));
                }
            }
        }
    }

    private void handleSnapOvershoot(UUID playerId,
                                     State state,
                                     RotationSample sample,
                                     long now,
                                     List<CombatCheckFinding> findings) {
        double yaw = sample.yawDelta;
        double pitch = sample.pitchDelta;
        if (state.snapWindowStartMs == 0L || now - state.snapWindowStartMs > 2200L) {
            state.snapWindowStartMs = now;
            state.snapLockCount = 0;
        }

        if (yaw >= 20.0D) {
            state.snapTicksRemaining = 3;
            state.snapLockSteps = 0;
            return;
        }
        if (state.snapTicksRemaining > 0) {
            state.snapTicksRemaining--;
            if (yaw <= 0.09D) {
                state.snapLockSteps++;
                if (state.snapLockSteps >= 2) {
                    state.snapLockCount++;
                    state.snapTicksRemaining = 0;
                    if (state.snapLockCount >= 3) {
                        add(findings, raise(playerId, state, now, "Aim-W",
                                format("snap-lock chain (count=%d, lock=%.4f, window=%dms)",
                                        state.snapLockCount, yaw, now - state.snapWindowStartMs),
                                1.0D, 1.0D, 10, 0.88D));
                        state.snapLockCount = 0;
                    }
                }
            }
        }

        int sign = yaw == 0.0D ? 0 : (sample.yaw - state.lastYaw >= 0.0D ? 1 : -1);
        if (state.pendingSnapSign == 0 && yaw >= 7.5D && pitch <= 2.5D) {
            state.pendingSnapSign = sign;
            state.pendingSnapAbs = yaw;
            state.pendingSnapMs = now;
        } else if (state.pendingSnapSign != 0) {
            boolean correction = sign == -state.pendingSnapSign
                    && now - state.pendingSnapMs <= 120L
                    && yaw >= state.pendingSnapAbs * 0.20D
                    && yaw <= state.pendingSnapAbs * 0.70D
                    && pitch <= 1.0D;
            if (correction) {
                state.snapCorrectionLoops++;
                state.pendingSnapSign = 0;
                if (state.snapCorrectionLoops >= 3) {
                    add(findings, raise(playerId, state, now, "Aim-D",
                            "snap-correction loop (loops=" + state.snapCorrectionLoops + "/3)",
                            1.0D, 1.0D, 10, 0.86D));
                    state.snapCorrectionLoops = 0;
                }
            } else if (now - state.pendingSnapMs > 120L || yaw >= 7.5D) {
                state.pendingSnapSign = 0;
            }
        }
    }

    private void handleAimErrorWindows(UUID playerId, State state, long now, List<CombatCheckFinding> findings) {
        if (state.aimErrors.size() >= 10 && recent(now, state.lastAttackMs, 320L)) {
            double meanError = mean(state.aimErrors);
            double stdError = stddev(state.aimErrors, meanError);
            double maxError = max(state.aimErrors);
            int low = 0;
            for (double value : state.aimErrors) {
                if (value <= 0.75D) {
                    low++;
                }
            }
            double meanYaw = mean(tail(state.yawDeltas, Math.min(14, state.yawDeltas.size())));
            if (meanError <= 0.72D && stdError <= 0.20D && maxError <= 1.35D && meanYaw >= 0.35D && low >= 8) {
                add(findings, raise(playerId, state, now, "Aim-A",
                        format("low aim error (mean=%.3f, std=%.3f, max=%.3f, low=%d)", meanError, stdError, maxError, low),
                        1.0D, 2.0D, 10, 0.86D));
            }
        }

        if (state.trackingYawErrors.size() >= 10 && state.trackingPitchErrors.size() >= 10) {
            double yawMean = mean(state.trackingYawErrors);
            double pitchMean = mean(state.trackingPitchErrors);
            double yawStd = stddev(state.trackingYawErrors, yawMean);
            double pitchStd = stddev(state.trackingPitchErrors, pitchMean);
            if (yawMean <= 1.0D && pitchMean <= 1.0D && yawStd <= 0.35D && pitchStd <= 0.35D) {
                add(findings, raise(playerId, state, now, "Aim-G",
                        format("predictive lead (yawMean=%.3f, pitchMean=%.3f, yawStd=%.3f, pitchStd=%.3f)",
                                yawMean, pitchMean, yawStd, pitchStd),
                        1.0D, 2.0D, 10, 0.78D));
            }
            if (yawMean <= 0.30D && pitchMean <= 0.30D && yawStd <= 0.12D && pitchStd <= 0.12D) {
                add(findings, raise(playerId, state, now, "Aim-R",
                        format("velocity-aware aim (yaw=%.3f, pitch=%.3f)", yawMean, pitchMean),
                        1.0D, 2.0D, 10, 0.78D));
            }
        }
    }

    private void handleKillAuraAttackOrder(UUID playerId,
                                           State state,
                                           int target,
                                           long now,
                                           List<CombatCheckFinding> findings) {
        long moveDelay = now - state.lastRotationPacketMs;
        if (moveDelay >= 0L && moveDelay < 10L) {
            state.queuedAttackForKillAuraA = true;
            state.queuedAttackMs = now;
        }

        if (target != Integer.MIN_VALUE && state.lastTargetId != Integer.MIN_VALUE) {
            boolean switched = target != state.lastTargetId && now - state.lastAttackMs <= 150L;
            if (switched) {
                state.switchTimes.addLast(now);
                while (!state.switchTimes.isEmpty() && now - state.switchTimes.peekFirst() > 1200L) {
                    state.switchTimes.removeFirst();
                }
                if (state.switchTimes.size() >= 5 && state.attackTimes.size() >= 9) {
                    add(findings, raise(playerId, state, now, "KillAura-G",
                            "target switch burst (switches=" + state.switchTimes.size()
                                    + ", attacks=" + state.attackTimes.size() + " in 1200ms)",
                            1.0D, 1.0D, 12, 0.90D));
                    state.switchTimes.clear();
                    state.attackTimes.clear();
                }
                if (state.switchTimes.size() >= 4 && state.attackTimes.size() >= 7 && state.attacksSinceFlying > 0) {
                    add(findings, raise(playerId, state, now, "KillAura-B",
                            "multi aura (switches=" + state.switchTimes.size()
                                    + ", attacks=" + state.attackTimes.size()
                                    + ", entity=" + target + ", last=" + state.lastTargetId + ")",
                            1.0D, 1.0D, 10, 0.92D));
                }
            }
        }
        state.attacksSinceFlying++;
    }

    private void handleKillAuraIntervals(UUID playerId, State state, long now, List<CombatCheckFinding> findings) {
        if (state.attackIntervals.size() >= 12) {
            List<Double> twelve = tail(state.attackIntervals, 12);
            double mean = mean(twelve);
            double std = stddev(twelve, mean);
            double range = max(twelve) - min(twelve);
            double tickAligned = tickAlignedShare(twelve);
            if (mean >= 35.0D
                    && mean <= 145.0D
                    && std <= 7.5D
                    && range <= 22.0D
                    && tickAligned >= 0.78D) {
                add(findings, raise(playerId, state, now, "KillAura-C",
                        format("attack pattern (mean=%.1fms, std=%.2fms, range=%.1fms, tick=%.2f)",
                                mean, std, range, tickAligned),
                        1.0D, 3.0D, 10, 0.76D));
            }
        }
        if (state.attackIntervals.size() >= 14) {
            List<Double> twenty = tail(state.attackIntervals, 20);
            double avg = mean(twenty);
            double std = stddev(twenty, avg);
            double range = max(twenty) - min(twenty);
            double tickAligned = tickAlignedShare(twenty);
            if (avg >= 35.0D
                    && avg <= 130.0D
                    && std <= 5.0D
                    && range <= 16.0D
                    && tickAligned >= 0.82D) {
                add(findings, raise(playerId, state, now, "KillAura-D",
                        format("regular attack timing (mean=%.2fms, std=%.2fms, range=%.2fms, tick=%.2f, n=%d)",
                                avg, std, range, tickAligned, twenty.size()),
                        1.0D, 3.0D, 10, 0.80D));
            }
        }
    }

    private void handleKillAuraView(UUID playerId,
                                    State state,
                                    double yawError,
                                    double distance,
                                    long now,
                                    List<CombatCheckFinding> findings) {
        double maxAngle = 100.0D + Math.min(40.0D, state.pingMs / 5.0D);
        if (distance >= 2.20D && yawError > maxAngle) {
            if (state.firstOffAngleMs == 0L || now - state.firstOffAngleMs > 2200L) {
                state.firstOffAngleMs = now;
                state.offAngleHits = 1;
            } else {
                state.offAngleHits++;
            }
            if (state.offAngleHits >= 3) {
                add(findings, raise(playerId, state, now, "KillAura-F",
                        format("outside view hits (yaw=%.2f > %.2f, distance=%.2f, hits=%d)",
                                yawError, maxAngle, distance, state.offAngleHits),
                        1.0D, 1.0D, 10, 0.90D));
                state.offAngleHits = 0;
            }
        }
        RotationSample last = state.rotations.peekLast();
        if (last != null && now - last.timeMs <= 120L && last.yawDelta >= 38.0D && last.pitchDelta <= 1.2D) {
            if (state.firstPreHitSnapMs == 0L || now - state.firstPreHitSnapMs > 2500L) {
                state.firstPreHitSnapMs = now;
                state.preHitSnaps = 1;
            } else {
                state.preHitSnaps++;
            }
            if (state.preHitSnaps >= 4) {
                add(findings, raise(playerId, state, now, "KillAura-E",
                        format("pre-hit snaps (dyaw=%.2f, dpitch=%.2f, snaps=%d)",
                                last.yawDelta, last.pitchDelta, state.preHitSnaps),
                        1.0D, 1.0D, 10, 0.88D));
                state.preHitSnaps = 0;
            }
        }
    }

    private void handleAutoBlockAttack(UUID playerId, State state, long now, List<CombatCheckFinding> findings) {
        if (state.lastConsumeMs > 0L) {
            long consumeDt = now - state.lastConsumeMs;
            if (consumeDt >= 0L && consumeDt < 150L) {
                add(findings, raise(playerId, state, now, "AutoBlock-C",
                        "consume+hit (item=" + state.lastConsumeItemType + ", dt=" + consumeDt + "ms < 150ms)",
                        1.0D, 1.0D, 10, 0.88D));
                state.lastConsumeMs = 0L;
            }
        }
        if (!state.lastBlockUseSword || state.lastBlockUseMs <= 0L) {
            return;
        }
        long dt = now - state.lastBlockUseMs;
        if (dt >= 0L && dt <= 18L) {
            add(findings, raise(playerId, state, now, "AutoBlock-E",
                    "block-hit timing (dt=" + dt + "ms <= 18ms, burst)",
                    1.0D, 2.0D, 10, 0.82D));
        } else if (dt >= 0L && dt <= 60L) {
            add(findings, raise(playerId, state, now, "AutoBlock-D",
                    "illegal combat (autoblock, blocking=" + state.lastBlockUseBlocking + ", dt=" + dt + "ms <= 60ms)",
                    1.0D, 2.0D, 10, 0.80D));
        }
        if (state.lastBlockUseBlocking) {
            state.blockingAttackSamples.addLast(true);
        } else {
            state.blockingAttackSamples.addLast(false);
        }
        while (state.blockingAttackSamples.size() > 5) {
            state.blockingAttackSamples.removeFirst();
        }
        if (state.blockingAttackSamples.size() >= 5) {
            int blocking = 0;
            for (boolean sample : state.blockingAttackSamples) {
                if (sample) {
                    blocking++;
                }
            }
            if (blocking / 5.0D >= 0.60D) {
                add(findings, raise(playerId, state, now, "AutoBlock-A",
                        "autoblock (blocking=" + blocking + "/5, ratio>=0.60)",
                        1.0D, 2.0D, 10, 0.82D));
            }
        }
    }

    private void handleReachLikeTelemetry(UUID playerId,
                                          State state,
                                          double distance,
                                          double yawError,
                                          long now,
                                          List<CombatCheckFinding> findings) {
        if (distance > 6.0D) {
            state.reachEnvelopeStreak = recent(now, state.lastReachEnvelopeMs, 1500L)
                    ? state.reachEnvelopeStreak + 1
                    : 1;
            state.lastReachEnvelopeMs = now;
            boolean extremeReach = distance > 7.0D;
            boolean repeatedReach = distance > 6.35D && state.reachEnvelopeStreak >= 2;
            if (extremeReach || repeatedReach) {
                add(findings, raise(playerId, state, now, "Reach-B",
                        format("reach envelope (distance=%.2f, streak=%d)",
                                distance, state.reachEnvelopeStreak),
                        1.0D, 1.0D, 10, 0.90D));
            }
        }
        if (Double.isFinite(yawError) && yawError > 105.0D && distance > 2.2D) {
            add(findings, raise(playerId, state, now, "HitBox-A",
                    format("ray miss telemetry (yawError=%.2f, distance=%.2f)", yawError, distance),
                    1.0D, 2.0D, 10, 0.75D));
        }
    }

    private void handleCrystalAttack(UUID playerId,
                                     State state,
                                     int target,
                                     String targetType,
                                     long now,
                                     List<CombatCheckFinding> findings) {
        boolean unknownCrystal = targetType == null || targetType.isEmpty();
        recordCrystalEngineAttack(playerId, state, now, unknownCrystal, findings);
        if (targetType == null || targetType.isEmpty()) {
            state.unknownCrystalStreak++;
            if (state.unknownCrystalStreak >= 2) {
                add(findings, raise(playerId, state, now, "CrystalAura-A",
                        "unknown crystal id (entity=" + target + ")",
                        1.0D, 1.0D, 10, 0.86D));
                state.unknownCrystalStreak = 0;
            }
        } else {
            state.unknownCrystalStreak = Math.max(0, state.unknownCrystalStreak - 1);
        }
        long heldSwitchAge = now - state.lastHeldChangeMs;
        if (heldSwitchAge >= 0L && heldSwitchAge <= 120L && state.lastHeldSlot >= 0) {
            state.crystalSwitchBursts++;
            if (state.crystalSwitchBursts >= 2) {
                add(findings, raise(playerId, state, now, "CrystalAura-C",
                        "silent crystal switch (slot=" + state.lastHeldSlot + ", attackAgo=" + heldSwitchAge + "ms)",
                        1.0D, 1.0D, 10, 0.78D));
                state.crystalSwitchBursts = 0;
            }
        }
        if (state.pingMs > 0 && state.lastHeldChangeMs > 0L) {
            long switchToAttack = now - state.lastHeldChangeMs;
            if (switchToAttack > 0L && switchToAttack <= 300L && state.pingMs - switchToAttack >= 60L) {
                add(findings, raise(playerId, state, now, "CrystalAura-B",
                        "early crystal break (switchToAttack=" + switchToAttack + "ms,ping=" + state.pingMs + "ms)",
                        1.0D, 2.0D, 10, 0.74D));
            }
        }
    }

    private void handleCrystalEngineBlockUse(State state, long now, String itemType) {
        String type = itemType == null ? "" : itemType.toUpperCase(Locale.ROOT);
        if (type.contains("END_CRYSTAL") || type.contains("ENDER_CRYSTAL")) {
            state.crystalEnginePlaceTimes.addLast(now);
        } else if (type.contains("OBSIDIAN") || type.contains("BEDROCK")) {
            state.crystalEngineSupportPlaceTimes.addLast(now);
        } else {
            return;
        }
        pruneLongDeque(state.crystalEnginePlaceTimes, now - 4000L);
        pruneLongDeque(state.crystalEngineSupportPlaceTimes, now - 4000L);
    }

    private void recordCrystalEngineAttack(UUID playerId,
                                           State state,
                                           long now,
                                           boolean unknownCrystal,
                                           List<CombatCheckFinding> findings) {
        state.crystalEngineAttackTimes.addLast(now);
        if (unknownCrystal) {
            state.crystalEngineUnknownAttackTimes.addLast(now);
        } else {
            state.crystalEngineKnownAttackTimes.addLast(now);
        }
        boolean ownCycle = recent(now, lastTime(state.crystalEnginePlaceTimes), 900L);
        if (ownCycle) {
            state.crystalEngineOwnCycleTimes.addLast(now);
        }
        boolean silentSwitch = recent(now, state.lastHeldChangeMs, 180L);
        if (silentSwitch) {
            state.crystalEngineSilentSwitchTimes.addLast(now);
        }
        boolean basePlace = recent(now, lastTime(state.crystalEngineSupportPlaceTimes), 1000L);
        if (basePlace) {
            state.crystalEngineBasePlaceTimes.addLast(now);
        }

        long cutoff = now - 4000L;
        pruneLongDeque(state.crystalEngineAttackTimes, cutoff);
        pruneLongDeque(state.crystalEngineKnownAttackTimes, cutoff);
        pruneLongDeque(state.crystalEngineUnknownAttackTimes, cutoff);
        pruneLongDeque(state.crystalEngineOwnCycleTimes, cutoff);
        pruneLongDeque(state.crystalEngineSilentSwitchTimes, cutoff);
        pruneLongDeque(state.crystalEngineBasePlaceTimes, cutoff);

        int attacks = countSince(state.crystalEngineAttackTimes, now - 2500L);
        if (attacks < 4) {
            return;
        }
        int known = countSince(state.crystalEngineKnownAttackTimes, now - 2500L);
        int unknown = countSince(state.crystalEngineUnknownAttackTimes, now - 2500L);
        int own = countSince(state.crystalEngineOwnCycleTimes, now - 2500L);
        int silent = countSince(state.crystalEngineSilentSwitchTimes, now - 2500L);
        int base = countSince(state.crystalEngineBasePlaceTimes, now - 2500L);
        double consistency = intervalConsistency(state.crystalEngineAttackTimes, now - 2500L);
        double unknownRatio = unknown / (double) attacks;
        double ownRatio = own / (double) attacks;
        double silentRatio = silent / (double) attacks;
        double baseRatio = base / (double) attacks;

        double score = 0.0D;
        score += Math.min(35.0D, unknownRatio * 55.0D);
        score += Math.min(28.0D, silentRatio * 38.0D);
        score += Math.min(24.0D, ownRatio * 34.0D);
        score += Math.min(18.0D, baseRatio * 26.0D);
        score += Math.min(22.0D, consistency * 28.0D);
        if (state.pingMs > 0 && recent(now, state.lastHeldChangeMs, 300L) && state.pingMs - (now - state.lastHeldChangeMs) >= 60L) {
            score += 22.0D;
        }

        if (score >= 68.0D && (unknown > 0 || silent > 0 || own >= 2)) {
            add(findings, raise(playerId, state, now, "CrystalEngine-A",
                    format("crystal verdict (score=%.1f atk=%d known=%d unk=%d own=%d ss=%.2f base=%.2f cons=%.2f ping=%d)",
                            score, attacks, known, unknown, own, silentRatio, baseRatio, consistency, state.pingMs),
                    1.0D, 1.0D, 10, 0.78D));
        } else {
            decay(state, "CrystalEngine-A", 0.35D);
        }
    }

    private void evaluateAutoClicker(UUID playerId, State state, long now, List<CombatCheckFinding> findings) {
        List<Long> samples = new ArrayList<>(state.clickSamples);
        if (samples.size() < 10) {
            return;
        }
        double recentCps = cps(tailLong(state.clickSamples, Math.min(20, state.clickSamples.size())));
        double minimumCps = autoClickerMinimumCps(state);
        if (recentCps < minimumCps) {
            if (state.lowCpsSinceMs <= 0L) {
                state.lowCpsSinceMs = now;
            }
            decayAutoClicker(state, 0.85D);
            if (now - state.lowCpsSinceMs >= LOW_CPS_MODEL_RESET_MS) {
                state.clickSamples.clear();
                resetClickBaselines(state);
            }
            return;
        }
        state.lowCpsSinceMs = 0L;
        if (samples.size() >= 20) {
            List<Long> last20 = tailLong(state.clickSamples, 20);
            double cps = cps(last20);
            int clickEvidence = clickTimingEvidence(last20, minimumCps);
            if (cps > 25.0D && cps > 5.0D) {
                add(findings, raise(playerId, state, now, "AutoClicker-A",
                        format("cps=%.2f", cps), 1.0D, 1.0D, 10, 0.92D));
            }
            double dev = stddevLong(last20);
            double roundedDiff = Math.abs(cps - Math.round(cps));
            if (clickEvidence >= 3 && cps >= minimumCps + 1.0D && dev < 35.0D && roundedDiff < 0.04D) {
                add(findings, raise(playerId, state, now, "AutoClicker-C",
                        format("rounded cps (difference=%.4f)", roundedDiff), 1.0D, 3.0D, 8, 0.66D));
            }
            if (dev < 45.0D
                    && Math.abs(dev - state.lastClickDeviation) < 1.75D
                    && state.lastClickDeviation > 0.0D
                    && clickEvidence >= 3) {
                add(findings, raise(playerId, state, now, "AutoClicker-H",
                        format("repeated deviation changes (difference=%.3f)", Math.abs(dev - state.lastClickDeviation)),
                        1.0D, 4.0D, 8, 0.66D));
            }
            state.lastClickDeviation = dev;
        }

        if (samples.size() >= 10) {
            List<Long> last10 = tailLong(state.clickSamples, 10);
            long range = maxLong(last10) - minLong(last10);
            if (range < 50L && clickTimingEvidence(last10, minimumCps) >= 3) {
                add(findings, raise(playerId, state, now, "AutoClicker-J",
                        "low range (range=" + range + ")", 1.0D, 4.0D, 8, 0.72D));
            }
            double cps = cps(last10);
            if (state.lastCps > 0.0D && cps > 9.25D && Math.abs(cps - state.lastCps) > 2.8D) {
                add(findings, raise(playerId, state, now, "AutoClicker-O",
                        format("cps spikes (difference=%.2f, cps=%.2f)", Math.abs(cps - state.lastCps), cps),
                        1.0D, 5.0D, 8, 0.66D));
            }
            state.lastCps = cps;
        }

        if (samples.size() < 50) {
            evaluateRollingClickWindows(playerId, state, now, findings);
            return;
        }
        List<Long> last50 = tailLong(state.clickSamples, 50);
        double deviation = stddevLong(last50);
        double average = averageLong(last50);
        if (average <= 0.0D || 1000.0D / average < minimumCps) {
            decayAutoClicker(state, 0.55D);
            resetClickBaselines(state);
            return;
        }
        double variance = varianceLong(last50);
        double skewness = skewnessLong(last50);
        double kurtosis = kurtosisLong(last50) / 1000.0D;
        int distinct = distinctLong(last50);
        int outliers = outlierCount(last50, average, 50.0D);
        int timingEvidence = clickTimingEvidence(last50, minimumCps);
        boolean statisticalTimingProof = timingEvidence >= 3;
        boolean strongTimingProof = timingEvidence >= 4;

        if (statisticalTimingProof && deviation < 167.0D) {
            add(findings, raise(playerId, state, now, "AutoClicker-B",
                    format("low stddev (deviation=%.3f average=%.2f)", deviation, average),
                    1.0D, 3.0D, 8, 0.62D));
        }
        if (statisticalTimingProof && skewness < -0.01D) {
            add(findings, raise(playerId, state, now, "AutoClicker-D",
                    format("low skewness (skewness=%.3f)", skewness), 1.0D, 3.0D, 8, 0.62D));
        }
        if (statisticalTimingProof && (variance / 1000.0D) < 28.2D) {
            add(findings, raise(playerId, state, now, "AutoClicker-E",
                    format("low variance (variance=%.3f)", variance / 1000.0D), 1.0D, 3.0D, 8, 0.66D));
        }
        if (strongTimingProof && distinct < 13) {
            add(findings, raise(playerId, state, now, "AutoClicker-F",
                    "few distinct values (distinct=" + distinct + ")", 1.0D, 4.0D, 8, 0.68D));
        }
        if (strongTimingProof && outliers < 3) {
            add(findings, raise(playerId, state, now, "AutoClicker-G",
                    format("few outliers (outliers=%d average=%.2f)", outliers, average),
                    1.0D, 5.0D, 8, 0.68D));
        }
        if (statisticalTimingProof && kurtosis < 41.78D) {
            add(findings, raise(playerId, state, now, "AutoClicker-I",
                    format("low kurtosis (kurtosis=%.3f)", kurtosis), 1.0D, 5.0D, 8, 0.66D));
        }
        if (strongTimingProof && state.lastClickAverage > 0.0D && Math.abs(average - state.lastClickAverage) < 2.56D) {
            add(findings, raise(playerId, state, now, "AutoClicker-K",
                    format("repeated averages (difference=%.3f average=%.2f)", Math.abs(average - state.lastClickAverage), average),
                    1.0D, 4.0D, 8, 0.66D));
        }
        if (strongTimingProof && state.lastClickKurtosis > 0.0D && Math.abs(kurtosis - state.lastClickKurtosis) < 8.35D) {
            add(findings, raise(playerId, state, now, "AutoClicker-L",
                    format("repeated kurtosis (difference=%.3f kurtosis=%.3f)", Math.abs(kurtosis - state.lastClickKurtosis), kurtosis),
                    1.0D, 4.0D, 8, 0.66D));
        }
        if (strongTimingProof && state.lastClickVariance > 0.0D && Math.abs((variance / 1000.0D) - state.lastClickVariance) < 5.28D) {
            add(findings, raise(playerId, state, now, "AutoClicker-M",
                    format("repeated variance (difference=%.3f variance=%.3f)",
                            Math.abs((variance / 1000.0D) - state.lastClickVariance), variance / 1000.0D),
                    1.0D, 4.0D, 8, 0.66D));
        }
        state.lastClickAverage = average;
        state.lastClickKurtosis = kurtosis;
        state.lastClickVariance = variance / 1000.0D;

        evaluateRollingClickWindows(playerId, state, now, findings);
    }

    private void evaluateRollingClickWindows(UUID playerId, State state, long now, List<CombatCheckFinding> findings) {
        if (state.clickSamples.size() >= 15
                && cps(tailLong(state.clickSamples, Math.min(20, state.clickSamples.size()))) < autoClickerMinimumCps(state)) {
            return;
        }
        if (state.clickSamples.size() >= 25) {
            List<Long> last25 = tailLong(state.clickSamples, 25);
            double deviation = stddevLong(last25);
            double average = averageLong(last25);
            double kurtosis = kurtosisLong(last25) / 1000.0D;
            int distinct = distinctLong(last25);
            int timingEvidence = clickTimingEvidence(last25, autoClickerMinimumCps(state));
            if (timingEvidence >= 4 && state.lastWindowDeviation > 0.0D && Math.abs(deviation - state.lastWindowDeviation) < 0.25D && average < 150.0D) {
                add(findings, raise(playerId, state, now, "AutoClicker-N",
                        format("repeated deviation window (difference=%.3f average=%.2f)", Math.abs(deviation - state.lastWindowDeviation), average),
                        1.0D, 25.0D, 8, 0.66D));
            }
            if (timingEvidence >= 4 && distinct < 6) {
                add(findings, raise(playerId, state, now, "AutoClicker-S",
                        "few distinct values (distinct=" + distinct + ")", 1.0D, 30.0D, 8, 0.66D));
            }
            if (timingEvidence >= 4 && kurtosis < 13.0D) {
                add(findings, raise(playerId, state, now, "AutoClicker-T",
                        format("low kurtosis (kurtosis=%.3f)", kurtosis), 1.0D, 25.0D, 8, 0.66D));
            }
            state.lastWindowDeviation = deviation;
        }

        if (state.clickSamples.size() >= 15) {
            List<Long> last15 = tailLong(state.clickSamples, 15);
            double deviation = stddevLong(last15);
            double skew = skewnessLong(last15);
            double kurt = kurtosisLong(last15);
            if (almostEqual(deviation, state.lastSampleDeviation, 0.0D)
                    && almostEqual(skew, state.lastSampleSkewness, 0.0D)
                    && almostEqual(kurt, state.lastSampleKurtosis, 0.0D)
                    && state.lastSampleDeviation > 0.0D) {
                add(findings, raise(playerId, state, now, "AutoClicker-P",
                        format("identical sample stats (dev=%.3f, skew=%.3f, kurt=%.3f)", deviation, skew, kurt),
                        1.0D, 2.0D, 8, 0.78D));
            }
            state.lastSampleDeviation = deviation;
            state.lastSampleSkewness = skew;
            state.lastSampleKurtosis = kurt;
        }

        if (state.clickSamples.size() >= 40) {
            List<Long> last40 = tailLong(state.clickSamples, 40);
            double deviation = stddevLong(last40);
            double average = averageLong(last40);
            if (clickTimingEvidence(last40, autoClickerMinimumCps(state)) >= 4
                    && state.lastLargeWindowDeviation > 0.0D
                    && Math.abs(deviation - state.lastLargeWindowDeviation) < 3.0D
                    && average < 150.0D) {
                add(findings, raise(playerId, state, now, "AutoClicker-Q",
                        format("deviation spikes (difference=%.3f average=%.2f)",
                                Math.abs(deviation - state.lastLargeWindowDeviation), average),
                        1.0D, 35.0D, 8, 0.66D));
            }
            state.lastLargeWindowDeviation = deviation;
        }

        if (state.clickSamples.size() >= 30) {
            List<Long> last30 = tailLong(state.clickSamples, 30);
            double average = averageLong(last30);
            int outliers = outlierCount(last30, average, 50.0D);
            if (outliers == 0 && clickTimingEvidence(last30, autoClickerMinimumCps(state)) >= 4) {
                add(findings, raise(playerId, state, now, "AutoClicker-R",
                        format("zero outliers (outliers=0 average=%.2f)", average),
                        1.0D, 50.0D, 8, 0.66D));
            }
        }
    }

    private boolean autoClickerExempt(State state, long now) {
        return recent(now, state.lastBlockUseMs, 500L)
                || recent(now, state.lastBlockBreakMs, 1250L)
                || recent(now, state.lastInventoryMs, 750L)
                || recent(now, state.lastDigStartMs, 1000L)
                || recent(now, state.lastDigFinishMs, 1000L)
                || recent(now, state.lastDigCancelMs, 2000L)
                || recent(now, state.lastDropItemMs, 750L)
                || recent(now, state.lastProjectileThrowMs, 1000L)
                || recent(now, state.lastInteractEntityMs, 250L);
    }

    private void beginWtap(State state, long now) {
        finalizeWtap(null, state, now, true, null);
        state.wtapAttackMs = now;
        state.wtapTracking = true;
        state.wtapWindowActions.clear();
        for (SprintAction action : state.recentSprintActions) {
            if (action.timeMs >= now - 160L && action.timeMs <= now) {
                state.wtapWindowActions.add(action);
            }
        }
    }

    private void finalizeWtap(UUID playerId,
                              State state,
                              long now,
                              boolean force,
                              List<CombatCheckFinding> findings) {
        if (!state.wtapTracking || state.wtapAttackMs <= 0L) {
            return;
        }
        if (!force && now - state.wtapAttackMs <= 160L) {
            return;
        }
        int sprintActions = 0;
        int starts = 0;
        int stops = 0;
        int transitions = 0;
        String previous = "";
        long first = 0L;
        long last = 0L;
        for (SprintAction action : state.wtapWindowActions) {
            sprintActions++;
            if ("START_SPRINTING".equals(action.action)) {
                starts++;
            } else {
                stops++;
            }
            if (!previous.isEmpty() && !previous.equals(action.action)) {
                transitions++;
            }
            previous = action.action;
            if (first == 0L) {
                first = action.timeMs;
            }
            last = action.timeMs;
        }
        long span = first > 0L && last >= first ? last - first : 0L;
        boolean suspicious = sprintActions >= 4 && starts > 0 && stops > 0 && transitions >= 3 && span <= 55L;
        if (suspicious) {
            if (state.wtapBurstWindowMs == 0L || now - state.wtapBurstWindowMs > 2500L) {
                state.wtapBurstWindowMs = now;
                state.wtapBursts = 1;
            } else {
                state.wtapBursts++;
            }
            if (state.wtapBursts >= 2 && playerId != null && findings != null) {
                add(findings, raise(playerId, state, now, "WTap-A",
                        format("wtap burst (actions=%d, start=%d, stop=%d, trans=%d, span=%dms, seq=%d)",
                                sprintActions, starts, stops, transitions, span, state.wtapBursts),
                        1.0D, 1.0D, 10, 0.86D));
                state.wtapBurstWindowMs = 0L;
                state.wtapBursts = 0;
            }
        }
        state.wtapTracking = false;
        state.wtapAttackMs = 0L;
        state.wtapWindowActions.clear();
    }

    private void beginBacktrackCombat(State state, long now) {
        state.backtrackLastAttackMs = now;
        if (state.backtrackInCombat) {
            return;
        }
        state.backtrackInCombat = true;
        state.backtrackBaselinePing = state.peacePingSamples.isEmpty()
                ? state.pingMs
                : (int) Math.round(meanInt(state.peacePingSamples));
        state.combatPingSamples.clear();
        state.backtrackScore = 50.0D;
        state.backtrackLastEvaluateMs = 0L;
    }

    private void handleBacktrackPing(UUID playerId,
                                     State state,
                                     long now,
                                     List<CombatCheckFinding> findings) {
        if (state.pingMs <= 0) {
            return;
        }
        if (!state.backtrackInCombat) {
            pushInt(state.peacePingSamples, state.pingMs, 80);
            return;
        }
        int baseline = state.backtrackBaselinePing <= 0 ? state.pingMs : state.backtrackBaselinePing;
        state.combatPingSamples.addLast(new PingSample(now, state.pingMs, state.pingMs - baseline, recent(now, state.lastAttackMs, 60L)));
        while (state.combatPingSamples.size() > 100) {
            state.combatPingSamples.removeFirst();
        }
        if (now - state.backtrackLastAttackMs > 10_000L) {
            evaluateBacktrack(playerId, state, now, findings, true);
            state.backtrackInCombat = false;
            state.combatPingSamples.clear();
            state.peacePingSamples.clear();
            return;
        }
        if (state.combatPingSamples.size() >= 60 && now - state.backtrackLastEvaluateMs >= 1000L) {
            evaluateBacktrack(playerId, state, now, findings, false);
            state.backtrackLastEvaluateMs = now;
        }
    }

    private void evaluateBacktrack(UUID playerId,
                                   State state,
                                   long now,
                                   List<CombatCheckFinding> findings,
                                   boolean finalPass) {
        if (state.combatPingSamples.size() < 60) {
            return;
        }
        int baseline = state.backtrackBaselinePing <= 0 ? state.pingMs : state.backtrackBaselinePing;
        List<PingSample> samples = new ArrayList<>(state.combatPingSamples);
        int spikes = 0;
        int elevated = 0;
        double pingSum = 0.0D;
        for (int i = 0; i < samples.size(); i++) {
            PingSample sample = samples.get(i);
            pingSum += sample.ping;
            if (sample.deltaFromBaseline >= 30) {
                elevated++;
            }
            if (i > 0 && i + 1 < samples.size()) {
                PingSample previous = samples.get(i - 1);
                PingSample next = samples.get(i + 1);
                if (sample.deltaFromBaseline >= 50
                        && previous.deltaFromBaseline < 30
                        && next.deltaFromBaseline < 30) {
                    spikes++;
                }
            }
        }
        double avgPing = pingSum / samples.size();
        double variance = 0.0D;
        for (PingSample sample : samples) {
            double diff = sample.ping - avgPing;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / samples.size());
        boolean elevatedPing = elevated / (double) samples.size() >= 0.70D;
        boolean oscillation = stdDev >= 50.0D && avgPing < 300.0D;

        if (spikes >= 3) {
            state.backtrackScore += 15.0D;
        }
        if (elevatedPing) {
            state.backtrackScore += 20.0D;
        }
        if (oscillation) {
            state.backtrackScore += 25.0D;
        }
        if (meanAbsolutePingDelta(samples) < 20.0D) {
            state.backtrackScore -= 10.0D;
        }
        boolean stableHighLatency = baseline >= 250 && spikes == 0 && !oscillation;
        if (!elevatedPing && spikes == 0 && !oscillation) {
            state.backtrackScore -= stableHighLatency ? 24.0D : 16.0D;
        } else if (stableHighLatency && !finalPass) {
            state.backtrackScore -= 18.0D;
        }
        state.backtrackScore = clamp(state.backtrackScore, 0.0D, 100.0D);

        boolean actionable = spikes >= 3
                || oscillation
                || (elevatedPing && baseline < 250 && avgPing >= baseline + 45.0D)
                || (finalPass && elevatedPing && avgPing >= baseline + 65.0D);
        if (state.backtrackScore >= 80.0D && actionable) {
            add(findings, raise(playerId, state, now, "Backtrack-B",
                    format("backtrack ping (score=%.0f, base=%dms, avg=%.0fms, spikes=%d, elevated=%s, osc=%s, final=%s)",
                            state.backtrackScore, state.backtrackBaselinePing, avgPing, spikes, elevatedPing, oscillation, finalPass),
                    1.0D, 1.0D, 10, 0.76D));
        } else {
            decay(state, "Backtrack-B", 0.35D);
        }
    }

    private void recordTotemAction(State state,
                                   long now,
                                   String name,
                                   boolean openedInventory,
                                   boolean swapClickToOffhand,
                                   boolean swapItemWithOffhand,
                                   boolean hotbarSwapToOffhand,
                                   int heldItemChanges,
                                   int windowClicks) {
        state.lastTotemActionMs = now;
        state.lastTotemActionName = name;
        state.totemOpenedInventory |= openedInventory;
        state.totemSwapClickToOffhand |= swapClickToOffhand;
        state.totemSwapItemWithOffhand |= swapItemWithOffhand;
        state.totemHotbarSwapToOffhand |= hotbarSwapToOffhand;
        state.totemHeldItemChanges += heldItemChanges;
        state.totemWindowClicks += windowClicks;
    }

    private void evaluateTotem(UUID playerId, State state, long now, List<CombatCheckFinding> findings) {
        TotemPop pop = state.totemPop;
        if (pop == null || now - pop.timeMs > 250L) {
            return;
        }
        long refillMs = state.lastTotemActionMs - pop.timeMs;
        long refillTicks = state.currentTick - pop.tick;
        boolean pattern = state.totemSwapClickToOffhand
                || state.totemPickupIntoOffhand
                || state.totemSwapItemWithOffhand
                || state.totemHotbarSwapToOffhand
                || state.totemWindowClicks >= 2;
        if (refillMs >= 0L && refillMs <= 75L && refillTicks >= 0L && refillTicks <= 1L && pattern) {
            add(findings, raise(playerId, state, now, "AutoTotem-A",
                    "post-pop refill (" + refillMs + "ms, " + refillTicks + "t, action=" + state.lastTotemActionName + ")",
                    1.0D, 1.0D, 10, 0.92D));
            clearTotem(state);
        }
    }

    private void clearTotem(State state) {
        state.totemPop = null;
        state.lastTotemActionMs = 0L;
        state.lastTotemActionName = "";
        state.totemOpenedInventory = false;
        state.totemSwapClickToOffhand = false;
        state.totemPickupIntoOffhand = false;
        state.totemSwapItemWithOffhand = false;
        state.totemHotbarSwapToOffhand = false;
        state.totemHeldItemChanges = 0;
        state.totemWindowClicks = 0;
    }

    private static CombatCheckFinding raise(UUID playerId,
                                            State state,
                                            long now,
                                            String checkId,
                                            String details,
                                            double add,
                                            double threshold,
                                            int vl,
                                            double confidence) {
        double next = Math.max(0.0D, state.buffers.getOrDefault(checkId, 0.0D) + add);
        state.buffers.put(checkId, Math.min(100.0D, next));
        long lastFlag = state.lastFlagByCheck.getOrDefault(checkId, 0L);
        if (next < threshold || now - lastFlag < 175L) {
            return null;
        }
        state.lastFlagByCheck.put(checkId, now);
        state.buffers.put(checkId, Math.max(0.0D, next * 0.45D));
        double score = Math.min(100.0D, (next / Math.max(1.0D, threshold)) * 100.0D);
        int adjustedVl = adjustedViolationPoints(checkId, details, vl, confidence, score);
        return new CombatCheckFinding(
                playerId,
                checkId,
                score,
                confidence,
                adjustedVl,
                details,
                List.of(checkId + ":" + compact(details))
        );
    }

    private static int adjustedViolationPoints(String checkId,
                                               String details,
                                               int requestedVl,
                                               double confidence,
                                               double score) {
        int vl = Math.max(1, requestedVl);
        String id = checkId == null ? "" : checkId;
        String summary = details == null ? "" : details;

        if ("CombatTelemetry".equals(id) || id.endsWith("-telemetry") || summary.contains("plugin combat signal")) {
            return Math.min(vl, 2);
        }
        if (id.startsWith("Backtrack")) {
            return Math.min(vl, summary.contains("final=true") ? 4 : 2);
        }
        if (id.startsWith("AutoClicker")) {
            return Math.min(vl, confidence >= 0.78D && score >= 95.0D ? 3 : 2);
        }
        if (id.startsWith("Aim")) {
            return Math.min(vl, confidence >= 0.84D && score >= 95.0D ? 4 : 3);
        }
        if (id.startsWith("BowAim")) {
            return Math.min(vl, 4);
        }
        if (id.startsWith("Reach") || id.startsWith("HitBox") || id.startsWith("ContainerReach")) {
            return Math.min(vl, confidence >= 0.88D && score >= 95.0D ? 5 : 3);
        }
        if (id.startsWith("KillAura") || id.startsWith("AutoBlock")) {
            return Math.min(vl, confidence >= 0.88D && score >= 95.0D ? 5 : 4);
        }
        if (id.startsWith("CrystalAura") || id.startsWith("CrystalEngine")) {
            return Math.min(vl, 4);
        }
        return Math.min(vl, 3);
    }

    private static void decay(State state, String checkId, double amount) {
        state.buffers.computeIfPresent(checkId, (ignored, value) -> Math.max(0.0D, value - amount));
    }

    private static void decayBuffers(State state, long now) {
        if (state.lastBufferDecayMs <= 0L) {
            state.lastBufferDecayMs = now;
            return;
        }
        long elapsed = Math.max(0L, now - state.lastBufferDecayMs);
        if (elapsed < 50L) {
            return;
        }
        double amount = Math.min(3.0D, (elapsed / 1000.0D) * 0.80D);
        state.buffers.replaceAll((ignored, value) -> Math.max(0.0D, value - amount));
        state.buffers.entrySet().removeIf(entry -> entry.getValue() <= 1.0E-4D);
        state.lastBufferDecayMs = now;
    }

    private static void decayAutoClicker(State state, double amount) {
        state.buffers.replaceAll((checkId, value) ->
                checkId.startsWith("AutoClicker-") ? Math.max(0.0D, value - amount) : value);
        state.buffers.entrySet().removeIf(entry -> entry.getValue() <= 1.0E-4D);
    }

    private static double autoClickerMinimumCps(State state) {
        return state.protocolVersion > 47 ? MODERN_AUTOCLICKER_MIN_CPS : LEGACY_AUTOCLICKER_MIN_CPS;
    }

    private static void resetClickModel(State state) {
        state.clickSamples.clear();
        state.lowCpsSinceMs = 0L;
        resetClickBaselines(state);
        decayAutoClicker(state, 4.0D);
    }

    private static void resetClickBaselines(State state) {
        state.lastClickDeviation = 0.0D;
        state.lastClickAverage = 0.0D;
        state.lastClickKurtosis = 0.0D;
        state.lastClickVariance = 0.0D;
        state.lastWindowDeviation = 0.0D;
        state.lastLargeWindowDeviation = 0.0D;
        state.lastSampleDeviation = 0.0D;
        state.lastSampleSkewness = 0.0D;
        state.lastSampleKurtosis = 0.0D;
        state.lastCps = 0.0D;
    }

    private static void add(List<CombatCheckFinding> findings, CombatCheckFinding finding) {
        if (findings != null && finding != null) {
            findings.add(finding);
        }
    }

    private static void pruneRotations(State state, long now) {
        while (state.rotations.size() > 140 || (!state.rotations.isEmpty() && now - state.rotations.peekFirst().timeMs > 3500L)) {
            state.rotations.removeFirst();
        }
    }

    private static void push(ArrayDeque<Double> samples, double value, int max) {
        if (!Double.isFinite(value)) {
            return;
        }
        samples.addLast(value);
        while (samples.size() > max) {
            samples.removeFirst();
        }
    }

    private static void pushLong(ArrayDeque<Long> samples, long value, int max) {
        samples.addLast(value);
        while (samples.size() > max) {
            samples.removeFirst();
        }
    }

    private static void pushInt(ArrayDeque<Integer> samples, int value, int max) {
        samples.addLast(value);
        while (samples.size() > max) {
            samples.removeFirst();
        }
    }

    private static boolean recent(long now, long then, long windowMs) {
        return then > 0L && now >= then && now - then <= windowMs;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }

    private static double doubleValue(JsonObject object, String key, double fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : fallback;
    }

    private static boolean boolValue(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }

    private static String compact(String details) {
        if (details == null || details.length() <= 48) {
            return details == null ? "" : details;
        }
        return details.substring(0, 47) + "~";
    }

    private static String format(String pattern, Object... args) {
        return String.format(Locale.US, pattern, args);
    }

    private static boolean almostEqual(double a, double b, double epsilon) {
        return Math.abs(a - b) <= epsilon;
    }

    private static boolean nearModulo(double value, double mod) {
        if (mod <= 0.0D) {
            return false;
        }
        double rem = value % mod;
        return rem <= 1.0E-5D || Math.abs(rem - mod) <= 1.0E-5D;
    }

    private static double gcdLike(double a, double b) {
        long x = Math.round(Math.abs(a) * 100_000.0D);
        long y = Math.round(Math.abs(b) * 100_000.0D);
        if (x <= 0L || y <= 0L) {
            return 0.0D;
        }
        while (y != 0L) {
            long temp = y;
            y = x % y;
            x = temp;
        }
        return x / 100_000.0D;
    }

    private static double effectiveRotationStep(State state, double currentStep) {
        double learned = usefulStep(state.learnedStep) ? state.learnedStep : 0.0D;
        double current = usefulStep(currentStep) ? currentStep : 0.0D;
        if (learned <= 0.0D) {
            return current;
        }
        if (current > 0.0D && current < learned && divisorLike(learned / current)) {
            return current;
        }
        return learned;
    }

    private static boolean usefulStep(double step) {
        return Double.isFinite(step) && step >= 0.0028D && step <= 0.65D;
    }

    private static boolean reliableRotationStep(State state, double step) {
        return usefulStep(step)
                && usefulStep(state.learnedStep)
                && state.gcdSteps.size() >= 24;
    }

    private static boolean divisorLike(double ratio) {
        if (!Double.isFinite(ratio) || ratio < 1.75D || ratio > 8.25D) {
            return false;
        }
        return Math.abs(ratio - Math.round(ratio)) <= 0.08D;
    }

    private static boolean fitsStep(double delta, double step) {
        if (delta <= 1.0E-5D || !usefulStep(step)) {
            return false;
        }
        return residual(delta, step) <= Math.max(1.0E-5D, step * 0.025D);
    }

    private static double residual(double delta, double step) {
        if (step <= 0.0D) {
            return 0.0D;
        }
        double modulo = delta % step;
        return Math.min(modulo, Math.abs(step - modulo));
    }

    private static double residualNorm(double delta, double step) {
        return step <= 0.0D ? 0.0D : residual(delta, step) / step;
    }

    private static double floorDistance(double value) {
        return Math.abs(Math.round(value) - value);
    }

    private static double tickAlignedShare(Collection<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0D;
        }
        int aligned = 0;
        int total = 0;
        for (double value : values) {
            if (!Double.isFinite(value) || value <= 0.0D) {
                continue;
            }
            total++;
            double nearestTick = Math.round(value / 50.0D) * 50.0D;
            if (Math.abs(value - nearestTick) <= 3.5D) {
                aligned++;
            }
        }
        return total <= 0 ? 0.0D : aligned / (double) total;
    }

    private static int clickTimingEvidence(Collection<Long> values, double minimumCps) {
        if (values == null || values.size() < 10) {
            return 0;
        }
        double average = averageLong(values);
        if (average <= 0.0D) {
            return 0;
        }
        double cps = 1000.0D / average;
        if (cps < minimumCps + 0.50D) {
            return 0;
        }
        double deviation = stddevLong(values);
        long range = maxLong(values) - minLong(values);
        int distinct = distinctLong(values);
        int outliers = outlierCount(values, average, 50.0D);
        double tickAligned = tickAlignedShareLong(values);
        int evidence = 0;
        if (deviation <= 24.0D) {
            evidence++;
        }
        if (range <= 45L) {
            evidence++;
        }
        if (distinct <= Math.max(6, values.size() / 4)) {
            evidence++;
        }
        if (outliers <= Math.max(1, values.size() / 18)) {
            evidence++;
        }
        if (tickAligned >= 0.72D) {
            evidence++;
        }
        if (cps >= minimumCps + 4.0D && deviation <= 32.0D) {
            evidence++;
        }
        return evidence;
    }

    private static double tickAlignedShareLong(Collection<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0.0D;
        }
        int aligned = 0;
        int total = 0;
        for (long value : values) {
            if (value <= 0L) {
                continue;
            }
            total++;
            double nearestTick = Math.round(value / 50.0D) * 50.0D;
            if (Math.abs(value - nearestTick) <= 3.5D) {
                aligned++;
            }
        }
        return total <= 0 ? 0.0D : aligned / (double) total;
    }

    private static double estimateSensitivity(double gcd) {
        if (gcd <= 0.0D) {
            return 0.0D;
        }
        return ((Math.cbrt(gcd / 1.2D) - 0.2D) / 0.6D) * 200.0D;
    }

    private static List<Double> tail(ArrayDeque<Double> deque, int size) {
        List<Double> values = new ArrayList<>(Math.min(size, deque.size()));
        int skip = Math.max(0, deque.size() - size);
        int index = 0;
        for (double value : deque) {
            if (index++ >= skip) {
                values.add(value);
            }
        }
        return values;
    }

    private static List<Long> tailLong(ArrayDeque<Long> deque, int size) {
        List<Long> values = new ArrayList<>(Math.min(size, deque.size()));
        int skip = Math.max(0, deque.size() - size);
        int index = 0;
        for (long value : deque) {
            if (index++ >= skip) {
                values.add(value);
            }
        }
        return values;
    }

    private static long lastTime(ArrayDeque<Long> times) {
        return times.isEmpty() ? 0L : times.peekLast();
    }

    private static void pruneLongDeque(ArrayDeque<Long> times, long cutoff) {
        while (!times.isEmpty() && times.peekFirst() < cutoff) {
            times.removeFirst();
        }
    }

    private static int countSince(ArrayDeque<Long> times, long cutoff) {
        int count = 0;
        for (long time : times) {
            if (time >= cutoff) {
                count++;
            }
        }
        return count;
    }

    private static double intervalConsistency(ArrayDeque<Long> times, long cutoff) {
        List<Long> filtered = new ArrayList<>();
        for (long time : times) {
            if (time >= cutoff) {
                filtered.add(time);
            }
        }
        if (filtered.size() < 4) {
            return 0.0D;
        }
        List<Double> intervals = new ArrayList<>();
        for (int i = 1; i < filtered.size(); i++) {
            long interval = filtered.get(i) - filtered.get(i - 1);
            if (interval > 0L && interval <= 1000L) {
                intervals.add((double) interval);
            }
        }
        if (intervals.size() < 3) {
            return 0.0D;
        }
        double mean = mean(intervals);
        double std = stddev(intervals, mean);
        if (mean <= 0.0D) {
            return 0.0D;
        }
        return clamp(1.0D - (std / Math.max(1.0D, mean)), 0.0D, 1.0D);
    }

    private static double mode(ArrayDeque<Double> values, double scale) {
        if (values.size() < 20) {
            return 0.0D;
        }
        Map<Integer, Integer> counts = new HashMap<>();
        int bestBucket = 0;
        int bestCount = 0;
        for (double value : values) {
            int bucket = (int) Math.round(value * scale);
            int count = counts.merge(bucket, 1, Integer::sum);
            if (count > bestCount) {
                bestCount = count;
                bestBucket = bucket;
            }
        }
        return (bestCount / (double) values.size()) >= 0.35D ? bestBucket / scale : 0.0D;
    }

    private static BucketProfile bucketProfile(ArrayDeque<Integer> values, int minSamples, double scale) {
        if (values.size() < minSamples) {
            return new BucketProfile(0.0D, 0.0D);
        }
        Map<Integer, Integer> counts = new HashMap<>();
        int bestBucket = 0;
        int bestCount = 0;
        for (int value : values) {
            int count = counts.merge(value, 1, Integer::sum);
            if (count > bestCount) {
                bestCount = count;
                bestBucket = value;
            }
        }
        return new BucketProfile(bestBucket / scale, bestCount / (double) values.size());
    }

    private static DominantBucket dominantBucket(Collection<Double> values, double scale) {
        Map<Integer, Integer> counts = new HashMap<>();
        int best = 0;
        for (double value : values) {
            int bucket = (int) Math.round(value * scale);
            best = Math.max(best, counts.merge(bucket, 1, Integer::sum));
        }
        return new DominantBucket(values.isEmpty() ? 0.0D : best / (double) values.size());
    }

    private static double mean(Collection<Double> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static double meanInt(Collection<Integer> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (int value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static double meanAbsolutePingDelta(Collection<PingSample> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (PingSample sample : values) {
            sum += Math.abs(sample.deltaFromBaseline);
        }
        return sum / values.size();
    }

    private static double variance(Collection<Double> values) {
        double mean = mean(values);
        if (values.isEmpty()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (double value : values) {
            double diff = value - mean;
            sum += diff * diff;
        }
        return sum / values.size();
    }

    private static double stddev(Collection<Double> values, double mean) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (double value : values) {
            double diff = value - mean;
            sum += diff * diff;
        }
        return Math.sqrt(sum / values.size());
    }

    private static double max(Collection<Double> values) {
        double max = Double.NEGATIVE_INFINITY;
        for (double value : values) {
            max = Math.max(max, value);
        }
        return max == Double.NEGATIVE_INFINITY ? 0.0D : max;
    }

    private static double min(Collection<Double> values) {
        double min = Double.POSITIVE_INFINITY;
        for (double value : values) {
            min = Math.min(min, value);
        }
        return min == Double.POSITIVE_INFINITY ? 0.0D : min;
    }

    private static double entropy(Collection<Double> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        Map<Integer, Integer> counts = new HashMap<>();
        for (double value : values) {
            counts.merge((int) Math.round(value * 10.0D), 1, Integer::sum);
        }
        double entropy = 0.0D;
        for (int count : counts.values()) {
            double p = count / (double) values.size();
            entropy -= p * (Math.log(p) / Math.log(2.0D));
        }
        return entropy;
    }

    private static double kurtosis(Collection<Double> values) {
        if (values.size() < 2) {
            return 0.0D;
        }
        double mean = mean(values);
        double variance = variance(values);
        if (variance <= 1.0E-9D) {
            return 0.0D;
        }
        double fourth = 0.0D;
        for (double value : values) {
            fourth += Math.pow(value - mean, 4.0D);
        }
        return (fourth / values.size()) / (variance * variance);
    }

    private static double jerk(List<Double> values) {
        if (values.size() < 4) {
            return 0.0D;
        }
        double sum = 0.0D;
        int count = 0;
        for (int i = 3; i < values.size(); i++) {
            double a = values.get(i) - values.get(i - 1);
            double b = values.get(i - 1) - values.get(i - 2);
            double c = values.get(i - 2) - values.get(i - 3);
            sum += Math.abs((a - b) - (b - c));
            count++;
        }
        return count == 0 ? 0.0D : sum / count;
    }

    private static double smoothingCoefficient(List<Double> values) {
        if (values.size() < 2) {
            return 0.0D;
        }
        double diffSum = 0.0D;
        double valueSum = 0.0D;
        for (int i = 1; i < values.size(); i++) {
            diffSum += Math.abs(values.get(i) - values.get(i - 1));
            valueSum += Math.abs(values.get(i));
        }
        return valueSum <= 1.0E-9D ? 0.0D : 1.0D - Math.min(1.0D, diffSum / valueSum);
    }

    private static double secondDerivative(List<Double> values) {
        if (values.size() < 3) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (int i = 2; i < values.size(); i++) {
            sum += Math.abs(values.get(i) - (2.0D * values.get(i - 1)) + values.get(i - 2));
        }
        return sum / (values.size() - 2);
    }

    private static double linearRSquared(List<Double> values) {
        if (values.size() < 2) {
            return 0.0D;
        }
        double meanX = (values.size() - 1) / 2.0D;
        double meanY = mean(values);
        double sxx = 0.0D;
        double sxy = 0.0D;
        double syy = 0.0D;
        for (int i = 0; i < values.size(); i++) {
            double dx = i - meanX;
            double dy = values.get(i) - meanY;
            sxx += dx * dx;
            sxy += dx * dy;
            syy += dy * dy;
        }
        if (sxx <= 0.0D || syy <= 0.0D) {
            return 0.0D;
        }
        return (sxy * sxy) / (sxx * syy);
    }

    private static double periodicity(List<Double> values) {
        double best = 0.0D;
        for (int lag = 1; lag <= Math.min(5, values.size() / 2); lag++) {
            best = Math.max(best, Math.abs(autocorrelation(values, lag)));
        }
        return best;
    }

    private static double autocorrelation(List<Double> values, int lag) {
        if (values.size() <= lag) {
            return 0.0D;
        }
        double mean = mean(values);
        double numerator = 0.0D;
        double denominator = 0.0D;
        for (int i = 0; i < values.size(); i++) {
            double diff = values.get(i) - mean;
            denominator += diff * diff;
            if (i + lag < values.size()) {
                numerator += diff * (values.get(i + lag) - mean);
            }
        }
        return denominator <= 1.0E-9D ? 0.0D : numerator / denominator;
    }

    private static double averageLong(Collection<Long> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (long value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static double varianceLong(Collection<Long> values) {
        double mean = averageLong(values);
        double sum = 0.0D;
        for (long value : values) {
            double diff = value - mean;
            sum += diff * diff;
        }
        return values.isEmpty() ? 0.0D : sum / values.size();
    }

    private static double stddevLong(Collection<Long> values) {
        return Math.sqrt(varianceLong(values));
    }

    private static double skewnessLong(List<Long> values) {
        if (values.size() < 3) {
            return 0.0D;
        }
        double mean = averageLong(values);
        double std = stddevLong(values);
        if (std <= 1.0E-9D) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (long value : values) {
            sum += Math.pow((value - mean) / std, 3.0D);
        }
        return sum / values.size();
    }

    private static double kurtosisLong(Collection<Long> values) {
        if (values.size() < 2) {
            return 0.0D;
        }
        double mean = averageLong(values);
        double variance = varianceLong(values);
        if (variance <= 1.0E-9D) {
            return 0.0D;
        }
        double fourth = 0.0D;
        for (long value : values) {
            fourth += Math.pow(value - mean, 4.0D);
        }
        return (fourth / values.size()) / (variance * variance) * 1000.0D;
    }

    private static int distinctLong(Collection<Long> values) {
        return (int) values.stream().distinct().count();
    }

    private static int outlierCount(Collection<Long> values, double average, double threshold) {
        int outliers = 0;
        for (long value : values) {
            if (Math.abs(value - average) > threshold) {
                outliers++;
            }
        }
        return outliers;
    }

    private static double cps(Collection<Long> delays) {
        double average = averageLong(delays);
        return average <= 0.0D ? 0.0D : 1000.0D / average;
    }

    private static double bowForce(long drawDurationMs) {
        double ticks = Math.max(0.0D, drawDurationMs / 50.0D);
        double force = (ticks * ticks + ticks * 2.0D) / 60.0D;
        return clamp(force, 0.0D, 1.0D);
    }

    private static double horizontalAngularTolerance(double targetWidth, double horizontalDistance) {
        double halfWidth = Math.max(0.20D, (targetWidth * 0.55D) + 0.08D);
        double base = Math.toDegrees(Math.atan(halfWidth / Math.max(0.1D, horizontalDistance)));
        return base + 0.10D;
    }

    private static double verticalAngularTolerance(double targetHeight, double horizontalDistance) {
        double halfHeight = Math.max(0.28D, Math.min((targetHeight * 0.45D) + 0.08D, 1.0D));
        double base = Math.toDegrees(Math.atan(halfHeight / Math.max(0.1D, horizontalDistance)));
        return base + 0.10D;
    }

    private static double normalizedError(double yawError, double pitchError, double yawTolerance, double pitchTolerance) {
        double yawPart = yawError / Math.max(0.05D, yawTolerance);
        double pitchPart = pitchError / Math.max(0.05D, pitchTolerance);
        return Math.hypot(yawPart, pitchPart);
    }

    private static double angleDiffDegrees(double current, double previous) {
        double diff = Math.abs(current - previous) % 360.0D;
        return diff > 180.0D ? 360.0D - diff : diff;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int dominantBowTarget(ArrayDeque<BowAimSample> samples) {
        Map<Integer, Integer> counts = new HashMap<>();
        int bestTarget = Integer.MIN_VALUE;
        int bestCount = 0;
        for (BowAimSample sample : samples) {
            int count = counts.merge(sample.targetId, 1, Integer::sum);
            if (count > bestCount) {
                bestCount = count;
                bestTarget = sample.targetId;
            }
        }
        return bestTarget;
    }

    private static double bowStddev(Collection<BowAimSample> samples, double mean) {
        if (samples.isEmpty()) {
            return 0.0D;
        }
        double variance = 0.0D;
        for (BowAimSample sample : samples) {
            double diff = sample.normalizedError - mean;
            variance += diff * diff;
        }
        return Math.sqrt(variance / samples.size());
    }

    private static long minLong(Collection<Long> values) {
        long min = Long.MAX_VALUE;
        for (long value : values) {
            min = Math.min(min, value);
        }
        return min == Long.MAX_VALUE ? 0L : min;
    }

    private static long maxLong(Collection<Long> values) {
        long max = Long.MIN_VALUE;
        for (long value : values) {
            max = Math.max(max, value);
        }
        return max == Long.MIN_VALUE ? 0L : max;
    }

    static final class State {
        private final ArrayDeque<RotationSample> rotations = new ArrayDeque<>();
        private final ArrayDeque<Double> yawDeltas = new ArrayDeque<>();
        private final ArrayDeque<Double> pitchDeltas = new ArrayDeque<>();
        private final ArrayDeque<Double> aimErrors = new ArrayDeque<>();
        private final ArrayDeque<Double> trackingYawErrors = new ArrayDeque<>();
        private final ArrayDeque<Double> trackingPitchErrors = new ArrayDeque<>();
        private final ArrayDeque<Double> gcdSteps = new ArrayDeque<>();
        private final ArrayDeque<Integer> aimEBaselineBuckets = new ArrayDeque<>();
        private final ArrayDeque<Integer> aimECombatBuckets = new ArrayDeque<>();
        private final ArrayDeque<Double> aimECombatDeltas = new ArrayDeque<>();
        private final ArrayDeque<Double> attackIntervals = new ArrayDeque<>();
        private final ArrayDeque<Long> attackIntervalWindow = new ArrayDeque<>();
        private final ArrayDeque<Long> attackTimes = new ArrayDeque<>();
        private final ArrayDeque<Long> switchTimes = new ArrayDeque<>();
        private final ArrayDeque<Long> crystalEnginePlaceTimes = new ArrayDeque<>();
        private final ArrayDeque<Long> crystalEngineSupportPlaceTimes = new ArrayDeque<>();
        private final ArrayDeque<Long> crystalEngineAttackTimes = new ArrayDeque<>();
        private final ArrayDeque<Long> crystalEngineKnownAttackTimes = new ArrayDeque<>();
        private final ArrayDeque<Long> crystalEngineUnknownAttackTimes = new ArrayDeque<>();
        private final ArrayDeque<Long> crystalEngineOwnCycleTimes = new ArrayDeque<>();
        private final ArrayDeque<Long> crystalEngineSilentSwitchTimes = new ArrayDeque<>();
        private final ArrayDeque<Long> crystalEngineBasePlaceTimes = new ArrayDeque<>();
        private final ArrayDeque<Long> clickSamples = new ArrayDeque<>();
        private final ArrayDeque<Long> blockToggleTimes = new ArrayDeque<>();
        private final ArrayDeque<Boolean> blockingAttackSamples = new ArrayDeque<>();
        private final ArrayDeque<SprintAction> recentSprintActions = new ArrayDeque<>();
        private final ArrayDeque<BowAimSample> bowAimSamples = new ArrayDeque<>();
        private final ArrayDeque<Integer> peacePingSamples = new ArrayDeque<>();
        private final ArrayDeque<PingSample> combatPingSamples = new ArrayDeque<>();
        private final List<SprintAction> wtapWindowActions = new ArrayList<>();
        private final Map<String, Double> buffers = new HashMap<>();
        private final Map<String, Long> lastFlagByCheck = new HashMap<>();
        private final Map<String, ArrayDeque<Long>> pluginCombatSignalTimes = new HashMap<>();

        private long lastSeenMs;
        private long lastBufferDecayMs;
        private long currentTick;
        private int pingMs;
        private int protocolVersion = -1;
        private double tps = 20.0D;

        private long lastAttackMs;
        private long lastHitMs;
        private long lastSwingMs;
        private long lastRotationPacketMs;
        private long lastBlockUseMs;
        private long lastBlockBreakMs;
        private long lastInventoryMs;
        private long lastDiggingMs;
        private long lastDigStartMs;
        private long lastDigCancelMs;
        private long lastDigFinishMs;
        private long lastDropItemMs;
        private long lastProjectileThrowMs;
        private long lastInteractEntityMs;
        private String lastDiggingAction = "";

        private double lastYaw;
        private double lastPitch;
        private double lastYawDelta;
        private double lastPitchDelta;
        private double learnedStep;
        private int aimEStreak;
        private long lastLargeYawSmallPitchMs;
        private int largeYawSmallPitchStreak;
        private long lastReachEnvelopeMs;
        private int reachEnvelopeStreak;

        private int lastTargetId = Integer.MIN_VALUE;
        private int attacksSinceFlying;
        private boolean queuedAttackForKillAuraA;
        private long queuedAttackMs;
        private long firstOffAngleMs;
        private int offAngleHits;
        private long firstPreHitSnapMs;
        private int preHitSnaps;

        private boolean lastBlockUseSword;
        private boolean lastBlockUseShield;
        private boolean lastBlockUseBlocking;
        private String lastBlockUseItemType = "";
        private int lastBlockUseProtocolVersion = -1;
        private long lastConsumeMs;
        private String lastConsumeItemType = "";

        private int snapTicksRemaining;
        private int snapLockSteps;
        private long snapWindowStartMs;
        private int snapLockCount;
        private int pendingSnapSign;
        private double pendingSnapAbs;
        private long pendingSnapMs;
        private int snapCorrectionLoops;

        private long lastClickDelay;
        private double lastClickDeviation;
        private double lastClickAverage;
        private double lastClickKurtosis;
        private double lastClickVariance;
        private double lastWindowDeviation;
        private double lastLargeWindowDeviation;
        private double lastSampleDeviation;
        private double lastSampleSkewness;
        private double lastSampleKurtosis;
        private double lastCps;
        private long lowCpsSinceMs;

        private boolean wtapTracking;
        private long wtapAttackMs;
        private long wtapBurstWindowMs;
        private int wtapBursts;

        private int lastHeldSlot = -1;
        private long lastHeldChangeMs;
        private int unknownCrystalStreak;
        private int crystalSwitchBursts;

        private TotemPop totemPop;
        private long lastTotemActionMs;
        private String lastTotemActionName = "";
        private boolean totemOpenedInventory;
        private boolean totemSwapClickToOffhand;
        private boolean totemPickupIntoOffhand;
        private boolean totemSwapItemWithOffhand;
        private boolean totemHotbarSwapToOffhand;
        private int totemHeldItemChanges;
        private int totemWindowClicks;

        private long bowDrawStartMs;
        private long bowLastEvaluateMs;
        private int bowAimStreak;

        private boolean backtrackInCombat;
        private long backtrackLastAttackMs;
        private long backtrackLastEvaluateMs;
        private int backtrackBaselinePing;
        private double backtrackScore = 50.0D;

        private int recordPluginCombatSignal(String key, long now) {
            ArrayDeque<Long> times = pluginCombatSignalTimes.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            times.addLast(now);
            while (!times.isEmpty() && now - times.peekFirst() > 4_000L) {
                times.removeFirst();
            }
            return times.size();
        }
    }

    private record RotationSample(long timeMs, double yaw, double pitch, double yawDelta, double pitchDelta) {
    }

    private record SprintAction(long timeMs, String action) {
    }

    private record TotemPop(long timeMs, long tick) {
    }

    private record BowAimSample(long timeMs,
                                int targetId,
                                double distance,
                                double targetSpeed,
                                double expectedYaw,
                                double expectedPitch,
                                double playerYaw,
                                double playerPitch,
                                double normalizedError,
                                boolean wouldHit) {
    }

    private record PingSample(long timeMs, int ping, int deltaFromBaseline, boolean attackSample) {
    }

    private record DominantBucket(double ratio) {
    }

    private record BucketProfile(double step, double confidence) {
    }
}
