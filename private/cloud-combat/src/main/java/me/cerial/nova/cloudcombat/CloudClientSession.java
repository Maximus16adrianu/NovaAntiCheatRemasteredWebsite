package me.cerial.nova.cloudcombat;

import com.google.gson.JsonObject;
import me.cerial.nova.cloudcombat.engine.KinematicDebugLogger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class CloudClientSession implements Runnable {
    private static final long ACTIVE_PLAYER_SLOT_TTL_MS = 1_500L;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DARK_GRAY = "\u001B[90m";
    private static final String GRAY = "\u001B[37m";
    private static final String RED = "\u001B[91m";
    private static final String DARK_RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[93m";
    private static final String GOLD = "\u001B[33m";
    private static final String GREEN = "\u001B[92m";
    private static final String WHITE = "\u001B[97m";

    private final Socket socket;
    private final CombatCloudAnalyzer analyzer;
    private final LicenseSessionValidator licenseValidator;
    private final boolean loggingEnabled;
    private final Map<UUID, Double> localViolationLevels = new ConcurrentHashMap<>();
    private final Map<UUID, Long> activePlayerSlots = new ConcurrentHashMap<>();
    private LicenseSessionValidator.ValidatedSession validatedSession;
    private long lastLicenseValidationMs;
    private long lastTraceMs;

    CloudClientSession(Socket socket, CombatCloudAnalyzer analyzer) {
        this(socket, analyzer, new LicenseSessionValidator(), true);
    }

    CloudClientSession(Socket socket, CombatCloudAnalyzer analyzer, boolean loggingEnabled) {
        this(socket, analyzer, new LicenseSessionValidator(), loggingEnabled);
    }

    CloudClientSession(Socket socket,
                       CombatCloudAnalyzer analyzer,
                       LicenseSessionValidator licenseValidator,
                       boolean loggingEnabled) {
        this.socket = socket;
        this.analyzer = analyzer;
        this.licenseValidator = licenseValidator == null ? new LicenseSessionValidator() : licenseValidator;
        this.loggingEnabled = loggingEnabled;
    }

    @Override
    public void run() {
        if (loggingEnabled) {
            info("Combat cloud client connected from " + socket.getRemoteSocketAddress());
        }
        try (Socket closeable = socket;
             DataInputStream reader = new DataInputStream(new BufferedInputStream(closeable.getInputStream()));
             DataOutputStream writer = new DataOutputStream(new BufferedOutputStream(closeable.getOutputStream()))) {
            byte[] key = handshake(reader, writer);
            while (true) {
                JsonObject request = CombatCloudProtocol.readEncryptedObject(reader, key);
                JsonObject response = handle(request);
                if (response == null) {
                    continue;
                }
                CombatCloudProtocol.writeEncryptedObject(writer, key, response);
            }
        } catch (IOException exception) {
            if (loggingEnabled && exception.getMessage() != null && !exception.getMessage().isEmpty()) {
                warn(exception.getMessage());
            }
        } finally {
            if (loggingEnabled) {
                warn("Combat cloud client disconnected from " + socket.getRemoteSocketAddress());
            }
        }
    }

    private JsonObject handle(JsonObject request) throws IOException {
        String type = string(request, "type");
        if (!"combat_event".equals(type)) {
            return null;
        }
        if (!ensureLicensed()) {
            throw new IOException("Combat cloud rejected unauthenticated license session");
        }
        long now = longValue(request, "time", System.currentTimeMillis());
        UUID playerId = uuid(request, "playerId");
        String eventName = string(request, "event");
        if ("player_disconnect".equals(eventName) || "player_reset".equals(eventName)) {
            if (playerId != null) {
                localViolationLevels.remove(playerId);
                activePlayerSlots.remove(playerId);
            }
            analyzer.accept(request, loggingEnabled);
            return null;
        }
        if (playerId != null && !reserveCloudPlayerSlot(playerId, now)) {
            trace(request, playerId, "slot-denied", null);
            return null;
        }
        CombatCloudVerdict verdict = analyzer.accept(request, loggingEnabled);
        CombatCloudAnalyzer.Trace trace = analyzer.lastTrace();
        trace(request, playerId, "received", trace);
        if (verdict == null) {
            return localVerdictClearResponse(request, playerId);
        }
        double after = localViolationLevels.merge(verdict.playerId(), (double) verdict.violationPoints(), Double::sum);
        double delta = verdict.violationPoints();
        KinematicDebugLogger.recordCloudVerdict(
                now,
                verdict.playerId(),
                playerAliasOrId(request, verdict.playerId()),
                string(request, "trustFactor"),
                eventName,
                delta,
                after,
                verdict.score(),
                verdict.confidence(),
                verdict.flag(),
                verdict.violationPoints(),
                verdict.topSignals(),
                verdict.summary()
        );
        logVerdict(request, verdict, delta, after);
        JsonObject response = verdict.asJson();
        String localVerdictId = string(request, "localVerdictId");
        if (!localVerdictId.isEmpty()) {
            response.addProperty("localVerdictId", localVerdictId);
        }
        return response;
    }

    private JsonObject localVerdictClearResponse(JsonObject request, UUID playerId) {
        String localVerdictId = string(request, "localVerdictId");
        if (playerId == null || localVerdictId.isEmpty() || !booleanValue(request, "localFlag", false)) {
            return null;
        }
        if ("local_verdict".equals(string(request, "event"))) {
            trace(request, playerId, "hold-local", analyzer.lastTrace());
        }
        return null;
    }

    private void trace(JsonObject request, UUID playerId, String phase, CombatCloudAnalyzer.Trace trace) {
        if (!loggingEnabled || request == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String eventName = string(request, "event");
        boolean important = "local_verdict".equals(eventName)
                || "attack".equals(eventName)
                || "hit".equals(eventName)
                || "check_delta".equals(eventName)
                || traceHasFindings(trace);
        if (!important && now - lastTraceMs < 1000L) {
            return;
        }
        lastTraceMs = now;

        String localId = string(request, "localVerdictId");
        StringBuilder builder = new StringBuilder();
        builder.append("cloud recv event=").append(eventName.isEmpty() ? "unknown" : eventName)
                .append(" phase=").append(phase)
                .append(" player=").append(playerAliasOrId(request, playerId));
        if (!localId.isEmpty()) {
            builder.append(" localId=").append(localId);
        }
        if (request.has("localScore")) {
            builder.append(String.format(Locale.US,
                    " local=%.2f/%.2f flag=%s",
                    doubleValue(request, "localScore", 0.0D),
                    doubleValue(request, "localConfidence", 0.0D),
                    Boolean.toString(booleanValue(request, "localFlag", false))));
        }
        if (trace != null) {
            builder.append(" mxRaw=").append(trace.rawCount())
                    .append(" mxSelected=").append(trace.selectedCount())
                    .append(" mxFiltered=").append(trace.filteredCount())
                    .append(" action=").append(trace.action())
                    .append(" raw=").append(trace.rawSummary());
            if (trace.selectedCount() > 0) {
                builder.append(" selected=").append(trace.selectedSummary());
            }
            if (trace.filteredCount() > 0) {
                builder.append(" filtered=").append(trace.filteredSummary());
            }
            String mxState = trace.mxStateSummary();
            if (!mxState.isEmpty()) {
                builder.append(" mxState=").append(mxState);
            }
        }
        info(builder.toString());
    }

    private static boolean traceHasFindings(CombatCloudAnalyzer.Trace trace) {
        return trace != null && (trace.rawCount() > 0 || trace.selectedCount() > 0 || trace.filteredCount() > 0);
    }

    private static String playerAliasOrId(JsonObject request, UUID playerId) {
        String alias = string(request, "playerAlias");
        return alias.isEmpty() ? (playerId == null ? "unknown" : playerId.toString()) : alias;
    }

    private boolean ensureLicensed() {
        long now = System.currentTimeMillis();
        if (validatedSession == null) {
            return false;
        }
        if (now - lastLicenseValidationMs < 10_000L) {
            return true;
        }

        LicenseSessionValidator.ValidatedSession refreshed = licenseValidator.validate(
                validatedSession.licenseKey,
                validatedSession.username,
                validatedSession.sessionToken
        );
        if (refreshed == null) {
            return false;
        }
        validatedSession = refreshed;
        lastLicenseValidationMs = now;
        return true;
    }

    private boolean reserveCloudPlayerSlot(UUID playerId, long now) {
        if (playerId == null || validatedSession == null) {
            return false;
        }
        int cap = validatedSession.cloudPlayerSlots;
        if (cap <= 0) {
            return false;
        }
        cleanupCloudPlayerSlots(now);
        if (activePlayerSlots.containsKey(playerId)) {
            activePlayerSlots.put(playerId, now);
            return true;
        }
        if (activePlayerSlots.size() >= cap) {
            return false;
        }
        activePlayerSlots.put(playerId, now);
        return true;
    }

    private void cleanupCloudPlayerSlots(long now) {
        for (Map.Entry<UUID, Long> entry : activePlayerSlots.entrySet()) {
            Long lastSeen = entry.getValue();
            if (lastSeen == null || now - lastSeen > ACTIVE_PLAYER_SLOT_TTL_MS) {
                UUID playerId = entry.getKey();
                if (activePlayerSlots.remove(playerId, lastSeen)) {
                    localViolationLevels.remove(playerId);
                    analyzer.resetPlayer(playerId);
                }
            }
        }
    }

    private byte[] handshake(DataInputStream reader, DataOutputStream writer) throws IOException {
        CombatCloudProtocol.Frame helloFrame = CombatCloudProtocol.readFrame(reader);
        if (helloFrame.type != CombatCloudProtocol.FRAME_CLIENT_HELLO) {
            CombatCloudProtocol.writeFrame(writer, CombatCloudProtocol.FRAME_REJECT, CombatCloudProtocol.rejectPayload("Expected combat cloud hello"));
            throw new IOException("Expected combat cloud hello");
        }
        CombatCloudProtocol.ClientHello hello = CombatCloudProtocol.readClientHello(helloFrame.payload);
        if (hello.protocol != CombatCloudProtocol.VERSION) {
            CombatCloudProtocol.writeFrame(writer, CombatCloudProtocol.FRAME_REJECT, CombatCloudProtocol.rejectPayload("Unsupported combat cloud protocol " + hello.protocol));
            throw new IOException("Unsupported combat cloud protocol " + hello.protocol);
        }
        byte[] serverNonce = CombatCloudProtocol.randomBytes(16);
        CombatCloudProtocol.writeFrame(writer, CombatCloudProtocol.FRAME_SERVER_CHALLENGE, CombatCloudProtocol.bytesPayload(serverNonce));
        CombatCloudProtocol.Frame authFrame = CombatCloudProtocol.readFrame(reader);
        if (authFrame.type != CombatCloudProtocol.FRAME_CLIENT_AUTH) {
            CombatCloudProtocol.writeFrame(writer, CombatCloudProtocol.FRAME_REJECT, CombatCloudProtocol.rejectPayload("Expected combat cloud auth"));
            throw new IOException("Expected combat cloud auth");
        }
        byte[] receivedAuth = CombatCloudProtocol.readBytesPayload(authFrame.payload);
        LicenseSessionValidator.ValidatedSession session = licenseValidator.validateHandshake(
                hello.licenseKey,
                hello.username,
                hello.serverId,
                hello.clientNonce,
                serverNonce,
                receivedAuth
        );
        if (session == null) {
            CombatCloudProtocol.writeFrame(writer, CombatCloudProtocol.FRAME_REJECT, CombatCloudProtocol.rejectPayload("Combat cloud authentication failed"));
            throw new IOException("Combat cloud authentication failed");
        }
        validatedSession = session;
        lastLicenseValidationMs = System.currentTimeMillis();
        byte[] serverProof = CombatCloudProtocol.serverProof(
                session.sessionToken,
                session.licenseKey,
                session.username,
                hello.serverId,
                hello.clientNonce,
                serverNonce
        );
        CombatCloudProtocol.writeFrame(writer, CombatCloudProtocol.FRAME_SERVER_ACCEPT, CombatCloudProtocol.serverAcceptPayload("nova-combat-cloud", serverProof));
        if (loggingEnabled) {
            info("Combat cloud handshake from " + socket.getRemoteSocketAddress()
                    + " license=" + session.licenseKey
                    + " user=" + session.username
                    + " plan=" + session.licensePlan
                    + " cloudSlots=" + session.cloudPlayerSlots);
        }
        return CombatCloudProtocol.sessionKey(
                session.sessionToken,
                session.licenseKey,
                session.username,
                hello.serverId,
                hello.clientNonce,
                serverNonce
        );
    }

    private void logVerdict(JsonObject request, CombatCloudVerdict verdict, double delta, double after) {
        if (!loggingEnabled) {
            return;
        }
        String playerAlias = playerAliasOrId(request, verdict.playerId());
        String trustFactor = string(request, "trustFactor");
        if (trustFactor.isEmpty()) {
            trustFactor = "darkred";
        }
        String details = String.format(
                Locale.US,
                "cloud verdict (score=%.2f, conf=%.2f, top=%s%s)",
                verdict.score(),
                verdict.confidence(),
                formatTopSignals(verdict.topSignals()),
                verdict.summary().isEmpty() ? "" : ", " + limit(verdict.summary(), 160)
        );
        System.out.println(prefix()
                + RED + playerAlias
                + GRAY + "/"
                + trustColor(trustFactor) + trustFactor
                + GRAY + " is fighting suspiciously ("
                + details
                + ") "
                + DARK_GRAY + String.format(Locale.US, "(+%.2f -> %.2f)", delta, after)
                + RESET);
    }

    private static String targetContext(JsonObject request) {
        if (!request.has("target") && !request.has("targetType")) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        if (request.has("target")) {
            builder.append(" target=").append(request.get("target").getAsString());
        }
        String targetType = string(request, "targetType");
        if (!targetType.isEmpty()) {
            builder.append(" targetType=").append(targetType);
        }
        int ping = intValue(request, "ping", -1);
        if (ping >= 0) {
            builder.append(" ping=").append(ping).append("ms");
        }
        double tps = doubleValue(request, "tps", -1.0D);
        if (tps > 0.0D) {
            builder.append(String.format(Locale.US, " tps=%.2f", tps));
        }
        return builder.toString();
    }

    private static String formatTopSignals(List<String> topSignals) {
        if (topSignals == null || topSignals.isEmpty()) {
            return "none";
        }
        StringBuilder compact = new StringBuilder();
        int added = 0;
        for (String signal : topSignals) {
            if (signal == null || signal.isEmpty()) {
                continue;
            }
            if (added >= 3) {
                break;
            }
            if (compact.length() > 0) {
                compact.append(',');
            }
            compact.append(limit(signal, 22));
            added++;
        }
        return added == 0 ? "none" : compact.toString();
    }

    private static String limit(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        if (maxLength <= 1) {
            return text.substring(0, 1);
        }
        return text.substring(0, maxLength - 1) + "~";
    }

    private static void info(String message) {
        System.out.println(prefix() + message + RESET);
    }

    private static void warn(String message) {
        System.out.println(prefix() + YELLOW + BOLD + "WARNING" + GRAY + ": " + RED + message + RESET);
    }

    private static String prefix() {
        return DARK_GRAY + "[" + GRAY + TIME_FORMAT.format(LocalTime.now()) + DARK_GRAY + "] "
                + "[" + RED + BOLD + "Nova" + RESET + DARK_GRAY + "]" + GRAY + " ";
    }

    private static String trustColor(String trustFactor) {
        String normalized = trustFactor == null ? "" : trustFactor.toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "bypass":
                return WHITE;
            case "green":
                return GREEN;
            case "yellow":
                return YELLOW;
            case "orange":
                return GOLD;
            case "red":
                return RED;
            case "darkred":
            case "dark_red":
                return DARK_RED;
            default:
                return GRAY;
        }
    }

    private static String string(JsonObject object, String key) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString()
                : "";
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsInt()
                    : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsLong()
                    : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static UUID uuid(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? UUID.fromString(object.get(key).getAsString())
                    : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static double doubleValue(JsonObject object, String key, double fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsDouble()
                    : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsBoolean()
                    : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
