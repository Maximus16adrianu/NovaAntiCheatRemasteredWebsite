package me.cerial.nova.cloudcombat.engine;

public final class CombatEngineRuntimeConfig {
    private static volatile boolean touchpadCompatibility = booleanSetting(
            "nova.combatcloud.touchpadCompatibility",
            "NOVA_COMBAT_CLOUD_TOUCHPAD_COMPATIBILITY",
            true
    );

    private CombatEngineRuntimeConfig() {
    }

    public static boolean touchpadCompatibility() {
        return touchpadCompatibility;
    }

    public static void setTouchpadCompatibility(boolean enabled) {
        touchpadCompatibility = enabled;
    }

    private static boolean booleanSetting(String property, String environment, boolean fallback) {
        String value = System.getProperty(property);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(environment);
        }
        return value == null || value.trim().isEmpty() ? fallback : Boolean.parseBoolean(value.trim());
    }
}
