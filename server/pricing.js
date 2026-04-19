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
    basePriceEur: 75.0,
    addonPriceEur: 10.0
  }
});

const DEFAULT_LICENSE_TYPE = "monthly";

function normalizeLicenseType(value, fallback = DEFAULT_LICENSE_TYPE) {
  const normalized = normalizeIdentifier(value) || fallback;
  return LICENSE_TYPES[normalized] ? normalized : fallback;
}

function getLicenseTypeDefinition(value) {
  return LICENSE_TYPES[normalizeLicenseType(value)];
}

function roundCurrency(value) {
  return Math.round((Number(value) + Number.EPSILON) * 100) / 100;
}

function computeSuggestedPricing({ licenseType, maxHwids = 1, maxInstances = 1 } = {}) {
  const definition = getLicenseTypeDefinition(licenseType);
  const normalizedHwids = Math.max(1, Number.parseInt(maxHwids, 10) || 1);
  const normalizedInstances = Math.max(1, Number.parseInt(maxInstances, 10) || 1);
  const addonBundles = Math.max(0, Math.max(normalizedHwids, normalizedInstances) - 1);
  const recommendedPriceEur = roundCurrency(definition.basePriceEur + (addonBundles * definition.addonPriceEur));

  return {
    licenseType: definition.id,
    label: definition.label,
    basePriceEur: roundCurrency(definition.basePriceEur),
    addonBundlePriceEur: roundCurrency(definition.addonPriceEur),
    addonBundles,
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
  LICENSE_TYPES,
  computeSuggestedPricing,
  getDaysRemaining,
  getLicenseTypeDefinition,
  isLicenseExpired,
  normalizeLicenseType,
  resolveLicenseExpiry
};
