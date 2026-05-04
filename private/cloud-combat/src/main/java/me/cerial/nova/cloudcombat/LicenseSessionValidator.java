package me.cerial.nova.cloudcombat;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

final class LicenseSessionValidator {
    private static final String SESSION_QUERY = """
            SELECT s.session_token,
                   s.license_id,
                   s.device_id,
                   s.instance_id,
                   s.instance_hash,
                   s.instance_name,
                   l.license_key,
                   l.customer_username,
                   COALESCE(l.license_plan, 'basic') AS license_plan,
                   COALESCE(l.cloud_player_slots, 0) AS cloud_player_slots,
                   l.active,
                   l.expires_at
              FROM service_sessions s
              JOIN licenses l ON l.id = s.license_id
             WHERE s.session_token = ?
               AND s.closed_at IS NULL
            """;
    private static final String HANDSHAKE_QUERY = """
            SELECT s.session_token,
                   s.license_id,
                   s.device_id,
                   s.instance_id,
                   s.instance_hash,
                   s.instance_name,
                   l.license_key,
                   l.customer_username,
                   COALESCE(l.license_plan, 'basic') AS license_plan,
                   COALESCE(l.cloud_player_slots, 0) AS cloud_player_slots,
                   l.active,
                   l.expires_at
              FROM service_sessions s
              JOIN licenses l ON l.id = s.license_id
             WHERE l.license_key = ?
               AND LOWER(l.customer_username) = LOWER(?)
               AND s.closed_at IS NULL
            """;

    private final String databasePath;

    LicenseSessionValidator() {
        this.databasePath = resolveDatabasePath();
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ignored) {
            // The runtime jar should include sqlite-jdbc; validation will fail loudly if it does not.
        }
    }

    ValidatedSession validate(String licenseKey, String username, String sessionToken) {
        String normalizedLicenseKey = normalizeLicenseKey(licenseKey);
        String normalizedUsername = normalize(username);
        String normalizedSessionToken = normalize(sessionToken);
        if (normalizedLicenseKey.isEmpty() || normalizedUsername.isEmpty() || normalizedSessionToken.isEmpty()) {
            return null;
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             PreparedStatement statement = connection.prepareStatement(SESSION_QUERY)) {
            statement.setString(1, normalizedSessionToken);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }

                String storedLicenseKey = normalizeLicenseKey(result.getString("license_key"));
                String storedUsername = normalize(result.getString("customer_username"));
                if (!storedLicenseKey.equals(normalizedLicenseKey)
                        || !storedUsername.equalsIgnoreCase(normalizedUsername)) {
                    return null;
                }
                if (result.getInt("active") != 1 || expired(result.getString("expires_at"))) {
                    return null;
                }

                ValidatedSession session = new ValidatedSession(
                        normalizedSessionToken,
                        result.getLong("license_id"),
                        result.getLong("device_id"),
                        result.getLong("instance_id"),
                        normalize(result.getString("instance_hash")),
                        normalize(result.getString("instance_name")),
                        storedLicenseKey,
                        storedUsername,
                        normalizeLicensePlan(result.getString("license_plan")),
                        result.getInt("cloud_player_slots")
                );
                return session.hasCloudAccess() ? session : null;
            }
        } catch (Exception exception) {
            System.out.println("[Nova] Combat cloud license validation failed: " + exception.getMessage());
            return null;
        }
    }

    ValidatedSession validateHandshake(String licenseKey,
                                       String username,
                                       UUID serverId,
                                       byte[] clientNonce,
                                       byte[] serverNonce,
                                       byte[] receivedAuth) {
        String normalizedLicenseKey = normalizeLicenseKey(licenseKey);
        String normalizedUsername = normalize(username);
        if (normalizedLicenseKey.isEmpty()
                || normalizedUsername.isEmpty()
                || serverId == null
                || clientNonce == null
                || serverNonce == null
                || receivedAuth == null) {
            return null;
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             PreparedStatement statement = connection.prepareStatement(HANDSHAKE_QUERY)) {
            statement.setString(1, normalizedLicenseKey);
            statement.setString(2, normalizedUsername);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    if (result.getInt("active") != 1 || expired(result.getString("expires_at"))) {
                        continue;
                    }
                    ValidatedSession candidate = new ValidatedSession(
                            normalize(result.getString("session_token")),
                            result.getLong("license_id"),
                            result.getLong("device_id"),
                            result.getLong("instance_id"),
                            normalize(result.getString("instance_hash")),
                            normalize(result.getString("instance_name")),
                            normalizeLicenseKey(result.getString("license_key")),
                            normalize(result.getString("customer_username")),
                            normalizeLicensePlan(result.getString("license_plan")),
                            result.getInt("cloud_player_slots")
                    );
                    if (!candidate.hasCloudAccess()) {
                        continue;
                    }
                    byte[] expectedAuth = CombatCloudProtocol.authentication(
                            candidate.sessionToken,
                            candidate.licenseKey,
                            candidate.username,
                            serverId,
                            clientNonce,
                            serverNonce
                    );
                    if (CombatCloudProtocol.secureEquals(receivedAuth, expectedAuth)) {
                        return candidate;
                    }
                }
            }
        } catch (Exception exception) {
            System.out.println("[Nova] Combat cloud handshake validation failed: " + exception.getMessage());
        }
        return null;
    }

    String databasePath() {
        return databasePath;
    }

    private static boolean expired(String expiresAt) {
        String value = normalize(expiresAt);
        if (value.isEmpty()) {
            return false;
        }
        try {
            return Instant.parse(value).isBefore(Instant.now());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String resolveDatabasePath() {
        String configured = firstPresent(
                System.getProperty("nova.combatcloud.db"),
                System.getProperty("nova.website.db"),
                System.getenv("NOVA_COMBAT_CLOUD_DB"),
                System.getenv("NOVA_WEBSITE_DB")
        );
        if (!configured.isEmpty()) {
            return new File(configured).getAbsolutePath();
        }

        String[] candidates = {
                "database/novaac.db",
                "../database/novaac.db",
                "../../database/novaac.db"
        };
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile()) {
                return file.getAbsolutePath();
            }
        }
        return new File("../database/novaac.db").getAbsolutePath();
    }

    private static String firstPresent(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return "";
    }

    private static String normalizeLicenseKey(String value) {
        return normalize(value).toUpperCase(Locale.ROOT);
    }

    private static String normalizeLicensePlan(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        return "pro".equals(normalized) ? "pro" : "basic";
    }

    private static int normalizeCloudPlayerSlots(String licensePlan, int value) {
        if (!"pro".equals(licensePlan)) {
            return 0;
        }
        if (value <= 10) {
            return 10;
        }
        if (value <= 25) {
            return 25;
        }
        return 50;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    static final class ValidatedSession {
        final String sessionToken;
        final long licenseId;
        final long deviceId;
        final long instanceId;
        final String instanceHash;
        final String instanceName;
        final String licenseKey;
        final String username;
        final String licensePlan;
        final int cloudPlayerSlots;

        private ValidatedSession(
                String sessionToken,
                long licenseId,
                long deviceId,
                long instanceId,
                String instanceHash,
                String instanceName,
                String licenseKey,
                String username,
                String licensePlan,
                int cloudPlayerSlots
        ) {
            this.sessionToken = sessionToken;
            this.licenseId = licenseId;
            this.deviceId = deviceId;
            this.instanceId = instanceId;
            this.instanceHash = instanceHash;
            this.instanceName = instanceName;
            this.licenseKey = licenseKey;
            this.username = username;
            this.licensePlan = normalizeLicensePlan(licensePlan);
            this.cloudPlayerSlots = normalizeCloudPlayerSlots(this.licensePlan, cloudPlayerSlots);
        }

        boolean hasCloudAccess() {
            return "pro".equals(licensePlan) && cloudPlayerSlots > 0;
        }
    }
}
