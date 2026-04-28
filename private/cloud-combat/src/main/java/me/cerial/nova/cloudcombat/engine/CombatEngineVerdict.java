package me.cerial.nova.cloudcombat.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CombatEngineVerdict {

    private final double score;
    private final double confidence;
    private final String reasonSummary;
    private final List<String> topSignals;
    private final boolean shouldFlag;
    private final boolean legitCompatibilityWindow;
    private final long timestamp;

    public CombatEngineVerdict(double score,
                               double confidence,
                               String reasonSummary,
                               List<String> topSignals,
                               boolean shouldFlag,
                               long timestamp) {
        this(score, confidence, reasonSummary, topSignals, shouldFlag, false, timestamp);
    }

    public CombatEngineVerdict(double score,
                               double confidence,
                               String reasonSummary,
                               List<String> topSignals,
                               boolean shouldFlag,
                               boolean legitCompatibilityWindow,
                               long timestamp) {
        this.score = score;
        this.confidence = confidence;
        this.reasonSummary = reasonSummary == null ? "" : reasonSummary;
        if (topSignals == null || topSignals.isEmpty()) {
            this.topSignals = Collections.emptyList();
        } else {
            this.topSignals = Collections.unmodifiableList(new ArrayList<>(topSignals));
        }
        this.shouldFlag = shouldFlag;
        this.legitCompatibilityWindow = legitCompatibilityWindow;
        this.timestamp = timestamp;
    }

    public double getScore() {
        return score;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getReasonSummary() {
        return reasonSummary;
    }

    public List<String> getTopSignals() {
        return topSignals;
    }

    public boolean shouldFlag() {
        return shouldFlag;
    }

    public boolean isLegitCompatibilityWindow() {
        return legitCompatibilityWindow;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
