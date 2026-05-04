const ADMIN_KEY_STORAGE = "novaac_admin_api_key";
const PRICING = {
  monthly: { label: "Monthly", base: 5, addon: 1 },
  yearly: { label: "Yearly", base: 30, addon: 5 },
  lifetime: { label: "Lifetime", base: 125, addon: 10 }
};
const LICENSE_PLANS = {
  basic: {
    label: "Basic",
    cloudIncludedSlots: 0,
    cloudSlotBundleSize: 5,
    cloudBase: { monthly: 0, yearly: 0, lifetime: 0 },
    cloudAddon: { monthly: 0, yearly: 0, lifetime: 0 }
  },
  pro: {
    label: "Pro",
    cloudIncludedSlots: 10,
    cloudSlotBundleSize: 15,
    cloudBase: { monthly: 10, yearly: 60, lifetime: 0 },
    cloudAddon: { monthly: 10, yearly: 60, lifetime: 0 }
  }
};
const TRUSTED_HTML = Symbol("trustedHtml");

const state = {
  apiKey: localStorage.getItem(ADMIN_KEY_STORAGE) || "",
  licenses: [],
  jars: [],
  auditLogs: [],
  auditFilters: {
    actors: [],
    actions: []
  },
  lockdown: {
    enabled: false,
    reason: "",
    since: null
  },
  selectedLicenseId: null,
  selectedDevices: new Set(),
  selectedInstances: new Set(),
  draggingJarId: null,
  openJarId: null,
  formLicense: null,
  adminActionConfirm: null
};

const elements = {
  authPanel: document.getElementById("authPanel"),
  adminPanel: document.getElementById("adminPanel"),
  loginForm: document.getElementById("loginForm"),
  apiKey: document.getElementById("apiKey"),
  authStatus: document.getElementById("authStatus"),
  adminStatus: document.getElementById("adminStatus"),
  logoutButton: document.getElementById("logoutButton"),
  statTotalLicenses: document.getElementById("statTotalLicenses"),
  statActiveLicenses: document.getElementById("statActiveLicenses"),
  statExpiredLicenses: document.getElementById("statExpiredLicenses"),
  statActiveDevices: document.getElementById("statActiveDevices"),
  statActiveInstances: document.getElementById("statActiveInstances"),
  statOpenSessions: document.getElementById("statOpenSessions"),
  lockdownStateBadge: document.getElementById("lockdownStateBadge"),
  lockdownReasonField: document.getElementById("lockdownReasonField"),
  lockdownStatusText: document.getElementById("lockdownStatusText"),
  lockdownSinceText: document.getElementById("lockdownSinceText"),
  lockdownReasonText: document.getElementById("lockdownReasonText"),
  toggleLockdownButton: document.getElementById("toggleLockdownButton"),
  refreshLockdownButton: document.getElementById("refreshLockdownButton"),
  openInspectorButton: document.getElementById("openInspectorButton"),
  openJarManagerButton: document.getElementById("openJarManagerButton"),
  openSessionsButton: document.getElementById("openSessionsButton"),
  openAuditButton: document.getElementById("openAuditButton"),
  refreshDataButton: document.getElementById("refreshDataButton"),
  reconcileSessionsButton: document.getElementById("reconcileSessionsButton"),
  backupButton: document.getElementById("backupButton"),
  licenseSearch: document.getElementById("licenseSearch"),
  licensesTableBody: document.getElementById("licensesTableBody"),
  selectedLicenseSummary: document.getElementById("selectedLicenseSummary"),
  summaryKey: document.getElementById("summaryKey"),
  summaryUser: document.getElementById("summaryUser"),
  summaryType: document.getElementById("summaryType"),
  summaryLimits: document.getElementById("summaryLimits"),
  summaryStatus: document.getElementById("summaryStatus"),
  summaryExpiry: document.getElementById("summaryExpiry"),
  licenseForm: document.getElementById("licenseForm"),
  licenseFormHeading: document.getElementById("licenseFormHeading"),
  licenseId: document.getElementById("licenseId"),
  licenseKeyField: document.getElementById("licenseKeyField"),
  customerUsernameField: document.getElementById("customerUsernameField"),
  displayNameField: document.getElementById("displayNameField"),
  licenseTypeField: document.getElementById("licenseTypeField"),
  licensePlanField: document.getElementById("licensePlanField"),
  cloudPlayerSlotsField: document.getElementById("cloudPlayerSlotsField"),
  expiryPreviewLabel: document.getElementById("expiryPreviewLabel"),
  expiryPreviewValue: document.getElementById("expiryPreviewValue"),
  expiryPreviewMeta: document.getElementById("expiryPreviewMeta"),
  maxSlotsField: document.getElementById("maxSlotsField"),
  resetIntervalField: document.getElementById("resetIntervalField"),
  notesField: document.getElementById("notesField"),
  activeField: document.getElementById("activeField"),
  pricePreviewTotal: document.getElementById("pricePreviewTotal"),
  pricePreviewMeta: document.getElementById("pricePreviewMeta"),
  saveLicenseButton: document.getElementById("saveLicenseButton"),
  deleteLicenseButton: document.getElementById("deleteLicenseButton"),
  resetFormButton: document.getElementById("resetFormButton"),
  jarUploadForm: document.getElementById("jarUploadForm"),
  jarFileField: document.getElementById("jarFileField"),
  jarNameField: document.getElementById("jarNameField"),
  jarNoteField: document.getElementById("jarNoteField"),
  jarVersionsField: document.getElementById("jarVersionsField"),
  jarChannelField: document.getElementById("jarChannelField"),
  jarChangelogField: document.getElementById("jarChangelogField"),
  jarAccessField: document.getElementById("jarAccessField"),
  jarRecommendedField: document.getElementById("jarRecommendedField"),
  uploadJarButton: document.getElementById("uploadJarButton"),
  jarList: document.getElementById("jarList"),
  jarCountChip: document.getElementById("jarCountChip"),
  devicesHeading: document.getElementById("devicesHeading"),
  devicesSubheading: document.getElementById("devicesSubheading"),
  devicesTableBody: document.getElementById("devicesTableBody"),
  refreshDevicesButton: document.getElementById("refreshDevicesButton"),
  resetDevicesButton: document.getElementById("resetDevicesButton"),
  instancesHeading: document.getElementById("instancesHeading"),
  instancesSubheading: document.getElementById("instancesSubheading"),
  instancesTableBody: document.getElementById("instancesTableBody"),
  refreshInstancesButton: document.getElementById("refreshInstancesButton"),
  resetInstancesButton: document.getElementById("resetInstancesButton"),
  refreshSessionsButton: document.getElementById("refreshSessionsButton"),
  sessionsTableBody: document.getElementById("sessionsTableBody"),
  refreshAuditLogsButton: document.getElementById("refreshAuditLogsButton"),
  auditSearchField: document.getElementById("auditSearchField"),
  auditActorField: document.getElementById("auditActorField"),
  auditActionField: document.getElementById("auditActionField"),
  auditLimitField: document.getElementById("auditLimitField"),
  auditLogsTableBody: document.getElementById("auditLogsTableBody"),
  inspectorModal: document.getElementById("inspectorModal"),
  closeInspectorButton: document.getElementById("closeInspectorButton"),
  jarManagerModal: document.getElementById("jarManagerModal"),
  closeJarManagerButton: document.getElementById("closeJarManagerButton"),
  sessionsModal: document.getElementById("sessionsModal"),
  closeSessionsButton: document.getElementById("closeSessionsButton"),
  auditModal: document.getElementById("auditModal"),
  closeAuditButton: document.getElementById("closeAuditButton"),
  adminActionConfirmModal: document.getElementById("adminActionConfirmModal"),
  adminActionConfirmPill: document.getElementById("adminActionConfirmPill"),
  adminActionConfirmTitle: document.getElementById("adminActionConfirmTitle"),
  adminActionConfirmDescription: document.getElementById("adminActionConfirmDescription"),
  adminActionConfirmInfoBox: document.getElementById("adminActionConfirmInfoBox"),
  adminActionConfirmInfoTitle: document.getElementById("adminActionConfirmInfoTitle"),
  adminActionConfirmInfoText: document.getElementById("adminActionConfirmInfoText"),
  adminActionConfirmEffects: document.getElementById("adminActionConfirmEffects"),
  adminActionConfirmCancelButton: document.getElementById("adminActionConfirmCancelButton"),
  adminActionConfirmSubmitButton: document.getElementById("adminActionConfirmSubmitButton"),
  closeAdminActionConfirmButton: document.getElementById("closeAdminActionConfirmButton"),
  downloadKeyModal: document.getElementById("downloadKeyModal"),
  downloadKeyForm: document.getElementById("downloadKeyForm"),
  downloadKeyInput: document.getElementById("downloadKeyInput"),
  cancelDownloadButton: document.getElementById("cancelDownloadButton")
};

function esc(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll("\"", "&quot;")
    .replaceAll("'", "&#39;");
}

function rawHtml(value) {
  return {
    [TRUSTED_HTML]: String(value ?? "")
  };
}

function normalizeHtmlInterpolation(value) {
  if (value == null || value === false) {
    return "";
  }
  if (Array.isArray(value)) {
    return value.map((entry) => normalizeHtmlInterpolation(entry)).join("");
  }
  if (typeof value === "object" && value[TRUSTED_HTML] != null) {
    return value[TRUSTED_HTML];
  }
  return esc(value);
}

function html(strings, ...values) {
  let output = "";
  for (let index = 0; index < strings.length; index += 1) {
    output += strings[index];
    if (index < values.length) {
      output += normalizeHtmlInterpolation(values[index]);
    }
  }
  return output;
}

function showStatus(element, message, tone = "info") {
  element.textContent = message;
  element.className = `status-banner ${tone}`;
}

function clearStatus(element) {
  element.textContent = "";
  element.className = "status-banner hidden";
}

function openModal(modal) {
  if (!modal) {
    return;
  }
  modal.classList.remove("hidden");
  modal.setAttribute("aria-hidden", "false");
}

function closeModal(modal) {
  if (!modal) {
    return;
  }
  modal.classList.add("hidden");
  modal.setAttribute("aria-hidden", "true");
}

function closeAdminActionConfirm(confirmed = false) {
  if (elements.adminActionConfirmModal) {
    closeModal(elements.adminActionConfirmModal);
  }

  if (elements.adminActionConfirmSubmitButton) {
    elements.adminActionConfirmSubmitButton.className = "button button-primary";
    elements.adminActionConfirmSubmitButton.textContent = "Confirm";
    elements.adminActionConfirmSubmitButton.disabled = false;
  }
  if (elements.adminActionConfirmInfoBox) {
    elements.adminActionConfirmInfoBox.className = "action-info-box info";
  }

  const pending = state.adminActionConfirm;
  state.adminActionConfirm = null;
  if (pending?.resolve) {
    pending.resolve(Boolean(confirmed));
  }
}

function requestAdminActionConfirm({
  pill = "Confirm",
  title = "Confirm action",
  description = "Review what this action changes before you continue.",
  infoTitle = "What this does",
  infoText = "This action updates admin data immediately.",
  effects = [],
  confirmLabel = "Confirm",
  tone = "info"
} = {}) {
  if (!elements.adminActionConfirmModal) {
    return Promise.resolve(true);
  }

  if (state.adminActionConfirm?.resolve) {
    state.adminActionConfirm.resolve(false);
  }

  elements.adminActionConfirmPill.textContent = pill;
  elements.adminActionConfirmTitle.textContent = title;
  elements.adminActionConfirmDescription.textContent = description;
  elements.adminActionConfirmInfoTitle.textContent = infoTitle;
  elements.adminActionConfirmInfoText.textContent = infoText;
  elements.adminActionConfirmInfoBox.className = `action-info-box ${tone}`;
  elements.adminActionConfirmSubmitButton.textContent = confirmLabel;
  elements.adminActionConfirmSubmitButton.className = tone === "danger"
    ? "button button-danger"
    : "button button-primary";

  const effectList = Array.isArray(effects) ? effects.filter(Boolean) : [];
  elements.adminActionConfirmEffects.innerHTML = "";
  for (const effect of effectList) {
    const item = document.createElement("li");
    item.textContent = effect;
    elements.adminActionConfirmEffects.appendChild(item);
  }

  openModal(elements.adminActionConfirmModal);

  return new Promise((resolve) => {
    state.adminActionConfirm = {
      resolve
    };
  });
}

function setButtonBusy(button, busy, busyLabel) {
  if (!button) {
    return;
  }

  if (!button.dataset.defaultLabel) {
    button.dataset.defaultLabel = button.textContent.trim();
  }

  button.disabled = busy;
  button.textContent = busy ? busyLabel : button.dataset.defaultLabel;
}

function formatDate(iso) {
  if (!iso) {
    return "No expiry";
  }

  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

function formatExpiryDate(iso) {
  if (!iso) {
    return "No expiry";
  }

  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }

  return date.toLocaleString([], {
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit"
  });
}

function formatMoney(value) {
  const amount = Number(value || 0);
  if (Number.isInteger(amount)) {
    return `\u20AC${amount}`;
  }
  return `\u20AC${amount.toFixed(2).replace(/\.?0+$/, "")}`;
}

function formatBytes(value) {
  const bytes = Math.max(0, Number(value || 0));
  if (bytes < 1024) {
    return `${bytes} B`;
  }

  const units = ["KB", "MB", "GB"];
  let size = bytes / 1024;
  let unitIndex = 0;
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex += 1;
  }

  return `${size.toFixed(size >= 100 ? 0 : 1).replace(/\.0$/, "")} ${units[unitIndex]}`;
}

function cleanLabel(value, fallback = "Unknown") {
  const text = String(value ?? "").trim();
  if (!text || text === "." || text === "-") {
    return fallback;
  }
  return text;
}

function getMeaningfulLabel(value) {
  const text = String(value ?? "").trim();
  if (!text || text === "." || text === "-") {
    return "";
  }
  return text;
}

function getInstanceDisplayName(instance) {
  const label = getMeaningfulLabel(instance?.instanceName);
  if (label) {
    return label;
  }

  const port = instance?.fingerprint?.normalized?.serverPort;
  if (port) {
    return `Instance on :${port}`;
  }

  const instanceUuid = getMeaningfulLabel(instance?.instanceUuid);
  if (instanceUuid) {
    return `Instance ${instanceUuid.slice(0, 8)}`;
  }

  return "Stored instance";
}

function getServerDisplayName(instance) {
  const label = getMeaningfulLabel(instance?.lastServerName);
  if (label) {
    return label;
  }

  const port = instance?.fingerprint?.normalized?.serverPort;
  if (port) {
    return `Port ${port}`;
  }

  return "Server unknown";
}

function badge(label, tone = "") {
  const className = tone ? `badge ${tone}` : "badge";
  return html`<span class="${className}">${label}</span>`;
}

function jarMetaBadge(label, tone = "") {
  const className = tone ? `jar-meta-badge ${tone}` : "jar-meta-badge";
  return html`<span class="${className}">${label}</span>`;
}

function buildStatusBadge(license) {
  if (!license.active) {
    return badge("Disabled", "danger");
  }
  if (license.expired) {
    return badge("Expired", "warning");
  }
  return badge("Active", "success");
}

function getLicenseLabel(license) {
  return license ? (license.displayName || license.key || `license #${license.id}`) : "selected license";
}

function getLicenseTypeText(license) {
  const label = license?.licenseTypeLabel || "Unknown";
  if (license?.licenseType === "lifetime") {
    return label;
  }
  return `${label} | ${license?.daysRemaining ?? 0} day${license?.daysRemaining === 1 ? "" : "s"} left`;
}

function canExtendLicense(license) {
  return Boolean(license) && (license.licenseType === "monthly" || license.licenseType === "yearly");
}

function getExtendLabel(license) {
  return license?.licenseType === "yearly" ? "+1 Year" : "+1 Month";
}

function buildInstanceStateBadge(instance) {
  if (instance.online) {
    return badge("Online", "success");
  }
  return badge("Offline", "");
}

function formatAuditDetails(details) {
  try {
    return JSON.stringify(details || {}, null, 2);
  } catch (_error) {
    return "{}";
  }
}

function getAuditActionLabel(action) {
  switch (action) {
    case "license_activated":
      return "Allowed";
    case "auth_denied_unknown_license":
      return "Denied: invalid key";
    case "auth_denied_device_blacklisted":
      return "Denied: blocked device";
    case "auth_denied_instance_blacklisted":
      return "Denied: blocked instance";
    case "instance_limit_denied":
      return "Denied: instance limit";
    case "hwid_limit_denied":
      return "Denied: HWID limit";
    case "auth_denied_license_disabled":
      return "Denied: disabled license";
    case "auth_denied_license_expired":
      return "Denied: expired license";
    case "auth_denied_username_mismatch":
      return "Denied: username mismatch";
    case "reset_cooldown_denied":
      return "Denied: cooldown";
    case "heartbeat_denied_session_revoked":
      return "Denied: session revoked";
    case "heartbeat_denied_license_missing":
      return "Denied: session missing";
    case "heartbeat_denied_license_disabled":
      return "Denied: disabled license";
    case "heartbeat_denied_license_expired":
      return "Denied: expired license";
    default:
      return action || "-";
  }
}

function addMonths(dateInput, months) {
  const date = new Date(dateInput || Date.now());
  const hours = date.getHours();
  const minutes = date.getMinutes();
  const seconds = date.getSeconds();
  const milliseconds = date.getMilliseconds();
  const targetDay = date.getDate();
  const shifted = new Date(date.getFullYear(), date.getMonth() + months, 1, hours, minutes, seconds, milliseconds);
  const lastDay = new Date(shifted.getFullYear(), shifted.getMonth() + 1, 0).getDate();
  shifted.setDate(Math.min(targetDay, lastDay));
  return shifted;
}

function addYears(dateInput, years) {
  return addMonths(dateInput, years * 12);
}

function setToEndOfDay(dateInput) {
  const date = new Date(dateInput);
  date.setHours(23, 59, 0, 0);
  return date;
}

function getFormSlotCap() {
  const parsed = Number.parseInt(elements.maxSlotsField.value || "1", 10);
  return Math.min(100, Math.max(1, Number.isFinite(parsed) ? parsed : 1));
}

function getFormLicensePlan() {
  if ((elements.licenseTypeField.value || "monthly") === "lifetime") {
    return "basic";
  }
  return LICENSE_PLANS[elements.licensePlanField.value] ? elements.licensePlanField.value : "basic";
}

function getFormCloudPlayerSlots() {
  const plan = getFormLicensePlan();
  if (plan !== "pro") {
    return 0;
  }
  const parsed = Number.parseInt(elements.cloudPlayerSlotsField.value || "10", 10);
  if (parsed <= 10) {
    return 10;
  }
  if (parsed <= 25) {
    return 25;
  }
  return 50;
}

function updatePlanFields() {
  const licenseType = elements.licenseTypeField.value || "monthly";
  if (licenseType === "lifetime") {
    elements.licensePlanField.value = "basic";
    elements.licensePlanField.disabled = true;
  } else {
    elements.licensePlanField.disabled = false;
  }

  if (getFormLicensePlan() !== "pro") {
    elements.cloudPlayerSlotsField.value = "10";
    elements.cloudPlayerSlotsField.disabled = true;
    return;
  }
  elements.cloudPlayerSlotsField.disabled = false;
  if (![10, 25, 50].includes(Number.parseInt(elements.cloudPlayerSlotsField.value || "0", 10))) {
    elements.cloudPlayerSlotsField.value = "10";
  }
}

function computeFormPricing() {
  const licenseType = elements.licenseTypeField.value || "monthly";
  const pricing = PRICING[licenseType] || PRICING.monthly;
  const planKey = getFormLicensePlan();
  const plan = LICENSE_PLANS[planKey] || LICENSE_PLANS.basic;
  const slotCap = getFormSlotCap();
  const cloudSlots = getFormCloudPlayerSlots();
  const addonBundles = Math.max(0, slotCap - 1);
  const cloudExtraSlots = Math.max(0, cloudSlots - plan.cloudIncludedSlots);
  const cloudAddonBundles = planKey === "pro" ? Math.ceil(cloudExtraSlots / plan.cloudSlotBundleSize) : 0;
  const cloudPrice = (plan.cloudBase[licenseType] || 0) + (cloudAddonBundles * (plan.cloudAddon[licenseType] || 0));
  const normalPrice = pricing.base + (addonBundles * pricing.addon);
  const total = normalPrice + cloudPrice;

  elements.pricePreviewTotal.textContent = formatMoney(total);
  elements.pricePreviewMeta.textContent = planKey === "pro"
    ? `${pricing.label} Pro: ${formatMoney(normalPrice)} license + ${formatMoney(cloudPrice)} Pro analysis, max ${cloudSlots} players.`
    : `${pricing.label} Basic: ${formatMoney(normalPrice)} total for ${slotCap} package${slotCap === 1 ? "" : "s"}.`;
}

function updateExpiryPreview() {
  const licenseType = elements.licenseTypeField.value || "monthly";
  const isEditing = Boolean(state.formLicense);
  const sameTypeAsStored = isEditing && state.formLicense.licenseType === licenseType;

  if (licenseType === "lifetime") {
    elements.expiryPreviewLabel.textContent = "Expiry";
    elements.expiryPreviewValue.textContent = "No expiry";
    elements.expiryPreviewMeta.textContent = "Lifetime licenses do not expire.";
    return;
  }

  let previewDate = null;
  let previewMeta = `${PRICING[licenseType].label} licenses set this automatically from the current time.`;

  if (sameTypeAsStored && state.formLicense.expiresAt) {
    previewDate = new Date(state.formLicense.expiresAt);
    previewMeta = "Current stored expiry for this license.";
  } else {
    previewDate = licenseType === "yearly" ? addYears(new Date(), 1) : addMonths(new Date(), 1);
    previewDate = setToEndOfDay(previewDate);
  }

  elements.expiryPreviewLabel.textContent = "Expiry";
  elements.expiryPreviewValue.textContent = formatExpiryDate(previewDate.toISOString());
  elements.expiryPreviewMeta.textContent = previewMeta;
}

function renderSummary(license) {
  if (!license) {
    elements.selectedLicenseSummary.classList.add("hidden");
    elements.summaryKey.textContent = "-";
    elements.summaryUser.textContent = "-";
    elements.summaryType.textContent = "-";
    elements.summaryLimits.textContent = "-";
    elements.summaryStatus.textContent = "-";
    elements.summaryExpiry.textContent = "-";
    return;
  }

  elements.selectedLicenseSummary.classList.remove("hidden");
  elements.summaryKey.textContent = license.key;
  elements.summaryUser.textContent = license.customerUsername || "Unbound";
  elements.summaryType.textContent = `${license.licenseTypeLabel || "Unknown"} ${license.licensePlanLabel || "Basic"}`;
  elements.summaryLimits.textContent = `HWIDs ${license.activeDeviceCount}/${license.maxSlots} | Instances ${license.onlineInstanceCount}/${license.maxSlots} | Pro cap ${license.cloudPlayerSlots || 0}`;
  elements.summaryStatus.textContent = !license.active ? "Disabled" : (license.expired ? "Expired" : "Active");
  elements.summaryExpiry.textContent = license.licenseType === "lifetime" ? "Lifetime" : formatExpiryDate(license.expiresAt);
}

function resetLicenseForm() {
  state.formLicense = null;
  elements.licenseId.value = "";
  elements.licenseKeyField.value = "";
  elements.licenseKeyField.disabled = false;
  elements.customerUsernameField.value = "";
  elements.displayNameField.value = "";
  elements.licenseTypeField.value = "monthly";
  elements.licensePlanField.value = "basic";
  elements.cloudPlayerSlotsField.value = "10";
  elements.maxSlotsField.value = "1";
  elements.resetIntervalField.value = "30";
  elements.notesField.value = "";
  elements.activeField.checked = true;
  elements.licenseFormHeading.textContent = "Create license";
  elements.saveLicenseButton.textContent = "Save license";
  elements.deleteLicenseButton.disabled = true;
  renderSummary(null);
  updatePlanFields();
  computeFormPricing();
  updateExpiryPreview();
}

function populateLicenseForm(license) {
  state.formLicense = license;
  elements.licenseId.value = String(license.id);
  elements.licenseKeyField.value = license.key || "";
  elements.licenseKeyField.disabled = true;
  elements.customerUsernameField.value = license.customerUsername || "";
  elements.displayNameField.value = license.displayName || "";
  elements.licenseTypeField.value = license.licenseType || "monthly";
  elements.licensePlanField.value = license.licensePlan || "basic";
    elements.cloudPlayerSlotsField.value = String(license.cloudPlayerSlots || 10);
  elements.maxSlotsField.value = String(license.maxSlots || 1);
  elements.resetIntervalField.value = String(license.resetIntervalDays || 30);
  elements.notesField.value = license.notes || "";
  elements.activeField.checked = Boolean(license.active);
  elements.licenseFormHeading.textContent = `Edit ${license.key}`;
  elements.saveLicenseButton.textContent = "Update license";
  elements.deleteLicenseButton.disabled = false;
  renderSummary(license);
  updatePlanFields();
  computeFormPricing();
  updateExpiryPreview();
}

function filteredLicenses() {
  const searchTerm = elements.licenseSearch.value.trim().toLowerCase();
  if (!searchTerm) {
    return state.licenses;
  }

  return state.licenses.filter((license) => (
    [license.key, license.customerUsername, license.displayName, license.licenseTypeLabel, license.licensePlanLabel]
      .filter(Boolean)
      .some((value) => value.toLowerCase().includes(searchTerm))
  ));
}

function renderLicenses() {
  const licenses = filteredLicenses();
  elements.licensesTableBody.innerHTML = "";

  if (licenses.length === 0) {
    elements.licensesTableBody.innerHTML = html`<tr class="empty-row"><td colspan="7">No licenses found.</td></tr>`;
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const license of licenses) {
    const row = document.createElement("tr");
    const addTimeActions = license.licenseType === "lifetime"
      ? ""
      : html`
        <button class="mini-button" data-action="add-month" data-license-id="${license.id}">+1 Month</button>
        <button class="mini-button" data-action="add-year" data-license-id="${license.id}">+1 Year</button>
      `;

    row.innerHTML = html`
      <td>
        <strong>${license.key}</strong>
        <small>${license.displayName || ""}</small>
      </td>
      <td>${license.customerUsername || "Unbound"}</td>
      <td>
        ${getLicenseTypeText(license)}
        <small>${license.licenseType === "lifetime" ? "No expiry" : formatExpiryDate(license.expiresAt)}</small>
        <small>${license.licensePlanLabel || "Basic"}${license.cloudEnabled ? ` max ${license.cloudPlayerSlots} players` : ""}</small>
      </td>
      <td>
        HWIDs ${license.activeDeviceCount}/${license.maxSlots}
        <small>Instances ${license.onlineInstanceCount}/${license.maxSlots}</small>
      </td>
      <td>
        ${rawHtml(buildStatusBadge(license))}
        <small>${license.openSessionCount ? `${license.openSessionCount} open session${license.openSessionCount === 1 ? "" : "s"}` : "No open sessions"}</small>
      </td>
      <td>${formatMoney(license.pricing?.recommendedPriceEur || 0)}</td>
      <td>
        <div class="inline-actions">
          <button class="mini-button primary" data-action="open" data-license-id="${license.id}">Edit</button>
          ${rawHtml(addTimeActions)}
          <button class="mini-button danger" data-action="delete" data-license-id="${license.id}">Delete</button>
        </div>
      </td>
    `;
    fragment.appendChild(row);
  }

  elements.licensesTableBody.appendChild(fragment);
}

function setEmptyDeviceRows() {
  elements.devicesTableBody.innerHTML = html`<tr class="empty-row"><td colspan="8">No devices for the selected license.</td></tr>`;
}

function setEmptyInstanceRows() {
  elements.instancesTableBody.innerHTML = html`<tr class="empty-row"><td colspan="8">No instances for the selected license.</td></tr>`;
}

function updateResetButtons() {
  elements.resetDevicesButton.disabled = state.selectedLicenseId == null || state.selectedDevices.size === 0;
  elements.resetInstancesButton.disabled = state.selectedLicenseId == null || state.selectedInstances.size === 0;
}

function renderDevices(devices) {
  state.selectedDevices.clear();
  updateResetButtons();
  elements.devicesTableBody.innerHTML = "";

  if (!Array.isArray(devices) || devices.length === 0) {
    setEmptyDeviceRows();
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const device of devices) {
    const statusBadge = device.online
      ? badge("Online", "success")
      : device.active
        ? badge(device.slotClaimed ? "Active" : "Stored", device.slotClaimed ? "" : "warning")
        : badge("Reset", "warning");
    const row = document.createElement("tr");
    row.innerHTML = html`
      <td class="pick-cell"><input class="device-selector" type="checkbox" value="${device.id}"></td>
      <td><strong>${cleanLabel(device.deviceName, "Unknown device")}</strong></td>
      <td>${rawHtml(statusBadge)}</td>
      <td>${String(device.openSessionCount || 0)}</td>
      <td>${device.lastUsername || "unknown"}</td>
      <td>${formatDate(device.firstSeenAt)}</td>
      <td>${formatDate(device.lastSeenAt)}</td>
      <td><small>${device.hwidHash}</small></td>
    `;
    fragment.appendChild(row);
  }

  elements.devicesTableBody.appendChild(fragment);
  elements.devicesTableBody.querySelectorAll(".device-selector").forEach((checkbox) => {
    checkbox.addEventListener("change", () => {
      const id = Number.parseInt(checkbox.value, 10);
      if (checkbox.checked) {
        state.selectedDevices.add(id);
      } else {
        state.selectedDevices.delete(id);
      }
      updateResetButtons();
    });
  });
}

function renderInstances(instances) {
  state.selectedInstances.clear();
  updateResetButtons();
  elements.instancesTableBody.innerHTML = "";

  if (!Array.isArray(instances) || instances.length === 0) {
    setEmptyInstanceRows();
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const instance of instances) {
    const row = document.createElement("tr");
    row.innerHTML = html`
      <td class="pick-cell"><input class="instance-selector" type="checkbox" value="${instance.id}"></td>
      <td>
        <strong>${getInstanceDisplayName(instance)}</strong>
        <small>${instance.instanceUuid || instance.instanceHash || ""}</small>
      </td>
      <td>${rawHtml(buildInstanceStateBadge(instance))}</td>
      <td>${String(instance.openSessionCount || 0)}</td>
      <td>${cleanLabel(instance.deviceName, "Unknown device")}</td>
      <td>${getServerDisplayName(instance)}</td>
      <td>${formatDate(instance.firstSeenAt)}</td>
      <td>${formatDate(instance.lastSeenAt)}</td>
    `;
    fragment.appendChild(row);
  }

  elements.instancesTableBody.appendChild(fragment);
  elements.instancesTableBody.querySelectorAll(".instance-selector").forEach((checkbox) => {
    checkbox.addEventListener("change", () => {
      const id = Number.parseInt(checkbox.value, 10);
      if (checkbox.checked) {
        state.selectedInstances.add(id);
      } else {
        state.selectedInstances.delete(id);
      }
      updateResetButtons();
    });
  });
}

function renderSessions(sessions) {
  elements.sessionsTableBody.innerHTML = "";

  if (!Array.isArray(sessions) || sessions.length === 0) {
    elements.sessionsTableBody.innerHTML = html`<tr class="empty-row"><td colspan="7">No sessions recorded yet.</td></tr>`;
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const session of sessions) {
    const row = document.createElement("tr");
    row.innerHTML = html`
      <td>
        <strong>${session.licenseKey}</strong>
        <small>${session.customerUsername || ""}</small>
      </td>
      <td>
        ${cleanLabel(session.instanceName, "Unknown instance")}
        <small>${session.instanceHash || ""}</small>
      </td>
      <td>${cleanLabel(session.deviceName, "Unknown device")}</td>
      <td>
        ${cleanLabel(session.serverName, "Unknown server")}
        <small>${session.pluginVersion || "unknown"}</small>
      </td>
      <td>${rawHtml(session.online ? badge("Online", "success") : badge(session.staleClosed ? "Timed out" : "Closed", session.staleClosed ? "warning" : ""))}</td>
      <td>${formatDate(session.lastHeartbeatAt)}</td>
      <td>${session.closeReason || "-"}</td>
    `;
    fragment.appendChild(row);
  }

  elements.sessionsTableBody.appendChild(fragment);
}

function resetJarUploadForm() {
  if (!elements.jarUploadForm) {
    return;
  }

  elements.jarUploadForm.reset();
}

function renderJars() {
  if (!elements.jarList || !elements.jarCountChip) {
    return;
  }

  state.draggingJarId = null;
  const jars = Array.isArray(state.jars) ? state.jars : [];
  if (state.openJarId != null && !jars.some((entry) => entry.id === state.openJarId)) {
    state.openJarId = null;
  }
  elements.jarCountChip.textContent = `${jars.length} jar${jars.length === 1 ? "" : "s"}`;
  elements.jarList.innerHTML = "";

  if (jars.length === 0) {
    elements.jarList.innerHTML = html`<div class="empty-jar-list">No jars uploaded yet.</div>`;
    return;
  }

  const fragment = document.createDocumentFragment();
  jars.forEach((jar, index) => {
    const displayLabel = jar.displayName || jar.downloadName || jar.originalName || "Jar";
    const isOpen = state.openJarId === jar.id;
    const metaBadges = [
      jar.recommended ? jarMetaBadge("Recommended", "recommended") : "",
      jarMetaBadge(jar.releaseChannelLabel || "Stable"),
      jar.supportedVersions ? jarMetaBadge(jar.supportedVersions) : "",
      jarMetaBadge(jar.accessLabel || "Buyers only")
    ].filter(Boolean).join("");
    const item = document.createElement("article");
    item.className = `jar-item${isOpen ? " is-open" : ""}`;
    item.dataset.jarId = String(jar.id);
    item.innerHTML = html`
      <div class="jar-item-shell" draggable="true" data-jar-id="${jar.id}">
        <div class="jar-drag-handle" aria-hidden="true">
          <span></span>
          <span></span>
          <span></span>
        </div>
        <div class="jar-item-main">
          <strong class="jar-item-title">${displayLabel}</strong>
          <div class="jar-meta-row">
            ${rawHtml(metaBadges)}
          </div>
        </div>
        <div class="jar-item-tools">
          <span class="order-badge">#${index + 1}</span>
          <button class="mini-button primary" type="button" data-action="toggle-jar" data-jar-id="${jar.id}">${isOpen ? "Close" : "Open"}</button>
        </div>
      </div>
      ${isOpen ? rawHtml(html`
        <div class="jar-item-editor">
          <div class="jar-item-summary">
            <div class="jar-summary-card">
              <span>Stored file</span>
              <strong>${jar.originalName || "Unknown jar"}</strong>
            </div>
            <div class="jar-summary-card">
              <span>File size</span>
              <strong>${formatBytes(jar.fileSize || 0)}</strong>
            </div>
            <div class="jar-summary-card">
              <span>Updated</span>
              <strong>${formatDate(jar.updatedAt)}</strong>
            </div>
            <div class="jar-summary-card">
              <span>Access</span>
              <strong>${jar.accessLabel || "Buyers only"}</strong>
            </div>
          </div>
          <div class="jar-item-fields">
            <label>
              <span>Public name</span>
              <input class="jar-name-input" type="text" value="${jar.displayName || ""}" autocomplete="off" spellcheck="false">
            </label>
            <label>
              <span>Public note</span>
              <textarea class="jar-note-input" rows="3" spellcheck="false">${jar.notes || ""}</textarea>
            </label>
            <label>
              <span>Supported versions</span>
              <input class="jar-versions-input" type="text" value="${jar.supportedVersions || ""}" autocomplete="off" spellcheck="false">
            </label>
            <label>
              <span>Release channel</span>
              <select class="jar-channel-input">
                <option value="stable"${jar.releaseChannel === "stable" ? " selected" : ""}>Stable</option>
                <option value="beta"${jar.releaseChannel === "beta" ? " selected" : ""}>Beta</option>
                <option value="legacy"${jar.releaseChannel === "legacy" ? " selected" : ""}>Legacy</option>
              </select>
            </label>
            <label>
              <span>Changelog</span>
              <textarea class="jar-changelog-input" rows="5" spellcheck="false">${jar.changelog || ""}</textarea>
            </label>
            <label>
              <span>Download access</span>
              <select class="jar-access-input">
                <option value="buyers"${jar.accessScope === "buyers" ? " selected" : ""}>Buyers only</option>
                <option value="public"${jar.accessScope === "public" ? " selected" : ""}>Everybody</option>
              </select>
            </label>
            <label class="checkbox-row">
              <input class="jar-recommended-input" type="checkbox"${jar.recommended ? " checked" : ""}>
              <span>Recommended build</span>
            </label>
          </div>
          <div class="actions-row">
            <button class="mini-button primary" type="button" data-action="save-jar" data-jar-id="${jar.id}">Save</button>
            <button class="mini-button danger" type="button" data-action="delete-jar" data-jar-id="${jar.id}">Delete jar</button>
          </div>
        </div>
      `) : ""}
    `;
    fragment.appendChild(item);
  });

  elements.jarList.appendChild(fragment);
}

async function loadJars() {
  if (!elements.jarList) {
    return;
  }

  const payload = await adminRequest("/api/admin/jars");
  state.jars = payload.jars || [];
  renderJars();
}

async function uploadJarFromForm(event) {
  event.preventDefault();
  clearStatus(elements.adminStatus);

  const file = elements.jarFileField?.files?.[0];
  if (!file) {
    showStatus(elements.adminStatus, "Choose a jar file first.", "error");
    return;
  }

  const displayName = elements.jarNameField.value.trim() || file.name.replace(/\.jar$/i, "");
  const notes = elements.jarNoteField.value.trim();
  const supportedVersions = elements.jarVersionsField.value.trim();
  const releaseChannel = elements.jarChannelField.value || "stable";
  const changelog = elements.jarChangelogField.value.trim();
  const recommended = elements.jarRecommendedField.checked ? "true" : "false";
  const accessScope = elements.jarAccessField.value || "buyers";

  const confirmed = await requestAdminActionConfirm({
    pill: "Downloads",
    title: "Upload jar",
    description: "This uploads the selected jar and adds it to the jar manager immediately.",
    infoTitle: "Jar upload",
    infoText: "The file is stored on the server and the release settings below become the starting config for this build.",
    effects: [
      `Upload ${file.name} to private jar storage.`,
      `Publish it as ${displayName}.`,
      `Set the release channel to ${releaseChannel}.`,
      accessScope === "public" ? "Allow public downloads without a license." : "Keep this build buyer-only.",
      recommended === "true" ? "Mark this build as the recommended download." : ""
    ],
    confirmLabel: "Upload jar",
    tone: "info"
  });
  if (!confirmed) {
    return;
  }

  const query = new URLSearchParams({
    displayName,
    notes,
    supportedVersions,
    releaseChannel,
    changelog,
    recommended,
    accessScope,
    originalName: file.name
  });

  setButtonBusy(elements.uploadJarButton, true, "Uploading...");

  try {
    const response = await fetch(`/api/admin/jars/upload?${query.toString()}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/octet-stream",
        "x-admin-key": state.apiKey
      },
      body: file
    });

    let payload = {};
    try {
      payload = await response.json();
    } catch (_error) {
      payload = {};
    }

    if (!response.ok || payload.ok === false) {
      throw new Error(payload.message || `Upload failed with status ${response.status}.`);
    }

    await loadJars();
    resetJarUploadForm();
    showStatus(elements.adminStatus, `${payload.jar?.displayName || file.name} uploaded.`, "success");
  } catch (error) {
    showStatus(elements.adminStatus, error.message, "error");
  } finally {
    setButtonBusy(elements.uploadJarButton, false, "Uploading...");
  }
}

async function saveJar(jarId, sourceButton) {
  const item = elements.jarList?.querySelector(`.jar-item[data-jar-id="${jarId}"]`);
  if (!item) {
    return;
  }

  const displayName = item.querySelector(".jar-name-input")?.value.trim() || "";
  const notes = item.querySelector(".jar-note-input")?.value.trim() || "";
  const supportedVersions = item.querySelector(".jar-versions-input")?.value.trim() || "";
  const releaseChannel = item.querySelector(".jar-channel-input")?.value || "stable";
  const changelog = item.querySelector(".jar-changelog-input")?.value.trim() || "";
  const accessScope = item.querySelector(".jar-access-input")?.value || "buyers";
  const recommended = Boolean(item.querySelector(".jar-recommended-input")?.checked);
  const jar = state.jars.find((entry) => entry.id === jarId);
  const jarLabel = displayName || jar?.displayName || jar?.originalName || "this jar";

  const confirmed = await requestAdminActionConfirm({
    pill: "Downloads",
    title: "Save jar changes",
    description: "This updates the selected build in the jar manager.",
    infoTitle: "Jar update",
    infoText: "The saved values are used right away in the admin panel and public download list.",
    effects: [
      `Save the current settings for ${jarLabel}.`,
      `Set the release channel to ${releaseChannel}.`,
      accessScope === "public" ? "Allow public downloads without a license." : "Keep this build buyer-only.",
      recommended ? "Mark this build as the recommended download." : "Keep the recommended build setting unchanged unless another jar is marked."
    ],
    confirmLabel: "Save jar",
    tone: "info"
  });
  if (!confirmed) {
    return;
  }

  setButtonBusy(sourceButton, true, "Saving...");
  try {
    state.openJarId = jarId;
    await adminRequest(`/api/admin/jars/${jarId}`, {
      method: "PATCH",
      body: { displayName, notes, supportedVersions, releaseChannel, changelog, accessScope, recommended }
    });
    await loadJars();
    showStatus(elements.adminStatus, "Jar details updated.", "success");
  } catch (error) {
    showStatus(elements.adminStatus, error.message, "error");
  } finally {
    setButtonBusy(sourceButton, false, "Saving...");
  }
}

async function deleteJarById(jarId, sourceButton) {
  const jar = state.jars.find((entry) => entry.id === jarId);
  const label = jar?.displayName || jar?.originalName || "this jar";

  const confirmed = await requestAdminActionConfirm({
    pill: "Downloads",
    title: "Delete jar",
    description: "This removes the selected build from the jar manager.",
    infoTitle: "Jar deletion",
    infoText: "The stored jar file is deleted from the server and the build disappears from the download list.",
    effects: [
      `Delete ${label}.`,
      "Remove the stored jar file from private server storage.",
      "Remove this build from public and buyer download listings."
    ],
    confirmLabel: "Delete jar",
    tone: "danger"
  });
  if (!confirmed) {
    return;
  }

  setButtonBusy(sourceButton, true, "Deleting...");
  try {
    if (state.openJarId === jarId) {
      state.openJarId = null;
    }
    await adminRequest(`/api/admin/jars/${jarId}`, { method: "DELETE" });
    await loadJars();
    showStatus(elements.adminStatus, `${label} deleted.`, "success");
  } catch (error) {
    showStatus(elements.adminStatus, error.message, "error");
  } finally {
    setButtonBusy(sourceButton, false, "Deleting...");
  }
}

function moveJarInState(dragId, targetId, insertAfter = false) {
  const fromIndex = state.jars.findIndex((entry) => entry.id === dragId);
  const toIndex = state.jars.findIndex((entry) => entry.id === targetId);
  if (fromIndex === -1 || toIndex === -1 || fromIndex === toIndex) {
    return false;
  }

  const [moved] = state.jars.splice(fromIndex, 1);
  const adjustedTargetIndex = state.jars.findIndex((entry) => entry.id === targetId);
  const nextIndex = insertAfter ? adjustedTargetIndex + 1 : adjustedTargetIndex;
  state.jars.splice(nextIndex, 0, moved);
  return true;
}

async function persistJarOrder() {
  await adminRequest("/api/admin/jars/reorder", {
    method: "POST",
    body: { orderedIds: state.jars.map((entry) => entry.id) }
  });
}

async function handleJarListClick(event) {
  const button = event.target.closest("button[data-action][data-jar-id]");
  if (!button) {
    return;
  }

  const jarId = Number.parseInt(button.dataset.jarId || "", 10);
  if (!Number.isFinite(jarId)) {
    return;
  }

  if (button.dataset.action === "toggle-jar") {
    state.openJarId = state.openJarId === jarId ? null : jarId;
    renderJars();
    return;
  }

  if (button.dataset.action === "save-jar") {
    await saveJar(jarId, button);
    return;
  }

  if (button.dataset.action === "delete-jar") {
    await deleteJarById(jarId, button);
  }
}

function handleJarDragStart(event) {
  const item = event.target.closest(".jar-item-shell[data-jar-id]");
  if (!item) {
    return;
  }

  state.draggingJarId = Number.parseInt(item.dataset.jarId || "", 10);
  if (!Number.isFinite(state.draggingJarId)) {
    state.draggingJarId = null;
    return;
  }

  item.closest(".jar-item")?.classList.add("dragging");
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setData("text/plain", String(state.draggingJarId));
  }
}

function handleJarDragOver(event) {
  const item = event.target.closest(".jar-item[data-jar-id]");
  if (!item) {
    return;
  }

  event.preventDefault();
  elements.jarList?.querySelectorAll(".jar-item.drag-over").forEach((entry) => {
    if (entry !== item) {
      entry.classList.remove("drag-over");
    }
  });
  item.classList.add("drag-over");
}

async function handleJarDrop(event) {
  const item = event.target.closest(".jar-item[data-jar-id]");
  if (!item || !Number.isFinite(state.draggingJarId)) {
    return;
  }

  event.preventDefault();
  item.classList.remove("drag-over");

  const targetId = Number.parseInt(item.dataset.jarId || "", 10);
  if (!Number.isFinite(targetId) || targetId === state.draggingJarId) {
    return;
  }

  const midpoint = item.getBoundingClientRect().top + (item.getBoundingClientRect().height / 2);
  const insertAfter = event.clientY > midpoint;
  const didMove = moveJarInState(state.draggingJarId, targetId, insertAfter);
  if (!didMove) {
    return;
  }

  renderJars();
  try {
    await persistJarOrder();
    await loadJars();
    showStatus(elements.adminStatus, "Jar order updated.", "success");
  } catch (error) {
    await loadJars();
    showStatus(elements.adminStatus, error.message, "error");
  }
}

function clearJarDragState() {
  state.draggingJarId = null;
  elements.jarList?.querySelectorAll(".jar-item").forEach((item) => {
    item.classList.remove("dragging", "drag-over");
  });
}

async function adminRequest(path, options = {}) {
  const headers = {
    "Content-Type": "application/json"
  };

  if (state.apiKey) {
    headers["x-admin-key"] = state.apiKey;
  }

  const response = await fetch(path, {
    method: options.method || "GET",
    headers,
    body: options.body ? JSON.stringify(options.body) : undefined
  });

  let payload = {};
  try {
    payload = await response.json();
  } catch (_error) {
    payload = {};
  }

  if (!response.ok || payload.ok === false) {
    throw new Error(payload.message || `Request failed with status ${response.status}`);
  }

  return payload;
}

function showAuthedUi() {
  elements.authPanel.classList.add("hidden");
  elements.adminPanel.classList.remove("hidden");
}

function showLoggedOutUi() {
  elements.authPanel.classList.remove("hidden");
  elements.adminPanel.classList.add("hidden");
  clearStatus(elements.adminStatus);
}

async function loadOverview() {
  const payload = await adminRequest("/api/admin/overview");
  const overview = payload.overview || {};
  elements.statTotalLicenses.textContent = overview.totalLicenses ?? 0;
  elements.statActiveLicenses.textContent = overview.activeLicenses ?? 0;
  elements.statExpiredLicenses.textContent = overview.expiredLicenses ?? 0;
  elements.statActiveDevices.textContent = overview.activeDevices ?? 0;
  elements.statActiveInstances.textContent = overview.activeInstances ?? 0;
  elements.statOpenSessions.textContent = overview.openSessions ?? 0;
}

async function loadLicenses() {
  const payload = await adminRequest("/api/admin/licenses");
  state.licenses = payload.licenses || [];
  renderLicenses();
}

async function loadSessions() {
  const payload = await adminRequest("/api/admin/sessions");
  renderSessions(payload.sessions || []);
}

function renderLockdown() {
  const lockdown = state.lockdown || {};
  const enabled = Boolean(lockdown.enabled);

  if (elements.lockdownStateBadge) {
    elements.lockdownStateBadge.className = enabled ? "badge danger" : "badge success";
    elements.lockdownStateBadge.textContent = enabled ? "Locked down" : "Normal";
  }

  if (elements.lockdownStatusText) {
    elements.lockdownStatusText.textContent = enabled ? "Locked down" : "Normal";
  }
  if (elements.lockdownSinceText) {
    elements.lockdownSinceText.textContent = lockdown.since ? formatDate(lockdown.since) : "-";
  }
  if (elements.lockdownReasonText) {
    elements.lockdownReasonText.textContent = lockdown.reason || "-";
  }
  if (elements.lockdownReasonField && document.activeElement !== elements.lockdownReasonField) {
    elements.lockdownReasonField.value = lockdown.reason || "";
  }
  if (elements.toggleLockdownButton) {
    elements.toggleLockdownButton.textContent = enabled ? "Disable lockdown" : "Enable lockdown";
  }
  if (elements.openInspectorButton) {
    elements.openInspectorButton.disabled = state.selectedLicenseId == null;
  }
}

function renderAuditFilters() {
  if (!elements.auditActorField || !elements.auditActionField) {
    return;
  }

  const actorValue = elements.auditActorField.value;
  const actionValue = elements.auditActionField.value;
  elements.auditActorField.innerHTML = '<option value="">All actors</option>';
  elements.auditActionField.innerHTML = '<option value="">All actions</option>';

  for (const actor of state.auditFilters.actors || []) {
    const option = document.createElement("option");
    option.value = actor;
    option.textContent = actor;
    elements.auditActorField.appendChild(option);
  }

  for (const action of state.auditFilters.actions || []) {
    const option = document.createElement("option");
    option.value = action;
    option.textContent = getAuditActionLabel(action);
    elements.auditActionField.appendChild(option);
  }

  elements.auditActorField.value = actorValue;
  elements.auditActionField.value = actionValue;
}

function renderAuditLogs() {
  if (!elements.auditLogsTableBody) {
    return;
  }

  elements.auditLogsTableBody.innerHTML = "";
  if (!Array.isArray(state.auditLogs) || state.auditLogs.length === 0) {
    elements.auditLogsTableBody.innerHTML = html`<tr class="empty-row"><td colspan="5">No audit logs matched the current filters.</td></tr>`;
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const entry of state.auditLogs) {
    const row = document.createElement("tr");
    row.innerHTML = html`
      <td>${formatDate(entry.createdAt)}</td>
      <td>${entry.actor || "-"}</td>
      <td><span title="${entry.action || "-"}">${getAuditActionLabel(entry.action)}</span></td>
      <td>
        <strong>${entry.licenseKey || "System"}</strong>
        <small>${entry.customerUsername || entry.displayName || ""}</small>
      </td>
      <td><pre class="detail-code">${formatAuditDetails(entry.details)}</pre></td>
    `;
    fragment.appendChild(row);
  }

  elements.auditLogsTableBody.appendChild(fragment);
}

async function loadLockdown() {
  const payload = await adminRequest("/api/admin/lockdown");
  state.lockdown = payload.lockdown || { enabled: false, reason: "", since: null };
  renderLockdown();
}

async function loadAuditLogs() {
  const query = new URLSearchParams();
  const search = elements.auditSearchField?.value.trim() || "";
  const actor = elements.auditActorField?.value || "";
  const action = elements.auditActionField?.value || "";
  const limit = elements.auditLimitField?.value || "150";

  if (search) {
    query.set("search", search);
  }
  if (actor) {
    query.set("actor", actor);
  }
  if (action) {
    query.set("action", action);
  }
  if (state.selectedLicenseId != null) {
    query.set("licenseId", String(state.selectedLicenseId));
  }
  query.set("limit", limit);

  const payload = await adminRequest(`/api/admin/audit-logs?${query.toString()}`);
  state.auditLogs = payload.logs || [];
  state.auditFilters = payload.filters || { actors: [], actions: [] };
  renderAuditFilters();
  renderAuditLogs();
}

async function openInspectorModal() {
  if (state.selectedLicenseId == null) {
    throw new Error("Open a license first.");
  }
  await loadLicenseDetail(state.selectedLicenseId);
  closeModal(elements.jarManagerModal);
  closeModal(elements.sessionsModal);
  closeModal(elements.auditModal);
  openModal(elements.inspectorModal);
}

async function openJarManagerModal() {
  await loadJars();
  closeModal(elements.inspectorModal);
  closeModal(elements.sessionsModal);
  closeModal(elements.auditModal);
  openModal(elements.jarManagerModal);
}

async function openSessionsModal() {
  await loadSessions();
  closeModal(elements.inspectorModal);
  closeModal(elements.jarManagerModal);
  closeModal(elements.auditModal);
  openModal(elements.sessionsModal);
}

async function openAuditModal() {
  await loadAuditLogs();
  closeModal(elements.inspectorModal);
  closeModal(elements.jarManagerModal);
  closeModal(elements.sessionsModal);
  openModal(elements.auditModal);
}

async function loadLicenseDetail(licenseId) {
  state.selectedLicenseId = licenseId;
  elements.refreshDevicesButton.disabled = false;
  elements.refreshInstancesButton.disabled = false;
  updateResetButtons();
  renderLockdown();

  const license = state.licenses.find((entry) => entry.id === licenseId);
  const label = getLicenseLabel(license);

  elements.devicesHeading.textContent = "Devices";
  elements.devicesSubheading.textContent = `Registered HWIDs for ${label}.`;
  elements.instancesHeading.textContent = "Instances";
  elements.instancesSubheading.textContent = `Registered server instances for ${label}.`;

  const [devicesPayload, instancesPayload] = await Promise.all([
    adminRequest(`/api/admin/licenses/${licenseId}/devices`),
    adminRequest(`/api/admin/licenses/${licenseId}/instances`)
  ]);

  renderDevices(devicesPayload.devices || []);
  renderInstances(instancesPayload.instances || []);
}

function clearInspectorState() {
  state.selectedLicenseId = null;
  state.selectedDevices.clear();
  state.selectedInstances.clear();
  closeModal(elements.inspectorModal);
  elements.devicesHeading.textContent = "Devices";
  elements.devicesSubheading.textContent = "Open a license to inspect registered HWIDs.";
  elements.instancesHeading.textContent = "Instances";
  elements.instancesSubheading.textContent = "Open a license to inspect registered server instances.";
  elements.refreshDevicesButton.disabled = true;
  elements.refreshInstancesButton.disabled = true;
  setEmptyDeviceRows();
  setEmptyInstanceRows();
  updateResetButtons();
  renderLockdown();
}

function syncEditedLicenseForm() {
  const editingLicenseId = Number.parseInt(elements.licenseId.value || "", 10);
  if (!Number.isFinite(editingLicenseId)) {
    return;
  }

  const updatedLicense = state.licenses.find((entry) => entry.id === editingLicenseId);
  if (updatedLicense) {
    populateLicenseForm(updatedLicense);
  }
}

async function refreshDashboard() {
  await Promise.all([loadOverview(), loadLicenses(), loadSessions(), loadJars(), loadLockdown(), loadAuditLogs()]);
  syncEditedLicenseForm();

  if (state.selectedLicenseId != null) {
    const stillExists = state.licenses.some((entry) => entry.id === state.selectedLicenseId);
    if (stillExists) {
      await loadLicenseDetail(state.selectedLicenseId);
    } else {
      clearInspectorState();
      await loadAuditLogs();
    }
  }
}

async function authenticate(apiKey) {
  state.apiKey = apiKey.trim();
  if (!state.apiKey) {
    throw new Error("Your admin API key is required.");
  }

  await adminRequest("/api/admin/login", {
    method: "POST",
    body: { apiKey: state.apiKey }
  });

  localStorage.setItem(ADMIN_KEY_STORAGE, state.apiKey);
  showAuthedUi();
  showStatus(elements.authStatus, "Admin opened.", "success");
  await refreshDashboard();
}

async function onLogin(event) {
  event.preventDefault();
  clearStatus(elements.authStatus);

  try {
    await authenticate(elements.apiKey.value);
  } catch (error) {
    showStatus(elements.authStatus, error.message, "error");
  }
}

function logout() {
  state.apiKey = "";
  state.licenses = [];
  state.jars = [];
  state.auditLogs = [];
  state.auditFilters = { actors: [], actions: [] };
  state.lockdown = { enabled: false, reason: "", since: null };
  clearJarDragState();
  state.openJarId = null;
  localStorage.removeItem(ADMIN_KEY_STORAGE);
  elements.apiKey.value = "";
  closeAdminActionConfirm(false);
  closeDownloadKeyModal();
  closeModal(elements.inspectorModal);
  closeModal(elements.jarManagerModal);
  closeModal(elements.sessionsModal);
  closeModal(elements.auditModal);
  resetLicenseForm();
  resetJarUploadForm();
  clearInspectorState();
  elements.licensesTableBody.innerHTML = "";
  elements.sessionsTableBody.innerHTML = "";
  if (elements.auditLogsTableBody) {
    elements.auditLogsTableBody.innerHTML = "";
  }
  if (elements.jarList) {
    elements.jarList.innerHTML = html`<div class="empty-jar-list">No jars uploaded yet.</div>`;
  }
  if (elements.jarCountChip) {
    elements.jarCountChip.textContent = "0 jars";
  }
  showLoggedOutUi();
  renderLockdown();
  showStatus(elements.authStatus, "Logged out.", "info");
}

function openDownloadKeyModal() {
  elements.downloadKeyInput.value = "";
  elements.downloadKeyModal.classList.remove("hidden");
  elements.downloadKeyModal.setAttribute("aria-hidden", "false");
  window.setTimeout(() => elements.downloadKeyInput.focus(), 0);
}

function closeDownloadKeyModal() {
  elements.downloadKeyModal.classList.add("hidden");
  elements.downloadKeyModal.setAttribute("aria-hidden", "true");
  elements.downloadKeyInput.value = "";
}

async function submitBackupDownload(event) {
  event.preventDefault();
  clearStatus(elements.adminStatus);

  if (!state.apiKey) {
    showStatus(elements.adminStatus, "Open the admin panel first.", "error");
    return;
  }

  const downloadKey = elements.downloadKeyInput.value.trim();
  if (!downloadKey) {
    showStatus(elements.adminStatus, "Download API key is required.", "error");
    return;
  }

  setButtonBusy(elements.backupButton, true, "Preparing...");
  closeDownloadKeyModal();

  try {
    const response = await fetch("/api/admin/backup", {
      method: "GET",
      headers: {
        "x-admin-key": state.apiKey,
        "x-download-key": downloadKey
      }
    });

    if (!response.ok) {
      let payload = {};
      try {
        payload = await response.json();
      } catch (_error) {
        payload = {};
      }
      throw new Error(payload.message || `Backup failed with status ${response.status}.`);
    }

    const blob = await response.blob();
    const objectUrl = window.URL.createObjectURL(blob);
    const download = document.createElement("a");
    download.href = objectUrl;
    download.download = `novaac-backup-${new Date().toISOString().split("T")[0]}.zip`;
    document.body.appendChild(download);
    download.click();
    download.remove();
    window.URL.revokeObjectURL(objectUrl);

    showStatus(elements.adminStatus, "Backup downloaded.", "success");
  } catch (error) {
    showStatus(elements.adminStatus, error.message, "error");
  } finally {
    setButtonBusy(elements.backupButton, false, "Preparing...");
  }
}

async function submitLicenseForm(event) {
  event.preventDefault();
  clearStatus(elements.adminStatus);

  const licenseId = elements.licenseId.value.trim();
  const isEditing = Boolean(licenseId);
  const body = {
    key: elements.licenseKeyField.value.trim() || undefined,
    customerUsername: elements.customerUsernameField.value.trim(),
    displayName: elements.displayNameField.value.trim(),
    licenseType: elements.licenseTypeField.value,
    licensePlan: getFormLicensePlan(),
    cloudPlayerSlots: getFormCloudPlayerSlots(),
    maxSlots: getFormSlotCap(),
    resetIntervalDays: Number.parseInt(elements.resetIntervalField.value, 10),
    notes: elements.notesField.value.trim(),
    active: elements.activeField.checked
  };

  const confirmed = await requestAdminActionConfirm({
    pill: "Licenses",
    title: isEditing ? "Update license" : "Create license",
    description: isEditing
      ? "This saves the current license settings and applies them immediately."
      : "This creates a new license from the current editor values.",
    infoTitle: isEditing ? "License update" : "License creation",
    infoText: isEditing
      ? "Slot limits, status, cooldown days, and notes are updated right away."
      : "A new license key is created now unless you entered one manually.",
    effects: [
      `${isEditing ? "Save changes for" : "Create"} ${body.displayName || body.customerUsername || "this license"}.`,
      `Set the term to ${body.licenseType} and the plan to ${body.licensePlan}.`,
      `Set the package size to ${body.maxSlots} slot${body.maxSlots === 1 ? "" : "s"}.`,
      body.licensePlan === "pro" ? `Allow Pro analysis for max ${body.cloudPlayerSlots} players.` : "Keep this license on Basic.",
      `Set the reset cooldown to ${body.resetIntervalDays} day${body.resetIntervalDays === 1 ? "" : "s"}.`,
      body.active ? "Leave the license active." : "Mark the license as disabled.",
      !isEditing ? (body.key ? `Use the manual key ${body.key}.` : "Generate the license key automatically.") : ""
    ],
    confirmLabel: isEditing ? "Save license" : "Create license",
    tone: "info"
  });
  if (!confirmed) {
    return;
  }

  setButtonBusy(elements.saveLicenseButton, true, "Saving...");

  try {
    if (licenseId) {
      const payload = await adminRequest(`/api/admin/licenses/${licenseId}`, {
        method: "PATCH",
        body
      });
      showStatus(elements.adminStatus, `Updated ${payload.license.key}.`, "success");
    } else {
      const payload = await adminRequest("/api/admin/licenses", {
        method: "POST",
        body
      });
      showStatus(elements.adminStatus, `Created ${payload.license.key}.`, "success");
      resetLicenseForm();
    }

    await refreshDashboard();
  } catch (error) {
    showStatus(elements.adminStatus, error.message, "error");
  } finally {
    setButtonBusy(elements.saveLicenseButton, false, "Saving...");
  }
}

async function deleteLicenseById(licenseId, sourceButton = null) {
  const license = state.licenses.find((entry) => entry.id === licenseId);
  const label = getLicenseLabel(license);

  const confirmed = await requestAdminActionConfirm({
    pill: "Licenses",
    title: "Delete license",
    description: "This permanently removes the selected license.",
    infoTitle: "License deletion",
    infoText: "The license, its stored devices, stored instances, and related sessions are deleted together.",
    effects: [
      `Delete ${label}.`,
      "Remove stored devices and stored instances for this license.",
      "Close and remove related session records."
    ],
    confirmLabel: "Delete license",
    tone: "danger"
  });
  if (!confirmed) {
    return;
  }

  setButtonBusy(sourceButton || elements.deleteLicenseButton, true, "Deleting...");
  try {
    await adminRequest(`/api/admin/licenses/${licenseId}`, { method: "DELETE" });

    if (Number.parseInt(elements.licenseId.value || "", 10) === licenseId) {
      resetLicenseForm();
    }
    if (state.selectedLicenseId === licenseId) {
      clearInspectorState();
    }

    await refreshDashboard();
    showStatus(elements.adminStatus, `${label} deleted.`, "success");
  } catch (error) {
    showStatus(elements.adminStatus, error.message, "error");
  } finally {
    setButtonBusy(sourceButton || elements.deleteLicenseButton, false, "Deleting...");
  }
}

async function deleteCurrentLicense() {
  const licenseId = Number.parseInt(elements.licenseId.value || "", 10);
  if (!Number.isFinite(licenseId)) {
    showStatus(elements.adminStatus, "Open a license first.", "error");
    return;
  }

  await deleteLicenseById(licenseId);
}

async function openLicense(licenseId) {
  const license = state.licenses.find((entry) => entry.id === licenseId);
  if (!license) {
    return;
  }

  populateLicenseForm(license);
  await loadLicenseDetail(licenseId);
  await loadAuditLogs();
  showStatus(elements.adminStatus, `Opened ${getLicenseLabel(license)}.`, "info");
}

function resetEditorSelection() {
  resetLicenseForm();
  clearInspectorState();
  loadAuditLogs().catch((error) => {
    showStatus(elements.adminStatus, error.message, "error");
  });
}

async function handleLicenseTableClick(event) {
  const button = event.target.closest("button[data-action]");
  if (!button) {
    return;
  }

  const licenseId = Number.parseInt(button.dataset.licenseId || "", 10);
  if (!Number.isFinite(licenseId)) {
    return;
  }

  const action = button.dataset.action;
  const license = state.licenses.find((entry) => entry.id === licenseId);
  if (!license) {
    return;
  }

  if (action === "open") {
    try {
      await openLicense(licenseId);
    } catch (error) {
      showStatus(elements.adminStatus, error.message, "error");
    }
    return;
  }

  if (action === "extend" || action === "add-month" || action === "add-year") {
    const amountLabel = action === "add-year" ? "1 year" : "1 month";
    const confirmed = await requestAdminActionConfirm({
      pill: "Licenses",
      title: action === "add-year" ? "Add 1 year" : "Add 1 month",
      description: "This extends the selected license from its current end date.",
      infoTitle: "Add time",
      infoText: "Time is added onto the current expiry date instead of restarting the term from today.",
      effects: [
        `Extend ${license.key} by ${amountLabel}.`,
        license.expiresAt ? `Current expiry: ${formatExpiryDate(license.expiresAt)}.` : "Current expiry: no expiry set.",
        "Save the new expiry immediately."
      ],
      confirmLabel: action === "add-year" ? "Add 1 year" : "Add 1 month",
      tone: "info"
    });
    if (!confirmed) {
      return;
    }

    const body = action === "add-year" ? { years: 1 } : { months: 1 };
    setButtonBusy(button, true, "Adding...");
    try {
      const payload = action === "extend"
        ? await adminRequest(`/api/admin/licenses/${licenseId}/extend`, { method: "POST" })
        : await adminRequest(`/api/admin/licenses/${licenseId}/add-time`, {
            method: "POST",
            body
          });
      await refreshDashboard();
      showStatus(elements.adminStatus, `${license.key} now ends on ${formatExpiryDate(payload.license?.expiresAt)}.`, "success");
    } catch (error) {
      showStatus(elements.adminStatus, error.message, "error");
    } finally {
      setButtonBusy(button, false, "Adding...");
    }
    return;
  }

  if (action === "delete") {
    await deleteLicenseById(licenseId, button);
  }
}

async function refreshSelectedLicenseDetail() {
  if (state.selectedLicenseId == null) {
    return;
  }
  await loadLicenseDetail(state.selectedLicenseId);
}

async function resetSelectedDevices() {
  if (state.selectedLicenseId == null || state.selectedDevices.size === 0) {
    showStatus(elements.adminStatus, "Select at least one device first.", "error");
    return;
  }

  const confirmed = await requestAdminActionConfirm({
    pill: "Inspector",
    title: "Reset selected devices",
    description: "This clears the selected HWIDs from the license immediately.",
    infoTitle: "Device reset",
    infoText: "Any open sessions on those devices are closed right away and the running jars are told to stop instead of reconnecting.",
    effects: [
      `Reset ${state.selectedDevices.size} selected device${state.selectedDevices.size === 1 ? "" : "s"}.`,
      "Close current sessions on those devices and all their instances.",
      "Require those servers to be restarted before they can authenticate again."
    ],
    confirmLabel: "Reset devices",
    tone: "danger"
  });
  if (!confirmed) {
    return;
  }

  setButtonBusy(elements.resetDevicesButton, true, "Resetting...");
  try {
    await adminRequest(`/api/admin/licenses/${state.selectedLicenseId}/reset`, {
      method: "POST",
      body: { deviceIds: [...state.selectedDevices] }
    });
    await refreshDashboard();
    showStatus(elements.adminStatus, "Selected devices were reset.", "success");
  } catch (error) {
    showStatus(elements.adminStatus, error.message, "error");
  } finally {
    setButtonBusy(elements.resetDevicesButton, false, "Resetting...");
  }
}

async function resetSelectedInstances() {
  if (state.selectedLicenseId == null || state.selectedInstances.size === 0) {
    showStatus(elements.adminStatus, "Select at least one instance first.", "error");
    return;
  }

  const confirmed = await requestAdminActionConfirm({
    pill: "Inspector",
    title: "Reset selected instances",
    description: "This clears the selected stored server instances from the license immediately.",
    infoTitle: "Instance reset",
    infoText: "Any open sessions on those servers are closed right away and the selected running jars are told to stop instead of reconnecting.",
    effects: [
      `Reset ${state.selectedInstances.size} selected instance${state.selectedInstances.size === 1 ? "" : "s"}.`,
      "Close current sessions for those servers.",
      "Require those servers to be restarted before they can authenticate again."
    ],
    confirmLabel: "Reset instances",
    tone: "danger"
  });
  if (!confirmed) {
    return;
  }

  setButtonBusy(elements.resetInstancesButton, true, "Resetting...");
  try {
    await adminRequest(`/api/admin/licenses/${state.selectedLicenseId}/reset-instances`, {
      method: "POST",
      body: { instanceIds: [...state.selectedInstances] }
    });
    await refreshDashboard();
    showStatus(elements.adminStatus, "Selected instances were reset.", "success");
  } catch (error) {
    showStatus(elements.adminStatus, error.message, "error");
  } finally {
    setButtonBusy(elements.resetInstancesButton, false, "Resetting...");
  }
}

async function reconcileSessions() {
  clearStatus(elements.adminStatus);

  const confirmed = await requestAdminActionConfirm({
    pill: "Maintenance",
    title: "Reconcile sessions",
    description: "This forces an immediate cleanup pass over live session state.",
    infoTitle: "Session reconciliation",
    infoText: "Use this when you want the backend to close stale sessions now instead of waiting for the background cleanup cycle.",
    effects: [
      "Re-check every open session against the timeout rules.",
      "Close sessions that are already stale.",
      "Refresh device, instance, and session counts after cleanup."
    ],
    confirmLabel: "Reconcile sessions",
    tone: "warning"
  });
  if (!confirmed) {
    return;
  }

  setButtonBusy(elements.reconcileSessionsButton, true, "Reconciling...");
  try {
    await adminRequest("/api/admin/maintenance/reconcile", { method: "POST" });
    await refreshDashboard();
    showStatus(elements.adminStatus, "Session reconciliation completed.", "success");
  } catch (error) {
    showStatus(elements.adminStatus, error.message, "error");
  } finally {
    setButtonBusy(elements.reconcileSessionsButton, false, "Reconciling...");
  }
}

async function toggleLockdown() {
  clearStatus(elements.adminStatus);
  const enabled = !state.lockdown?.enabled;
  const reason = elements.lockdownReasonField?.value.trim() || "";

  const confirmed = await requestAdminActionConfirm({
    pill: "Lockdown",
    title: enabled ? "Enable global lockdown" : "Disable global lockdown",
    description: enabled
      ? "This puts the whole licensing system into emergency cutoff mode."
      : "This reopens normal plugin and buyer access.",
    infoTitle: enabled ? "Lockdown behavior" : "Lockdown release",
    infoText: enabled
      ? "Plugin auth, downloads, self-service actions, and admin data changes are blocked while lockdown is enabled."
      : "Closed sessions stay closed, but new plugin auth, downloads, and normal admin changes are allowed again.",
    effects: enabled
      ? [
          "Close current active plugin sessions.",
          "Block new plugin auth, jar downloads, and self-service actions.",
          reason ? `Show this reason in errors and the admin panel: ${reason}` : "Leave the lockdown reason empty."
        ]
      : [
          "Allow plugin auth and jar downloads again.",
          "Reopen buyer self-service and normal admin data changes.",
          "Keep existing closed sessions closed."
        ],
    confirmLabel: enabled ? "Enable lockdown" : "Disable lockdown",
    tone: enabled ? "danger" : "warning"
  });
  if (!confirmed) {
    return;
  }

  setButtonBusy(elements.toggleLockdownButton, true, enabled ? "Locking..." : "Unlocking...");
  try {
    const payload = await adminRequest("/api/admin/lockdown", {
      method: "POST",
      body: {
        enabled,
        reason
      }
    });
    state.lockdown = payload.lockdown || state.lockdown;
    renderLockdown();
    await Promise.all([loadOverview(), loadSessions(), loadAuditLogs()]);
    showStatus(elements.adminStatus, payload.message || (enabled ? "Lockdown enabled." : "Lockdown disabled."), enabled ? "error" : "success");
  } catch (error) {
    showStatus(elements.adminStatus, error.message, "error");
  } finally {
    setButtonBusy(elements.toggleLockdownButton, false, enabled ? "Locking..." : "Unlocking...");
  }
}

elements.loginForm.addEventListener("submit", onLogin);
elements.logoutButton.addEventListener("click", logout);
elements.licenseForm.addEventListener("submit", submitLicenseForm);
elements.deleteLicenseButton.addEventListener("click", deleteCurrentLicense);
elements.resetFormButton.addEventListener("click", resetEditorSelection);
elements.jarUploadForm?.addEventListener("submit", uploadJarFromForm);
elements.jarList?.addEventListener("click", (event) => {
  handleJarListClick(event).catch((error) => {
    showStatus(elements.adminStatus, error.message, "error");
  });
});
elements.jarList?.addEventListener("dragstart", handleJarDragStart);
elements.jarList?.addEventListener("dragover", handleJarDragOver);
elements.jarList?.addEventListener("drop", (event) => {
  handleJarDrop(event).catch((error) => {
    loadJars().catch(() => {});
    showStatus(elements.adminStatus, error.message, "error");
  });
});
elements.jarList?.addEventListener("dragleave", (event) => {
  const item = event.target.closest(".jar-item[data-jar-id]");
  if (item) {
    item.classList.remove("drag-over");
  }
});
elements.jarList?.addEventListener("dragend", clearJarDragState);
elements.jarFileField?.addEventListener("change", () => {
  const file = elements.jarFileField.files?.[0];
  if (!file) {
    return;
  }

  if (!elements.jarNameField.value.trim()) {
    elements.jarNameField.value = file.name.replace(/\.jar$/i, "");
  }
});
elements.refreshDataButton.addEventListener("click", async () => {
  clearStatus(elements.adminStatus);
  try {
    await refreshDashboard();
    showStatus(elements.adminStatus, "Admin data refreshed.", "info");
  } catch (error) {
    showStatus(elements.adminStatus, error.message, "error");
  }
});
elements.reconcileSessionsButton.addEventListener("click", reconcileSessions);
elements.openInspectorButton?.addEventListener("click", () => {
  openInspectorModal().catch((error) => {
    showStatus(elements.adminStatus, error.message, "error");
  });
});
elements.openJarManagerButton?.addEventListener("click", () => {
  openJarManagerModal().catch((error) => {
    showStatus(elements.adminStatus, error.message, "error");
  });
});
elements.openSessionsButton?.addEventListener("click", () => {
  openSessionsModal().catch((error) => {
    showStatus(elements.adminStatus, error.message, "error");
  });
});
elements.openAuditButton?.addEventListener("click", () => {
  openAuditModal().catch((error) => {
    showStatus(elements.adminStatus, error.message, "error");
  });
});
elements.closeInspectorButton?.addEventListener("click", () => closeModal(elements.inspectorModal));
elements.closeJarManagerButton?.addEventListener("click", () => closeModal(elements.jarManagerModal));
elements.closeSessionsButton?.addEventListener("click", () => closeModal(elements.sessionsModal));
elements.closeAuditButton?.addEventListener("click", () => closeModal(elements.auditModal));
elements.closeAdminActionConfirmButton?.addEventListener("click", () => closeAdminActionConfirm(false));
elements.adminActionConfirmCancelButton?.addEventListener("click", () => closeAdminActionConfirm(false));
elements.adminActionConfirmSubmitButton?.addEventListener("click", () => closeAdminActionConfirm(true));
elements.toggleLockdownButton?.addEventListener("click", () => {
  toggleLockdown().catch((error) => {
    showStatus(elements.adminStatus, error.message, "error");
  });
});
elements.refreshLockdownButton?.addEventListener("click", async () => {
  try {
    await loadLockdown();
    showStatus(elements.adminStatus, "Lockdown state refreshed.", "info");
  } catch (error) {
    showStatus(elements.adminStatus, error.message, "error");
  }
});
elements.backupButton.addEventListener("click", openDownloadKeyModal);
elements.licenseSearch.addEventListener("input", renderLicenses);
elements.licensesTableBody.addEventListener("click", handleLicenseTableClick);
elements.refreshDevicesButton.addEventListener("click", async () => {
  try {
    await refreshSelectedLicenseDetail();
    showStatus(elements.adminStatus, "Devices refreshed.", "info");
  } catch (error) {
    showStatus(elements.adminStatus, error.message, "error");
  }
});
elements.refreshInstancesButton.addEventListener("click", async () => {
  try {
    await refreshSelectedLicenseDetail();
    showStatus(elements.adminStatus, "Instances refreshed.", "info");
  } catch (error) {
    showStatus(elements.adminStatus, error.message, "error");
  }
});
elements.resetDevicesButton.addEventListener("click", resetSelectedDevices);
elements.resetInstancesButton.addEventListener("click", resetSelectedInstances);
elements.refreshSessionsButton.addEventListener("click", async () => {
  try {
    await loadSessions();
    showStatus(elements.adminStatus, "Sessions refreshed.", "info");
  } catch (error) {
    showStatus(elements.adminStatus, error.message, "error");
  }
});
elements.refreshAuditLogsButton?.addEventListener("click", async () => {
  try {
    await loadAuditLogs();
    showStatus(elements.adminStatus, "Audit logs refreshed.", "info");
  } catch (error) {
    showStatus(elements.adminStatus, error.message, "error");
  }
});
[
  elements.auditSearchField,
  elements.auditActorField,
  elements.auditActionField,
  elements.auditLimitField
].forEach((input) => {
  input?.addEventListener("change", () => {
    loadAuditLogs().catch((error) => {
      showStatus(elements.adminStatus, error.message, "error");
    });
  });
});
elements.auditSearchField?.addEventListener("input", () => {
  window.clearTimeout(elements.auditSearchField._auditTimerId);
  elements.auditSearchField._auditTimerId = window.setTimeout(() => {
    loadAuditLogs().catch((error) => {
      showStatus(elements.adminStatus, error.message, "error");
    });
  }, 200);
});
elements.downloadKeyForm.addEventListener("submit", submitBackupDownload);
elements.cancelDownloadButton.addEventListener("click", closeDownloadKeyModal);
elements.downloadKeyModal.addEventListener("click", (event) => {
  if (event.target === elements.downloadKeyModal) {
    closeDownloadKeyModal();
  }
});
[
  elements.inspectorModal,
  elements.jarManagerModal,
  elements.sessionsModal,
  elements.auditModal,
  elements.adminActionConfirmModal
].forEach((modal) => {
  modal?.addEventListener("click", (event) => {
    if (event.target === modal) {
      if (modal === elements.adminActionConfirmModal) {
        closeAdminActionConfirm(false);
        return;
      }
      closeModal(modal);
    }
  });
});
document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape") {
    return;
  }
  if (!elements.adminActionConfirmModal.classList.contains("hidden")) {
    closeAdminActionConfirm(false);
    return;
  }
  if (!elements.downloadKeyModal.classList.contains("hidden")) {
    closeDownloadKeyModal();
    return;
  }
  [
    elements.inspectorModal,
    elements.jarManagerModal,
    elements.sessionsModal,
    elements.auditModal
  ].forEach((modal) => {
    if (modal && !modal.classList.contains("hidden")) {
      closeModal(modal);
    }
  });
});

[elements.licenseTypeField, elements.maxSlotsField, elements.licensePlanField, elements.cloudPlayerSlotsField].forEach((input) => {
  input.addEventListener("input", () => {
    if (input === elements.licenseTypeField || input === elements.licensePlanField) {
      updatePlanFields();
    }
    computeFormPricing();
    updateExpiryPreview();
  });
  input.addEventListener("change", () => {
    if (input === elements.licenseTypeField || input === elements.licensePlanField) {
      updatePlanFields();
    }
    computeFormPricing();
    updateExpiryPreview();
  });
});

resetLicenseForm();
clearInspectorState();
showLoggedOutUi();

if (state.apiKey) {
  elements.apiKey.value = state.apiKey;
  authenticate(state.apiKey).catch((error) => {
    logout();
    showStatus(elements.authStatus, error.message, "error");
  });
}
