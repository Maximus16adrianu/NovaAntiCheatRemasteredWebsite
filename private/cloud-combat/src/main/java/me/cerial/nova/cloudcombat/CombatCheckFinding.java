package me.cerial.nova.cloudcombat;

import java.util.List;
import java.util.UUID;

record CombatCheckFinding(
        UUID playerId,
        String checkId,
        double score,
        double confidence,
        int violationPoints,
        String details,
        List<String> topSignals
) {
}
