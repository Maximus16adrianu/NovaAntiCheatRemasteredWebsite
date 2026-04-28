package me.cerial.nova.cloudcombat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.UUID;

final class CombatCloudVerdict {
    private final UUID playerId;
    private final double score;
    private final double confidence;
    private final boolean flag;
    private final int vl;
    private final String summary;
    private final List<String> topSignals;

    CombatCloudVerdict(UUID playerId,
                       double score,
                       double confidence,
                       boolean flag,
                       int vl,
                       String summary,
                       List<String> topSignals) {
        this.playerId = playerId;
        this.score = score;
        this.confidence = confidence;
        this.flag = flag;
        this.vl = vl;
        this.summary = summary == null ? "" : summary;
        this.topSignals = topSignals;
    }

    double score() {
        return score;
    }

    UUID playerId() {
        return playerId;
    }

    double confidence() {
        return confidence;
    }

    int violationPoints() {
        return vl;
    }

    String summary() {
        return summary;
    }

    List<String> topSignals() {
        return topSignals;
    }

    boolean flag() {
        return flag;
    }

    JsonObject asJson() {
        JsonObject object = new JsonObject();
        object.addProperty("type", "combat_verdict");
        object.addProperty("playerId", playerId.toString());
        object.addProperty("score", score);
        object.addProperty("confidence", confidence);
        object.addProperty("flag", flag);
        object.addProperty("vl", vl);
        object.addProperty("summary", summary);
        JsonArray top = new JsonArray();
        if (topSignals != null) {
            for (String signal : topSignals) {
                if (signal != null && !signal.isEmpty()) {
                    top.add(signal);
                }
            }
        }
        object.add("top", top);
        return object;
    }
}
