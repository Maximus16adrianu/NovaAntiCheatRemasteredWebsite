package me.cerial.nova.cloudcombat.engine;

final class CombatEngineProfileLayer {

    private CombatEngineProfileLayer() {
    }

    static ProfileFlags evaluate(CombatEngineScorer.CombatSnapshot snapshot, CombatEngineConfig config) {
        if (snapshot == null || config == null) {
            return ProfileFlags.EMPTY;
        }

        boolean sparsePrecisionHighRotation = snapshot.attacksMedium >= Math.max(2, config.getMinAttacksShort())
                && snapshot.attacksMedium <= Math.max(5, config.getMinAttacksMedium() + 1)
                && snapshot.aimSampleCount >= 1
                && snapshot.aimSampleCount <= Math.max(3, config.getMinAttacksShort() + 1)
                && snapshot.preciseAimShare >= 0.88D
                && snapshot.snapPreciseAimShare >= 0.18D
                && snapshot.rotationsMedium >= Math.max(30, config.getMinRotationsMedium() * 6)
                && snapshot.rotationDeltasMedium >= Math.max(72, config.getMinRotationDeltasMedium() * 10)
                && snapshot.apsShort <= Math.max(2.4D, config.getModernCooldownApsMin() + 1.2D);

        boolean lowSnapCooldownPrecision = snapshot.attacksMedium >= Math.max(2, config.getMinAttacksShort())
                && snapshot.attacksMedium <= Math.max(6, config.getMinAttacksMedium() + 2)
                && snapshot.aimSampleCount >= 1
                && snapshot.aimSampleCount <= Math.max(4, config.getMinAttacksShort() + 2)
                && snapshot.preciseAimShare >= 0.90D
                && snapshot.snapPreciseAimShare <= 0.05D
                && snapshot.hitRatio <= 0.76D
                && snapshot.apsShort <= Math.max(2.2D, config.getModernCooldownApsMin() + 1.1D)
                && snapshot.rotationsMedium >= Math.max(20, config.getMinRotationsMedium() * 5)
                && snapshot.rotationDeltasMedium >= Math.max(60, config.getMinRotationDeltasMedium() * 8);

        boolean highHitLowSnapCooldown = snapshot.attacksMedium >= Math.max(3, config.getMinAttacksMedium())
                && snapshot.attacksMedium <= Math.max(6, config.getMinAttacksMedium() + 2)
                && snapshot.aimSampleCount >= Math.max(2, config.getMinAttacksShort())
                && snapshot.preciseAimShare >= 0.75D
                && snapshot.snapPreciseAimShare <= 0.05D
                && snapshot.hitRatio >= 0.90D
                && snapshot.apsShort <= Math.max(2.4D, config.getModernCooldownApsMin() + 1.4D)
                && snapshot.rotationsMedium >= Math.max(24, config.getMinRotationsMedium() * 5)
                && snapshot.rotationDeltasMedium >= Math.max(72, config.getMinRotationDeltasMedium() * 8);

        boolean cooldownCadenceSaturation = snapshot.attacksMedium >= config.getMinAttacksMedium()
                && snapshot.attacksMedium <= Math.max(7, config.getMinAttacksMedium() + 3)
                && snapshot.apsShort >= Math.max(1.3D, config.getModernCooldownApsMin() + 0.20D)
                && snapshot.apsShort <= Math.min(2.4D, config.getModernCooldownApsMax())
                && snapshot.hitRatio >= 0.72D
                && snapshot.hitRatio <= 1.00D
                && snapshot.snapPreciseAimShare <= 0.30D
                && snapshot.rotationsMedium >= Math.max(48, config.getMinRotationsMedium() * 10)
                && snapshot.rotationDeltasMedium >= Math.max(110, config.getMinRotationDeltasMedium() * 14);
        boolean lowPrecisionLowHitHighRotation = snapshot.attacksMedium >= Math.max(8, config.getMinAttacksMedium() + 4)
                && snapshot.aimSampleCount <= Math.max(2, config.getMinAttacksShort())
                && snapshot.preciseAimShare <= 0.12D
                && snapshot.snapPreciseAimShare <= 0.04D
                && snapshot.hitRatio <= 0.58D
                && snapshot.apsShort >= Math.max(1.6D, config.getModernCooldownApsMin() + 0.55D)
                && snapshot.apsShort <= Math.min(9.5D, config.getModernCooldownApsMax() + 3.5D)
                && snapshot.rotationsMedium >= Math.max(56, config.getMinRotationsMedium() * 10)
                && snapshot.rotationDeltasMedium >= Math.max(105, config.getMinRotationDeltasMedium() * 14);
        boolean lowApsInstantNoAim = snapshot.attacksMedium >= Math.max(5, config.getMinAttacksMedium() + 2)
                && snapshot.attacksMedium <= Math.max(14, config.getMinAttacksMedium() * 5)
                && snapshot.aimSampleCount <= Math.max(2, config.getMinAttacksShort())
                && snapshot.preciseAimShare <= 0.06D
                && snapshot.snapPreciseAimShare <= 0.03D
                && snapshot.hitRatio >= 0.68D
                && snapshot.hitRatio <= 0.90D
                && snapshot.apsShort >= 0.35D
                && snapshot.apsShort <= Math.max(1.25D, config.getModernCooldownApsMin() + 0.35D)
                && snapshot.instantRotationAttacksMedium >= Math.max(2, (int) Math.ceil(snapshot.attacksMedium * 0.35D))
                && snapshot.rotationsMedium >= Math.max(30, config.getMinRotationsMedium() * 8)
                && snapshot.rotationDeltasMedium >= Math.max(70, config.getMinRotationDeltasMedium() * 12);
        boolean lowTempoNoAimHighHitRotation = snapshot.attacksMedium >= Math.max(3, config.getMinAttacksMedium() + 1)
                && snapshot.attacksMedium <= Math.max(6, config.getMinAttacksMedium() + 4)
                && snapshot.preciseAimShare <= 0.06D
                && snapshot.snapPreciseAimShare <= 0.03D
                && snapshot.hitRatio >= 0.94D
                && snapshot.apsShort >= 0.60D
                && snapshot.apsShort <= 2.40D
                && snapshot.rotationsMedium >= Math.max(56, config.getMinRotationsMedium() * 10)
                && snapshot.rotationDeltasMedium >= Math.max(110, config.getMinRotationDeltasMedium() * 14)
                && snapshot.fovMissRatio >= 0.35D
                && !Double.isNaN(snapshot.meanFovReacquireMs)
                && snapshot.meanFovReacquireMs >= 260.0D;
        boolean cooldownNoAimPerfectHitRotation = snapshot.attacksMedium >= Math.max(4, config.getMinAttacksMedium() + 1)
                && snapshot.attacksMedium <= Math.max(7, config.getMinAttacksMedium() + 5)
                && snapshot.aimSampleCount <= Math.max(2, config.getMinAttacksShort())
                && snapshot.preciseAimShare <= 0.03D
                && snapshot.snapPreciseAimShare <= 0.02D
                && snapshot.hitRatio >= 0.92D
                && snapshot.apsShort >= 0.55D
                && snapshot.apsShort <= Math.min(2.4D, config.getModernCooldownApsMax())
                && snapshot.rotationsMedium >= Math.max(52, config.getMinRotationsMedium() * 9)
                && snapshot.rotationDeltasMedium >= Math.max(98, config.getMinRotationDeltasMedium() * 13)
                && snapshot.fovMissRatio <= 0.32D
                && (Double.isNaN(snapshot.meanFovReacquireMs) || snapshot.meanFovReacquireMs >= 140.0D);
        boolean cooldownNoAimFastReacquire = snapshot.attacksMedium >= Math.max(4, config.getMinAttacksMedium() + 1)
                && snapshot.attacksMedium <= Math.max(6, config.getMinAttacksMedium() + 4)
                && snapshot.aimSampleCount <= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare <= 0.02D
                && snapshot.snapPreciseAimShare <= 0.01D
                && snapshot.hitRatio >= 0.94D
                && snapshot.apsShort >= 0.60D
                && snapshot.apsShort <= Math.min(2.4D, config.getModernCooldownApsMax())
                && snapshot.rotationsMedium >= Math.max(58, config.getMinRotationsMedium() * 10)
                && snapshot.rotationDeltasMedium >= Math.max(112, config.getMinRotationDeltasMedium() * 15)
                && snapshot.fovMissRatio <= 0.12D
                && (Double.isNaN(snapshot.meanFovReacquireMs) || snapshot.meanFovReacquireMs <= 130.0D);
        boolean cooldownNoAimNoReacquire = snapshot.attacksMedium >= Math.max(4, config.getMinAttacksMedium() + 1)
                && snapshot.attacksMedium <= Math.max(6, config.getMinAttacksMedium() + 4)
                && snapshot.aimSampleCount <= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare <= 0.02D
                && snapshot.snapPreciseAimShare <= 0.01D
                && snapshot.hitRatio >= 0.94D
                && snapshot.apsShort >= 0.60D
                && snapshot.apsShort <= Math.min(2.4D, config.getModernCooldownApsMax())
                && snapshot.rotationsMedium >= Math.max(56, config.getMinRotationsMedium() * 10)
                && snapshot.rotationDeltasMedium >= Math.max(110, config.getMinRotationDeltasMedium() * 14)
                && snapshot.fovMissRatio <= 0.10D
                && snapshot.fovReacquireSampleCount <= 0
                && Double.isNaN(snapshot.meanFovReacquireMs);
        boolean rhythmicLowConversionCooldown = snapshot.attacksMedium >= Math.max(12, config.getMinAttacksMedium() + 10)
                && snapshot.attacksShort >= Math.max(5, config.getMinAttacksShort() + 3)
                && snapshot.aimSampleCount <= Math.max(2, config.getMinAttacksShort())
                && snapshot.preciseAimShare <= 0.08D
                && snapshot.snapPreciseAimShare <= 0.02D
                && snapshot.hitRatio >= 0.24D
                && snapshot.hitRatio <= 0.46D
                && snapshot.confirmedHitsMedium <= Math.max(10, (int) Math.ceil(snapshot.attacksMedium * 0.46D))
                && snapshot.apsShort >= 4.2D
                && snapshot.apsShort <= Math.min(6.2D, config.getModernCooldownApsMax())
                && snapshot.intervalSampleCount >= Math.max(8, config.getModernCooldownMinIntervalSamples() + 6)
                && snapshot.intervalCv <= 0.24D
                && snapshot.rotationsMedium >= Math.max(42, config.getMinRotationsMedium() * 10)
                && snapshot.rotationsMedium <= Math.max(86, config.getMinRotationsMedium() * 22)
                && snapshot.rotationDeltasMedium >= Math.max(42, config.getMinRotationDeltasMedium() * 8)
                && snapshot.rotationDeltasMedium <= Math.max(92, config.getMinRotationDeltasMedium() * 18)
                && snapshot.fovMissRatio <= 0.12D
                && snapshot.fovReacquireSampleCount <= 0
                && Double.isNaN(snapshot.meanFovReacquireMs);
        boolean sampleStarvedPerfectCooldown = snapshot.attacksMedium >= Math.max(4, config.getMinAttacksMedium() + 2)
                && snapshot.attacksMedium <= Math.max(6, config.getMinAttacksMedium() + 4)
                && snapshot.confirmedHitsMedium >= Math.max(4, snapshot.attacksMedium - 1)
                && snapshot.hitRatio >= 0.98D
                && snapshot.aimSampleCount <= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare <= 0.01D
                && snapshot.snapPreciseAimShare <= 0.01D
                && snapshot.apsShort >= 0.90D
                && snapshot.apsShort <= Math.min(2.4D, config.getModernCooldownApsMax())
                && snapshot.rotationsMedium >= Math.max(48, config.getMinRotationsMedium() * 9)
                && snapshot.rotationsMedium <= Math.max(84, config.getMinRotationsMedium() * 22)
                && snapshot.rotationDeltasMedium >= Math.max(80, config.getMinRotationDeltasMedium() * 14)
                && snapshot.rotationDeltasMedium <= Math.max(124, config.getMinRotationDeltasMedium() * 22)
                && snapshot.fovMissRatio <= 0.04D
                && snapshot.fovReacquireSampleCount <= 0
                && Double.isNaN(snapshot.meanFovReacquireMs);
        boolean lowVolumeInstantReacquireHighHit = snapshot.attacksMedium >= Math.max(2, config.getMinAttacksMedium())
                && snapshot.attacksMedium <= Math.max(4, config.getMinAttacksMedium() + 2)
                && snapshot.confirmedHitsMedium >= Math.max(2, snapshot.attacksMedium - 1)
                && snapshot.hitRatio >= 0.98D
                && snapshot.aimSampleCount <= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare <= 0.02D
                && snapshot.snapPreciseAimShare <= 0.01D
                && snapshot.apsShort >= 0.55D
                && snapshot.apsShort <= Math.min(1.20D, config.getModernCooldownApsMax())
                && snapshot.instantRotationAttacksMedium >= Math.max(2, snapshot.attacksMedium - 1)
                && snapshot.rotationsMedium >= Math.max(60, config.getMinRotationsMedium() * 10)
                && snapshot.rotationsMedium <= Math.max(78, config.getMinRotationsMedium() * 20)
                && snapshot.rotationDeltasMedium >= Math.max(120, config.getMinRotationDeltasMedium() * 18)
                && snapshot.rotationDeltasMedium <= Math.max(148, config.getMinRotationDeltasMedium() * 24)
                && snapshot.fovMissRatio >= 0.28D
                && snapshot.fovMissRatio <= 0.55D
                && snapshot.fovReacquireSampleCount >= 2
                && !Double.isNaN(snapshot.meanFovReacquireMs)
                && snapshot.meanFovReacquireMs >= 190.0D
                && snapshot.meanFovReacquireMs <= 280.0D
                && !Double.isNaN(snapshot.meanYawAcceleration)
                && snapshot.meanYawAcceleration >= 10.0D
                && !Double.isNaN(snapshot.meanYawJerk)
                && snapshot.meanYawJerk >= 9.0D;
        boolean midHitInstantReacquireRotation = snapshot.attacksMedium >= Math.max(5, config.getMinAttacksMedium() + 3)
                && snapshot.attacksMedium <= Math.max(6, config.getMinAttacksMedium() + 4)
                && snapshot.confirmedHitsMedium >= Math.max(4, snapshot.attacksMedium - 1)
                && snapshot.hitRatio >= 0.74D
                && snapshot.hitRatio <= 0.88D
                && snapshot.aimSampleCount <= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare <= 0.02D
                && snapshot.snapPreciseAimShare <= 0.01D
                && snapshot.apsShort <= Math.min(2.10D, config.getModernCooldownApsMax())
                && snapshot.instantRotationAttacksMedium >= Math.max(3, snapshot.attacksMedium - 2)
                && snapshot.rotationsMedium >= Math.max(64, config.getMinRotationsMedium() * 10)
                && snapshot.rotationsMedium <= Math.max(84, config.getMinRotationsMedium() * 21)
                && snapshot.rotationDeltasMedium >= Math.max(128, config.getMinRotationDeltasMedium() * 18)
                && snapshot.rotationDeltasMedium <= Math.max(154, config.getMinRotationDeltasMedium() * 25)
                && snapshot.fovMissRatio >= 0.18D
                && snapshot.fovMissRatio <= 0.45D
                && snapshot.fovReacquireSampleCount >= 1
                && !Double.isNaN(snapshot.meanFovReacquireMs)
                && snapshot.meanFovReacquireMs >= 130.0D
                && snapshot.meanFovReacquireMs <= 340.0D
                && !Double.isNaN(snapshot.meanYawAcceleration)
                && snapshot.meanYawAcceleration >= 9.0D
                && !Double.isNaN(snapshot.meanYawJerk)
                && snapshot.meanYawJerk >= 7.0D;
        boolean midTempoCooldownFastReacquire = snapshot.attacksMedium >= Math.max(8, config.getMinAttacksMedium() + 6)
                && snapshot.attacksMedium <= Math.max(14, config.getMinAttacksMedium() * 7)
                && snapshot.confirmedHitsMedium >= Math.max(6, (int) Math.ceil(snapshot.attacksMedium * 0.62D))
                && snapshot.hitRatio >= 0.68D
                && snapshot.hitRatio <= 0.82D
                && snapshot.aimSampleCount <= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare <= 0.02D
                && snapshot.snapPreciseAimShare <= 0.01D
                && snapshot.apsShort >= 2.6D
                && snapshot.apsShort <= Math.min(4.4D, config.getModernCooldownApsMax())
                && snapshot.intervalSampleCount >= config.getModernCooldownMinIntervalSamples()
                && snapshot.rotationsMedium >= Math.max(68, config.getMinRotationsMedium() * 10)
                && snapshot.rotationsMedium <= Math.max(92, config.getMinRotationsMedium() * 23)
                && snapshot.rotationDeltasMedium >= Math.max(136, config.getMinRotationDeltasMedium() * 19)
                && snapshot.rotationDeltasMedium <= Math.max(168, config.getMinRotationDeltasMedium() * 28)
                && snapshot.fovMissRatio >= 0.08D
                && snapshot.fovMissRatio <= 0.22D
                && snapshot.fovReacquireSampleCount >= 1
                && !Double.isNaN(snapshot.meanFovReacquireMs)
                && snapshot.meanFovReacquireMs >= 125.0D
                && snapshot.meanFovReacquireMs <= 190.0D
                && !Double.isNaN(snapshot.meanYawAcceleration)
                && snapshot.meanYawAcceleration >= 5.5D
                && !Double.isNaN(snapshot.meanYawJerk)
                && snapshot.meanYawJerk >= 5.4D;
        boolean lowRotationNoReacquireCooldown = snapshot.attacksMedium >= Math.max(3, config.getMinAttacksMedium() + 1)
                && snapshot.attacksMedium <= Math.max(8, config.getMinAttacksMedium() + 6)
                && snapshot.confirmedHitsMedium >= Math.max(2, (int) Math.ceil(snapshot.attacksMedium * 0.58D))
                && snapshot.hitRatio >= 0.62D
                && snapshot.hitRatio <= 1.00D
                && snapshot.aimSampleCount <= Math.max(2, config.getMinAttacksShort())
                && snapshot.preciseAimShare <= 0.16D
                && snapshot.snapPreciseAimShare <= 0.02D
                && snapshot.apsShort >= 0.45D
                && snapshot.apsShort <= Math.min(4.4D, config.getModernCooldownApsMax())
                && snapshot.rotationsMedium >= Math.max(18, config.getMinRotationsMedium() * 4)
                && snapshot.rotationsMedium <= Math.max(46, config.getMinRotationsMedium() * 12)
                && snapshot.rotationDeltasMedium >= Math.max(38, config.getMinRotationDeltasMedium() * 7)
                && snapshot.rotationDeltasMedium <= Math.max(86, config.getMinRotationDeltasMedium() * 14)
                && snapshot.fovMissRatio <= 0.30D
                && snapshot.fovReacquireSampleCount <= 0
                && Double.isNaN(snapshot.meanFovReacquireMs)
                && !Double.isNaN(snapshot.meanYawAcceleration)
                && snapshot.meanYawAcceleration >= 4.5D
                && !Double.isNaN(snapshot.meanYawJerk)
                && snapshot.meanYawJerk >= 4.8D;
        boolean softCooldownFastReacquire = snapshot.attacksMedium >= Math.max(5, config.getMinAttacksMedium() + 3)
                && snapshot.attacksMedium <= Math.max(7, config.getMinAttacksMedium() + 5)
                && snapshot.confirmedHitsMedium >= Math.max(5, snapshot.attacksMedium - 1)
                && snapshot.hitRatio >= 0.90D
                && snapshot.aimSampleCount <= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare <= 0.02D
                && snapshot.snapPreciseAimShare <= 0.01D
                && snapshot.apsShort >= 1.10D
                && snapshot.apsShort <= Math.min(2.20D, config.getModernCooldownApsMax())
                && snapshot.rotationsMedium >= Math.max(56, config.getMinRotationsMedium() * 9)
                && snapshot.rotationsMedium <= Math.max(74, config.getMinRotationsMedium() * 18)
                && snapshot.rotationDeltasMedium >= Math.max(104, config.getMinRotationDeltasMedium() * 14)
                && snapshot.rotationDeltasMedium <= Math.max(132, config.getMinRotationDeltasMedium() * 22)
                && snapshot.fovMissRatio >= 0.02D
                && snapshot.fovMissRatio <= 0.10D
                && snapshot.fovReacquireSampleCount >= 1
                && !Double.isNaN(snapshot.meanFovReacquireMs)
                && snapshot.meanFovReacquireMs >= 130.0D
                && snapshot.meanFovReacquireMs <= 220.0D
                && !Double.isNaN(snapshot.meanYawAcceleration)
                && snapshot.meanYawAcceleration >= 1.2D
                && snapshot.meanYawAcceleration <= 4.2D
                && !Double.isNaN(snapshot.meanYawJerk)
                && snapshot.meanYawJerk >= 1.0D
                && snapshot.meanYawJerk <= 4.2D;
        boolean compactInstantReacquireSoftKinematics = snapshot.attacksMedium >= Math.max(5, config.getMinAttacksMedium() + 3)
                && snapshot.attacksMedium <= Math.max(6, config.getMinAttacksMedium() + 4)
                && snapshot.confirmedHitsMedium >= Math.max(4, snapshot.attacksMedium - 1)
                && snapshot.hitRatio >= 0.78D
                && snapshot.hitRatio <= 1.00D
                && snapshot.aimSampleCount <= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare <= 0.02D
                && snapshot.snapPreciseAimShare <= 0.01D
                && snapshot.apsShort >= 1.30D
                && snapshot.apsShort <= Math.min(2.05D, config.getModernCooldownApsMax())
                && snapshot.instantRotationAttacksMedium >= Math.max(3, snapshot.attacksMedium - 2)
                && snapshot.rotationsMedium >= Math.max(72, config.getMinRotationsMedium() * 11)
                && snapshot.rotationsMedium <= Math.max(82, config.getMinRotationsMedium() * 20)
                && snapshot.rotationDeltasMedium >= Math.max(144, config.getMinRotationDeltasMedium() * 20)
                && snapshot.rotationDeltasMedium <= Math.max(160, config.getMinRotationDeltasMedium() * 26)
                && snapshot.fovMissRatio >= 0.14D
                && snapshot.fovMissRatio <= 0.28D
                && snapshot.fovReacquireSampleCount >= 1
                && !Double.isNaN(snapshot.meanFovReacquireMs)
                && snapshot.meanFovReacquireMs >= 150.0D
                && snapshot.meanFovReacquireMs <= 205.0D
                && !Double.isNaN(snapshot.meanYawAcceleration)
                && snapshot.meanYawAcceleration >= 5.2D
                && snapshot.meanYawAcceleration <= 7.2D
                && !Double.isNaN(snapshot.meanYawJerk)
                && snapshot.meanYawJerk >= 4.6D
                && snapshot.meanYawJerk <= 6.0D;
        boolean instantReacquireSkirmish = snapshot.attacksMedium >= Math.max(3, config.getMinAttacksMedium() + 1)
                && snapshot.attacksMedium <= Math.max(5, config.getMinAttacksMedium() + 3)
                && snapshot.confirmedHitsMedium >= Math.max(3, snapshot.attacksMedium - 1)
                && snapshot.hitRatio >= 0.72D
                && snapshot.hitRatio <= 1.40D
                && snapshot.aimSampleCount <= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare <= 0.02D
                && snapshot.snapPreciseAimShare <= 0.01D
                && snapshot.apsShort <= Math.max(0.40D, config.getModernCooldownApsMin() * 0.40D)
                && snapshot.instantRotationAttacksMedium >= Math.max(2, snapshot.attacksMedium - 1)
                && snapshot.rotationsMedium >= Math.max(64, config.getMinRotationsMedium() * 12)
                && snapshot.rotationsMedium <= Math.max(92, config.getMinRotationsMedium() * 24)
                && snapshot.rotationDeltasMedium >= Math.max(128, config.getMinRotationDeltasMedium() * 20)
                && snapshot.rotationDeltasMedium <= Math.max(168, config.getMinRotationDeltasMedium() * 28)
                && snapshot.fovMissRatio >= 0.22D
                && snapshot.fovMissRatio <= 0.40D
                && snapshot.fovReacquireSampleCount >= 2
                && !Double.isNaN(snapshot.meanFovReacquireMs)
                && snapshot.meanFovReacquireMs >= 180.0D
                && snapshot.meanFovReacquireMs <= 280.0D
                && !Double.isNaN(snapshot.meanYawAcceleration)
                && snapshot.meanYawAcceleration >= 6.0D
                && !Double.isNaN(snapshot.meanYawJerk)
                && snapshot.meanYawJerk >= 5.0D;
        boolean tightCooldownFastReacquire = snapshot.attacksMedium >= Math.max(4, config.getMinAttacksMedium() + 2)
                && snapshot.attacksMedium <= Math.max(6, config.getMinAttacksMedium() + 4)
                && snapshot.confirmedHitsMedium >= Math.max(4, snapshot.attacksMedium - 1)
                && snapshot.hitRatio >= 0.98D
                && snapshot.aimSampleCount <= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare <= 0.01D
                && snapshot.snapPreciseAimShare <= 0.01D
                && snapshot.apsShort >= 0.70D
                && snapshot.apsShort <= Math.min(1.95D, config.getModernCooldownApsMax())
                && snapshot.instantRotationAttacksMedium >= Math.max(2, snapshot.attacksMedium - 1)
                && snapshot.rotationsMedium >= Math.max(68, config.getMinRotationsMedium() * 11)
                && snapshot.rotationsMedium <= Math.max(84, config.getMinRotationsMedium() * 22)
                && snapshot.rotationDeltasMedium >= Math.max(136, config.getMinRotationDeltasMedium() * 19)
                && snapshot.rotationDeltasMedium <= Math.max(160, config.getMinRotationDeltasMedium() * 27)
                && snapshot.fovMissRatio >= 0.10D
                && snapshot.fovMissRatio <= 0.22D
                && snapshot.fovReacquireSampleCount >= 2
                && !Double.isNaN(snapshot.meanFovReacquireMs)
                && snapshot.meanFovReacquireMs >= 130.0D
                && snapshot.meanFovReacquireMs <= 180.0D
                && !Double.isNaN(snapshot.meanYawAcceleration)
                && snapshot.meanYawAcceleration >= 7.0D
                && !Double.isNaN(snapshot.meanYawJerk)
                && snapshot.meanYawJerk >= 6.5D;
        boolean compactCooldownStickiness = snapshot.attacksMedium >= Math.max(3, config.getMinAttacksMedium() + 1)
                && snapshot.attacksMedium <= Math.max(5, config.getMinAttacksMedium() + 3)
                && snapshot.confirmedHitsMedium >= Math.max(3, snapshot.attacksMedium - 1)
                && snapshot.hitRatio >= 0.98D
                && snapshot.aimSampleCount <= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare <= 0.01D
                && snapshot.snapPreciseAimShare <= 0.01D
                && snapshot.apsShort >= 1.25D
                && snapshot.apsShort <= Math.min(2.05D, config.getModernCooldownApsMax())
                && snapshot.instantRotationAttacksMedium >= Math.max(2, snapshot.attacksMedium - 1)
                && snapshot.rotationsMedium >= Math.max(60, config.getMinRotationsMedium() * 10)
                && snapshot.rotationsMedium <= Math.max(74, config.getMinRotationsMedium() * 19)
                && snapshot.rotationDeltasMedium >= Math.max(72, config.getMinRotationDeltasMedium() * 12)
                && snapshot.rotationDeltasMedium <= Math.max(112, config.getMinRotationDeltasMedium() * 19)
                && snapshot.fovMissRatio >= 0.02D
                && snapshot.fovMissRatio <= 0.08D
                && snapshot.fovReacquireSampleCount >= 1
                && !Double.isNaN(snapshot.meanFovReacquireMs)
                && snapshot.meanFovReacquireMs >= 70.0D
                && snapshot.meanFovReacquireMs <= 115.0D
                && !Double.isNaN(snapshot.meanYawAcceleration)
                && snapshot.meanYawAcceleration >= 7.0D
                && !Double.isNaN(snapshot.meanYawJerk)
                && snapshot.meanYawJerk >= 6.0D;
        boolean tempoBlockOverlapReacquire = snapshot.attacksMedium >= Math.max(10, config.getMinAttacksMedium() + 6)
                && snapshot.attacksMedium <= Math.max(20, config.getMinAttacksMedium() * 6)
                && snapshot.attacksShort >= Math.max(4, config.getMinAttacksShort() + 2)
                && snapshot.confirmedHitsMedium >= Math.max(7, (int) Math.ceil(snapshot.attacksMedium * 0.48D))
                && snapshot.hitRatio >= 0.62D
                && snapshot.hitRatio <= 0.84D
                && snapshot.aimSampleCount <= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare <= 0.02D
                && snapshot.snapPreciseAimShare <= 0.01D
                && snapshot.apsShort >= 4.2D
                && snapshot.apsShort <= Math.min(6.2D, config.getModernCooldownApsMax())
                && snapshot.blockOverlapMedium >= 1
                && snapshot.blockOverlapMedium <= Math.max(4, (int) Math.ceil(snapshot.attacksMedium * 0.22D))
                && snapshot.inventoryOverlapMedium <= Math.max(1, (int) Math.ceil(snapshot.attacksMedium * 0.08D))
                && snapshot.rotationsMedium >= Math.max(62, config.getMinRotationsMedium() * 10)
                && snapshot.rotationsMedium <= Math.max(92, config.getMinRotationsMedium() * 24)
                && snapshot.rotationDeltasMedium >= Math.max(120, config.getMinRotationDeltasMedium() * 16)
                && snapshot.rotationDeltasMedium <= Math.max(168, config.getMinRotationDeltasMedium() * 28)
                && snapshot.fovMissRatio >= 0.06D
                && snapshot.fovMissRatio <= 0.16D
                && snapshot.fovReacquireSampleCount >= 1
                && !Double.isNaN(snapshot.meanFovReacquireMs)
                && snapshot.meanFovReacquireMs >= 80.0D
                && snapshot.meanFovReacquireMs <= 130.0D;
        boolean tempoBlockOverlapSlowReacquire = snapshot.attacksMedium >= Math.max(14, config.getMinAttacksMedium() + 10)
                && snapshot.attacksMedium <= Math.max(22, config.getMinAttacksMedium() * 7)
                && snapshot.attacksShort >= Math.max(4, config.getMinAttacksShort() + 2)
                && snapshot.confirmedHitsMedium >= Math.max(10, (int) Math.ceil(snapshot.attacksMedium * 0.68D))
                && snapshot.hitRatio >= 0.74D
                && snapshot.hitRatio <= 0.86D
                && snapshot.aimSampleCount <= Math.max(1, config.getMinAttacksShort() - 1)
                && snapshot.preciseAimShare <= 0.02D
                && snapshot.snapPreciseAimShare <= 0.01D
                && snapshot.apsShort >= 3.6D
                && snapshot.apsShort <= Math.min(4.6D, config.getModernCooldownApsMax())
                && snapshot.blockOverlapMedium >= 1
                && snapshot.blockOverlapMedium <= Math.max(4, (int) Math.ceil(snapshot.attacksMedium * 0.18D))
                && snapshot.inventoryOverlapMedium <= Math.max(1, (int) Math.ceil(snapshot.attacksMedium * 0.06D))
                && snapshot.rotationsMedium >= Math.max(64, config.getMinRotationsMedium() * 10)
                && snapshot.rotationsMedium <= Math.max(86, config.getMinRotationsMedium() * 22)
                && snapshot.rotationDeltasMedium >= Math.max(124, config.getMinRotationDeltasMedium() * 16)
                && snapshot.rotationDeltasMedium <= Math.max(160, config.getMinRotationDeltasMedium() * 27)
                && snapshot.fovMissRatio >= 0.10D
                && snapshot.fovMissRatio <= 0.24D
                && snapshot.fovReacquireSampleCount >= 1
                && !Double.isNaN(snapshot.meanFovReacquireMs)
                && snapshot.meanFovReacquireMs >= 220.0D
                && snapshot.meanFovReacquireMs <= 600.0D
                && !Double.isNaN(snapshot.meanYawAcceleration)
                && snapshot.meanYawAcceleration >= 2.8D
                && !Double.isNaN(snapshot.meanYawJerk)
                && snapshot.meanYawJerk >= 2.8D;
        boolean legacyTempoDrift = snapshot.attacksMedium >= Math.max(18, config.getMinAttacksMedium() * 4)
                && snapshot.attacksShort >= Math.max(6, config.getMinAttacksShort() * 3)
                && snapshot.apsShort >= 7.2D
                && snapshot.hitRatio <= 0.42D
                && snapshot.confirmedHitsMedium <= Math.max(14, (int) Math.ceil(snapshot.attacksMedium * 0.52D))
                && snapshot.aimSampleCount <= Math.max(2, config.getMinAttacksShort())
                && snapshot.preciseAimShare <= 0.06D
                && snapshot.snapPreciseAimShare <= 0.04D
                && snapshot.rotationDeltasMedium <= Math.max(40, config.getMinRotationDeltasMedium() * 6);

        return new ProfileFlags(
                sparsePrecisionHighRotation,
                lowSnapCooldownPrecision,
                highHitLowSnapCooldown,
                cooldownCadenceSaturation,
                lowPrecisionLowHitHighRotation,
                lowApsInstantNoAim,
                lowTempoNoAimHighHitRotation,
                cooldownNoAimPerfectHitRotation,
                cooldownNoAimFastReacquire,
                cooldownNoAimNoReacquire,
                rhythmicLowConversionCooldown,
                sampleStarvedPerfectCooldown,
                lowVolumeInstantReacquireHighHit,
                midHitInstantReacquireRotation,
                midTempoCooldownFastReacquire,
                lowRotationNoReacquireCooldown,
                softCooldownFastReacquire,
                compactInstantReacquireSoftKinematics,
                instantReacquireSkirmish,
                tightCooldownFastReacquire,
                compactCooldownStickiness,
                tempoBlockOverlapReacquire,
                tempoBlockOverlapSlowReacquire,
                legacyTempoDrift
        );
    }

    static final class ProfileFlags {
        static final ProfileFlags EMPTY = new ProfileFlags(false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);

        final boolean sparsePrecisionHighRotation;
        final boolean lowSnapCooldownPrecision;
        final boolean highHitLowSnapCooldown;
        final boolean cooldownCadenceSaturation;
        final boolean lowPrecisionLowHitHighRotation;
        final boolean lowApsInstantNoAim;
        final boolean lowTempoNoAimHighHitRotation;
        final boolean cooldownNoAimPerfectHitRotation;
        final boolean cooldownNoAimFastReacquire;
        final boolean cooldownNoAimNoReacquire;
        final boolean rhythmicLowConversionCooldown;
        final boolean sampleStarvedPerfectCooldown;
        final boolean lowVolumeInstantReacquireHighHit;
        final boolean midHitInstantReacquireRotation;
        final boolean midTempoCooldownFastReacquire;
        final boolean lowRotationNoReacquireCooldown;
        final boolean softCooldownFastReacquire;
        final boolean compactInstantReacquireSoftKinematics;
        final boolean instantReacquireSkirmish;
        final boolean tightCooldownFastReacquire;
        final boolean compactCooldownStickiness;
        final boolean tempoBlockOverlapReacquire;
        final boolean tempoBlockOverlapSlowReacquire;
        final boolean legacyTempoDrift;

        private ProfileFlags(boolean sparsePrecisionHighRotation,
                             boolean lowSnapCooldownPrecision,
                             boolean highHitLowSnapCooldown,
                             boolean cooldownCadenceSaturation,
                             boolean lowPrecisionLowHitHighRotation,
                             boolean lowApsInstantNoAim,
                             boolean lowTempoNoAimHighHitRotation,
                             boolean cooldownNoAimPerfectHitRotation,
                             boolean cooldownNoAimFastReacquire,
                             boolean cooldownNoAimNoReacquire,
                             boolean rhythmicLowConversionCooldown,
                             boolean sampleStarvedPerfectCooldown,
                             boolean lowVolumeInstantReacquireHighHit,
                             boolean midHitInstantReacquireRotation,
                             boolean midTempoCooldownFastReacquire,
                             boolean lowRotationNoReacquireCooldown,
                             boolean softCooldownFastReacquire,
                             boolean compactInstantReacquireSoftKinematics,
                             boolean instantReacquireSkirmish,
                             boolean tightCooldownFastReacquire,
                             boolean compactCooldownStickiness,
                             boolean tempoBlockOverlapReacquire,
                             boolean tempoBlockOverlapSlowReacquire,
                             boolean legacyTempoDrift) {
            this.sparsePrecisionHighRotation = sparsePrecisionHighRotation;
            this.lowSnapCooldownPrecision = lowSnapCooldownPrecision;
            this.highHitLowSnapCooldown = highHitLowSnapCooldown;
            this.cooldownCadenceSaturation = cooldownCadenceSaturation;
            this.lowPrecisionLowHitHighRotation = lowPrecisionLowHitHighRotation;
            this.lowApsInstantNoAim = lowApsInstantNoAim;
            this.lowTempoNoAimHighHitRotation = lowTempoNoAimHighHitRotation;
            this.cooldownNoAimPerfectHitRotation = cooldownNoAimPerfectHitRotation;
            this.cooldownNoAimFastReacquire = cooldownNoAimFastReacquire;
            this.cooldownNoAimNoReacquire = cooldownNoAimNoReacquire;
            this.rhythmicLowConversionCooldown = rhythmicLowConversionCooldown;
            this.sampleStarvedPerfectCooldown = sampleStarvedPerfectCooldown;
            this.lowVolumeInstantReacquireHighHit = lowVolumeInstantReacquireHighHit;
            this.midHitInstantReacquireRotation = midHitInstantReacquireRotation;
            this.midTempoCooldownFastReacquire = midTempoCooldownFastReacquire;
            this.lowRotationNoReacquireCooldown = lowRotationNoReacquireCooldown;
            this.softCooldownFastReacquire = softCooldownFastReacquire;
            this.compactInstantReacquireSoftKinematics = compactInstantReacquireSoftKinematics;
            this.instantReacquireSkirmish = instantReacquireSkirmish;
            this.tightCooldownFastReacquire = tightCooldownFastReacquire;
            this.compactCooldownStickiness = compactCooldownStickiness;
            this.tempoBlockOverlapReacquire = tempoBlockOverlapReacquire;
            this.tempoBlockOverlapSlowReacquire = tempoBlockOverlapSlowReacquire;
            this.legacyTempoDrift = legacyTempoDrift;
        }
    }
}
