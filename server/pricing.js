const {
  addCalendarMonths,
  addCalendarYears,
  endOfLocalDay,
  normalizeIdentifier
} = require("./utils");

const LICENSE_TYPES = Object.freeze({
  monthly: {
    id: "monthly",
    label: "Monthly",
    durationDays: 30,
    basePriceEur: 5.0,
    addonPriceEur: 1.0
  },
  yearly: {
    id: "yearly",
    label: "Yearly",
    durationDays: 365,
    basePriceEur: 30.0,
    addonPriceEur: 5.0
  },
  lifetime: {
    id: "lifetime",
    label: "Lifetime",
    durationDays: null,
    basePriceEur: 125.0,
    addonPriceEur: 10.0
  }
});

const LICENSE_PLANS = Object.freeze({
  basic: {
    id: "basic",
    label: "Basic",
    cloudIncludedSlots: 0,
    cloudSlotBundleSize: 5,
    cloudBasePriceEur: {
      monthly: 0.0,
      yearly: 0.0,
      lifetime: 0.0
    },
    cloudBundlePriceEur: {
      monthly: 0.0,
      yearly: 0.0,
      lifetime: 0.0
    }
  },
  pro: {
    id: "pro",
    label: "Pro",
    cloudIncludedSlots: 5,
    cloudSlotBundleSize: 5,
    cloudBasePriceEur: {
      monthly: 5.0,
      yearly: 30.0,
      lifetime: 0.0
    },
    cloudBundlePriceEur: {
      monthly: 5.0,
      yearly: 30.0,
      lifetime: 0.0
    }
  }
});

const DEFAULT_LICENSE_TYPE = "monthly";
const DEFAULT_LICENSE_PLAN = "basic";

function normalizeLicenseType(value, fallback = DEFAULT_LICENSE_TYPE) {
  const normalized = normalizeIdentifier(value) || fallback;
  return LICENSE_TYPES[normalized] ? normalized : fallback;
}

function normalizeLicensePlan(value, fallback = DEFAULT_LICENSE_PLAN) {
  const normalized = normalizeIdentifier(value) || fallback;
  return LICENSE_PLANS[normalized] ? normalized : fallback;
}

function getLicenseTypeDefinition(value) {
  return LICENSE_TYPES[normalizeLicenseType(value)];
}

function normalizeLicensePlanForType(licenseType, licensePlan) {
  return normalizeLicenseType(licenseType) === "lifetime"
    ? "basic"
    : normalizeLicensePlan(licensePlan);
}

function getLicensePlanDefinition(value) {
  return LICENSE_PLANS[normalizeLicensePlan(value)];
}

function normalizeCloudPlayerSlots(licensePlan, value) {
  const plan = getLicensePlanDefinition(licensePlan);
  if (plan.id !== "pro") {
    return 0;
  }
  const parsed = Number.parseInt(value, 10) || plan.cloudIncludedSlots;
  if (parsed <= 5) {
    return 5;
  }
  if (parsed <= 10) {
    return 10;
  }
  return 15;
}

function roundCurrency(value) {
  return Math.round((Number(value) + Number.EPSILON) * 100) / 100;
}

function computeSuggestedPricing({ licenseType, licensePlan = DEFAULT_LICENSE_PLAN, maxHwids = 1, maxInstances = 1, cloudPlayerSlots = 0 } = {}) {
  const definition = getLicenseTypeDefinition(licenseType);
  const plan = getLicensePlanDefinition(normalizeLicensePlanForType(definition.id, licensePlan));
  const normalizedHwids = Math.max(1, Number.parseInt(maxHwids, 10) || 1);
  const normalizedInstances = Math.max(1, Number.parseInt(maxInstances, 10) || 1);
  const normalizedCloudSlots = normalizeCloudPlayerSlots(plan.id, cloudPlayerSlots);
  const addonBundles = Math.max(0, Math.max(normalizedHwids, normalizedInstances) - 1);
  const cloudExtraSlots = Math.max(0, normalizedCloudSlots - plan.cloudIncludedSlots);
  const cloudAddonBundles = plan.id === "pro"
    ? Math.ceil(cloudExtraSlots / plan.cloudSlotBundleSize)
    : 0;
  const cloudBasePriceEur = roundCurrency(plan.cloudBasePriceEur[definition.id] || 0);
  const cloudBundlePriceEur = roundCurrency(plan.cloudBundlePriceEur[definition.id] || 0);
  const cloudPriceEur = roundCurrency(cloudBasePriceEur + (cloudAddonBundles * cloudBundlePriceEur));
  const recommendedPriceEur = roundCurrency(definition.basePriceEur
    + (addonBundles * definition.addonPriceEur)
    + cloudPriceEur);

  return {
    licenseType: definition.id,
    licensePlan: plan.id,
    label: definition.label,
    planLabel: plan.label,
    basePriceEur: roundCurrency(definition.basePriceEur),
    addonBundlePriceEur: roundCurrency(definition.addonPriceEur),
    addonBundles,
    cloudIncludedSlots: plan.cloudIncludedSlots,
    cloudPlayerSlots: normalizedCloudSlots,
    cloudAddonBundles,
    cloudBasePriceEur,
    cloudBundlePriceEur,
    cloudPriceEur,
    recommendedPriceEur
  };
}

function resolveLicenseExpiry(licenseType, explicitExpiry, baseTimestamp) {
  const definition = getLicenseTypeDefinition(licenseType);
  if (explicitExpiry !== undefined && explicitExpiry !== null && String(explicitExpiry).trim() !== "") {
    const parsed = Date.parse(String(explicitExpiry).trim());
    if (Number.isFinite(parsed)) {
      return endOfLocalDay(new Date(parsed));
    }
    throw new Error("Invalid expiry date.");
  }

  if (definition.durationDays == null) {
    return null;
  }

  if (definition.id === "yearly") {
    return endOfLocalDay(addCalendarYears(baseTimestamp, 1));
  }

  return endOfLocalDay(addCalendarMonths(baseTimestamp, 1));
}

function isLicenseExpired(expiresAt, referenceTime = Date.now()) {
  if (!expiresAt) {
    return false;
  }

  const parsed = Date.parse(expiresAt);
  return Number.isFinite(parsed) && parsed <= referenceTime;
}

function getDaysRemaining(expiresAt, referenceTime = Date.now()) {
  if (!expiresAt) {
    return null;
  }

  const parsed = Date.parse(expiresAt);
  if (!Number.isFinite(parsed)) {
    return null;
  }

  return Math.ceil((parsed - referenceTime) / 86_400_000);
}

module.exports = {
  LICENSE_PLANS,
  LICENSE_TYPES,
  computeSuggestedPricing,
  getDaysRemaining,
  getLicensePlanDefinition,
  getLicenseTypeDefinition,
  isLicenseExpired,
  normalizeCloudPlayerSlots,
  normalizeLicensePlanForType,
  normalizeLicensePlan,
  normalizeLicenseType,
  resolveLicenseExpiry
};
