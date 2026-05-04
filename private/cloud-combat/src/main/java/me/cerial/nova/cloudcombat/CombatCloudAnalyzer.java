package me.cerial.nova.cloudcombat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.cerial.nova.cloudcombat.engine.CombatEngineVerdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class CombatCloudAnalyzer {
    private static final long FLAG_COOLDOWN_MS = 175L;
    private static final long PLAYER_IDLE_RESET_MS = 20_000L;
    private static final long IMPORTED_ENGINE_VERDICT_MAX_AGE_MS = 12_000L;
    private static final long SUSTAINED_EVIDENCE_MAX_AGE_MS = 12_000L;
    private static final long SUSTAINED_PACKET_COMBAT_FRESH_AGE_MS = 30_000L;
    private static final long SUSTAINED_PACKET_COMBAT_MAX_AGE_MS = 90_000L;
    private static final long SUSTAINED_VERDICT_INTERVAL_MS = 500L;
    private static final long ONGOING_COMBAT_ACTIVITY_MS = 1_750L;

    private final MxCombatCheckSuite mxChecks = new MxCombatCheckSuite();
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private volatile Trace lastTrace = Trace.empty();

    CombatCloudVerdict accept(JsonObject event) {
        return accept(event, true);
    }

    CombatCloudVerdict accept(JsonObject event, boolean traceEnabled) {
        lastTrace = Trace.empty();
        UUID playerId = uuid(event, "playerId");
        if (playerId == null) {
            return null;
        }
        long now = longValue(event, "time", System.currentTimeMillis());
        String eventName = string(event, "event");
        if ("player_disconnect".equals(eventName) || "player_reset".equals(eventName)) {
            states.remove(playerId);
            return null;
        }
        PlayerState state = states.computeIfAbsent(playerId, PlayerState::new);
        state.resetIfIdle(now);
        boolean importedEngineEvent = state.record(event, now);
        List<CombatCheckFinding> novaFindings = List.of();
        List<CombatCheckFinding> mxFindings = mxChecks.accept(playerId, state.mxState, event, now);
        String mxStateSummary = traceEnabled ? state.mxState.debugSummary(now) : "";
        CombatEngineVerdict engineVerdict = state.currentEngineVerdict(now);
        CombatEngineVerdict directEngineVerdict = importedEngineEvent ? engineVerdict : null;
        List<CombatCheckFinding> checkFindings = new ArrayList<>(novaFindings);
        List<CombatCheckFinding> selectedMxFindings = traceEnabled ? new ArrayList<>(mxFindings.size()) : List.of();
        List<CombatCheckFinding> filteredFindings = traceEnabled ? new ArrayList<>() : List.of();
        for (CombatCheckFinding finding : mxFindings) {
            if (includeMxFinding(finding, engineVerdict, novaFindings)) {
                if (traceEnabled) {
                    selectedMxFindings.add(finding);
                }
                checkFindings.add(finding);
            } else if (traceEnabled && finding != null) {
                filteredFindings.add(finding);
            }
        }

        CombatCloudVerdict cloudVerdict = strongest(playerId, directEngineVerdict, checkFindings);
        state.decaySustainedEvidence(now);
        if (cloudVerdict != null && cloudVerdict.flag()) {
            if (eligibleForSustained(cloudVerdict)) {
                state.recordDirectVerdict(now, cloudVerdict);
            }
            if (now - state.lastFlagMs < FLAG_COOLDOWN_MS) {
                lastTrace = new Trace(mxFindings, selectedMxFindings, filteredFindings, cloudVerdict, null, "cooldown", mxStateSummary);
                return null;
            }
            state.lastFlagMs = now;
            lastTrace = new Trace(mxFindings, selectedMxFindings, filteredFindings, cloudVerdict, null, "direct", mxStateSummary);
            return cloudVerdict;
        }

        CombatCloudVerdict sustainedVerdict = state.sustainedVerdict(now);
        if (sustainedVerdict == null) {
            lastTrace = new Trace(mxFindings, selectedMxFindings, filteredFindings, cloudVerdict, null, "none", mxStateSummary);
            return null;
        }
        if (now - state.lastFlagMs < FLAG_COOLDOWN_MS) {
            lastTrace = new Trace(mxFindings, selectedMxFindings, filteredFindings, cloudVerdict, sustainedVerdict, "cooldown", mxStateSummary);
            return null;
        }
        state.lastFlagMs = now;
        lastTrace = new Trace(mxFindings, selectedMxFindings, filteredFindings, cloudVerdict, sustainedVerdict, "sustained", mxStateSummary);
        return sustainedVerdict;
    }

    Trace lastTrace() {
        return lastTrace;
    }

    void resetPlayer(UUID playerId) {
        if (playerId != null) {
            states.remove(playerId);
        }
    }

    private static CombatCloudVerdict strongest(UUID playerId,
                                                CombatEngineVerdict engineVerdict,
                                                List<CombatCheckFinding> findings) {
        CombatCloudVerdict strongest = null;
        if (engineVerdict != null && engineVerdict.shouldFlag()) {
            CombatCloudVerdict cloudEngineVerdict = toEngineCloudVerdict(playerId, engineVerdict);
            if (cloudEngineVerdict != null) {
                strongest = cloudEngineVerdict;
            }
        }
        if (findings != null) {
            for (CombatCheckFinding finding : findings) {
                if (finding == null) {
                    continue;
                }
                CombatCloudVerdict verdict = toCloudVerdict(playerId, finding);
                if (verdict != null && (strongest == null || verdict.score() > strongest.score())) {
                    strongest = verdict;
                }
            }
        }
        return strongest;
    }

    private static CombatCloudVerdict toEngineCloudVerdict(UUID playerId, CombatEngineVerdict engineVerdict) {
        if (engineVerdict == null || !engineVerdict.shouldFlag()) {
            return null;
        }
        double score = engineVerdict.getScore();
        double confidence = engineVerdict.getConfidence();
        int vl = engineViolationPoints(score, confidence);
        if (vl <= 0) {
            return null;
        }

        List<String> topSignals = new ArrayList<>();
        topSignals.add("CombatEngine-B");
        if (engineVerdict.getTopSignals() != null) {
            topSignals.addAll(engineVerdict.getTopSignals());
        }
        return new CombatCloudVerdict(
                playerId,
                score,
                confidence,
                true,
                vl,
                "CombatEngine-B " + engineVerdict.getReasonSummary(),
                topSignals
        );
    }

    private static int engineViolationPoints(double score, double confidence) {
        if (score >= 115.0D && confidence >= 0.88D) {
            return 10;
        }
        if (score >= 95.0D && confidence >= 0.86D) {
            return 8;
        }
        if (score >= 84.0D && confidence >= 0.84D) {
            return 5;
        }
        if (score >= 76.0D && confidence >= 0.86D) {
            return 3;
        }
        return 0;
    }

    private static CombatCloudVerdict toCloudVerdict(UUID playerId, CombatCheckFinding finding) {
        if (finding == null) {
            return null;
        }
        if (isMxFinding(finding)) {
            return new CombatCloudVerdict(
                    playerId,
                    finding.score(),
                    finding.confidence(),
                    true,
                    finding.violationPoints(),
                    finding.details(),
                    finding.topSignals()
            );
        }

        return new CombatCloudVerdict(
                playerId,
                Math.min(finding.score(), novaSupportScoreCap(finding)),
                Math.min(finding.confidence(), novaSupportConfidenceCap(finding)),
                true,
                Math.min(finding.violationPoints(), novaSupportVlCap(finding)),
                finding.details(),
                finding.topSignals()
        );
    }

    private static boolean isMxFinding(CombatCheckFinding finding) {
        return finding != null && finding.checkId() != null && finding.checkId().startsWith("MX-");
    }

    private static double novaSupportScoreCap(CombatCheckFinding finding) {
        String id = findingId(finding);
        String summary = findingSummary(finding);
        if ("CombatTelemetry".equals(id) || id.endsWith("-telemetry") || summary.contains("plugin combat signal")) {
            return 64.0D;
        }
        if (id.startsWith("Backtrack")) {
            return summary.contains("final=true") ? 84.0D : 72.0D;
        }
        if (id.startsWith("AutoClicker")) {
            return 78.0D;
        }
        if (id.startsWith("Aim")) {
            return 84.0D;
        }
        if (id.startsWith("Reach") || id.startsWith("HitBox") || id.startsWith("ContainerReach")) {
            return 90.0D;
        }
        if (id.startsWith("KillAura") || id.startsWith("AutoBlock") || id.startsWith("BowAim")) {
            return 88.0D;
        }
        if (id.startsWith("CrystalAura") || id.startsWith("CrystalEngine")) {
            return 86.0D;
        }
        return 82.0D;
    }

    private static double novaSupportConfidenceCap(CombatCheckFinding finding) {
        String id = findingId(finding);
        String summary = findingSummary(finding);
        if ("CombatTelemetry".equals(id) || id.endsWith("-telemetry") || summary.contains("plugin combat signal")) {
            return 0.70D;
        }
        if (id.startsWith("Backtrack")) {
            return summary.contains("final=true") ? 0.78D : 0.70D;
        }
        if (id.startsWith("AutoClicker")) {
            return 0.76D;
        }
        if (id.startsWith("Aim")) {
            return 0.82D;
        }
        if (id.startsWith("Reach") || id.startsWith("HitBox") || id.startsWith("ContainerReach")) {
            return 0.86D;
        }
        if (id.startsWith("KillAura") || id.startsWith("AutoBlock") || id.startsWith("BowAim")) {
            return 0.84D;
        }
        return 0.80D;
    }

    private static int novaSupportVlCap(CombatCheckFinding finding) {
        String id = findingId(finding);
        String summary = findingSummary(finding);
        if ("CombatTelemetry".equals(id) || id.endsWith("-telemetry") || summary.contains("plugin combat signal")) {
            return 2;
        }
        if (id.startsWith("Backtrack")) {
            return summary.contains("final=true") ? 4 : 2;
        }
        if (id.startsWith("AutoClicker")) {
            return 3;
        }
        if (id.startsWith("Reach") || id.startsWith("HitBox") || id.startsWith("ContainerReach")) {
            return 5;
        }
        if (id.startsWith("KillAura") || id.startsWith("AutoBlock") || id.startsWith("BowAim")
                || id.startsWith("Aim") || id.startsWith("CrystalAura") || id.startsWith("CrystalEngine")) {
            return 4;
        }
        return 3;
    }

    private static String findingId(CombatCheckFinding finding) {
        return finding == null || finding.checkId() == null ? "" : finding.checkId();
    }

    private static String findingSummary(CombatCheckFinding finding) {
        return finding == null || finding.details() == null ? "" : finding.details();
    }

    private static boolean eligibleForSustained(CombatCloudVerdict verdict) {
        if (verdict == null || !verdict.flag() || verdict.confidence() < 0.80D) {
            return false;
        }
        List<String> signals = verdict.topSignals();
        if (signals != null) {
            for (String signal : signals) {
                if (signal == null) {
                    continue;
                }
                if (signal.startsWith("MX-")) {
                    if (signal.startsWith("MX-ML-RNN")) {
                        return false;
                    }
                    if (signal.startsWith("MX-ML")) {
                        return verdict.score() >= 140.0D
                                && verdict.confidence() >= 0.90D
                                && verdict.violationPoints() >= 8;
                    }
                    return signal.startsWith("MX-AutoClicker")
                            && verdict.score() >= 90.0D
                            && verdict.confidence() >= 0.86D;
                }
                if (signal.startsWith("Aim-B")
                        || signal.startsWith("Aim-C")
                        || signal.startsWith("Aim-F")
                        || signal.startsWith("KillAura-C")) {
                    return false;
                }
            }
            if (signals.contains("CombatEngine-A")) {
                String summary = verdict.summary();
                int hits = metricInt(summary, "hit=", Integer.MAX_VALUE);
                double hitRatio = metricDouble(summary, "hr=", 1.0D);
                if (hits <= 3 && hitRatio <= 0.35D) {
                    return false;
                }
                return verdict.score() >= 90.0D && verdict.confidence() >= 0.90D;
            }
        }
        return false;
    }

    private static boolean includeMxFinding(CombatCheckFinding finding,
                                            CombatEngineVerdict engineVerdict,
                                            List<CombatCheckFinding> novaFindings) {
        if (finding == null || finding.checkId() == null || !finding.checkId().startsWith("MX-")) {
            return finding != null;
        }
        if (mxStandaloneFinding(finding)) {
            if (engineVerdict != null
                    && engineVerdict.isLegitCompatibilityWindow()
                    && finding.score() < 180.0D) {
                return false;
            }
            return finding.score() >= 80.0D && finding.confidence() >= 0.82D;
        }
        if (engineVerdict != null && engineVerdict.shouldFlag()
                && engineViolationPoints(engineVerdict.getScore(), engineVerdict.getConfidence()) > 0) {
            return true;
        }
        if (novaFindings == null) {
            return false;
        }
        for (CombatCheckFinding novaFinding : novaFindings) {
            if (novaFinding != null && novaFinding.score() >= 78.0D && novaFinding.confidence() >= 0.78D) {
                return true;
            }
        }
        return false;
    }

    private static boolean mxStandaloneFinding(CombatCheckFinding finding) {
        String checkId = finding.checkId();
        return checkId.startsWith("MX-ML")
                || checkId.startsWith("MX-AutoClicker");
    }

    private static int metricInt(String summary, String key, int fallback) {
        double value = metricDouble(summary, key, Double.NaN);
        return Double.isFinite(value) ? (int) Math.round(value) : fallback;
    }

    private static double metricDouble(String summary, String key, double fallback) {
        if (summary == null || key == null || key.isEmpty()) {
            return fallback;
        }
        int start = summary.indexOf(key);
        if (start < 0) {
            return fallback;
        }
        start += key.length();
        int end = start;
        while (end < summary.length()) {
            char c = summary.charAt(end);
            if ((c >= '0' && c <= '9') || c == '-' || c == '.' || c == '+') {
                end++;
                continue;
            }
            break;
        }
        if (end <= start) {
            return fallback;
        }
        try {
            return Double.parseDouble(summary.substring(start, end));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static UUID uuid(JsonObject object, String key) {
        try {
            return object.has(key) ? UUID.fromString(object.get(key).getAsString()) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : fallback;
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

    private static List<String> stringList(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return List.of();
        }
        JsonArray array = object.getAsJsonArray(key);
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            String value = element.getAsString();
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            values.add(value.trim());
            if (values.size() >= 8) {
                break;
            }
        }
        return values;
    }

    record Trace(List<CombatCheckFinding> mxFindings,
                 List<CombatCheckFinding> selectedFindings,
                 List<CombatCheckFinding> filteredFindings,
                 CombatCloudVerdict directVerdict,
                 CombatCloudVerdict sustainedVerdict,
                 String action,
                 String mxStateSummary) {
        static Trace empty() {
            return new Trace(List.of(), List.of(), List.of(), null, null, "empty", "");
        }

        int rawCount() {
            return mxFindings == null ? 0 : mxFindings.size();
        }

        int selectedCount() {
            return selectedFindings == null ? 0 : selectedFindings.size();
        }

        int filteredCount() {
            return filteredFindings == null ? 0 : filteredFindings.size();
        }

        String rawSummary() {
            return findingSummary(mxFindings);
        }

        String selectedSummary() {
            return findingSummary(selectedFindings);
        }

        String filteredSummary() {
            return findingSummary(filteredFindings);
        }

        public String mxStateSummary() {
            return mxStateSummary == null ? "" : mxStateSummary;
        }

        private static String findingSummary(List<CombatCheckFinding> findings) {
            if (findings == null || findings.isEmpty()) {
                return "none";
            }
            StringBuilder builder = new StringBuilder();
            int added = 0;
            for (CombatCheckFinding finding : findings) {
                if (finding == null) {
                    continue;
                }
                if (added >= 3) {
                    builder.append(",...");
                    break;
                }
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(finding.checkId())
                        .append('/')
                        .append(String.format(java.util.Locale.US, "%.1f", finding.score()))
                        .append('/')
                        .append(String.format(java.util.Locale.US, "%.2f", finding.confidence()));
                added++;
            }
            return builder.length() == 0 ? "none" : builder.toString();
        }
    }

    private static final class PlayerState {
        private final UUID playerId;
        private MxCombatCheckSuite.State mxState = new MxCombatCheckSuite.State();
        private CombatEngineVerdict importedEngineVerdict;
        private long importedEngineVerdictReceivedMs;
        private long lastFlagMs;
        private long lastEventMs;
        private long lastSustainedDecayMs;
        private long lastDirectVerdictMs;
        private long lastSustainedVerdictMs;
        private long lastCombatEventMs;
        private long lastAttackEventMs;
        private long lastHitEventMs;
        private long lastSwingEventMs;
        private long lastTrackingAimMs;
        private int attacksSinceDirectVerdict;
        private int combatSamplesSinceDirectVerdict;
        private double sustainedEvidence;
        private CombatCloudVerdict lastDirectVerdict;
        private int pingMs;
        private double tps = 20.0D;

        private PlayerState(UUID playerId) {
            this.playerId = playerId;
        }

        private void resetIfIdle(long now) {
            if (lastEventMs > 0L && now - lastEventMs >= PLAYER_IDLE_RESET_MS) {
                reset();
            }
            lastEventMs = now;
        }

        private void reset() {
            this.mxState = new MxCombatCheckSuite.State();
            this.importedEngineVerdict = null;
            this.importedEngineVerdictReceivedMs = 0L;
            this.lastFlagMs = 0L;
            this.lastSustainedDecayMs = 0L;
            this.lastDirectVerdictMs = 0L;
            this.lastSustainedVerdictMs = 0L;
            this.lastCombatEventMs = 0L;
            this.lastAttackEventMs = 0L;
            this.lastHitEventMs = 0L;
            this.lastSwingEventMs = 0L;
            this.lastTrackingAimMs = 0L;
            this.attacksSinceDirectVerdict = 0;
            this.combatSamplesSinceDirectVerdict = 0;
            this.sustainedEvidence = 0.0D;
            this.lastDirectVerdict = null;
        }

        private void decaySustainedEvidence(long now) {
            if (lastSustainedDecayMs <= 0L) {
                lastSustainedDecayMs = now;
                return;
            }
            long elapsed = Math.max(0L, now - lastSustainedDecayMs);
            if (elapsed < 50L) {
                return;
            }
            double decayPerSecond = ongoingDirectCombat(now) ? 2.5D : combatActive(now) ? 5.5D : 30.0D;
            sustainedEvidence = Math.max(0.0D, sustainedEvidence - ((elapsed / 1000.0D) * decayPerSecond));
            if (sustainedEvidence <= 1.0E-4D) {
                lastDirectVerdict = null;
            }
            lastSustainedDecayMs = now;
        }

        private void recordDirectVerdict(long now, CombatCloudVerdict verdict) {
            double confidence = verdict == null ? 0.0D : verdict.confidence();
            double points = verdict == null ? 0.0D : verdict.violationPoints();
            double gain = 10.0D + Math.min(10.0D, points) + (confidence * 10.0D);
            sustainedEvidence = Math.min(100.0D, sustainedEvidence + gain);
            lastDirectVerdictMs = now;
            lastDirectVerdict = verdict;
            attacksSinceDirectVerdict = 0;
            combatSamplesSinceDirectVerdict = 0;
        }

        private CombatCloudVerdict sustainedVerdict(long now) {
            boolean ongoingDirectCombat = ongoingDirectCombat(now);
            double requiredEvidence = ongoingDirectCombat ? 35.0D : 55.0D;
            long evidenceMaxAge = ongoingDirectCombat ? SUSTAINED_PACKET_COMBAT_MAX_AGE_MS : SUSTAINED_EVIDENCE_MAX_AGE_MS;
            if (lastDirectVerdict == null
                    || sustainedEvidence < requiredEvidence
                    || (!combatActive(now) && !ongoingDirectCombat)
                    || now - lastDirectVerdictMs > evidenceMaxAge
                    || now - lastSustainedVerdictMs < SUSTAINED_VERDICT_INTERVAL_MS) {
                return null;
            }
            lastSustainedVerdictMs = now;
            sustainedEvidence = Math.max(0.0D, sustainedEvidence - (ongoingDirectCombat ? 2.0D : 6.0D));
            List<String> signals = new ArrayList<>();
            signals.add("CombatCloud-Sustained");
            if (lastDirectVerdict.topSignals() != null) {
                signals.addAll(lastDirectVerdict.topSignals());
            }
            return new CombatCloudVerdict(
                    playerId,
                    Math.max(72.0D, Math.min(100.0D, lastDirectVerdict.score() * 0.88D)),
                    Math.max(0.62D, Math.min(0.84D, lastDirectVerdict.confidence() * 0.92D)),
                    true,
                    Math.max(4, Math.min(8, lastDirectVerdict.violationPoints() / 2)),
                    "sustained combat evidence after " + lastDirectVerdict.summary(),
                    signals
            );
        }

        private boolean combatActive(long now) {
            return lastCombatEventMs > 0L && now - lastCombatEventMs <= 1_200L;
        }

        private boolean ongoingDirectCombat(long now) {
            long directAge = now - lastDirectVerdictMs;
            if (!strongDirectVerdict() || directAge > SUSTAINED_PACKET_COMBAT_MAX_AGE_MS) {
                return false;
            }
            long lastPacketCombat = Math.max(Math.max(lastAttackEventMs, lastHitEventMs), Math.max(lastSwingEventMs, lastTrackingAimMs));
            if (lastPacketCombat <= 0L || now - lastPacketCombat > ONGOING_COMBAT_ACTIVITY_MS) {
                return false;
            }
            if (directAge > SUSTAINED_PACKET_COMBAT_FRESH_AGE_MS && !matureOngoingDirectCombat()) {
                return false;
            }
            return attacksSinceDirectVerdict >= 2
                    || (attacksSinceDirectVerdict >= 1 && combatSamplesSinceDirectVerdict >= 6);
        }

        private boolean matureOngoingDirectCombat() {
            return lastDirectVerdict != null
                    && lastDirectVerdict.score() >= 130.0D
                    && attacksSinceDirectVerdict >= 4
                    && combatSamplesSinceDirectVerdict >= 24;
        }

        private boolean strongDirectVerdict() {
            return lastDirectVerdict != null
                    && lastDirectVerdict.flag()
                    && lastDirectVerdict.confidence() >= 0.90D
                    && lastDirectVerdict.score() >= 90.0D;
        }

        private void markCombatEvent(long now, boolean attack, boolean hit, boolean swing, boolean trackingAim) {
            lastCombatEventMs = now;
            if (attack) {
                lastAttackEventMs = now;
            }
            if (hit) {
                lastHitEventMs = now;
            }
            if (swing) {
                lastSwingEventMs = now;
            }
            if (trackingAim) {
                lastTrackingAimMs = now;
            }
            if (lastDirectVerdict == null || now - lastDirectVerdictMs > SUSTAINED_PACKET_COMBAT_MAX_AGE_MS) {
                return;
            }
            combatSamplesSinceDirectVerdict++;
            if (attack) {
                attacksSinceDirectVerdict++;
            }
            if (!strongDirectVerdict()) {
                return;
            }
            double gain = attack ? 3.0D : hit ? 1.5D : trackingAim ? 1.0D : 0.75D;
            if (attack || attacksSinceDirectVerdict > 0) {
                sustainedEvidence = Math.min(90.0D, sustainedEvidence + gain);
            }
        }

        private boolean combatCheckDelta(JsonObject event) {
            String family = string(event, "family");
            return "aim".equals(family)
                    || "killaura".equals(family)
                    || "combat".equals(family)
                    || "reach".equals(family)
                    || "hitbox".equals(family)
                    || "autoclicker".equals(family);
        }

        private boolean record(JsonObject event, long now) {
            this.pingMs = intValue(event, "ping", pingMs);
            this.tps = doubleValue(event, "tps", tps);
            boolean importedEngineEvent = recordImportedEngineVerdict(event, now);

            String eventName = string(event, "event");
            if ("attack".equals(eventName)) {
                markCombatEvent(now, true, false, false, false);
            } else if ("hit".equals(eventName)) {
                markCombatEvent(now, false, true, false, false);
            } else if ("tracking_aim".equals(eventName)) {
                markCombatEvent(now, false, false, false, true);
            } else if ("animation".equals(eventName) || "swing".equals(eventName)) {
                markCombatEvent(now, false, false, true, false);
            } else if ("check_delta".equals(eventName)) {
                int delta = intValue(event, "delta", 0);
                if (delta > 0) {
                    if (combatCheckDelta(event)) {
                        markCombatEvent(now, false, false, false, false);
                    }
                }
            }
            return importedEngineEvent;
        }

        private CombatEngineVerdict currentEngineVerdict(long now) {
            if (importedEngineVerdict == null || importedEngineVerdictReceivedMs <= 0L) {
                return null;
            }
            if (now - importedEngineVerdictReceivedMs > IMPORTED_ENGINE_VERDICT_MAX_AGE_MS) {
                importedEngineVerdict = null;
                importedEngineVerdictReceivedMs = 0L;
                return null;
            }
            return importedEngineVerdict;
        }

        private boolean recordImportedEngineVerdict(JsonObject event, long now) {
            double score = doubleValue(event, "localEngineScore", Double.NaN);
            double confidence = doubleValue(event, "localEngineConfidence", Double.NaN);
            if (!Double.isFinite(score) || !Double.isFinite(confidence)) {
                return false;
            }

            boolean flag = booleanValue(event, "localEngineFlag", false);
            boolean legitCompatibility = booleanValue(event, "localEngineLegitCompatibility", false);
            String summary = string(event, "localEngineSummary");
            List<String> topSignals = stringList(event, "localEngineTopSignals");
            long timestamp = longValue(event, "localEngineTimestamp", now);
            importedEngineVerdict = new CombatEngineVerdict(
                    score,
                    confidence,
                    summary,
                    topSignals,
                    flag,
                    legitCompatibility,
                    timestamp
            );
            importedEngineVerdictReceivedMs = now;
            return true;
        }
    }
}
