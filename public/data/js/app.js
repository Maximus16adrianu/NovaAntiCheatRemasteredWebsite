const state = {
  licenseKey: "",
  licenseUser: "",
  devices: [],
  instances: [],
  authAttempts: [],
  blacklistedDevices: [],
  blacklistedInstances: [],
  downloads: [],
  webhookEvents: [],
  downloadLicenseKey: "",
  downloadLicenseUser: "",
  pendingDownloadJarId: null,
  downloadCooldownUntil: 0,
  downloadCooldownTimerId: null,
  actionConfirm: null
};

const PRICING = {
  monthly: { label: "Monthly", base: 5, addon: 1 },
  yearly: { label: "Yearly", base: 30, addon: 5 },
  lifetime: { label: "Lifetime", base: 75, addon: 10 }
};

const elements = {
  mobileMenuButton: document.getElementById("mobileMenuButton"),
  closeMobileMenuButton: document.getElementById("closeMobileMenuButton"),
  mobileNavOverlay: document.getElementById("mobileNavOverlay"),
  mobileNavDrawer: document.getElementById("mobileNavDrawer"),
  lookupForm: document.getElementById("lookupForm"),
  licenseUserInput: document.getElementById("licenseUserInput"),
  licenseKey: document.getElementById("licenseKey"),
  lookupButton: document.getElementById("lookupButton"),
  lookupStatus: document.getElementById("lookupStatus"),
  lookupResult: document.getElementById("lookupResult"),
  licenseDisplayName: document.getElementById("licenseDisplayName"),
  licenseUser: document.getElementById("licenseUser"),
  licenseExpiry: document.getElementById("licenseExpiry"),
  licenseSlots: document.getElementById("licenseSlots"),
  resetCooldown: document.getElementById("resetCooldown"),
  webhookStatus: document.getElementById("webhookStatus"),
  deviceCountChip: document.getElementById("deviceCountChip"),
  instanceCountChip: document.getElementById("instanceCountChip"),
  openDeviceManagerButton: document.getElementById("openDeviceManagerButton"),
  openInstanceViewerButton: document.getElementById("openInstanceViewerButton"),
  openWebhookSettingsButton: document.getElementById("openWebhookSettingsButton"),
  deviceManagerModal: document.getElementById("deviceManagerModal"),
  closeDeviceManagerButton: document.getElementById("closeDeviceManagerButton"),
  devicesList: document.getElementById("devicesList"),
  instanceViewerModal: document.getElementById("instanceViewerModal"),
  closeInstanceViewerButton: document.getElementById("closeInstanceViewerButton"),
  instancesList: document.getElementById("instancesList"),
  webhookSettingsModal: document.getElementById("webhookSettingsModal"),
  closeWebhookSettingsButton: document.getElementById("closeWebhookSettingsButton"),
  webhookCountChip: document.getElementById("webhookCountChip"),
  webhookForm: document.getElementById("webhookForm"),
  manageWebhookUrl: document.getElementById("manageWebhookUrl"),
  manageWebhookEvents: document.getElementById("manageWebhookEvents"),
  saveWebhookButton: document.getElementById("saveWebhookButton"),
  sendTestWebhookButton: document.getElementById("sendTestWebhookButton"),
  clearWebhookButton: document.getElementById("clearWebhookButton"),
  openSecurityModalButton: document.getElementById("openSecurityModalButton"),
  securityCountChip: document.getElementById("securityCountChip"),
  securityModal: document.getElementById("securityModal"),
  closeSecurityModalButton: document.getElementById("closeSecurityModalButton"),
  securityBlockedCount: document.getElementById("securityBlockedCount"),
  securityBlacklistList: document.getElementById("securityBlacklistList"),
  securityDeviceList: document.getElementById("securityDeviceList"),
  securityInstanceList: document.getElementById("securityInstanceList"),
  clearSecurityListButton: document.getElementById("clearSecurityListButton"),
  authAttemptCountChip: document.getElementById("authAttemptCountChip"),
  authAttemptsList: document.getElementById("authAttemptsList"),
  actionConfirmModal: document.getElementById("actionConfirmModal"),
  actionConfirmPill: document.getElementById("actionConfirmPill"),
  actionConfirmTitle: document.getElementById("actionConfirmTitle"),
  actionConfirmDescription: document.getElementById("actionConfirmDescription"),
  actionConfirmInfoBox: document.getElementById("actionConfirmInfoBox"),
  actionConfirmInfoTitle: document.getElementById("actionConfirmInfoTitle"),
  actionConfirmInfoText: document.getElementById("actionConfirmInfoText"),
  actionConfirmEffects: document.getElementById("actionConfirmEffects"),
  actionConfirmCancelButton: document.getElementById("actionConfirmCancelButton"),
  actionConfirmSubmitButton: document.getElementById("actionConfirmSubmitButton"),
  closeActionConfirmButton: document.getElementById("closeActionConfirmButton"),
  submitResetButton: document.getElementById("submitResetButton"),
  clearSelectionButton: document.getElementById("clearSelectionButton"),
  calcSlots: document.getElementById("calcSlots"),
  calcMonths: document.getElementById("calcMonths"),
  calcMonthlyPrice: document.getElementById("calcMonthlyPrice"),
  calcMonthlyMeta: document.getElementById("calcMonthlyMeta"),
  calcYearlyPrice: document.getElementById("calcYearlyPrice"),
  calcYearlyMeta: document.getElementById("calcYearlyMeta"),
  calcLifetimePrice: document.getElementById("calcLifetimePrice"),
  calcLifetimeMeta: document.getElementById("calcLifetimeMeta"),
  calcBestPlan: document.getElementById("calcBestPlan"),
  calcBestMeta: document.getElementById("calcBestMeta"),
  openChecksButton: document.getElementById("openChecksButton"),
  checksModal: document.getElementById("checksModal"),
  closeChecksButton: document.getElementById("closeChecksButton"),
  openVersionsButton: document.getElementById("openVersionsButton"),
  versionsModal: document.getElementById("versionsModal"),
  closeVersionsButton: document.getElementById("closeVersionsButton"),
  openDownloadsButton: document.getElementById("openDownloadsButton"),
  downloadsModal: document.getElementById("downloadsModal"),
  closeDownloadsButton: document.getElementById("closeDownloadsButton"),
  downloadsStatus: document.getElementById("downloadsStatus"),
  downloadsCooldown: document.getElementById("downloadsCooldown"),
  downloadsList: document.getElementById("downloadsList"),
  downloadAccessPanel: document.getElementById("downloadAccessPanel"),
  downloadAccessTitle: document.getElementById("downloadAccessTitle"),
  downloadAccessForm: document.getElementById("downloadAccessForm"),
  downloadAccessUser: document.getElementById("downloadAccessUser"),
  downloadAccessKey: document.getElementById("downloadAccessKey"),
  downloadAccessCancel: document.getElementById("downloadAccessCancel")
};

function setMobileMenuOpen(open) {
  if (!elements.mobileNavDrawer || !elements.mobileNavOverlay || !elements.mobileMenuButton) {
    return;
  }

  elements.mobileNavDrawer.classList.toggle("is-open", open);
  elements.mobileNavOverlay.classList.toggle("is-open", open);
  elements.mobileNavDrawer.setAttribute("aria-hidden", String(!open));
  elements.mobileNavOverlay.setAttribute("aria-hidden", String(!open));
  elements.mobileMenuButton.setAttribute("aria-expanded", String(open));
  document.body.classList.toggle("nav-open", open);
}

function openMobileMenu() {
  setMobileMenuOpen(true);
}

function closeMobileMenu() {
  setMobileMenuOpen(false);
}

function toggleMobileMenu() {
  if (!elements.mobileNavDrawer) {
    return;
  }

  setMobileMenuOpen(!elements.mobileNavDrawer.classList.contains("is-open"));
}

function showStatus(message, tone = "info") {
  elements.lookupStatus.textContent = message;
  elements.lookupStatus.className = `status-banner ${tone}`;
}

function clearStatus() {
  elements.lookupStatus.className = "status-banner hidden";
  elements.lookupStatus.textContent = "";
}

function showDownloadStatus(message, tone = "info") {
  if (!elements.downloadsStatus) {
    return;
  }

  elements.downloadsStatus.textContent = message;
  elements.downloadsStatus.className = `status-banner ${tone}`;
}

function clearDownloadStatus() {
  if (!elements.downloadsStatus) {
    return;
  }

  elements.downloadsStatus.textContent = "";
  elements.downloadsStatus.className = "status-banner hidden";
}

function setBusy(button, busy, label) {
  if (!button) {
    return;
  }
  button.disabled = busy;
  if (!button.dataset.defaultLabel) {
    button.dataset.defaultLabel = button.textContent.trim();
  }
  button.textContent = busy ? label : button.dataset.defaultLabel;
}

function hydrateCardInfoDots(root = document) {
  if (!root?.querySelectorAll) {
    return;
  }

  root.querySelectorAll("[data-card-info]").forEach((card) => {
    card.classList.add("has-card-info");

    const hasDirectHint = Array.from(card.children || []).some((child) => child.classList?.contains("card-info-hint"));
    if (hasDirectHint) {
      return;
    }

    const tooltipText = String(card.dataset.cardInfo || "").trim();
    if (!tooltipText) {
      return;
    }

    const hint = document.createElement("span");
    hint.className = "card-info-hint";
    hint.setAttribute("aria-hidden", "true");
    hint.innerHTML = `
      <span class="card-info-symbol">i</span>
      <span class="card-info-tooltip">${escapeHtml(tooltipText)}</span>
    `;
    card.appendChild(hint);
  });
}

function closeOpenCardHints(except = null) {
  document.querySelectorAll(".card-info-hint.is-open").forEach((hint) => {
    if (hint !== except) {
      hint.classList.remove("is-open");
    }
  });
}

function handleCardInfoHintClick(event) {
  const hint = event.target.closest(".card-info-hint");
  if (!hint) {
    closeOpenCardHints();
    return;
  }

  event.preventDefault();
  event.stopPropagation();
  const shouldOpen = !hint.classList.contains("is-open");
  closeOpenCardHints(hint);
  hint.classList.toggle("is-open", shouldOpen);
}

function formatDate(isoValue) {
  if (!isoValue) {
    return "Unknown";
  }

  const date = new Date(isoValue);
  if (Number.isNaN(date.getTime())) {
    return isoValue;
  }

  return date.toLocaleString();
}

function formatExpiry(license) {
  if (!license) {
    return "-";
  }
  if (license.licenseType === "lifetime") {
    return "Lifetime";
  }
  if (!license.expiresAt) {
    return "No expiry";
  }
  const base = formatDate(license.expiresAt);
  return license.expired ? `${base} (expired)` : base;
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

function getInstanceCardTitle(instance) {
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

function getInstanceServerBadge(instance) {
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

function formatDuration(ms) {
  if (!ms || ms <= 0) {
    return "Ready now";
  }

  const totalSeconds = Math.ceil(ms / 1000);
  const days = Math.floor(totalSeconds / 86400);
  const hours = Math.floor((totalSeconds % 86400) / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);

  const parts = [];
  if (days) {
    parts.push(`${days}d`);
  }
  if (hours) {
    parts.push(`${hours}h`);
  }
  if (minutes || parts.length === 0) {
    parts.push(`${minutes}m`);
  }
  return parts.join(" ");
}

function getChannelBadge(jar) {
  if (jar?.releaseChannel === "beta") {
    return '<span class="download-badge beta">Beta</span>';
  }
  if (jar?.releaseChannel === "legacy") {
    return '<span class="download-badge legacy">Legacy</span>';
  }
  return '<span class="download-badge subtle">Stable</span>';
}

function buildDownloadCardHtml(jar, buttonAttributeName = "data-download-jar-id") {
  const buttonLabel = jar.requiresBuyerLicense ? "Buyer download" : "Download";
  return `
    <div class="download-card-head">
      <div>
        <h4>${escapeHtml(jar.displayName || jar.downloadName || jar.originalName || "Nova jar")}</h4>
        <div class="download-card-meta">
          ${jar.recommended ? '<span class="download-badge recommended">Recommended</span>' : ""}
          ${getChannelBadge(jar)}
          ${getDownloadAccessBadge(jar)}
          ${jar.supportedVersions ? `<span class="download-badge subtle">${escapeHtml(jar.supportedVersions)}</span>` : ""}
          ${jar.createdAt ? `<span class="download-badge subtle">Published ${escapeHtml(formatDate(jar.createdAt))}</span>` : ""}
          <span class="download-badge subtle">${escapeHtml(jar.originalName || jar.downloadName || "jar")}</span>
          <span class="download-badge subtle">${escapeHtml(formatBytes(jar.fileSize || 0))}</span>
          <span class="download-badge subtle">Updated ${escapeHtml(formatDate(jar.updatedAt))}</span>
        </div>
      </div>
      <button class="button button-primary" type="button" ${buttonAttributeName}="${jar.id}">${buttonLabel}</button>
    </div>
    <p>${escapeHtml(jar.notes || "Official Nova jar download.")}</p>
    ${jar.changelog ? `<p>${escapeHtml(jar.changelog)}</p>` : ""}
  `;
}

function getSelectedDeviceIds() {
  return [...document.querySelectorAll(".device-selector:checked")]
    .map((input) => Number.parseInt(input.value, 10))
    .filter(Number.isFinite);
}

function updateActionState() {
  const selectedIds = getSelectedDeviceIds();
  const hasDevices = state.devices.length > 0;
  elements.submitResetButton.disabled = selectedIds.length === 0;
  elements.clearSelectionButton.disabled = !hasDevices || selectedIds.length === 0;
}

function renderDevices(devices) {
  state.devices = Array.isArray(devices) ? devices : [];
  elements.devicesList.innerHTML = "";
  elements.deviceCountChip.textContent = `${state.devices.length} device${state.devices.length === 1 ? "" : "s"}`;

  if (state.devices.length === 0) {
    elements.devicesList.innerHTML = `<div class="empty-devices">No active devices are currently registered for this license.</div>`;
    updateActionState();
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const device of state.devices) {
    const card = document.createElement("article");
    card.className = "device-card";
    card.dataset.cardInfo = "Stored device on this license.";
    card.innerHTML = `
      <label class="device-check">
        <input class="device-selector" type="checkbox" value="${device.id}">
        <div>
          <h5>${escapeHtml(cleanLabel(device.deviceName, "Unknown Device"))}</h5>
          <div class="device-chip-row">
            <span class="device-chip">${device.online ? "Online" : "Offline"}</span>
            <span class="device-chip">${device.openSessionCount || 0} open session${device.openSessionCount === 1 ? "" : "s"}</span>
          </div>
          <p class="device-meta">
            Last username: ${escapeHtml(device.lastUsername || "unknown")}<br>
            First seen: ${escapeHtml(formatDate(device.firstSeenAt))}<br>
            Last seen: ${escapeHtml(formatDate(device.lastSeenAt))}<br>
            HWID: <span class="mono-wrap">${escapeHtml(device.hwidHash)}</span>
          </p>
        </div>
      </label>
    `;
    fragment.appendChild(card);
  }

  elements.devicesList.appendChild(fragment);
  hydrateCardInfoDots(elements.devicesList);
  document.querySelectorAll(".device-selector").forEach((input) => {
    input.addEventListener("change", updateActionState);
  });
  updateActionState();
}

function renderInstances(instances) {
  state.instances = Array.isArray(instances) ? instances : [];
  if (!elements.instancesList || !elements.instanceCountChip) {
    return;
  }

  elements.instancesList.innerHTML = "";
  elements.instanceCountChip.textContent = `${state.instances.length} instance${state.instances.length === 1 ? "" : "s"}`;

  if (state.instances.length === 0) {
    elements.instancesList.innerHTML = `<div class="download-empty">No stored instances are registered for this license.</div>`;
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const instance of state.instances) {
    const card = document.createElement("article");
    card.className = "instance-card";
    card.dataset.cardInfo = "Stored server instance on this license.";
    const status = instance.online ? "Online" : "Offline";
    card.innerHTML = `
      <h5>${escapeHtml(getInstanceCardTitle(instance))}</h5>
      <div class="device-chip-row">
        <span class="device-chip">${escapeHtml(status)}</span>
        <span class="device-chip">${escapeHtml(getInstanceServerBadge(instance))}</span>
      </div>
      <p class="instance-meta">
        Device: ${escapeHtml(cleanLabel(instance.deviceName, "Unknown device"))}<br>
        First seen: ${escapeHtml(formatDate(instance.firstSeenAt))}<br>
        Last seen: ${escapeHtml(formatDate(instance.lastSeenAt))}<br>
        Instance: <span class="mono-wrap">${escapeHtml(instance.instanceUuid || instance.instanceHash || "-")}</span>
      </p>
    `;
    fragment.appendChild(card);
  }

  elements.instancesList.appendChild(fragment);
  hydrateCardInfoDots(elements.instancesList);
}

function renderWebhookSettings(webhook, definitions) {
  const configured = Boolean(webhook?.configured);
  state.webhookEvents = Array.isArray(definitions) ? definitions : [];
  if (elements.manageWebhookUrl) {
    elements.manageWebhookUrl.value = webhook?.url || "";
  }
  if (elements.webhookStatus) {
    elements.webhookStatus.textContent = configured ? "Configured" : "Not configured";
  }
  if (elements.webhookCountChip) {
    const enabledCount = Object.values(webhook?.events || {}).filter(Boolean).length;
    elements.webhookCountChip.textContent = `${enabledCount} alert${enabledCount === 1 ? "" : "s"}`;
  }
  if (!elements.manageWebhookEvents) {
    return;
  }

  elements.manageWebhookEvents.innerHTML = "";
  const fragment = document.createDocumentFragment();
  for (const definition of state.webhookEvents) {
    const checked = webhook?.events?.[definition.id] !== false;
    const label = document.createElement("label");
    label.className = "webhook-event";
    label.innerHTML = `
      <input type="checkbox" data-webhook-event-id="${escapeHtml(definition.id)}"${checked ? " checked" : ""}>
      <div>
        <strong>${escapeHtml(definition.label)}</strong>
        <p>${escapeHtml(definition.description)}</p>
      </div>
    `;
    fragment.appendChild(label);
  }
  elements.manageWebhookEvents.appendChild(fragment);
}

function isDeviceBlacklisted(device) {
  const hwidHash = String(device?.hwidHash || "").trim();
  return hwidHash && state.blacklistedDevices.some((entry) => entry.hwidHash === hwidHash);
}

function isInstanceBlacklisted(instance) {
  const instanceUuid = String(instance?.instanceUuid || "").trim();
  const instanceHash = String(instance?.instanceHash || "").trim();
  return state.blacklistedInstances.some((entry) => {
    const blacklistedUuid = String(entry.instanceUuid || "").trim();
    const blacklistedHash = String(entry.instanceHash || "").trim();
    return (instanceUuid && blacklistedUuid && instanceUuid === blacklistedUuid)
      || (instanceHash && blacklistedHash && instanceHash === blacklistedHash);
  });
}

function renderSecurityState(security = {}) {
  state.blacklistedDevices = Array.isArray(security.blacklistedDevices) ? security.blacklistedDevices : [];
  state.blacklistedInstances = Array.isArray(security.blacklistedInstances) ? security.blacklistedInstances : [];

  const totalBlocked = state.blacklistedDevices.length + state.blacklistedInstances.length;
  if (elements.securityCountChip) {
    elements.securityCountChip.textContent = `${totalBlocked} blocked`;
  }
  if (elements.securityBlockedCount) {
    elements.securityBlockedCount.textContent = `${totalBlocked} blocked`;
  }
  if (elements.clearSecurityListButton) {
    elements.clearSecurityListButton.disabled = totalBlocked === 0;
  }

  if (elements.securityBlacklistList) {
    elements.securityBlacklistList.innerHTML = "";
    if (totalBlocked === 0) {
      elements.securityBlacklistList.innerHTML = `<div class="download-empty"><strong>No blocked devices or instances.</strong><p>Blacklist entries will appear here after you block a PC or server instance.</p></div>`;
    } else {
      const fragment = document.createDocumentFragment();

      for (const device of state.blacklistedDevices) {
        const card = document.createElement("article");
        card.className = "security-blacklist-card";
        card.dataset.cardInfo = "Blocked by the license security list.";
        card.innerHTML = `
          <div class="device-chip-row">
            <span class="device-chip">Device</span>
            <span class="device-chip">Blocked ${escapeHtml(formatDate(device.createdAt))}</span>
          </div>
          <h5>${escapeHtml(cleanLabel(device.deviceName, "Blocked device"))}</h5>
          <p class="instance-meta">HWID: <span class="mono-wrap">${escapeHtml(device.hwidHash || "-")}</span></p>
        `;
        fragment.appendChild(card);
      }

      for (const instance of state.blacklistedInstances) {
        const title = getMeaningfulLabel(instance.instanceName)
          || (instance.instanceUuid ? `Instance ${instance.instanceUuid.slice(0, 8)}` : "Blocked instance");
        const serverBadge = getMeaningfulLabel(instance.lastServerName) || "Server unknown";
        const card = document.createElement("article");
        card.className = "security-blacklist-card";
        card.dataset.cardInfo = "Blocked by the license security list.";
        card.innerHTML = `
          <div class="device-chip-row">
            <span class="device-chip">Instance</span>
            <span class="device-chip">${escapeHtml(serverBadge)}</span>
            <span class="device-chip">Blocked ${escapeHtml(formatDate(instance.createdAt))}</span>
          </div>
          <h5>${escapeHtml(title)}</h5>
          <p class="instance-meta">Instance: <span class="mono-wrap">${escapeHtml(instance.instanceUuid || instance.instanceHash || "-")}</span></p>
        `;
        fragment.appendChild(card);
      }

      elements.securityBlacklistList.appendChild(fragment);
      hydrateCardInfoDots(elements.securityBlacklistList);
    }
  }

  if (elements.securityDeviceList) {
    elements.securityDeviceList.innerHTML = "";
    if (state.devices.length === 0) {
      elements.securityDeviceList.innerHTML = `<div class="download-empty"><strong>No active devices to block.</strong><p>Devices that successfully authenticated on this license will show up here.</p></div>`;
    } else {
      const fragment = document.createDocumentFragment();
      for (const device of state.devices) {
        const blacklisted = isDeviceBlacklisted(device);
        const card = document.createElement("article");
        card.className = "security-entry-card";
        card.dataset.cardInfo = "Use this to block the device on this license.";
        card.innerHTML = `
          <div class="security-entry-head">
            <div>
              <h5>${escapeHtml(cleanLabel(device.deviceName, "Unknown Device"))}</h5>
              <p>${escapeHtml(device.online ? "Currently online" : "Currently offline")}</p>
            </div>
            <button class="button ${blacklisted ? "button-secondary" : "button-primary"}" type="button" data-blacklist-device-id="${device.id}"${blacklisted ? " disabled" : ""}>
              ${blacklisted ? "Blocked" : "Blacklist"}
            </button>
          </div>
          <p class="instance-meta">HWID: <span class="mono-wrap">${escapeHtml(device.hwidHash || "-")}</span></p>
        `;
        fragment.appendChild(card);
      }
      elements.securityDeviceList.appendChild(fragment);
      hydrateCardInfoDots(elements.securityDeviceList);
    }
  }

  if (elements.securityInstanceList) {
    elements.securityInstanceList.innerHTML = "";
    if (state.instances.length === 0) {
      elements.securityInstanceList.innerHTML = `<div class="download-empty"><strong>No active instances to block.</strong><p>Stored server instances tied to this license will show up here.</p></div>`;
    } else {
      const fragment = document.createDocumentFragment();
      for (const instance of state.instances) {
        const blacklisted = isInstanceBlacklisted(instance);
        const card = document.createElement("article");
        card.className = "security-entry-card";
        card.dataset.cardInfo = "Use this to block the server on this license.";
        card.innerHTML = `
          <div class="security-entry-head">
            <div>
              <h5>${escapeHtml(getInstanceCardTitle(instance))}</h5>
              <p>${escapeHtml(getInstanceServerBadge(instance))}</p>
            </div>
            <button class="button ${blacklisted ? "button-secondary" : "button-primary"}" type="button" data-blacklist-instance-id="${instance.id}"${blacklisted ? " disabled" : ""}>
              ${blacklisted ? "Blocked" : "Blacklist"}
            </button>
          </div>
          <p class="instance-meta">Instance: <span class="mono-wrap">${escapeHtml(instance.instanceUuid || instance.instanceHash || "-")}</span></p>
        `;
        fragment.appendChild(card);
      }
      elements.securityInstanceList.appendChild(fragment);
      hydrateCardInfoDots(elements.securityInstanceList);
    }
  }
}

function getAuthAttemptStatus(entry) {
  return entry?.action === "license_activated" ? "allowed" : "denied";
}

function getAuthAttemptTitle(entry) {
  switch (entry?.action) {
    case "license_activated":
      return "Activation allowed";
    case "auth_denied_license_disabled":
      return "License disabled";
    case "auth_denied_license_expired":
      return "License expired";
    case "auth_denied_username_mismatch":
      return "Username mismatch";
    case "hwid_limit_denied":
      return "HWID limit denied";
    case "instance_limit_denied":
      return "Instance limit denied";
    case "auth_denied_device_blacklisted":
      return "Device blocked";
    case "auth_denied_instance_blacklisted":
      return "Instance blocked";
    case "heartbeat_denied_license_missing":
      return "Heartbeat denied";
    case "heartbeat_denied_license_disabled":
      return "Heartbeat blocked";
    case "heartbeat_denied_license_expired":
      return "Heartbeat expired";
    default:
      return "Auth attempt";
  }
}

function getAuthAttemptSummary(entry) {
  const details = entry?.details || {};
  switch (entry?.action) {
    case "license_activated":
      return `${cleanLabel(details.instanceName || details.deviceName, "A server")} authenticated successfully.`;
    case "auth_denied_license_disabled":
      return "A plugin tried to authenticate while this license was disabled.";
    case "auth_denied_license_expired":
      return "A plugin tried to authenticate after the license had expired.";
    case "auth_denied_username_mismatch":
      return details.providedUsername
        ? `A plugin used the wrong username: ${details.providedUsername}.`
        : "A plugin used a username that does not match this license.";
    case "hwid_limit_denied":
      return `${details.activeDeviceCount ?? "?"}/${details.maxHwids ?? "?"} device slots were already in use.`;
    case "instance_limit_denied":
      return `${details.activeInstanceCount ?? "?"}/${details.maxInstances ?? "?"} instance slots were already in use.`;
    case "auth_denied_device_blacklisted":
      return "This device is on the security blacklist for the license.";
    case "auth_denied_instance_blacklisted":
      return "This server instance is on the security blacklist for the license.";
    case "heartbeat_denied_license_missing":
      return "A heartbeat reached the backend after the session or license was no longer valid.";
    case "heartbeat_denied_license_disabled":
      return "A heartbeat was rejected because the license had been disabled.";
    case "heartbeat_denied_license_expired":
      return "A heartbeat was rejected because the license had expired.";
    default:
      return "Recent authentication activity for this license.";
  }
}

function renderAuthAttempts(attempts) {
  state.authAttempts = Array.isArray(attempts) ? attempts : [];
  if (!elements.authAttemptsList || !elements.authAttemptCountChip) {
    return;
  }

  elements.authAttemptCountChip.textContent = `${state.authAttempts.length} attempt${state.authAttempts.length === 1 ? "" : "s"}`;
  elements.authAttemptsList.innerHTML = "";

  if (state.authAttempts.length === 0) {
    elements.authAttemptsList.innerHTML = `<div class="download-empty"><strong>No recent auth attempts yet.</strong><p>Recent activations and denied auth events will show up here.</p></div>`;
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const entry of state.authAttempts) {
    const status = getAuthAttemptStatus(entry);
    const details = entry?.details || {};
    const card = document.createElement("article");
    card.className = "auth-attempt-card";
    card.dataset.cardInfo = "Recent auth result for this license.";

    const chips = [];
    const deviceName = cleanLabel(entry.deviceName || details.deviceName, "");
    const serverName = cleanLabel(details.serverName, "");
    if (deviceName) {
      chips.push(`<span class="device-chip">${escapeHtml(deviceName)}</span>`);
    }
    if (serverName) {
      chips.push(`<span class="device-chip">${escapeHtml(serverName)}</span>`);
    }
    if (details.providedUsername) {
      chips.push(`<span class="device-chip">User ${escapeHtml(details.providedUsername)}</span>`);
    }

    card.innerHTML = `
      <div class="auth-attempt-head">
        <div>
          <h5>${escapeHtml(getAuthAttemptTitle(entry))}</h5>
          <p>${escapeHtml(getAuthAttemptSummary(entry))}</p>
        </div>
        <div class="auth-attempt-meta">
          <span class="auth-attempt-chip ${status === "allowed" ? "success" : "danger"}">${status === "allowed" ? "Allowed" : "Denied"}</span>
          <span class="device-chip">${escapeHtml(formatDate(entry.createdAt))}</span>
        </div>
      </div>
      ${chips.length ? `<div class="device-chip-row">${chips.join("")}</div>` : ""}
    `;

    fragment.appendChild(card);
  }

  elements.authAttemptsList.appendChild(fragment);
  hydrateCardInfoDots(elements.authAttemptsList);
}

function renderLookupResult(payload) {
  const license = payload.license || {};
  elements.lookupResult.classList.remove("hidden");
  elements.licenseDisplayName.textContent = license.displayName || license.key || "Unknown license";
  elements.licenseUser.textContent = license.customerUsername || "Unbound";
  elements.licenseExpiry.textContent = formatExpiry(license);
  elements.licenseSlots.textContent = `HWIDs ${license.activeDeviceCount || 0}/${license.maxSlots || license.maxHwids || 0} | Instances ${(license.onlineInstanceCount ?? license.activeInstanceCount ?? 0)}/${license.maxSlots || license.maxInstances || 0}`;
  elements.resetCooldown.textContent = payload.cooldownRemainingMs > 0
    ? `${formatDuration(payload.cooldownRemainingMs)} remaining`
    : "Ready now";
  state.downloadLicenseUser = license.customerUsername || state.licenseUser;
  state.downloadLicenseKey = license.key || state.licenseKey;
  elements.openDeviceManagerButton.disabled = false;
  elements.openInstanceViewerButton.disabled = false;
  elements.openWebhookSettingsButton.disabled = false;
  if (elements.openSecurityModalButton) {
    elements.openSecurityModalButton.disabled = false;
  }
  renderDevices(payload.devices || []);
  renderInstances(payload.instances || []);
  renderWebhookSettings(payload.webhook || {}, payload.webhookEvents || []);
  renderAuthAttempts(payload.recentAuthAttempts || []);
  renderSecurityState(payload.security || {});
}

function clearResult() {
  elements.lookupResult.classList.add("hidden");
  closeModal(elements.deviceManagerModal);
  closeModal(elements.instanceViewerModal);
  closeModal(elements.webhookSettingsModal);
  closeModal(elements.securityModal);
  closeActionConfirm(false);
  elements.devicesList.innerHTML = "";
  elements.licenseDisplayName.textContent = "-";
  elements.licenseUser.textContent = "-";
  if (elements.licenseExpiry) {
    elements.licenseExpiry.textContent = "-";
  }
  if (elements.licenseSlots) {
    elements.licenseSlots.textContent = "-";
  }
  if (elements.webhookStatus) {
    elements.webhookStatus.textContent = "Not configured";
  }
  if (elements.instancesList) {
    elements.instancesList.innerHTML = "";
  }
  if (elements.authAttemptsList) {
    elements.authAttemptsList.innerHTML = "";
  }
  if (elements.securityBlacklistList) {
    elements.securityBlacklistList.innerHTML = "";
  }
  if (elements.securityDeviceList) {
    elements.securityDeviceList.innerHTML = "";
  }
  if (elements.securityInstanceList) {
    elements.securityInstanceList.innerHTML = "";
  }
  if (elements.manageWebhookEvents) {
    elements.manageWebhookEvents.innerHTML = "";
  }
  if (elements.manageWebhookUrl) {
    elements.manageWebhookUrl.value = "";
  }
  state.devices = [];
  state.instances = [];
  state.authAttempts = [];
  state.blacklistedDevices = [];
  state.blacklistedInstances = [];
  state.webhookEvents = [];
  state.downloadLicenseKey = "";
  state.downloadLicenseUser = "";
  if (elements.openDeviceManagerButton) {
    elements.openDeviceManagerButton.disabled = true;
  }
  if (elements.openInstanceViewerButton) {
    elements.openInstanceViewerButton.disabled = true;
  }
  if (elements.openWebhookSettingsButton) {
    elements.openWebhookSettingsButton.disabled = true;
  }
  if (elements.openSecurityModalButton) {
    elements.openSecurityModalButton.disabled = true;
  }
  if (elements.deviceCountChip) {
    elements.deviceCountChip.textContent = "0 devices";
  }
  if (elements.instanceCountChip) {
    elements.instanceCountChip.textContent = "0 instances";
  }
  if (elements.webhookCountChip) {
    elements.webhookCountChip.textContent = "0 alerts";
  }
  if (elements.authAttemptCountChip) {
    elements.authAttemptCountChip.textContent = "0 attempts";
  }
  if (elements.securityCountChip) {
    elements.securityCountChip.textContent = "0 blocked";
  }
  if (elements.securityBlockedCount) {
    elements.securityBlockedCount.textContent = "0 blocked";
  }
  if (elements.clearSecurityListButton) {
    elements.clearSecurityListButton.disabled = true;
  }
  updateActionState();
}

async function postJson(url, body) {
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(body)
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

function closeActionConfirm(confirmed = false) {
  if (elements.actionConfirmModal) {
    closeModal(elements.actionConfirmModal);
  }

  if (elements.actionConfirmSubmitButton) {
    elements.actionConfirmSubmitButton.className = "button button-primary";
    elements.actionConfirmSubmitButton.textContent = "Confirm";
    elements.actionConfirmSubmitButton.disabled = false;
  }
  if (elements.actionConfirmInfoBox) {
    elements.actionConfirmInfoBox.className = "action-info-box";
  }

  const pending = state.actionConfirm;
  state.actionConfirm = null;
  if (pending?.resolve) {
    pending.resolve(Boolean(confirmed));
  }
}

function requestActionConfirm({
  pill = "Confirm",
  title = "Confirm action",
  description = "Review what this action does before you continue.",
  infoTitle = "What this does",
  infoText = "This action will immediately update the license state.",
  effects = [],
  confirmLabel = "Confirm",
  tone = "info"
} = {}) {
  if (!elements.actionConfirmModal) {
    return Promise.resolve(true);
  }

  if (state.actionConfirm?.resolve) {
    state.actionConfirm.resolve(false);
  }

  elements.actionConfirmPill.textContent = pill;
  elements.actionConfirmTitle.textContent = title;
  elements.actionConfirmDescription.textContent = description;
  elements.actionConfirmInfoTitle.textContent = infoTitle;
  elements.actionConfirmInfoText.textContent = infoText;
  elements.actionConfirmInfoBox.className = `action-info-box ${tone}`;
  elements.actionConfirmSubmitButton.textContent = confirmLabel;
  elements.actionConfirmSubmitButton.className = tone === "danger"
    ? "button button-danger"
    : "button button-primary";

  const effectList = Array.isArray(effects) ? effects.filter(Boolean) : [];
  elements.actionConfirmEffects.innerHTML = "";
  for (const effect of effectList) {
    const item = document.createElement("li");
    item.textContent = effect;
    elements.actionConfirmEffects.appendChild(item);
  }

  openModal(elements.actionConfirmModal);

  return new Promise((resolve) => {
    state.actionConfirm = {
      resolve
    };
  });
}

async function lookupLicense(event) {
  event.preventDefault();
  clearStatus();
  clearResult();

  const licenseUser = elements.licenseUserInput.value.trim();
  const licenseKey = elements.licenseKey.value.trim().toUpperCase();
  if (!licenseUser) {
    showStatus("Enter the license user first.", "error");
    return;
  }
  if (!licenseKey) {
    showStatus("Enter a license key first.", "error");
    return;
  }

  state.licenseUser = licenseUser;
  state.licenseKey = licenseKey;
  setBusy(elements.lookupButton, true, "Looking up...");

  try {
    const payload = await postJson("/api/manage/lookup", { licenseKey, username: licenseUser });
    renderLookupResult(payload);
    showStatus("License loaded. You can now manage resets, the security blacklist, instances and webhook alerts.", "success");
  } catch (error) {
    showStatus(error.message, "error");
  } finally {
    setBusy(elements.lookupButton, false, "Looking up...");
  }
}

async function submitReset() {
  clearStatus();

  const selectedIds = getSelectedDeviceIds();
  if (!state.licenseKey) {
    showStatus("Run a license lookup first.", "error");
    return;
  }
  if (!state.licenseUser) {
    showStatus("Run a license lookup first.", "error");
    return;
  }
  if (selectedIds.length === 0) {
    showStatus("Select at least one device to reset.", "error");
    return;
  }

  const selectedNames = state.devices
    .filter((device) => selectedIds.includes(device.id))
    .map((device) => cleanLabel(device.deviceName, "Unknown device"));
  const confirmed = await requestActionConfirm({
    pill: "Reset",
    title: "Reset selected devices",
    description: "This removes the selected HWIDs from the license so they have to authenticate again later.",
    infoTitle: "Reset behavior",
    infoText: "Active sessions on the selected devices are closed immediately and the normal reset cooldown starts after this action.",
    effects: [
      `Close the current sessions on ${selectedIds.length} selected device${selectedIds.length === 1 ? "" : "s"}.`,
      "Remove those HWIDs from the license until they authenticate again later.",
      "Start the normal reset cooldown for this license.",
      selectedNames.length ? `Selected devices: ${selectedNames.join(", ")}.` : ""
    ],
    confirmLabel: "Reset HWIDs",
    tone: "danger"
  });
  if (!confirmed) {
    return;
  }

  setBusy(elements.submitResetButton, true, "Resetting...");

  try {
    const payload = await postJson("/api/manage/reset", {
      licenseKey: state.licenseKey,
      username: state.licenseUser,
      deviceIds: selectedIds
    });
    renderLookupResult(payload);
    showStatus(payload.message || "Selected HWIDs were reset.", "success");
  } catch (error) {
    showStatus(error.message, "error");
  } finally {
    setBusy(elements.submitResetButton, false, "Resetting...");
  }
}

function clearSelection() {
  document.querySelectorAll(".device-selector:checked").forEach((input) => {
    input.checked = false;
  });
  updateActionState();
}

function getSelectedWebhookEvents() {
  const events = {};
  document.querySelectorAll("[data-webhook-event-id]").forEach((input) => {
    events[input.dataset.webhookEventId] = Boolean(input.checked);
  });
  return events;
}

async function saveWebhook(event) {
  event.preventDefault();
  clearStatus();

  if (!state.licenseKey || !state.licenseUser) {
    showStatus("Run a license lookup first.", "error");
    return;
  }

  const configuredEventCount = Object.values(getSelectedWebhookEvents()).filter(Boolean).length;
  const confirmed = await requestActionConfirm({
    pill: "Webhook",
    title: "Save Discord webhook",
    description: "This stores the current webhook URL and alert switches on the license.",
    infoTitle: "Webhook storage",
    infoText: "Nova will use this webhook for the enabled alert types after you save it.",
    effects: [
      "Store the Discord webhook on this license.",
      `Enable ${configuredEventCount} selected alert type${configuredEventCount === 1 ? "" : "s"}.`,
      "Replace any webhook URL that was saved before."
    ],
    confirmLabel: "Save webhook",
    tone: "info"
  });
  if (!confirmed) {
    return;
  }

  setBusy(elements.saveWebhookButton, true, "Saving...");
  try {
    const payload = await postJson("/api/manage/webhook", {
      licenseKey: state.licenseKey,
      username: state.licenseUser,
      webhookUrl: elements.manageWebhookUrl.value.trim(),
      events: getSelectedWebhookEvents()
    });
    renderLookupResult(payload);
    showStatus(payload.message || "Webhook saved.", "success");
  } catch (error) {
    showStatus(error.message, "error");
  } finally {
    setBusy(elements.saveWebhookButton, false, "Saving...");
  }
}

async function clearWebhook() {
  if (!state.licenseKey || !state.licenseUser) {
    showStatus("Run a license lookup first.", "error");
    return;
  }

  const confirmed = await requestActionConfirm({
    pill: "Webhook",
    title: "Clear Discord webhook",
    description: "This removes the saved webhook from the license.",
    infoTitle: "Webhook removal",
    infoText: "After this, Discord alerts stop until a new webhook is saved.",
    effects: [
      "Remove the stored Discord webhook URL.",
      "Stop future Discord alerts for this license.",
      "Keep the current license and auth data unchanged."
    ],
    confirmLabel: "Clear webhook",
    tone: "danger"
  });
  if (!confirmed) {
    return;
  }

  setBusy(elements.clearWebhookButton, true, "Clearing...");
  try {
    const payload = await postJson("/api/manage/webhook", {
      licenseKey: state.licenseKey,
      username: state.licenseUser,
      webhookUrl: "",
      events: getSelectedWebhookEvents()
    });
    renderLookupResult(payload);
    showStatus(payload.message || "Webhook removed.", "success");
  } catch (error) {
    showStatus(error.message, "error");
  } finally {
    setBusy(elements.clearWebhookButton, false, "Clearing...");
  }
}

async function sendTestWebhook() {
  clearStatus();

  if (!state.licenseKey || !state.licenseUser) {
    showStatus("Run a license lookup first.", "error");
    return;
  }

  if (!elements.manageWebhookUrl.value.trim()) {
    showStatus("Enter a Discord webhook URL first.", "error");
    return;
  }

  const confirmed = await requestActionConfirm({
    pill: "Webhook",
    title: "Send test alert",
    description: "This sends one sample message to the Discord webhook currently entered in the field.",
    infoTitle: "Test alert",
    infoText: "Use this to verify that the webhook works before or after saving it.",
    effects: [
      "Send one sample Nova alert to the entered Discord webhook URL.",
      "Leave the rest of the license state unchanged.",
      "Not change HWIDs, instances or blacklist entries."
    ],
    confirmLabel: "Send test alert",
    tone: "info"
  });
  if (!confirmed) {
    return;
  }

  setBusy(elements.sendTestWebhookButton, true, "Sending...");
  try {
    const payload = await postJson("/api/manage/webhook/test", {
      licenseKey: state.licenseKey,
      username: state.licenseUser,
      webhookUrl: elements.manageWebhookUrl.value.trim()
    });
    showStatus(payload.message || "Test alert sent successfully.", "success");
  } catch (error) {
    showStatus(error.message, "error");
  } finally {
    setBusy(elements.sendTestWebhookButton, false, "Sending...");
  }
}

function openDeviceManagerModal() {
  if (!state.licenseKey) {
    showStatus("Run a license lookup first.", "error");
    return;
  }
  closeModal(elements.instanceViewerModal);
  closeModal(elements.webhookSettingsModal);
  closeModal(elements.securityModal);
  openModal(elements.deviceManagerModal);
}

function openInstanceViewerModal() {
  if (!state.licenseKey) {
    showStatus("Run a license lookup first.", "error");
    return;
  }
  closeModal(elements.deviceManagerModal);
  closeModal(elements.webhookSettingsModal);
  closeModal(elements.securityModal);
  openModal(elements.instanceViewerModal);
}

function openWebhookSettingsModal() {
  if (!state.licenseKey) {
    showStatus("Run a license lookup first.", "error");
    return;
  }
  closeModal(elements.deviceManagerModal);
  closeModal(elements.instanceViewerModal);
  closeModal(elements.securityModal);
  openModal(elements.webhookSettingsModal);
}

function openSecurityModal() {
  if (!state.licenseKey) {
    showStatus("Run a license lookup first.", "error");
    return;
  }

  closeModal(elements.deviceManagerModal);
  closeModal(elements.instanceViewerModal);
  closeModal(elements.webhookSettingsModal);
  openModal(elements.securityModal);
}

async function blacklistDevice(deviceId) {
  clearStatus();
  if (!state.licenseKey || !state.licenseUser) {
    showStatus("Run a license lookup first.", "error");
    return;
  }
  const device = state.devices.find((entry) => entry.id === deviceId);
  if (!device) {
    showStatus("Device not found.", "error");
    return;
  }

  const confirmed = await requestActionConfirm({
    pill: "Security",
    title: "Blacklist this device",
    description: "This permanently blocks the selected HWID from authenticating on this license until the security list is cleared.",
    infoTitle: "Device blacklist",
    infoText: "The matching device is kicked immediately if it is online and future auth attempts from that HWID are denied.",
    effects: [
      `Block ${cleanLabel(device.deviceName, "this device")} on this license.`,
      "Close the current live session on that device if one is open.",
      "Free the slot so a different device can authenticate later.",
      "Keep the block active until you clear the security list."
    ],
    confirmLabel: "Blacklist device",
    tone: "danger"
  });
  if (!confirmed) {
    return;
  }

  const button = document.querySelector(`[data-blacklist-device-id="${deviceId}"]`);
  setBusy(button, true, "Blocking...");
  try {
    const payload = await postJson("/api/manage/security/device-blacklist", {
      licenseKey: state.licenseKey,
      username: state.licenseUser,
      deviceId
    });
    renderLookupResult(payload);
    showStatus(payload.message || "The device was blacklisted.", "success");
  } catch (error) {
    showStatus(error.message, "error");
  } finally {
    setBusy(button, false, "Blocking...");
  }
}

async function blacklistInstance(instanceId) {
  clearStatus();
  if (!state.licenseKey || !state.licenseUser) {
    showStatus("Run a license lookup first.", "error");
    return;
  }
  const instance = state.instances.find((entry) => entry.id === instanceId);
  if (!instance) {
    showStatus("Instance not found.", "error");
    return;
  }

  const confirmed = await requestActionConfirm({
    pill: "Security",
    title: "Blacklist this server instance",
    description: "This blocks the selected server instance from authenticating on this license until the security list is cleared.",
    infoTitle: "Instance blacklist",
    infoText: "The matching server is kicked immediately if it is online and future auth attempts from the same stable instance are denied.",
    effects: [
      `Block ${getInstanceCardTitle(instance)} on this license.`,
      "Close the current live session for that server if it is online.",
      "Free the instance slot for a different server.",
      "Keep the block active until you clear the security list."
    ],
    confirmLabel: "Blacklist instance",
    tone: "danger"
  });
  if (!confirmed) {
    return;
  }

  const button = document.querySelector(`[data-blacklist-instance-id="${instanceId}"]`);
  setBusy(button, true, "Blocking...");
  try {
    const payload = await postJson("/api/manage/security/instance-blacklist", {
      licenseKey: state.licenseKey,
      username: state.licenseUser,
      instanceId
    });
    renderLookupResult(payload);
    showStatus(payload.message || "The instance was blacklisted.", "success");
  } catch (error) {
    showStatus(error.message, "error");
  } finally {
    setBusy(button, false, "Blocking...");
  }
}

async function clearSecurityList() {
  clearStatus();
  if (!state.licenseKey || !state.licenseUser) {
    showStatus("Run a license lookup first.", "error");
    return;
  }

  const blockedCount = state.blacklistedDevices.length + state.blacklistedInstances.length;
  const confirmed = await requestActionConfirm({
    pill: "Security",
    title: "Clear license security list",
    description: "This removes every blocked device and server instance from the license blacklist.",
    infoTitle: "Security list clear",
    infoText: "Anything on the blacklist is allowed to authenticate again after this action, as long as the license and slots still allow it.",
    effects: [
      `Remove ${blockedCount} blocked entr${blockedCount === 1 ? "y" : "ies"} from the security blacklist.`,
      "Allow those devices or server instances to authenticate again later.",
      "Not restore any live session automatically."
    ],
    confirmLabel: "Clear security list",
    tone: "danger"
  });
  if (!confirmed) {
    return;
  }

  setBusy(elements.clearSecurityListButton, true, "Clearing...");
  try {
    const payload = await postJson("/api/manage/security/clear", {
      licenseKey: state.licenseKey,
      username: state.licenseUser
    });
    renderLookupResult(payload);
    showStatus(payload.message || "The security list was cleared.", "success");
  } catch (error) {
    showStatus(error.message, "error");
  } finally {
    setBusy(elements.clearSecurityListButton, false, "Clearing...");
  }
}

function handleSecurityListClick(event) {
  const deviceButton = event.target.closest("[data-blacklist-device-id]");
  if (deviceButton) {
    const deviceId = Number.parseInt(deviceButton.dataset.blacklistDeviceId, 10);
    if (Number.isFinite(deviceId)) {
      blacklistDevice(deviceId).catch((error) => {
        showStatus(error.message, "error");
      });
    }
    return;
  }

  const instanceButton = event.target.closest("[data-blacklist-instance-id]");
  if (instanceButton) {
    const instanceId = Number.parseInt(instanceButton.dataset.blacklistInstanceId, 10);
    if (Number.isFinite(instanceId)) {
      blacklistInstance(instanceId).catch((error) => {
        showStatus(error.message, "error");
      });
    }
  }
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

function openChecksModal() {
  openModal(elements.checksModal);
}

function closeChecksModal() {
  closeModal(elements.checksModal);
}

function openVersionsModal() {
  openModal(elements.versionsModal);
}

function closeVersionsModal() {
  closeModal(elements.versionsModal);
}

function hasDownloadAccess() {
  return Boolean(state.downloadLicenseUser && state.downloadLicenseKey);
}

function openDownloadAccessPanel(jar) {
  if (!elements.downloadAccessPanel) {
    return;
  }

  state.pendingDownloadJarId = jar?.id ?? null;
  elements.downloadAccessTitle.textContent = `Buyer access for ${jar?.displayName || jar?.downloadName || "this jar"}`;
  elements.downloadAccessUser.value = state.downloadLicenseUser || "";
  elements.downloadAccessKey.value = state.downloadLicenseKey || "";
  elements.downloadAccessPanel.classList.remove("hidden");
  window.setTimeout(() => {
    if (!elements.downloadAccessUser.value.trim()) {
      elements.downloadAccessUser.focus();
      return;
    }
    elements.downloadAccessKey.focus();
  }, 0);
}

function closeDownloadAccessPanel(clearPending = true) {
  if (!elements.downloadAccessPanel) {
    return;
  }

  elements.downloadAccessPanel.classList.add("hidden");
  if (clearPending) {
    state.pendingDownloadJarId = null;
  }
}

function getDownloadCooldownRemainingMs() {
  return Math.max(0, state.downloadCooldownUntil - Date.now());
}

function updateDownloadCooldownUi() {
  if (!elements.downloadsCooldown) {
    return;
  }

  const remaining = getDownloadCooldownRemainingMs();
  if (remaining > 0) {
    elements.downloadsCooldown.classList.remove("hidden");
    elements.downloadsCooldown.textContent = `You can download another jar in ${Math.ceil(remaining / 1000)} seconds.`;
  } else {
    elements.downloadsCooldown.classList.add("hidden");
    elements.downloadsCooldown.textContent = "";
  }

  document.querySelectorAll("[data-download-jar-id]").forEach((button) => {
    const isCoolingDown = remaining > 0;
    button.disabled = isCoolingDown;
    if (!button.dataset.defaultLabel) {
      button.dataset.defaultLabel = button.textContent.trim();
    }
    button.textContent = isCoolingDown
      ? `Wait ${Math.ceil(remaining / 1000)}s`
      : button.dataset.defaultLabel;
  });

  if (remaining <= 0 && state.downloadCooldownTimerId != null) {
    window.clearInterval(state.downloadCooldownTimerId);
    state.downloadCooldownTimerId = null;
  }
}

function setDownloadCooldown(remainingMs = 0) {
  state.downloadCooldownUntil = remainingMs > 0 ? (Date.now() + remainingMs) : 0;

  if (state.downloadCooldownTimerId != null) {
    window.clearInterval(state.downloadCooldownTimerId);
    state.downloadCooldownTimerId = null;
  }

  updateDownloadCooldownUi();

  if (remainingMs > 0) {
    state.downloadCooldownTimerId = window.setInterval(updateDownloadCooldownUi, 500);
  }
}

function getDownloadAccessBadge(jar) {
  if (jar?.requiresBuyerLicense) {
    return '<span class="download-badge buyers">Buyers only</span>';
  }
  return '<span class="download-badge public">Everybody</span>';
}

function renderDownloads(jars) {
  state.downloads = Array.isArray(jars) ? jars : [];

  if (!elements.downloadsList) {
    return;
  }

  elements.downloadsList.innerHTML = "";
  if (state.downloads.length === 0) {
    const emptyTitle = state.downloadLicenseKey
      ? "No builds are currently published for this license."
      : "No builds are currently published right now.";
    elements.downloadsList.innerHTML = `<div class="download-empty"><strong>${escapeHtml(emptyTitle)}</strong><p>If you think this is wrong, contact support.</p></div>`;
    updateDownloadCooldownUi();
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const jar of state.downloads) {
    const card = document.createElement("article");
    card.className = "download-card";
    card.dataset.cardInfo = "Build info and direct download.";
    card.innerHTML = buildDownloadCardHtml(jar, "data-download-jar-id");
    fragment.appendChild(card);
  }

  elements.downloadsList.appendChild(fragment);
  hydrateCardInfoDots(elements.downloadsList);
  updateDownloadCooldownUi();
}

async function loadDownloads() {
  const response = await fetch("/api/downloads");
  let payload = {};
  try {
    payload = await response.json();
  } catch (_error) {
    payload = {};
  }

  if (!response.ok || payload.ok === false) {
    throw new Error(payload.message || `Request failed with status ${response.status}`);
  }

  renderDownloads(payload.jars || []);
  setDownloadCooldown(payload.cooldownRemainingMs || 0);
}

async function openDownloadsModal() {
  if (!elements.downloadsModal) {
    return;
  }

  clearDownloadStatus();
  openModal(elements.downloadsModal);
  renderDownloads(state.downloads);

  try {
    await loadDownloads();
  } catch (error) {
    showDownloadStatus(error.message, "error");
  }
}

function closeDownloadsModal() {
  if (!elements.downloadsModal) {
    return;
  }

  closeDownloadAccessPanel();
  closeModal(elements.downloadsModal);
}

function getDownloadNameFromResponse(response, fallbackName = "nova-build.jar") {
  const encoded = response.headers.get("x-download-name");
  if (encoded) {
    try {
      return decodeURIComponent(encoded);
    } catch (_error) {
      return encoded;
    }
  }
  return fallbackName;
}

async function downloadJar(jarId, button, collection = state.downloads) {
  const jar = (Array.isArray(collection) ? collection : []).find((entry) => entry.id === jarId);
  if (!jar) {
    return;
  }

  clearDownloadStatus();
  setBusy(button, true, "Preparing...");

  try {
    const accessPayload = jar.requiresBuyerLicense
      ? {
          licenseKey: state.downloadLicenseKey,
          username: state.downloadLicenseUser
        }
      : {};

    const response = await fetch(`/api/downloads/${jarId}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify(accessPayload)
    });
    if (!response.ok) {
      let payload = {};
      try {
        payload = await response.json();
      } catch (_error) {
        payload = {};
      }

      const remainingMs = payload?.details?.cooldownRemainingMs || 0;
      if (remainingMs > 0) {
        setDownloadCooldown(remainingMs);
      }

      if (response.status === 403 && jar.requiresBuyerLicense) {
        state.downloadLicenseKey = "";
        openDownloadAccessPanel(jar);
      }
      throw new Error(payload.message || `Download failed with status ${response.status}.`);
    }

    const blob = await response.blob();
    const objectUrl = window.URL.createObjectURL(blob);
    const download = document.createElement("a");
    download.href = objectUrl;
    download.download = getDownloadNameFromResponse(response, jar.downloadName || jar.originalName || "nova-build.jar");
    document.body.appendChild(download);
    download.click();
    download.remove();
    window.URL.revokeObjectURL(objectUrl);

    const cooldownMs = Number.parseInt(response.headers.get("x-download-cooldown-ms") || "30000", 10);
    setDownloadCooldown(Number.isFinite(cooldownMs) ? cooldownMs : 30_000);
    if (jar.requiresBuyerLicense) {
      closeDownloadAccessPanel();
    }
    showDownloadStatus(`${jar.displayName || jar.downloadName || "Jar"} download started.`, "success");
  } catch (error) {
    showDownloadStatus(error.message, "error");
  } finally {
    setBusy(button, false, "Preparing...");
    updateDownloadCooldownUi();
  }
}

function handleDownloadsClick(event) {
  const button = event.target.closest("button[data-download-jar-id]");
  if (!button) {
    return;
  }

  const jarId = Number.parseInt(button.dataset.downloadJarId || "", 10);
  if (!Number.isFinite(jarId)) {
    return;
  }

  const jar = state.downloads.find((entry) => entry.id === jarId);
  if (!jar) {
    return;
  }

  if (jar.requiresBuyerLicense && !hasDownloadAccess()) {
    clearDownloadStatus();
    openDownloadAccessPanel(jar);
    return;
  }

  downloadJar(jarId, button, state.downloads);
}

async function submitDownloadAccess(event) {
  event.preventDefault();
  clearDownloadStatus();

  const username = elements.downloadAccessUser.value.trim();
  const licenseKey = elements.downloadAccessKey.value.trim().toUpperCase();
  if (!username) {
    showDownloadStatus("Enter the license user first.", "error");
    return;
  }
  if (!licenseKey) {
    showDownloadStatus("Enter a license key first.", "error");
    return;
  }

  const jar = state.downloads.find((entry) => entry.id === state.pendingDownloadJarId);
  if (!jar) {
    showDownloadStatus("Pick a buyer-only jar first.", "error");
    closeDownloadAccessPanel();
    return;
  }

  state.downloadLicenseUser = username;
  state.downloadLicenseKey = licenseKey;

  const button = elements.downloadsList?.querySelector(`[data-download-jar-id="${jar.id}"]`);
  await downloadJar(jar.id, button, state.downloads);
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll("\"", "&quot;")
    .replaceAll("'", "&#39;");
}

function getPlanPrice(planKey, slots) {
  const plan = PRICING[planKey];
  const normalizedSlots = Math.min(100, Math.max(1, Number.parseInt(slots || "1", 10) || 1));
  const addons = Math.max(0, normalizedSlots - 1);
  return plan.base + (addons * plan.addon);
}

function getPlanRank(planKey) {
  if (planKey === "lifetime") {
    return 3;
  }
  if (planKey === "yearly") {
    return 2;
  }
  return 1;
}

function describeSavings(bestTotal, comparisonTotal) {
  const difference = Math.max(0, comparisonTotal - bestTotal);
  if (difference <= 0) {
    return "same total";
  }
  return `${formatMoney(difference)} less`;
}

function getBestPricingPlan(context) {
  const plans = [
    { key: "monthly", total: context.monthlyTotal },
    { key: "yearly", total: context.yearlyTotal },
    { key: "lifetime", total: context.lifetimeTotal }
  ];

  plans.sort((left, right) => {
    if (left.total !== right.total) {
      return left.total - right.total;
    }
    return getPlanRank(right.key) - getPlanRank(left.key);
  });

  return plans[0];
}

function buildBestPlanMessage(bestPlanKey, context) {
  const bestTotal = context[`${bestPlanKey}Total`];

  if (bestPlanKey === "monthly") {
    return `Cheapest for a short ${context.months}-month run at ${formatMoney(bestTotal)}.`;
  }

  if (bestPlanKey === "yearly") {
    if (bestTotal === context.monthlyTotal) {
      return `Same total as monthly here, so yearly wins because it needs fewer renewals.`;
    }
    return `Cheapest total for ${context.months} months at ${formatMoney(bestTotal)}. ${describeSavings(bestTotal, context.monthlyTotal)} than monthly.`;
  }

  const tiedWithYearly = bestTotal === context.yearlyTotal;
  const tiedWithMonthly = bestTotal === context.monthlyTotal;

  if (tiedWithYearly || tiedWithMonthly) {
    const tiedPlans = [];
    if (tiedWithMonthly) {
      tiedPlans.push("monthly");
    }
    if (tiedWithYearly) {
      tiedPlans.push("yearly");
    }
    return `Same total as ${tiedPlans.join(" and ")} here, so lifetime wins because you only pay once and never renew again.`;
  }

  return `Best long-term value at ${formatMoney(bestTotal)}. ${describeSavings(bestTotal, context.yearlyTotal)} than yearly and ${describeSavings(bestTotal, context.monthlyTotal)} than monthly.`;
}

function updatePricingCalculator() {
  if (!elements.calcSlots || !elements.calcMonths) {
    return;
  }

  const slots = Math.min(100, Math.max(1, Number.parseInt(elements.calcSlots.value || "1", 10) || 1));
  const months = Math.min(120, Math.max(1, Number.parseInt(elements.calcMonths.value || "12", 10) || 12));
  const yearlyBlocks = Math.ceil(months / 12);

  const monthlyUnit = getPlanPrice("monthly", slots);
  const yearlyUnit = getPlanPrice("yearly", slots);
  const lifetimeUnit = getPlanPrice("lifetime", slots);

  const context = {
    slots,
    months,
    yearlyBlocks,
    monthlyTotal: monthlyUnit * months,
    yearlyTotal: yearlyUnit * yearlyBlocks,
    lifetimeTotal: lifetimeUnit
  };

  elements.calcMonthlyPrice.textContent = formatMoney(context.monthlyTotal);
  elements.calcMonthlyMeta.textContent = `${months} month${months === 1 ? "" : "s"} at ${formatMoney(monthlyUnit)} each`;
  elements.calcYearlyPrice.textContent = formatMoney(context.yearlyTotal);
  elements.calcYearlyMeta.textContent = `${yearlyBlocks} full year block${yearlyBlocks === 1 ? "" : "s"} at ${formatMoney(yearlyUnit)} each`;
  elements.calcLifetimePrice.textContent = formatMoney(context.lifetimeTotal);
  elements.calcLifetimeMeta.textContent = `One payment forever at ${formatMoney(lifetimeUnit)}`;

  const bestPlanResult = getBestPricingPlan(context);
  const bestPlanKey = bestPlanResult.key;
  const bestPlan = PRICING[bestPlanKey];
  elements.calcBestPlan.textContent = `${bestPlan.label} for ${slots} slot${slots === 1 ? "" : "s"}`;
  elements.calcBestMeta.textContent = buildBestPlanMessage(bestPlanKey, context);

  document.querySelectorAll(".calc-result").forEach((card) => {
    card.classList.toggle("is-best", card.dataset.plan === bestPlanKey);
  });
}

function initCheckItems() {
  const blocks = [...document.querySelectorAll(".category-block")];
  if (blocks.length === 0) {
    return;
  }

  blocks.forEach((block) => {
    const list = block.querySelector(".check-list");
    if (!list) {
      return;
    }

    const items = [...list.querySelectorAll(".check-item")];
    if (items.length === 0) {
      return;
    }

    const panel = document.createElement("div");
    panel.className = "check-detail-panel is-empty";
    panel.innerHTML = `
      <strong class="check-detail-title">Select a check</strong>
      <p class="check-detail-body">Click any check above to see a short explanation.</p>
    `;
    list.insertAdjacentElement("afterend", panel);

    const title = panel.querySelector(".check-detail-title");
    const body = panel.querySelector(".check-detail-body");

    items.forEach((item) => {
      const summary = item.querySelector("summary");
      const description = item.querySelector(".check-description");
      if (!summary || !description) {
        return;
      }

      summary.addEventListener("click", (event) => {
        event.preventDefault();

        if (item.open) {
          item.open = false;
          panel.classList.add("is-empty");
          title.textContent = "Select a check";
          body.textContent = "Click any check above to see a short explanation.";
          return;
        }

        items.forEach((other) => {
          other.open = false;
        });

        item.open = true;
        panel.classList.remove("is-empty");
        title.textContent = summary.textContent.trim();
        body.textContent = description.textContent.trim();
      });
    });
  });
}

function initAnchorScroll() {
  const links = [...document.querySelectorAll('a[href^="#"]')];
  if (links.length === 0) {
    return;
  }

  links.forEach((link) => {
    const targetId = link.getAttribute("href");
    if (!targetId || targetId === "#") {
      return;
    }

    const target = document.querySelector(targetId);
    if (!target) {
      return;
    }

    link.addEventListener("click", (event) => {
      event.preventDefault();
      target.scrollIntoView({
        behavior: "smooth",
        block: "start"
      });
      window.history.replaceState(null, "", targetId);
      closeMobileMenu();
    });
  });
}

elements.mobileMenuButton?.addEventListener("click", toggleMobileMenu);
elements.closeMobileMenuButton?.addEventListener("click", closeMobileMenu);
elements.mobileNavOverlay?.addEventListener("click", closeMobileMenu);
document.addEventListener("click", handleCardInfoHintClick, true);
elements.lookupForm.addEventListener("submit", lookupLicense);
elements.openDeviceManagerButton?.addEventListener("click", openDeviceManagerModal);
elements.openInstanceViewerButton?.addEventListener("click", openInstanceViewerModal);
elements.openWebhookSettingsButton?.addEventListener("click", openWebhookSettingsModal);
elements.openSecurityModalButton?.addEventListener("click", openSecurityModal);
elements.closeDeviceManagerButton?.addEventListener("click", () => closeModal(elements.deviceManagerModal));
elements.closeInstanceViewerButton?.addEventListener("click", () => closeModal(elements.instanceViewerModal));
elements.closeWebhookSettingsButton?.addEventListener("click", () => closeModal(elements.webhookSettingsModal));
elements.closeSecurityModalButton?.addEventListener("click", () => closeModal(elements.securityModal));
elements.closeActionConfirmButton?.addEventListener("click", () => closeActionConfirm(false));
elements.actionConfirmCancelButton?.addEventListener("click", () => closeActionConfirm(false));
elements.actionConfirmSubmitButton?.addEventListener("click", () => closeActionConfirm(true));
elements.webhookForm?.addEventListener("submit", (event) => {
  saveWebhook(event).catch((error) => {
    showStatus(error.message, "error");
  });
});
elements.sendTestWebhookButton?.addEventListener("click", () => {
  sendTestWebhook().catch((error) => {
    showStatus(error.message, "error");
  });
});
elements.clearWebhookButton?.addEventListener("click", () => {
  clearWebhook().catch((error) => {
    showStatus(error.message, "error");
  });
});
elements.clearSecurityListButton?.addEventListener("click", () => {
  clearSecurityList().catch((error) => {
    showStatus(error.message, "error");
  });
});
elements.securityDeviceList?.addEventListener("click", handleSecurityListClick);
elements.securityInstanceList?.addEventListener("click", handleSecurityListClick);
elements.submitResetButton.addEventListener("click", submitReset);
elements.clearSelectionButton.addEventListener("click", clearSelection);
elements.openChecksButton?.addEventListener("click", openChecksModal);
elements.closeChecksButton?.addEventListener("click", closeChecksModal);
elements.openVersionsButton?.addEventListener("click", openVersionsModal);
elements.closeVersionsButton?.addEventListener("click", closeVersionsModal);
elements.openDownloadsButton?.addEventListener("click", () => {
  openDownloadsModal().catch((error) => {
    showDownloadStatus(error.message, "error");
  });
});
elements.closeDownloadsButton?.addEventListener("click", closeDownloadsModal);
elements.downloadsList?.addEventListener("click", handleDownloadsClick);
elements.downloadAccessForm?.addEventListener("submit", (event) => {
  submitDownloadAccess(event).catch((error) => {
    showDownloadStatus(error.message, "error");
  });
});
elements.downloadAccessCancel?.addEventListener("click", () => {
  closeDownloadAccessPanel();
});
elements.downloadsModal?.addEventListener("click", (event) => {
  if (event.target === elements.downloadsModal) {
    closeDownloadsModal();
  }
});
elements.checksModal?.addEventListener("click", (event) => {
  if (event.target === elements.checksModal) {
    closeChecksModal();
  }
});
elements.versionsModal?.addEventListener("click", (event) => {
  if (event.target === elements.versionsModal) {
    closeVersionsModal();
  }
});
[
  elements.deviceManagerModal,
  elements.instanceViewerModal,
  elements.webhookSettingsModal,
  elements.securityModal,
  elements.actionConfirmModal
].forEach((modal) => {
  modal?.addEventListener("click", (event) => {
    if (event.target === modal) {
      if (modal === elements.actionConfirmModal) {
        closeActionConfirm(false);
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

  closeOpenCardHints();
  closeMobileMenu();
  if (elements.downloadsModal && !elements.downloadsModal.classList.contains("hidden")) {
    closeDownloadsModal();
  }
  if (elements.checksModal && !elements.checksModal.classList.contains("hidden")) {
    closeChecksModal();
  }
  if (elements.versionsModal && !elements.versionsModal.classList.contains("hidden")) {
    closeVersionsModal();
  }
  [
    elements.deviceManagerModal,
    elements.instanceViewerModal,
    elements.webhookSettingsModal,
    elements.securityModal
  ].forEach((modal) => {
    if (modal && !modal.classList.contains("hidden")) {
      closeModal(modal);
    }
  });
  if (elements.actionConfirmModal && !elements.actionConfirmModal.classList.contains("hidden")) {
    closeActionConfirm(false);
  }
});
window.addEventListener("resize", () => {
  if (window.innerWidth > 780) {
    closeMobileMenu();
  }
});
if (elements.calcSlots && elements.calcMonths) {
  [elements.calcSlots, elements.calcMonths].forEach((input) => {
    input.addEventListener("input", updatePricingCalculator);
    input.addEventListener("change", updatePricingCalculator);
  });
  updatePricingCalculator();
}
initCheckItems();
initAnchorScroll();
hydrateCardInfoDots(document);
