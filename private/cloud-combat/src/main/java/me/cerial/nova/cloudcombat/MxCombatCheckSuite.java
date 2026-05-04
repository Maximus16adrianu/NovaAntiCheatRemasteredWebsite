package me.cerial.nova.cloudcombat;

import com.google.gson.JsonObject;
import kireiko.dev.millennium.math.Statistics;
import kireiko.dev.millennium.ml.ClientML;
import kireiko.dev.millennium.ml.FactoryML;
import kireiko.dev.millennium.ml.data.ObjectML;
import kireiko.dev.millennium.ml.data.ResultML;
import kireiko.dev.millennium.ml.data.module.FlagType;
import kireiko.dev.millennium.ml.data.module.ModuleML;
import kireiko.dev.millennium.ml.data.module.ModuleResultML;
import kireiko.dev.millennium.ml.logic.Millennium;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class MxCombatCheckSuite {
    private static final long AIM_AFTER_ATTACK_MS = 3500L;
    private static final long CLICK_AFTER_ATTACK_MS = 7000L;
    private static final long CLICK_AFTER_MOVE_MS = 500L;
    private static final int RNN_SAMPLE_SIZE = 150;
    private static final int LEGACY_ML_SAMPLE_SIZE = 600;
    private static final double RNN_LOCAL_ASSIST_MIN_PROBABILITY = 0.52D;
    private static final double RNN_LOCAL_ASSIST_STRONG_PROBABILITY = 0.48D;
    private static final double RNN_LOCAL_ASSIST_MIN_SCORE = 82.0D;
    private static final double RNN_LOCAL_ASSIST_STRONG_SCORE = 125.0D;
    private static final double RNN_LOCAL_ASSIST_MIN_CONFIDENCE = 0.74D;
    private static final long RNN_CONFIRM_WINDOW_MS = 12_000L;
    private static final double RNN_CONFIRM_THRESHOLD = 2.0D;
    private static final double RNN_IMMEDIATE_PROBABILITY = 0.965D;
    private static final int RNN_IMMEDIATE_PRIORITY = 24;
    private static final double RNN_LOCAL_FLAG_MIN_SCORE = 76.0D;
    private static final double RNN_LOCAL_FLAG_MIN_CONFIDENCE = 0.80D;
    private static final int ML_WORKERS = Math.max(1, intSetting(
            "nova.combatcloud.mxWorkers",
            "NOVA_COMBAT_CLOUD_MX_WORKERS",
            Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2))
    ));
    private static final int ML_QUEUE_CAPACITY = Math.max(16, intSetting(
            "nova.combatcloud.mxQueue",
            "NOVA_COMBAT_CLOUD_MX_QUEUE",
            128
    ));

    private static final AtomicBoolean MODELS_LOADED = new AtomicBoolean();
    private static final AtomicBoolean MODELS_AVAILABLE = new AtomicBoolean();
    private static final AtomicInteger ML_THREAD_COUNTER = new AtomicInteger();
    private static final ThreadPoolExecutor ML_EXECUTOR = new ThreadPoolExecutor(
            ML_WORKERS,
            ML_WORKERS,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(ML_QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "nova-combat-mx-ml-" + ML_THREAD_COUNTER.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
    );

    MxCombatCheckSuite() {
        ensureModels();
    }

    private static int intSetting(String property, String env, int fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    List<CombatCheckFinding> accept(UUID playerId, State state, JsonObject event, long now) {
        state.lastSeenMs = now;
        state.pingMs = intValue(event, "ping", state.pingMs);
        state.tps = doubleValue(event, "tps", state.tps);
        if (event.has("localEngineScore")) {
            state.localScore = doubleValue(event, "localEngineScore", state.localScore);
        }
        if (event.has("localEngineConfidence")) {
            state.localConfidence = doubleValue(event, "localEngineConfidence", state.localConfidence);
        }
        if (event.has("localEngineFlag")) {
            state.localFlag = booleanValue(event, "localEngineFlag", state.localFlag);
        }

        String eventName = string(event, "event");
        List<CombatCheckFinding> findings = new ArrayList<>(3);
        drainPendingMlFindings(state, findings);
        switch (eventName) {
            case "attack" -> {
                state.lastAttackMs = now;
                state.clickCollectionEnabled = true;
            }
            case "hit" -> state.lastHitMs = now;
            case "rotation_packet", "movement_tick" -> state.lastMoveMs = now;
            case "rotation_delta" -> handleRotation(playerId, state, event, now, findings);
            case "swing", "animation" -> handleSwing(playerId, state, now, findings);
            case "digging", "block_use", "inventory" -> {
                state.clickCollectionEnabled = false;
                state.clickDelays.clear();
            }
            default -> {
            }
        }
        return findings;
    }

    private void handleRotation(UUID playerId, State state, JsonObject event, long now, List<CombatCheckFinding> findings) {
        double yaw = doubleValue(event, "yaw", state.lastYaw);
        double pitch = doubleValue(event, "pitch", state.lastPitch);
        double yawDelta = doubleValue(event, "yawDelta", 0.0D);
        double pitchDelta = doubleValue(event, "pitchDelta", 0.0D);
        double absYawDelta = doubleValue(event, "absYawDelta", Math.abs(yawDelta));
        double absPitchDelta = doubleValue(event, "absPitchDelta", Math.abs(pitchDelta));
        if (absYawDelta <= 0.0D && absPitchDelta <= 0.0D) {
            return;
        }
        state.lastMoveMs = now;
        state.lastYaw = yaw;
        state.lastPitch = pitch;

        if (!recentCombat(state, now)) {
            clearAimWindows(state);
            return;
        }

        RotationSample sample = new RotationSample(now, yaw, pitch, absYawDelta, absPitchDelta, absYawDelta, absPitchDelta);
        push(state.statisticsWindow, sample, 25);
        push(state.analysisWindow, sample, 100);
        if (absYawDelta > 1.35D || (absPitchDelta > 1.35D && absYawDelta > 0.32D)) {
            push(state.limitedAnalysisWindow, sample, 100);
        }
        push(state.complexWindow, sample, 10);
        push(state.basicWindow, sample, 10);
        if (absYawDelta > 2.0D && absPitchDelta > 0.2D) {
            push(state.smoothAngleWindow, angle(absYawDelta, absPitchDelta), 20);
        }
        push(state.mlYaw, absYawDelta, LEGACY_ML_SAMPLE_SIZE);
        push(state.mlPitch, absPitchDelta, LEGACY_ML_SAMPLE_SIZE);
        push(state.rnnYaw, absYawDelta, RNN_SAMPLE_SIZE);
        push(state.rnnPitch, absPitchDelta, RNN_SAMPLE_SIZE);

        evaluateStatistics(playerId, state, findings);
        evaluateAnalysis(playerId, state, findings);
        evaluateComplex(playerId, state, findings);
        evaluateBasic(playerId, state, findings);
        evaluateSmooth(playerId, state, findings);
        evaluateMachineLearning(playerId, state, findings);
    }

    private void handleSwing(UUID playerId, State state, long now, List<CombatCheckFinding> findings) {
        if (state.lastSwingMs > 0L) {
            long rawDelay = now - state.lastSwingMs;
            if (rawDelay > 0L && rawDelay < 1250L
                    && state.clickCollectionEnabled
                    && recent(now, state.lastAttackMs, CLICK_AFTER_ATTACK_MS)
                    && recent(now, state.lastMoveMs, CLICK_AFTER_MOVE_MS)) {
                double tickDelay = rawDelay / 50.0D;
                if (tickDelay < 25.0D) {
                    push(state.clickDelays, tickDelay, 110);
                    evaluateAutoClicker(playerId, state, findings);
                }
            } else if (rawDelay >= 1250L) {
                state.clickDelays.clear();
                state.autoClickerEntropyQuery = 0;
            }
        }
        state.lastSwingMs = now;
    }

    private void evaluateAutoClicker(UUID playerId, State state, List<CombatCheckFinding> findings) {
        if (state.clickDelays.size() < 101) {
            return;
        }
        List<Double> samples = new ArrayList<>(state.clickDelays);
        state.clickDelays.clear();
        List<Double> kurtosis = new ArrayList<>();
        List<Double> entropy = new ArrayList<>();
        for (int offset = 0; offset + 20 <= samples.size(); offset += 20) {
            List<Double> window = samples.subList(offset, offset + 20);
            kurtosis.add(Statistics.getKurtosis(window));
            entropy.add(Statistics.getShannonEntropy(window));
        }
        double maxKurtosis = max(kurtosis);
        double averageDelay = mean(samples);
        if (averageDelay > 0.0D && averageDelay <= 4.5D && maxKurtosis < 0.0D
                && buffered(state, "click.kurtosis", 1.0D, 1.6D, 0.25D)) {
            findings.add(finding(playerId, "MX-AutoClicker-A", 88.0D, 0.86D, 8,
                    "MX AutoClicker kurtosis pattern (maxKurtosis=" + fmt(maxKurtosis)
                            + ", samples=" + samples.size() + ")",
                    List.of("MX-AutoClicker-A", "mx-kurtosis")));
            return;
        }

        List<Float> entropyDelta = Statistics.getJiffDelta(entropy, 2);
        if (!entropyDelta.isEmpty() && min(entropyDelta) < 0.04D && max(entropyDelta) < 0.06D) {
            state.autoClickerEntropyQuery++;
            if (state.autoClickerEntropyQuery > 1) {
                findings.add(finding(playerId, "MX-AutoClicker-B", 82.0D, 0.83D, 7,
                        "MX AutoClicker entropy pattern (range=" + fmt(min(entropyDelta))
                                + ".." + fmt(max(entropyDelta)) + ")",
                        List.of("MX-AutoClicker-B", "mx-entropy")));
            }
        } else if (state.autoClickerEntropyQuery > 0) {
            state.autoClickerEntropyQuery--;
        }
    }

    private void evaluateStatistics(UUID playerId, State state, List<CombatCheckFinding> findings) {
        if (state.statisticsWindow.size() < 25) {
            return;
        }
        List<RotationSample> samples = drain(state.statisticsWindow);
        List<Double> yaw = yawDeltas(samples);
        List<Double> pitch = pitchDeltas(samples);

        List<Double> zOutliers = Statistics.getZScoreOutliers(yaw, 2.0D);
        if (zOutliers.size() == 2 && zOutliers.stream().anyMatch(v -> v > 10.0D)
                && zOutliers.stream().anyMatch(v -> v < -10.0D) && max(yaw) < 55.0D
                && buffered(state, "stats.zfactor", 1.0D, 7.0D, 0.35D)) {
            findings.add(finding(playerId, "MX-Aim-Statistics", 74.0D, 0.78D, 5,
                    "MX Aim Statistics z-factor outliers (count=2, maxYaw=" + fmt(max(yaw)) + ")",
                    List.of("MX-Aim-Statistics", "mx-zfactor")));
        }

        List<Float> yawJiff = Statistics.getJiffDelta(yaw, 5);
        List<Float> pitchJiff = Statistics.getJiffDelta(pitch, 5);
        List<Double> omni = new ArrayList<>();
        int infinities = 0;
        int limit = Math.min(yawJiff.size(), pitchJiff.size());
        for (int i = 0; i < limit; i++) {
            double divisor = pitchJiff.get(i);
            double value = divisor == 0.0D ? Double.POSITIVE_INFINITY : yawJiff.get(i) / divisor;
            if (Double.isFinite(value)) {
                omni.add(value);
            } else {
                infinities++;
            }
        }
        double iqr = omni.isEmpty() ? 0.0D : Statistics.getIQR(omni);
        if (iqr > 12.5D && iqr < 96.0D && infinities > 0) {
            double add = iqr > 20.0D ? 1.4D : 0.8D;
            if (buffered(state, "stats.iqr", add, 11.0D, 0.0D)) {
                findings.add(finding(playerId, "MX-Aim-Statistics", 86.0D, 0.84D, 8,
                        "MX Aim Statistics jiff ratio (iqr=" + fmt(iqr) + ", inf=" + infinities + ")",
                        List.of("MX-Aim-Statistics", "mx-jiff-ratio")));
            }
        } else {
            lowerBuffer(state, "stats.iqr", iqr < 7.0D || infinities == 0 ? 5.0D : 3.5D);
        }

        List<Float> yawJiff4 = Statistics.getJiffDelta(yaw, 4);
        int patterns = duplicateScientificPatterns(yawJiff4);
        if (patterns > 2
                && mean(yaw) > 3.0D
                && patterns != 4
                && patterns != 6
                && patterns != 12
                && buffered(state, "stats.patterns", 1.0D, 3.0D, 0.0D)) {
            findings.add(finding(playerId, "MX-Aim-Statistics", 86.0D, 0.84D, 8,
                    "MX Aim Statistics repeated jiff patterns (patterns=" + patterns + ")",
                    List.of("MX-Aim-Statistics", "mx-jiff-pattern")));
        } else {
            lowerBuffer(state, "stats.patterns", 1.0D);
        }
    }

    private void evaluateAnalysis(UUID playerId, State state, List<CombatCheckFinding> findings) {
        if (state.analysisWindow.size() >= 100) {
            List<RotationSample> samples = drain(state.analysisWindow);
            List<Double> deviation = new ArrayList<>();
            double distinct = 0.0D;
            for (int offset = 0; offset + 10 <= samples.size(); offset += 10) {
                List<Double> yaw = yawDeltas(samples.subList(offset, offset + 10));
                deviation.add(Statistics.getStandardDeviation(Statistics.getJiffDelta(yaw, 5)));
                distinct += Statistics.getDistinct(Statistics.getJiffDelta(yaw, 4));
            }
            List<Double> outliers = Statistics.getZScoreOutliers(deviation, 0.5D);
            boolean linear = outliers.isEmpty()
                    || (outliers.size() == 1 && Math.abs(outliers.get(0)) > 10.0D && Math.abs(outliers.get(0)) < 100.0D);
            if (linear) {
                state.analysisLinearQuery++;
                if (state.analysisLinearQuery > 1) {
                    findings.add(finding(playerId, "MX-Aim-Analysis", 90.0D, 0.86D, 8,
                            "MX Aim Analysis linear deviation (outliers=" + outliers.size() + ")",
                            List.of("MX-Aim-Analysis", "mx-linear")));
                }
            } else if (state.analysisLinearQuery > 0) {
                state.analysisLinearQuery--;
            }

            double distinctRank = distinct / 60.0D;
            if (distinctRank < 0.90D && distinctRank > 0.7D && meanAbs(yawDeltas(samples)) > 1.8D
                    && buffered(state, "analysis.rank", distinctRank > 0.8D ? 2.0D : 3.0D, 8.0D, 0.0D)) {
                findings.add(finding(playerId, "MX-Aim-Analysis", 84.0D, 0.83D, 7,
                        "MX Aim Analysis distinct rank (rank=" + fmt(distinctRank) + ")",
                        List.of("MX-Aim-Analysis", "mx-distinct-rank")));
            } else {
                lowerBuffer(state, "analysis.rank", 2.25D);
            }
        }

        if (state.limitedAnalysisWindow.size() >= 100) {
            List<RotationSample> samples = drain(state.limitedAnalysisWindow);
            double distinct = 0.0D;
            int normal = 0;
            for (int offset = 0; offset + 10 <= samples.size(); offset += 10) {
                List<Double> yaw = yawDeltas(samples.subList(offset, offset + 10));
                int d = Statistics.getDistinct(Statistics.getJiffDelta(yaw, 4));
                distinct += d;
                if (d > 6) {
                    normal++;
                }
            }
            double rank = distinct / 60.0D;
            push(state.limitedRankHistory, rank, 10);
            if (state.limitedRankHistory.size() >= 10 && mean(state.limitedRankHistory) < 0.95D && normal < 4
                    && buffered(state, "analysis.limited", 1.0D, 1.8D, 0.2D)) {
                findings.add(finding(playerId, "MX-Aim-Analysis", 88.0D, 0.85D, 8,
                        "MX Aim Analysis limited rank (avg=" + fmt(mean(state.limitedRankHistory))
                                + ", normal=" + normal + ")",
                        List.of("MX-Aim-Analysis", "mx-limited-rank")));
            }
        }
    }

    private void evaluateComplex(UUID playerId, State state, List<CombatCheckFinding> findings) {
        if (state.complexWindow.size() < 10) {
            return;
        }
        List<RotationSample> samples = drain(state.complexWindow);
        List<Double> yaw = yawDeltas(samples);
        List<Double> pitch = pitchDeltas(samples);
        int distinctYaw = Statistics.getDistinct(rounded(yaw, 4));
        if (distinctYaw < 8 && meanAbs(yaw) > 2.5D
                && buffered(state, "complex.distinct", 1.0D, 3.0D, 0.3D)) {
            findings.add(finding(playerId, "MX-Aim-Complex", 82.0D, 0.81D, 7,
                    "MX Aim Complex repeated yaw structure (distinct=" + distinctYaw + ")",
                    List.of("MX-Aim-Complex", "mx-complex-distinct")));
        }

        List<Double> quantizedYaw = quantized(yaw);
        List<Double> quantizedPitch = quantized(pitch);
        double yawVariance = Statistics.getVariance(quantizedYaw);
        double pitchVariance = Statistics.getVariance(quantizedPitch);
        double minVariance = Math.min(yawVariance, pitchVariance);
        double maxVariance = Math.max(yawVariance, pitchVariance);
        if (minVariance < 0.09D && maxVariance > 35.0D && min(quantizedPitch) != 0.0D
                && buffered(state, "complex.randomizer", 1.0D, 3.5D, 0.0D)) {
            findings.add(finding(playerId, "MX-Aim-Complex", 88.0D, 0.86D, 8,
                    "MX Aim Complex randomizer flaw (minVar=" + fmt(minVariance)
                            + ", maxVar=" + fmt(maxVariance) + ")",
                    List.of("MX-Aim-Complex", "mx-randomizer")));
        } else {
            lowerBuffer(state, "complex.randomizer", 0.4D);
        }
    }

    private void evaluateBasic(UUID playerId, State state, List<CombatCheckFinding> findings) {
        if (state.basicWindow.size() < 10) {
            return;
        }
        List<RotationSample> samples = drain(state.basicWindow);
        int constant = 0;
        int machineKnown = 0;
        int exactGrid = 0;
        int snap = 0;
        RotationSample previous = null;
        for (RotationSample sample : samples) {
            double yawAbs = sample.absYawDelta;
            double pitchAbs = sample.absPitchDelta;
            if (previous != null
                    && close(sample.yawDelta, previous.yawDelta, 1.0E-4D)
                    && close(sample.pitchDelta, previous.pitchDelta, 1.0E-4D)
                    && yawAbs > 0.0D) {
                constant++;
            }
            if ((closeGrid(yawAbs, 0.1D) || closeGrid(yawAbs, 0.01D))
                    && yawAbs > 0.0D && yawAbs < 40.0D) {
                exactGrid++;
            }
            if (pitchAbs > 0.0D && pitchAbs < 0.03D && yawAbs > 2.0D) {
                machineKnown++;
            }
            if (yawAbs >= 30.0D && pitchAbs <= 1.2D) {
                snap++;
            }
            previous = sample;
        }
        int signals = 0;
        if (constant >= 3) {
            signals++;
        }
        if (machineKnown >= 3) {
            signals++;
        }
        if (exactGrid >= 6) {
            signals++;
        }
        if (snap >= 2) {
            signals++;
        }
        if (signals >= 2 && meanAbs(yawDeltas(samples)) > 1.0D
                && buffered(state, "basic.machine", 1.0D, 2.0D, 0.25D)) {
            findings.add(finding(playerId, "MX-Aim-Basic", 82.0D + (signals * 3.0D), 0.83D, 8,
                    "MX Aim Basic machine pattern (signals=" + signals + ", constant=" + constant
                            + ", grid=" + exactGrid + ", snap=" + snap + ")",
                    List.of("MX-Aim-Basic", "mx-basic-machine")));
        }
    }

    private void evaluateSmooth(UUID playerId, State state, List<CombatCheckFinding> findings) {
        if (state.smoothAngleWindow.size() < 20) {
            return;
        }
        List<Double> angles = drainDouble(state.smoothAngleWindow);
        List<Float> jiff = Statistics.getJiffDelta(angles, 1);
        int zeroRuns = 0;
        int bestRun = 0;
        for (float value : jiff) {
            if (Math.abs(value) < 1.0E-5D) {
                zeroRuns++;
                bestRun = Math.max(bestRun, zeroRuns);
            } else {
                zeroRuns = 0;
            }
        }
        if (bestRun >= 3 && buffered(state, "smooth.zero", 1.0D, 1.5D, 0.2D)) {
            findings.add(finding(playerId, "MX-Aim-Smooth", 80.0D, 0.82D, 7,
                    "MX Aim Smooth repeated vector angle (run=" + bestRun + ")",
                    List.of("MX-Aim-Smooth", "mx-smooth-angle")));
        }
    }

    private void evaluateMachineLearning(UUID playerId, State state, List<CombatCheckFinding> findings) {
        if (!MODELS_AVAILABLE.get()) {
            return;
        }
        if (!state.rnnJobInFlight && state.rnnYaw.size() >= RNN_SAMPLE_SIZE && state.rnnPitch.size() >= RNN_SAMPLE_SIZE) {
            List<Double> yaw = state.rnnYaw.drainToList();
            List<Double> pitch = state.rnnPitch.drainToList();
            double localScore = state.localScore;
            double localConfidence = state.localConfidence;
            boolean localFlag = state.localFlag;
            state.rnnJobInFlight = true;
            submitMlJob(() -> {
                ModuleResultML result = evaluateModel(7, yaw, pitch);
                boolean emitted = false;
                if (result != null) {
                    CombatCheckFinding finding = null;
                    if (result.getType() != FlagType.NORMAL) {
                        if (shouldEmitRnnMlFinding(state, result, localScore, localConfidence, localFlag,
                                System.currentTimeMillis())) {
                            finding = mlFinding(playerId, "MX-ML-RNN", "RNN", result);
                            emitted = true;
                        }
                    } else if (shouldUseRnnLocalAssist(localScore, localConfidence, localFlag, result)) {
                        finding = rnnLocalAssistFinding(playerId, localScore, localConfidence, result);
                        emitted = true;
                    }
                    if (finding != null) {
                        state.pendingMlFindings.offer(finding);
                    }
                }
                state.lastRnnResult = describeMlResult(result) + (emitted ? "" : "/held");
                state.rnnJobInFlight = false;
            }, () -> state.rnnJobInFlight = false);
        }
        if (!state.legacyJobInFlight && state.mlYaw.size() >= LEGACY_ML_SAMPLE_SIZE && state.mlPitch.size() >= LEGACY_ML_SAMPLE_SIZE) {
            List<Double> yaw = state.mlYaw.drainToList();
            List<Double> pitch = state.mlPitch.drainToList();
            state.legacyJobInFlight = true;
            submitMlJob(() -> {
                ModuleResultML result = strongestLegacyModel(yaw, pitch);
                state.lastLegacyResult = describeMlResult(result);
                if (result != null && result.getType() != FlagType.NORMAL) {
                    state.pendingMlFindings.offer(mlFinding(playerId, "MX-ML-Legacy", "legacy", result));
                }
                state.legacyJobInFlight = false;
            }, () -> state.legacyJobInFlight = false);
        }
    }

    private static void submitMlJob(Runnable job, Runnable rejected) {
        try {
            ML_EXECUTOR.execute(() -> {
                try {
                    job.run();
                } catch (RuntimeException exception) {
                    if (rejected != null) {
                        rejected.run();
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            if (rejected != null) {
                rejected.run();
            }
        }
    }

    private static void drainPendingMlFindings(State state, List<CombatCheckFinding> findings) {
        if (state == null || findings == null) {
            return;
        }
        CombatCheckFinding finding;
        while ((finding = state.pendingMlFindings.poll()) != null) {
            findings.add(finding);
        }
    }

    private static ModuleResultML strongestLegacyModel(List<Double> yaw, List<Double> pitch) {
        ModuleResultML strongest = null;
        for (int i = 0; i < 7; i++) {
            ModuleResultML result = evaluateModel(i, yaw, pitch);
            if (result != null && result.getType() != FlagType.NORMAL) {
                if (strongest == null
                        || result.getType().getLevel() > strongest.getType().getLevel()
                        || (result.getType().getLevel() == strongest.getType().getLevel()
                        && result.getPriority() > strongest.getPriority())) {
                    strongest = result;
                }
            }
        }
        return strongest;
    }

    private static ModuleResultML evaluateModel(int modelIndex, List<Double> yaw, List<Double> pitch) {
        Millennium model = FactoryML.getModel(modelIndex);
        if (model == null || modelIndex < 0 || modelIndex >= ClientML.MODEL_LIST.size()) {
            return null;
        }
        ModuleML module = ClientML.MODEL_LIST.get(modelIndex);
        ResultML result = model.checkData(List.of(
                new ObjectML(yaw),
                new ObjectML(pitch)
        ));
        return module.getResult(result);
    }

    private static boolean shouldEmitRnnMlFinding(State state,
                                                  ModuleResultML result,
                                                  double localScore,
                                                  double localConfidence,
                                                  boolean localFlag,
                                                  long now) {
        if (state == null || result == null || result.getType() == FlagType.NORMAL) {
            return false;
        }
        double probability = probability(result);
        int priority = result.getPriority();
        if (localFlag
                && localScore >= RNN_LOCAL_FLAG_MIN_SCORE
                && localConfidence >= RNN_LOCAL_FLAG_MIN_CONFIDENCE) {
            return true;
        }
        if (probability >= RNN_IMMEDIATE_PROBABILITY && priority >= RNN_IMMEDIATE_PRIORITY) {
            return true;
        }

        double evidence = switch (result.getType()) {
            case SUSPECTED -> 1.0D
                    + (probability >= 0.90D ? 0.35D : 0.0D)
                    + (priority >= 18 ? 0.15D : 0.0D);
            case STRANGE -> 0.65D;
            case UNUSUAL -> 0.35D;
            case NORMAL -> 0.0D;
        };
        if (evidence <= 0.0D) {
            return false;
        }

        synchronized (state) {
            if (state.lastRnnMlDetectionMs <= 0L || now - state.lastRnnMlDetectionMs > RNN_CONFIRM_WINDOW_MS) {
                state.rnnMlEvidence = 0.0D;
            }
            state.lastRnnMlDetectionMs = now;
            state.rnnMlEvidence = Math.min(3.0D, state.rnnMlEvidence + evidence);
            return state.rnnMlEvidence >= RNN_CONFIRM_THRESHOLD;
        }
    }

    private static boolean shouldUseRnnLocalAssist(double localScore,
                                                   double localConfidence,
                                                   boolean localFlag,
                                                   ModuleResultML result) {
        if (result == null || result.getType() != FlagType.NORMAL) {
            return false;
        }
        if (localConfidence < RNN_LOCAL_ASSIST_MIN_CONFIDENCE) {
            return false;
        }
        double probability = probability(result);
        if (localFlag && localScore >= RNN_LOCAL_ASSIST_MIN_SCORE
                && probability >= RNN_LOCAL_ASSIST_MIN_PROBABILITY) {
            return true;
        }
        return localScore >= RNN_LOCAL_ASSIST_STRONG_SCORE
                && probability >= RNN_LOCAL_ASSIST_STRONG_PROBABILITY;
    }

    private static CombatCheckFinding rnnLocalAssistFinding(UUID playerId,
                                                            double localScore,
                                                            double localConfidence,
                                                            ModuleResultML result) {
        double probability = probability(result);
        double localFactor = Math.min(18.0D, Math.max(0.0D, localScore - RNN_LOCAL_ASSIST_MIN_SCORE) * 0.15D);
        double probabilityFactor = Math.max(0.0D, probability - RNN_LOCAL_ASSIST_STRONG_PROBABILITY) * 90.0D;
        double score = Math.min(104.0D, 74.0D + localFactor + probabilityFactor);
        double confidence = Math.min(0.88D, 0.72D + Math.max(0.0D, probability - 0.48D) * 0.45D
                + Math.max(0.0D, localConfidence - 0.74D) * 0.12D);
        String details = "MX RNN assist verdict (probability=" + result.getInfo()
                + ", localScore=" + fmt(localScore)
                + ", localConfidence=" + fmt(localConfidence) + ")";
        return finding(playerId, "MX-ML-RNN", score, confidence, 6, details,
                List.of("MX-ML-RNN", "mx-ml-rnn-assist"));
    }

    private static CombatCheckFinding mlFinding(UUID playerId, String checkId, String modelFamily, ModuleResultML result) {
        FlagType type = result.getType();
        int priority = result.getPriority();
        double score = switch (type) {
            case UNUSUAL -> 62.0D + Math.min(10.0D, priority);
            case STRANGE -> 78.0D + Math.min(18.0D, priority * 1.2D);
            case SUSPECTED -> 94.0D + Math.min(86.0D, priority * 4.0D);
            case NORMAL -> 0.0D;
        };
        double confidence = switch (type) {
            case UNUSUAL -> 0.72D;
            case STRANGE -> 0.84D;
            case SUSPECTED -> Math.min(0.96D, 0.90D + (priority * 0.002D));
            case NORMAL -> 0.0D;
        };
        int points = switch (type) {
            case UNUSUAL -> 4;
            case STRANGE -> 7;
            case SUSPECTED -> Math.max(9, Math.min(14, 8 + priority / 3));
            case NORMAL -> 0;
        };
        String details = "MX " + modelFamily + " ML verdict (" + type.name().toLowerCase(Locale.ROOT)
                + ", priority=" + priority + ", " + result.getInfo() + ")";
        return finding(playerId, checkId, score, confidence, points, details, List.of(checkId, "mx-ml-" + modelFamily));
    }

    private static String describeMlResult(ModuleResultML result) {
        if (result == null) {
            return "-";
        }
        String info = result.getInfo() == null ? "" : result.getInfo();
        return result.getType().name().toLowerCase(Locale.ROOT) + "/" + result.getPriority() + "/" + info;
    }

    private static double probability(ModuleResultML result) {
        if (result == null || result.getInfo() == null) {
            return 0.0D;
        }
        String value = result.getInfo()
                .replace("%", "")
                .replace(',', '.')
                .trim();
        int space = value.lastIndexOf(' ');
        if (space >= 0 && space + 1 < value.length()) {
            value = value.substring(space + 1).trim();
        }
        try {
            double parsed = Double.parseDouble(value);
            return parsed > 1.0D ? parsed / 100.0D : parsed;
        } catch (NumberFormatException ignored) {
            return 0.0D;
        }
    }

    private static CombatCheckFinding finding(UUID playerId,
                                              String checkId,
                                              double score,
                                              double confidence,
                                              int points,
                                              String details,
                                              List<String> topSignals) {
        return new CombatCheckFinding(playerId, checkId, score, confidence, points, details, topSignals);
    }

    private static void ensureModels() {
        if (!MODELS_LOADED.compareAndSet(false, true)) {
            return;
        }
        try {
            ClientML.run();
            MODELS_AVAILABLE.set(FactoryML.getModel(7) != null);
        } catch (RuntimeException error) {
            MODELS_AVAILABLE.set(false);
            System.out.println("[Nova] WARNING: MX combat ML models could not be loaded ("
                    + error.getClass().getSimpleName() + ": " + error.getMessage() + ")");
        }
    }

    private static boolean buffered(State state, String key, double add, double threshold, double missDecay) {
        double value = state.buffers.getOrDefault(key, 0.0D) + add;
        state.buffers.put(key, value);
        if (value >= threshold) {
            state.buffers.put(key, Math.max(0.0D, value - threshold * 0.75D));
            return true;
        }
        if (missDecay > 0.0D) {
            state.buffers.put(key, Math.max(0.0D, value - missDecay));
        }
        return false;
    }

    private static void lowerBuffer(State state, String key, double amount) {
        if (amount <= 0.0D) {
            return;
        }
        state.buffers.put(key, Math.max(0.0D, state.buffers.getOrDefault(key, 0.0D) - amount));
    }

    private static boolean recentCombat(State state, long now) {
        return (recent(now, state.lastAttackMs, AIM_AFTER_ATTACK_MS) || recent(now, state.lastHitMs, AIM_AFTER_ATTACK_MS))
                && state.tps >= 18.0D
                && state.pingMs <= 280;
    }

    private static void clearAimWindows(State state) {
        state.statisticsWindow.clear();
        state.analysisWindow.clear();
        state.limitedAnalysisWindow.clear();
        state.complexWindow.clear();
        state.basicWindow.clear();
        state.smoothAngleWindow.clear();
        state.rnnYaw.clear();
        state.rnnPitch.clear();
        state.mlYaw.clear();
        state.mlPitch.clear();
    }

    private static boolean recent(long now, long previous, long maxAgeMs) {
        return previous > 0L && now - previous <= maxAgeMs;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }

    private static double doubleValue(JsonObject object, String key, double fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : fallback;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static void push(ArrayDeque<Double> deque, double value, int maxSize) {
        deque.addLast(value);
        while (deque.size() > maxSize) {
            deque.pollFirst();
        }
    }

    private static void push(DoubleWindow window, double value, int maxSize) {
        if (window != null) {
            window.add(value);
        }
    }

    private static void push(ArrayDeque<RotationSample> deque, RotationSample value, int maxSize) {
        deque.addLast(value);
        while (deque.size() > maxSize) {
            deque.pollFirst();
        }
    }

    private static List<RotationSample> drain(ArrayDeque<RotationSample> deque) {
        List<RotationSample> values = new ArrayList<>(deque);
        deque.clear();
        return values;
    }

    private static List<Double> drainDouble(ArrayDeque<Double> deque) {
        List<Double> values = new ArrayList<>(deque);
        deque.clear();
        return values;
    }

    private static List<Double> yawDeltas(List<RotationSample> samples) {
        List<Double> values = new ArrayList<>(samples.size());
        for (RotationSample sample : samples) {
            values.add(sample.yawDelta);
        }
        return values;
    }

    private static List<Double> pitchDeltas(List<RotationSample> samples) {
        List<Double> values = new ArrayList<>(samples.size());
        for (RotationSample sample : samples) {
            values.add(sample.pitchDelta);
        }
        return values;
    }

    private static List<Double> rounded(List<Double> values, int scale) {
        double factor = Math.pow(10.0D, scale);
        List<Double> rounded = new ArrayList<>(values.size());
        for (double value : values) {
            rounded.add(Math.round(value * factor) / factor);
        }
        return rounded;
    }

    private static int duplicateScientificPatterns(List<Float> values) {
        Map<String, Integer> counts = new HashMap<>();
        int duplicates = 0;
        for (float value : values) {
            if (value <= 0.0F || !Float.isFinite(value)) {
                continue;
            }
            String key = String.format(Locale.ROOT, "%.2e", value);
            int count = counts.getOrDefault(key, 0) + 1;
            counts.put(key, count);
            if (count == 2) {
                duplicates++;
            }
        }
        return duplicates;
    }

    private static double angle(double yawDelta, double pitchDelta) {
        double angle = Math.toDegrees(Math.atan2(pitchDelta, yawDelta));
        double normalized = Math.abs(angle % 90.0D);
        return normalized < 0.0D ? normalized + 90.0D : normalized;
    }

    private static boolean close(double a, double b, double tolerance) {
        return Math.abs(a - b) <= tolerance;
    }

    private static boolean closeGrid(double value, double grid) {
        if (grid <= 0.0D) {
            return false;
        }
        return Math.abs(value - (Math.round(value / grid) * grid)) <= 1.0E-5D;
    }

    private static List<Double> quantized(List<Double> values) {
        double divisor = Statistics.getGCDValue(0.5D) * 3.0D;
        List<Double> result = new ArrayList<>(values.size());
        for (double value : values) {
            result.add((double) ((int) (value / divisor)));
        }
        return result;
    }

    private static double min(List<? extends Number> values) {
        double min = Double.POSITIVE_INFINITY;
        for (Number value : values) {
            min = Math.min(min, value.doubleValue());
        }
        return Double.isFinite(min) ? min : 0.0D;
    }

    private static double max(List<? extends Number> values) {
        double max = Double.NEGATIVE_INFINITY;
        for (Number value : values) {
            max = Math.max(max, value.doubleValue());
        }
        return Double.isFinite(max) ? max : 0.0D;
    }

    private static double mean(ArrayDeque<Double> values) {
        return mean(new ArrayList<>(values));
    }

    private static double mean(List<? extends Number> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (Number value : values) {
            sum += value.doubleValue();
        }
        return sum / values.size();
    }

    private static double meanAbs(List<? extends Number> values) {
        if (values.isEmpty()) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (Number value : values) {
            sum += Math.abs(value.doubleValue());
        }
        return sum / values.size();
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    static final class State {
        private long lastSeenMs;
        private long lastAttackMs;
        private long lastHitMs;
        private long lastMoveMs;
        private long lastSwingMs;
        private double lastYaw;
        private double lastPitch;
        private int pingMs;
        private double tps = 20.0D;
        private double localScore;
        private double localConfidence;
        private boolean localFlag;
        private boolean clickCollectionEnabled;
        private int autoClickerEntropyQuery;
        private int analysisLinearQuery;
        private volatile boolean rnnJobInFlight;
        private volatile boolean legacyJobInFlight;
        private volatile String lastRnnResult = "-";
        private volatile String lastLegacyResult = "-";
        private long lastRnnMlDetectionMs;
        private double rnnMlEvidence;
        private final Queue<CombatCheckFinding> pendingMlFindings = new ConcurrentLinkedQueue<>();
        private final Map<String, Double> buffers = new HashMap<>();
        private final ArrayDeque<Double> clickDelays = new ArrayDeque<>();
        private final ArrayDeque<RotationSample> statisticsWindow = new ArrayDeque<>();
        private final ArrayDeque<RotationSample> analysisWindow = new ArrayDeque<>();
        private final ArrayDeque<RotationSample> limitedAnalysisWindow = new ArrayDeque<>();
        private final ArrayDeque<RotationSample> complexWindow = new ArrayDeque<>();
        private final ArrayDeque<RotationSample> basicWindow = new ArrayDeque<>();
        private final ArrayDeque<Double> limitedRankHistory = new ArrayDeque<>();
        private final ArrayDeque<Double> smoothAngleWindow = new ArrayDeque<>();
        private final DoubleWindow rnnYaw = new DoubleWindow(RNN_SAMPLE_SIZE);
        private final DoubleWindow rnnPitch = new DoubleWindow(RNN_SAMPLE_SIZE);
        private final DoubleWindow mlYaw = new DoubleWindow(LEGACY_ML_SAMPLE_SIZE);
        private final DoubleWindow mlPitch = new DoubleWindow(LEGACY_ML_SAMPLE_SIZE);

        String debugSummary(long now) {
            return String.format(Locale.ROOT,
                    "attackAgo=%s hitAgo=%s moveAgo=%s swingAgo=%s local=%.2f/%.2f/%s click=%d stats=%d analysis=%d basic=%d complex=%d rnn=%d/%d lastRnn=%s ml=%d/%d lastMl=%s",
                    age(now, lastAttackMs),
                    age(now, lastHitMs),
                    age(now, lastMoveMs),
                    age(now, lastSwingMs),
                    localScore,
                    localConfidence,
                    localFlag,
                    clickDelays.size(),
                    statisticsWindow.size(),
                    analysisWindow.size(),
                    basicWindow.size(),
                    complexWindow.size(),
                    rnnYaw.size(),
                    rnnPitch.size(),
                    lastRnnResult,
                    mlYaw.size(),
                    mlPitch.size(),
                    lastLegacyResult);
        }

        private static String age(long now, long timestamp) {
            if (timestamp <= 0L) {
                return "-";
            }
            return Long.toString(Math.max(0L, now - timestamp));
        }
    }

    private static final class DoubleWindow {
        private final double[] values;
        private int size;

        private DoubleWindow(int capacity) {
            this.values = new double[Math.max(1, capacity)];
        }

        private void add(double value) {
            if (size < values.length) {
                values[size++] = value;
                return;
            }
            System.arraycopy(values, 1, values, 0, values.length - 1);
            values[values.length - 1] = value;
        }

        private int size() {
            return size;
        }

        private void clear() {
            size = 0;
        }

        private List<Double> drainToList() {
            List<Double> copy = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                copy.add(values[index]);
            }
            clear();
            return copy;
        }
    }

    private record RotationSample(long timeMs,
                                  double yaw,
                                  double pitch,
                                  double yawDelta,
                                  double pitchDelta,
                                  double absYawDelta,
                                  double absPitchDelta) {
    }
}
