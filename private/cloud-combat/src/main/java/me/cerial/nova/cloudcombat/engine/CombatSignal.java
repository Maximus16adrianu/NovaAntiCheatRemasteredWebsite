package me.cerial.nova.cloudcombat.engine;

public final class CombatSignal {

    private final CombatSignalType type;
    private final long timestamp;
    private final double value;
    private final String detail;

    public CombatSignal(CombatSignalType type, long timestamp, double value, String detail) {
        this.type = type;
        this.timestamp = timestamp;
        this.value = value;
        this.detail = detail == null ? "" : detail;
    }

    public CombatSignalType getType() {
        return type;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public double getValue() {
        return value;
    }

    public String getDetail() {
        return detail;
    }
}

