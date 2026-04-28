package me.cerial.nova.cloudcombat.engine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class KinematicDebugLogger {
    public static volatile String CURRENT_HARDWARE = stringSetting(
            "nova.combatcloud.telemetry.hardware",
            "NOVA_COMBAT_CLOUD_TELEMETRY_HARDWARE",
            "UNKNOWN"
    );

    private static volatile boolean enabled = booleanSetting(
            "nova.combatcloud.telemetry",
            "NOVA_COMBAT_CLOUD_TELEMETRY",
            false
    );
    private static volatile String outputPattern = stringSetting(
            "nova.combatcloud.telemetry.file",
            "NOVA_COMBAT_CLOUD_TELEMETRY_FILE",
            "combat_telemetry.csv"
    );
    private static final String HEADER = "timestamp,hardware_label,row_type,player_id,player_alias,trust_factor,event,"
            + "violation_delta,violation_after,verdictScore,verdictConfidence,verdictFlag,verdictVl,"
            + "verdictTopSignals,verdictSummary,attacksMedium,apsShort,hitRatio,rotationsMedium,"
            + "rotationDeltasMedium,tightRotationAttacksMedium,instantRotationAttacksMedium,rotationMaxBucketShare,"
            + "rotationUniqueRatio,microCorrectionShare,fastRotationShare,meanYawAcceleration,stdYawAcceleration,"
            + "meanYawJerk,stdYawJerk,preciseAimShare,snapPreciseAimShare,meanAimYawError,meanAimPitchError";
    private static final BlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(8192);
    private static volatile boolean started;

    private KinematicDebugLogger() {
    }

    public static void configure(boolean enabled, String hardware, String file) {
        KinematicDebugLogger.enabled = enabled;
        CURRENT_HARDWARE = sanitize(hardware);
        if (file != null && !file.trim().isEmpty()) {
            outputPattern = file.trim();
        }
        if (enabled) {
            ensureStarted();
        }
    }

    static void record(UUID playerId, long now, CombatEngineScorer.CombatSnapshot snapshot, CombatEngineVerdict verdict) {
        if (!enabled || snapshot == null || snapshot.attacksMedium < 3) {
            return;
        }
        ensureStarted();
        QUEUE.offer(snapshotRow(playerId, now, snapshot, verdict));
    }

    public static void recordCloudVerdict(long now,
                                          UUID playerId,
                                          String playerAlias,
                                          String trustFactor,
                                          String eventName,
                                          double violationDelta,
                                          double violationAfter,
                                          double score,
                                          double confidence,
                                          boolean flag,
                                          int violationPoints,
                                          List<String> topSignals,
                                          String summary) {
        if (!enabled) {
            return;
        }
        ensureStarted();
        QUEUE.offer(verdictRow(
                now,
                playerId,
                playerAlias,
                trustFactor,
                eventName,
                violationDelta,
                violationAfter,
                score,
                confidence,
                flag,
                violationPoints,
                topSignals,
                summary
        ));
    }

    private static void ensureStarted() {
        if (started) {
            return;
        }
        synchronized (KinematicDebugLogger.class) {
            if (started) {
                return;
            }
            Thread writer = new Thread(KinematicDebugLogger::writeLoop, "nova-combat-telemetry-writer");
            writer.setDaemon(true);
            writer.start();
            started = true;
        }
    }

    private static void writeLoop() {
        BufferedWriter writer = null;
        Path currentOutput = null;
        try {
            int pendingWrites = 0;
            while (true) {
                String row = QUEUE.poll(1L, TimeUnit.SECONDS);
                if (row == null) {
                    if (writer != null && pendingWrites > 0) {
                        writer.flush();
                        pendingWrites = 0;
                    }
                    continue;
                }
                Path target = output();
                if (writer == null || !target.equals(currentOutput)) {
                    if (writer != null) {
                        writer.flush();
                        writer.close();
                    }
                    writer = openWriter(target);
                    currentOutput = target;
                    pendingWrites = 0;
                }
                writer.write(row);
                writer.newLine();
                pendingWrites++;
                if (pendingWrites >= 128) {
                    writer.flush();
                    pendingWrites = 0;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            System.err.println("[Nova] Combat cloud telemetry logger stopped: " + exception.getMessage());
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static BufferedWriter openWriter(Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        boolean writeHeader = !Files.exists(target) || Files.size(target) == 0L;
        BufferedWriter writer = Files.newBufferedWriter(
                target,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
        if (writeHeader) {
            writer.write(HEADER);
            writer.newLine();
            writer.flush();
        }
        return writer;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static Path output() {
        return resolveOutputPath();
    }

    private static String snapshotRow(UUID playerId, long now, CombatEngineScorer.CombatSnapshot snapshot, CombatEngineVerdict verdict) {
        StringBuilder builder = new StringBuilder(256);
        appendCommon(builder, now, "ENGINE_SNAPSHOT", playerId, "", "", "", verdict != null && verdict.shouldFlag() ? 10.0D : 0.0D, Double.NaN);
        appendVerdict(
                builder,
                verdict == null ? Double.NaN : verdict.getScore(),
                verdict == null ? Double.NaN : verdict.getConfidence(),
                verdict != null && verdict.shouldFlag(),
                verdict != null && verdict.shouldFlag() ? 10 : 0,
                verdict == null ? null : verdict.getTopSignals(),
                verdict == null ? "" : verdict.getReasonSummary()
        );
        append(builder, Integer.toString(snapshot.attacksMedium));
        append(builder, number(snapshot.apsShort));
        append(builder, number(snapshot.hitRatio));
        append(builder, Integer.toString(snapshot.rotationsMedium));
        append(builder, Integer.toString(snapshot.rotationDeltasMedium));
        append(builder, Integer.toString(snapshot.tightRotationAttacksMedium));
        append(builder, Integer.toString(snapshot.instantRotationAttacksMedium));
        append(builder, number(snapshot.rotationMaxBucketShare));
        append(builder, number(snapshot.rotationUniqueRatio));
        append(builder, number(snapshot.microCorrectionShare));
        append(builder, number(snapshot.fastRotationShare));
        append(builder, number(snapshot.meanYawAcceleration));
        append(builder, number(snapshot.stdYawAcceleration));
        append(builder, number(snapshot.meanYawJerk));
        append(builder, number(snapshot.stdYawJerk));
        append(builder, number(snapshot.preciseAimShare));
        append(builder, number(snapshot.snapPreciseAimShare));
        append(builder, number(snapshot.meanAimYawError));
        append(builder, number(snapshot.meanAimPitchError));
        return builder.toString();
    }

    private static String verdictRow(long now,
                                     UUID playerId,
                                     String playerAlias,
                                     String trustFactor,
                                     String eventName,
                                     double violationDelta,
                                     double violationAfter,
                                     double score,
                                     double confidence,
                                     boolean flag,
                                     int violationPoints,
                                     List<String> topSignals,
                                     String summary) {
        StringBuilder builder = new StringBuilder(256);
        appendCommon(builder, now, "CLOUD_VERDICT", playerId, playerAlias, trustFactor, eventName, violationDelta, violationAfter);
        appendVerdict(builder, score, confidence, flag, violationPoints, topSignals, summary);
        for (int i = 0; i < 19; i++) {
            append(builder, "");
        }
        return builder.toString();
    }

    private static void appendCommon(StringBuilder builder,
                                     long now,
                                     String rowType,
                                     UUID playerId,
                                     String playerAlias,
                                     String trustFactor,
                                     String eventName,
                                     double violationDelta,
                                     double violationAfter) {
        append(builder, Long.toString(now));
        append(builder, sanitize(CURRENT_HARDWARE));
        append(builder, rowType);
        append(builder, playerId == null ? "" : playerId.toString());
        append(builder, sanitizeInline(playerAlias));
        append(builder, sanitizeInline(trustFactor));
        append(builder, sanitizeInline(eventName));
        append(builder, number(violationDelta));
        append(builder, number(violationAfter));
    }

    private static void appendVerdict(StringBuilder builder,
                                      double score,
                                      double confidence,
                                      boolean flag,
                                      int violationPoints,
                                      List<String> topSignals,
                                      String summary) {
        append(builder, number(score));
        append(builder, number(confidence));
        append(builder, flag ? "true" : "false");
        append(builder, Integer.toString(Math.max(0, violationPoints)));
        append(builder, topSignals == null || topSignals.isEmpty()
                ? ""
                : topSignals.stream().map(KinematicDebugLogger::sanitizeInline).collect(Collectors.joining("|")));
        append(builder, sanitizeInline(summary));
    }

    private static void append(StringBuilder builder, String value) {
        if (builder.length() > 0) {
            builder.append(',');
        }
        builder.append(csv(value));
    }

    private static String number(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? "" : String.format(Locale.US, "%.6f", value);
    }

    private static String sanitize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "UNKNOWN";
        }
        return value.replace(',', '_').trim();
    }

    private static String sanitizeInline(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!quote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static Path resolveOutputPath() {
        String hardware = sanitize(CURRENT_HARDWARE).toLowerCase(Locale.ROOT);
        String pattern = outputPattern == null || outputPattern.trim().isEmpty() ? "combat_telemetry.csv" : outputPattern.trim();
        if (pattern.contains("{hardware}")) {
            return Paths.get(pattern.replace("{hardware}", hardware));
        }
        Path base = Paths.get(pattern);
        Path fileName = base.getFileName();
        if (fileName == null) {
            return Paths.get("combat_telemetry-" + hardware + ".csv");
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        String hardwareName = dot > 0
                ? name.substring(0, dot) + "-" + hardware + name.substring(dot)
                : name + "-" + hardware;
        Path parent = base.getParent();
        return parent == null ? Paths.get(hardwareName) : parent.resolve(hardwareName);
    }

    private static boolean booleanSetting(String property, String environment, boolean fallback) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(environment);
        }
        return value == null || value.trim().isEmpty() ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static String stringSetting(String property, String environment, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(environment);
        }
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
