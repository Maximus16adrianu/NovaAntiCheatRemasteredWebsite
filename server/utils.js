const crypto = require("crypto");

function nowIso() {
  return new Date().toISOString();
}

function addDays(dateInput, days) {
  const date = dateInput instanceof Date ? new Date(dateInput.getTime()) : new Date(dateInput || Date.now());
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString();
}

function shiftUtcDate(dateInput, { months = 0, years = 0 } = {}) {
  const date = dateInput instanceof Date ? new Date(dateInput.getTime()) : new Date(dateInput || Date.now());
  if (Number.isNaN(date.getTime())) {
    return new Date().toISOString();
  }

  const targetYear = date.getUTCFullYear() + years;
  const targetMonth = date.getUTCMonth() + months;
  const targetDay = date.getUTCDate();
  const hours = date.getUTCHours();
  const minutes = date.getUTCMinutes();
  const seconds = date.getUTCSeconds();
  const milliseconds = date.getUTCMilliseconds();

  const monthStart = new Date(Date.UTC(targetYear, targetMonth, 1, hours, minutes, seconds, milliseconds));
  const lastDayOfTargetMonth = new Date(Date.UTC(
    monthStart.getUTCFullYear(),
    monthStart.getUTCMonth() + 1,
    0,
    hours,
    minutes,
    seconds,
    milliseconds
  )).getUTCDate();

  monthStart.setUTCDate(Math.min(targetDay, lastDayOfTargetMonth));
  return monthStart.toISOString();
}

function addCalendarMonths(dateInput, months) {
  return shiftUtcDate(dateInput, { months });
}

function addCalendarYears(dateInput, years) {
  return shiftUtcDate(dateInput, { years });
}

function endOfLocalDay(dateInput) {
  const date = dateInput instanceof Date ? new Date(dateInput.getTime()) : new Date(dateInput || Date.now());
  if (Number.isNaN(date.getTime())) {
    const fallback = new Date();
    fallback.setHours(23, 59, 0, 0);
    return fallback.toISOString();
  }

  date.setHours(23, 59, 0, 0);
  return date.toISOString();
}

function clampInteger(value, min, max, fallback = min) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed)) {
    return fallback;
  }
  return Math.min(max, Math.max(min, parsed));
}

function normalizeText(value, fallback = "") {
  if (value === undefined || value === null) {
    return fallback;
  }
  return String(value).trim();
}

function normalizeIdentifier(value) {
  return normalizeText(value).toLowerCase();
}

function stableStringify(value) {
  if (value === null || value === undefined) {
    return "null";
  }
  if (Array.isArray(value)) {
    return `[${value.map((entry) => stableStringify(entry)).join(",")}]`;
  }
  if (typeof value === "object") {
    const keys = Object.keys(value).sort();
    return `{${keys.map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function safeJsonParse(value, fallback = null) {
  try {
    return JSON.parse(value);
  } catch (_error) {
    return fallback;
  }
}

function generateLicenseKey() {
  const bytes = crypto.randomBytes(10).toString("hex").toUpperCase();
  return `NOVA-${bytes.slice(0, 5)}-${bytes.slice(5, 10)}-${bytes.slice(10, 15)}-${bytes.slice(15, 20)}`;
}

function generateToken(size = 32) {
  return crypto.randomBytes(size).toString("hex");
}

class HttpError extends Error {
  constructor(status, message, details = null) {
    super(message);
    this.status = status;
    this.details = details;
  }
}

module.exports = {
  HttpError,
  addCalendarMonths,
  addCalendarYears,
  addDays,
  endOfLocalDay,
  clampInteger,
  generateLicenseKey,
  generateToken,
  normalizeIdentifier,
  normalizeText,
  nowIso,
  safeJsonParse,
  stableStringify
};
