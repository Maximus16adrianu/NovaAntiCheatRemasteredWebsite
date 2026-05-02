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
  pendingPurchaseUrl: "",
  downloadCooldownUntil: 0,
  downloadCooldownTimerId: null,
  actionConfirm: null
};

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
    cloudIncludedSlots: 5,
    cloudSlotBundleSize: 5,
    cloudBase: { monthly: 5, yearly: 30, lifetime: 0 },
    cloudAddon: { monthly: 5, yearly: 30, lifetime: 0 }
  }
};
const NOVA_CONSOLE_LINES = [
  { tone: "flag", text: "[NAC] StraightTeledu failed Prediction (A) [VL:1] [*]", delay: 1450 },
  { tone: "flag", text: "[NAC] KuroSense failed Auto Clicker (H) [VL:2] [*]", delay: 1600 },
  { tone: "warn", text: "[NAC] KuroSense failed Auto Clicker (H) [VL:5] [*]", delay: 1350 },
  { tone: "flag", text: "[NAC] Vexoria failed Reach (A) [VL:1] [*]", delay: 1750 },
  { tone: "flag", text: "[NAC] Rainsprint failed Speed (C) [VL:3] [*]", delay: 1550 },
  { tone: "warn", text: "[NAC] Rainsprint failed Speed (C) [VL:7] [*]", delay: 1400 },
  { tone: "flag", text: "[NAC] NightByte failed Kill Aura (D) [VL:2] [*]", delay: 1700 },
  { tone: "success", text: "[NAC] setback applied to Rainsprint for Speed (C)", delay: 1850 },
  { tone: "flag", text: "[NAC] BedrockZ failed Timer (A) [VL:1] [*]", delay: 1650 },
  { tone: "flag", text: "[NAC] PacketGhost failed BadPacket (M) [VL:4] [*]", delay: 1500 },
  { tone: "warn", text: "[NAC] NightByte failed Kill Aura (D) [VL:8] [*]", delay: 1450 },
  { tone: "flag", text: "[NAC] ClickPattern failed Auto Clicker (P) [VL:3] [*]", delay: 1600 },
  { tone: "success", text: "[NAC] punishment queued for NightByte via Kill Aura (D)", delay: 1900 },
  { tone: "flag", text: "[NAC] BridgeMode failed Scaffold (F) [VL:2] [*]", delay: 1700 }
];
const TRUSTED_HTML = Symbol("trustedHtml");

const elements = {
  topbar: document.querySelector(".topbar"),
  novaConsoleLog: document.getElementById("novaConsoleLog"),
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
  openBlacklistPickerButton: document.getElementById("openBlacklistPickerButton"),
  securityBlacklistList: document.getElementById("securityBlacklistList"),
  securityDeviceList: document.getElementById("securityDeviceList"),
  securityInstanceList: document.getElementById("securityInstanceList"),
  blacklistPickerModal: document.getElementById("blacklistPickerModal"),
  closeBlacklistPickerButton: document.getElementById("closeBlacklistPickerButton"),
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
  calcLicensePlan: document.getElementById("calcLicensePlan"),
  calcLicenseTerm: document.getElementById("calcLicenseTerm"),
  calcSlots: document.getElementById("calcSlots"),
  calcCloudSlots: document.getElementById("calcCloudSlots"),
  calcEstimatePrice: document.getElementById("calcEstimatePrice"),
  calcEstimateMeta: document.getElementById("calcEstimateMeta"),
  calcEstimateNote: document.getElementById("calcEstimateNote"),
  openChecksButton: document.getElementById("openChecksButton"),
  checksModal: document.getElementById("checksModal"),
  closeChecksButton: document.getElementById("closeChecksButton"),
  openVersionsButton: document.getElementById("openVersionsButton"),
  versionsModal: document.getElementById("versionsModal"),
  closeVersionsButton: document.getElementById("closeVersionsButton"),
  openDownloadsButton: document.getElementById("openDownloadsButton"),
  downloadsModal: document.getElementById("downloadsModal"),
  closeDownloadsButton: document.getElementById("closeDownloadsButton"),
  openTeamButton: document.getElementById("openTeamButton"),
  teamModal: document.getElementById("teamModal"),
  closeTeamButton: document.getElementById("closeTeamButton"),
  downloadsStatus: document.getElementById("downloadsStatus"),
  downloadsCooldown: document.getElementById("downloadsCooldown"),
  downloadsList: document.getElementById("downloadsList"),
  downloadAccessPanel: document.getElementById("downloadAccessPanel"),
  downloadAccessTitle: document.getElementById("downloadAccessTitle"),
  downloadAccessForm: document.getElementById("downloadAccessForm"),
  downloadAccessUser: document.getElementById("downloadAccessUser"),
  downloadAccessKey: document.getElementById("downloadAccessKey"),
  downloadAccessCancel: document.getElementById("downloadAccessCancel"),
  buyCard: document.getElementById("buy-card"),
  purchaseModal: document.getElementById("purchaseModal"),
  closePurchaseModalButton: document.getElementById("closePurchaseModalButton"),
  cancelPurchaseButton: document.getElementById("cancelPurchaseButton"),
  continuePurchaseButton: document.getElementById("continuePurchaseButton"),
  termsAccept: document.getElementById("termsAccept"),
  digitalDeliveryAccept: document.getElementById("digitalDeliveryAccept"),
  purchaseTermsStatus: document.getElementById("purchaseTermsStatus")
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
    hint.innerHTML = html`
      <span class="card-info-symbol">i</span>
      <span class="card-info-tooltip">${tooltipText}</span>
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

function maskHwid(value) {
  const hwid = String(value || "").trim();
  if (!hwid) {
    return "-";
  }
  if (hwid.length <= 9) {
    return hwid;
  }
  return `${hwid.slice(0, 4)}...${hwid.slice(-5)}`;
}

function buildCopyableMetaLine(label, fullValue, copyLabel = "Copy full HWID") {
  const value = String(fullValue || "").trim();
  if (!value) {
    return html`${label}: -`;
  }

  return html`
    <span class="meta-inline-row">
      <span>${label}: <span class="mono-wrap">${maskHwid(value)}</span></span>
      <button
        class="meta-copy-button"
        type="button"
        data-copy-text="${value}"
        data-copy-label="${copyLabel}"
      >${copyLabel}</button>
    </span>
  `;
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
  return html`
    <div class="download-card-head">
      <div>
        <h4>${jar.displayName || jar.downloadName || jar.originalName || "Nova jar"}</h4>
        <div class="download-card-meta">
          ${jar.recommended ? rawHtml('<span class="download-badge recommended">Recommended</span>') : ""}
          ${rawHtml(getChannelBadge(jar))}
          ${rawHtml(getDownloadAccessBadge(jar))}
          ${jar.supportedVersions ? rawHtml(html`<span class="download-badge subtle">${jar.supportedVersions}</span>`) : ""}
          ${jar.createdAt ? rawHtml(html`<span class="download-badge subtle">Published ${formatDate(jar.createdAt)}</span>`) : ""}
          <span class="download-badge subtle">${jar.originalName || jar.downloadName || "jar"}</span>
          <span class="download-badge subtle">${formatBytes(jar.fileSize || 0)}</span>
          <span class="download-badge subtle">Updated ${formatDate(jar.updatedAt)}</span>
        </div>
      </div>
      <button class="button button-primary" type="button" ${buttonAttributeName}="${jar.id}">${buttonLabel}</button>
    </div>
    <p>${jar.notes || "Official Nova jar download."}</p>
    ${jar.changelog ? rawHtml(html`<p>${jar.changelog}</p>`) : ""}
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
    elements.devicesList.innerHTML = html`<div class="empty-devices">No active devices are currently registered for this license.</div>`;
    updateActionState();
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const device of state.devices) {
    const card = document.createElement("article");
    card.className = "device-card";
    card.innerHTML = html`
      <div class="device-check">
        <input id="device-selector-${device.id}" class="device-selector" type="checkbox" value="${device.id}">
        <div class="device-check-body">
          <label class="device-check-label" for="device-selector-${device.id}">
          <h5>${cleanLabel(device.deviceName, "Unknown Device")}</h5>
          <div class="device-chip-row">
            <span class="device-chip">${device.online ? "Online" : "Offline"}</span>
            <span class="device-chip">${device.openSessionCount || 0} open session${device.openSessionCount === 1 ? "" : "s"}</span>
          </div>
          </label>
          <p class="device-meta">
            Last username: ${device.lastUsername || "unknown"}<br>
            First seen: ${formatDate(device.firstSeenAt)}<br>
            Last seen: ${formatDate(device.lastSeenAt)}<br>
            ${rawHtml(buildCopyableMetaLine("HWID", device.hwidHash))}
          </p>
        </div>
      </div>
    `;
    fragment.appendChild(card);
  }

  elements.devicesList.appendChild(fragment);
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
    elements.instancesList.innerHTML = html`<div class="download-empty">No stored instances are registered for this license.</div>`;
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const instance of state.instances) {
    const card = document.createElement("article");
    card.className = "instance-card";
    const status = instance.online ? "Online" : "Offline";
    card.innerHTML = html`
      <h5>${getInstanceCardTitle(instance)}</h5>
      <div class="device-chip-row">
        <span class="device-chip">${status}</span>
        <span class="device-chip">${getInstanceServerBadge(instance)}</span>
      </div>
      <p class="instance-meta">
        Device: ${cleanLabel(instance.deviceName, "Unknown device")}<br>
        First seen: ${formatDate(instance.firstSeenAt)}<br>
        Last seen: ${formatDate(instance.lastSeenAt)}<br>
        Instance: <span class="mono-wrap">${instance.instanceUuid || instance.instanceHash || "-"}</span>
      </p>
    `;
    fragment.appendChild(card);
  }

  elements.instancesList.appendChild(fragment);
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
    label.innerHTML = html`
      <input type="checkbox" data-webhook-event-id="${definition.id}"${checked ? " checked" : ""}>
      <div>
        <strong>${definition.label}</strong>
        <p>${definition.description}</p>
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
      elements.securityBlacklistList.innerHTML = html`
        <div class="download-empty security-empty-state">
          <strong>No blocked devices or instances.</strong>
          <p>Blacklist entries will appear here after you block a PC or server instance.</p>
        </div>
      `;
    } else {
      const fragment = document.createDocumentFragment();

      for (const device of state.blacklistedDevices) {
        const card = document.createElement("article");
        card.className = "security-blacklist-card";
        card.innerHTML = html`
          <div class="device-chip-row">
            <span class="device-chip">Device</span>
            <span class="device-chip">Blocked ${formatDate(device.createdAt)}</span>
          </div>
          <h5>${cleanLabel(device.deviceName, "Blocked device")}</h5>
          <p class="instance-meta">${rawHtml(buildCopyableMetaLine("HWID", device.hwidHash))}</p>
        `;
        fragment.appendChild(card);
      }

      for (const instance of state.blacklistedInstances) {
        const title = getMeaningfulLabel(instance.instanceName)
          || (instance.instanceUuid ? `Instance ${instance.instanceUuid.slice(0, 8)}` : "Blocked instance");
        const serverBadge = getMeaningfulLabel(instance.lastServerName) || "Server unknown";
        const card = document.createElement("article");
        card.className = "security-blacklist-card";
        card.innerHTML = html`
          <div class="device-chip-row">
            <span class="device-chip">Instance</span>
            <span class="device-chip">${serverBadge}</span>
            <span class="device-chip">Blocked ${formatDate(instance.createdAt)}</span>
          </div>
          <h5>${title}</h5>
          <p class="instance-meta">Instance: <span class="mono-wrap">${instance.instanceUuid || instance.instanceHash || "-"}</span></p>
        `;
        fragment.appendChild(card);
      }

      elements.securityBlacklistList.appendChild(fragment);
    }
  }

  if (elements.securityDeviceList) {
    elements.securityDeviceList.innerHTML = "";
    if (state.devices.length === 0) {
      elements.securityDeviceList.innerHTML = html`<div class="download-empty"><strong>No active devices to block.</strong><p>Devices that successfully authenticated on this license will show up here.</p></div>`;
    } else {
      const fragment = document.createDocumentFragment();
      for (const device of state.devices) {
        const blacklisted = isDeviceBlacklisted(device);
        const card = document.createElement("article");
        card.className = "security-entry-card";
        card.innerHTML = html`
          <div class="security-entry-head">
            <div>
              <h5>${cleanLabel(device.deviceName, "Unknown Device")}</h5>
              <p>${device.online ? "Currently online" : "Currently offline"}</p>
            </div>
            <button class="button ${blacklisted ? "button-secondary" : "button-primary"}" type="button" data-blacklist-device-id="${device.id}"${blacklisted ? " disabled" : ""}>
              ${blacklisted ? "Blocked" : "Blacklist"}
            </button>
          </div>
          <p class="instance-meta">${rawHtml(buildCopyableMetaLine("HWID", device.hwidHash))}</p>
        `;
        fragment.appendChild(card);
      }
      elements.securityDeviceList.appendChild(fragment);
    }
  }

  if (elements.securityInstanceList) {
    elements.securityInstanceList.innerHTML = "";
    if (state.instances.length === 0) {
      elements.securityInstanceList.innerHTML = html`<div class="download-empty"><strong>No active instances to block.</strong><p>Stored server instances tied to this license will show up here.</p></div>`;
    } else {
      const fragment = document.createDocumentFragment();
      for (const instance of state.instances) {
        const blacklisted = isInstanceBlacklisted(instance);
        const card = document.createElement("article");
        card.className = "security-entry-card";
        card.innerHTML = html`
          <div class="security-entry-head">
            <div>
              <h5>${getInstanceCardTitle(instance)}</h5>
              <p>${getInstanceServerBadge(instance)}</p>
            </div>
            <button class="button ${blacklisted ? "button-secondary" : "button-primary"}" type="button" data-blacklist-instance-id="${instance.id}"${blacklisted ? " disabled" : ""}>
              ${blacklisted ? "Blocked" : "Blacklist"}
            </button>
          </div>
          <p class="instance-meta">Instance: <span class="mono-wrap">${instance.instanceUuid || instance.instanceHash || "-"}</span></p>
        `;
        fragment.appendChild(card);
      }
      elements.securityInstanceList.appendChild(fragment);
    }
  }
}

function getAuthAttemptStatus(entry) {
  return entry?.action === "license_activated" ? "allowed" : "denied";
}

function getAuthAttemptTitle(entry) {
  switch (entry?.action) {
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
    case "heartbeat_denied_license_missing":
      return "Denied: session missing";
    case "heartbeat_denied_license_disabled":
      return "Denied: disabled license";
    case "heartbeat_denied_license_expired":
      return "Denied: expired license";
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
    case "reset_cooldown_denied":
      return "A reset action was denied because the cooldown was still active.";
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
    elements.authAttemptsList.innerHTML = html`<div class="download-empty"><strong>No recent auth attempts yet.</strong><p>Recent activations and denied auth events will show up here.</p></div>`;
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const entry of state.authAttempts) {
    const status = getAuthAttemptStatus(entry);
    const details = entry?.details || {};
    const card = document.createElement("article");
    card.className = "auth-attempt-card";
    const deviceAlreadyBlocked = details.hwidHash
      ? isDeviceBlacklisted({ hwidHash: details.hwidHash })
      : false;
    const instanceAlreadyBlocked = (details.instanceUuid || details.instanceHash)
      ? isInstanceBlacklisted({
        instanceUuid: details.instanceUuid,
        instanceHash: details.instanceHash
      })
      : false;

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

    const actions = [];
    if (entry.canBlockDevice) {
      actions.push(html`
        <button
          class="button button-secondary"
          type="button"
          data-block-auth-device-id="${entry.id}"
          ${deviceAlreadyBlocked ? "disabled" : ""}
        >${deviceAlreadyBlocked ? "Device blocked" : "Block device"}</button>
      `);
    }
    if (entry.canBlockInstance) {
      actions.push(html`
        <button
          class="button button-secondary"
          type="button"
          data-block-auth-instance-id="${entry.id}"
          ${instanceAlreadyBlocked ? "disabled" : ""}
        >${instanceAlreadyBlocked ? "Instance blocked" : "Block instance"}</button>
      `);
    }

    const chipsHtml = chips.length
      ? html`<div class="device-chip-row">${rawHtml(chips.join(""))}</div>`
      : "";
    const actionsHtml = actions.length
      ? html`<div class="auth-attempt-actions">${rawHtml(actions.join(""))}</div>`
      : "";

    card.innerHTML = html`
      <div class="auth-attempt-head">
        <div>
          <h5>${getAuthAttemptTitle(entry)}</h5>
          <p>${getAuthAttemptSummary(entry)}</p>
        </div>
        <div class="auth-attempt-meta">
          <span class="auth-attempt-chip ${status === "allowed" ? "success" : "danger"}">${status === "allowed" ? "Allowed" : "Denied"}</span>
          <span class="device-chip">${formatDate(entry.createdAt)}</span>
        </div>
      </div>
      ${rawHtml(chipsHtml)}
      ${rawHtml(actionsHtml)}
    `;

    fragment.appendChild(card);
  }

  elements.authAttemptsList.appendChild(fragment);
}

function renderLookupResult(payload) {
  const license = payload.license || {};
  elements.lookupResult.classList.remove("hidden");
  elements.licenseDisplayName.textContent = license.displayName || license.key || "Unknown license";
  elements.licenseUser.textContent = license.customerUsername || "Unbound";
  elements.licenseExpiry.textContent = formatExpiry(license);
  elements.licenseSlots.textContent = `HWIDs ${license.activeDeviceCount || 0}/${license.maxSlots || license.maxHwids || 0} | Instances ${(license.onlineInstanceCount ?? license.activeInstanceCount ?? 0)}/${license.maxSlots || license.maxInstances || 0} | ${license.licensePlanLabel || "Basic"}${license.cloudEnabled ? ` max ${license.cloudPlayerSlots || 0} players` : ""}`;
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
  closeModal(elements.blacklistPickerModal);
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
  closeModal(elements.blacklistPickerModal);
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
  closeModal(elements.blacklistPickerModal);
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
  closeModal(elements.blacklistPickerModal);
  openModal(elements.securityModal);
}

function openBlacklistPickerModal() {
  if (!state.licenseKey) {
    showStatus("Run a license lookup first.", "error");
    return;
  }
  openModal(elements.blacklistPickerModal);
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

function getAuthAttemptById(authAttemptId) {
  return state.authAttempts.find((entry) => entry.id === authAttemptId) || null;
}

function getAuthAttemptDeviceName(entry) {
  const details = entry?.details || {};
  return cleanLabel(entry?.deviceName || details.deviceName, "this device");
}

function getAuthAttemptInstanceName(entry) {
  const details = entry?.details || {};
  const explicitName = getMeaningfulLabel(details.instanceName);
  if (explicitName) {
    return explicitName;
  }

  const serverName = getMeaningfulLabel(details.serverName || details.lastServerName);
  if (serverName) {
    return serverName;
  }

  const instanceUuid = getMeaningfulLabel(details.instanceUuid);
  if (instanceUuid) {
    return `Instance ${instanceUuid.slice(0, 8)}`;
  }

  return "this server instance";
}

async function blockDeviceFromAuthAttempt(authAttemptId) {
  clearStatus();
  if (!state.licenseKey || !state.licenseUser) {
    showStatus("Run a license lookup first.", "error");
    return;
  }

  const attempt = getAuthAttemptById(authAttemptId);
  if (!attempt) {
    showStatus("Auth attempt not found.", "error");
    return;
  }
  if (!attempt.canBlockDevice) {
    showStatus("This auth attempt does not include a device fingerprint.", "error");
    return;
  }

  const deviceName = getAuthAttemptDeviceName(attempt);
  const confirmed = await requestActionConfirm({
    pill: "Security",
    title: "Block device from auth attempt",
    description: "This adds the device from the selected auth attempt to the license security blacklist.",
    infoTitle: "Device blacklist",
    infoText: "The matching HWID is denied on future auth attempts and any current live session on that device is closed.",
    effects: [
      `Block ${deviceName} on this license.`,
      "Close the current live session on that device if one is open.",
      "Keep the block active until you clear the security list."
    ],
    confirmLabel: "Block device",
    tone: "danger"
  });
  if (!confirmed) {
    return;
  }

  const button = document.querySelector(`[data-block-auth-device-id="${authAttemptId}"]`);
  setBusy(button, true, "Blocking...");
  try {
    const payload = await postJson("/api/manage/security/auth-attempt/device-blacklist", {
      licenseKey: state.licenseKey,
      username: state.licenseUser,
      authAttemptId
    });
    renderLookupResult(payload);
    showStatus(payload.message || "The device from this auth attempt was blocked.", "success");
  } catch (error) {
    showStatus(error.message, "error");
  } finally {
    setBusy(button, false, "Blocking...");
  }
}

async function blockInstanceFromAuthAttempt(authAttemptId) {
  clearStatus();
  if (!state.licenseKey || !state.licenseUser) {
    showStatus("Run a license lookup first.", "error");
    return;
  }

  const attempt = getAuthAttemptById(authAttemptId);
  if (!attempt) {
    showStatus("Auth attempt not found.", "error");
    return;
  }
  if (!attempt.canBlockInstance) {
    showStatus("This auth attempt does not include a stable instance identity yet.", "error");
    return;
  }

  const instanceName = getAuthAttemptInstanceName(attempt);
  const confirmed = await requestActionConfirm({
    pill: "Security",
    title: "Block instance from auth attempt",
    description: "This adds the server instance from the selected auth attempt to the license security blacklist.",
    infoTitle: "Instance blacklist",
    infoText: "The matching server identity is denied on future auth attempts and its current live session is closed if it is online.",
    effects: [
      `Block ${instanceName} on this license.`,
      "Close the current live session for that server if one is open.",
      "Keep the block active until you clear the security list."
    ],
    confirmLabel: "Block instance",
    tone: "danger"
  });
  if (!confirmed) {
    return;
  }

  const button = document.querySelector(`[data-block-auth-instance-id="${authAttemptId}"]`);
  setBusy(button, true, "Blocking...");
  try {
    const payload = await postJson("/api/manage/security/auth-attempt/instance-blacklist", {
      licenseKey: state.licenseKey,
      username: state.licenseUser,
      authAttemptId
    });
    renderLookupResult(payload);
    showStatus(payload.message || "The instance from this auth attempt was blocked.", "success");
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

function handleAuthAttemptClick(event) {
  const deviceButton = event.target.closest("[data-block-auth-device-id]");
  if (deviceButton) {
    const authAttemptId = Number.parseInt(deviceButton.dataset.blockAuthDeviceId, 10);
    if (Number.isFinite(authAttemptId)) {
      blockDeviceFromAuthAttempt(authAttemptId).catch((error) => {
        showStatus(error.message, "error");
      });
    }
    return;
  }

  const instanceButton = event.target.closest("[data-block-auth-instance-id]");
  if (instanceButton) {
    const authAttemptId = Number.parseInt(instanceButton.dataset.blockAuthInstanceId, 10);
    if (Number.isFinite(authAttemptId)) {
      blockInstanceFromAuthAttempt(authAttemptId).catch((error) => {
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

async function copyTextToClipboard(text) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }

  const helper = document.createElement("textarea");
  helper.value = text;
  helper.setAttribute("readonly", "");
  helper.style.position = "fixed";
  helper.style.opacity = "0";
  document.body.appendChild(helper);
  helper.focus();
  helper.select();

  try {
    const copied = document.execCommand("copy");
    if (!copied) {
      throw new Error("Copy failed");
    }
  } finally {
    helper.remove();
  }
}

function handleCopyButtonClick(event) {
  const button = event.target.closest("[data-copy-text]");
  if (!button) {
    return;
  }

  event.preventDefault();
  event.stopPropagation();

  if (button.disabled) {
    return;
  }

  const copyText = String(button.dataset.copyText || "").trim();
  if (!copyText) {
    return;
  }

  const defaultLabel = button.dataset.copyLabel || button.textContent.trim() || "Copy";
  button.disabled = true;

  copyTextToClipboard(copyText)
    .then(() => {
      button.textContent = "Copied";
    })
    .catch(() => {
      button.textContent = "Copy failed";
      showStatus("Could not copy the full HWID.", "error");
    })
    .finally(() => {
      window.setTimeout(() => {
        button.textContent = defaultLabel;
        button.disabled = false;
      }, 1200);
    });
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

function openTeamModal() {
  openModal(elements.teamModal);
}

function closeTeamModal() {
  closeModal(elements.teamModal);
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
    elements.downloadsList.innerHTML = html`<div class="download-empty"><strong>${emptyTitle}</strong><p>If you think this is wrong, contact support.</p></div>`;
    updateDownloadCooldownUi();
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const jar of state.downloads) {
    const card = document.createElement("article");
    card.className = "download-card";
    card.innerHTML = buildDownloadCardHtml(jar, "data-download-jar-id");
    fragment.appendChild(card);
  }

  elements.downloadsList.appendChild(fragment);
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
  return escapeHtml(value);
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

function getLicensePlanKey() {
  return elements.calcLicensePlan && LICENSE_PLANS[elements.calcLicensePlan.value]
    ? elements.calcLicensePlan.value
    : "basic";
}

function getBillingTerm() {
  const term = elements.calcLicenseTerm?.value || "monthly";
  return PRICING[term] ? term : "monthly";
}

function getProPlayerCap(planKey) {
  if (planKey !== "pro") {
    return 0;
  }
  const parsed = Number.parseInt(elements.calcCloudSlots?.value || "5", 10);
  if (parsed <= 5) {
    return 5;
  }
  if (parsed <= 10) {
    return 10;
  }
  return 15;
}

function updatePricingFormFields(planKey) {
  if (elements.calcLicenseTerm) {
    const lifetimeOption = [...elements.calcLicenseTerm.options].find((option) => option.value === "lifetime");
    if (lifetimeOption) {
      lifetimeOption.disabled = planKey === "pro";
    }
    if (planKey === "pro" && elements.calcLicenseTerm.value === "lifetime") {
      elements.calcLicenseTerm.value = "monthly";
    }
  }

  if (!elements.calcCloudSlots) {
    return;
  }
  if (planKey !== "pro") {
    elements.calcCloudSlots.value = "5";
    elements.calcCloudSlots.disabled = true;
    return;
  }
  elements.calcCloudSlots.disabled = false;
  if (![5, 10, 15].includes(Number.parseInt(elements.calcCloudSlots.value || "0", 10))) {
    elements.calcCloudSlots.value = "5";
  }
}

function getPlanPrice(termKey, slots, licensePlanKey = "basic", proPlayerCap = 0) {
  const plan = PRICING[termKey];
  const licensePlan = LICENSE_PLANS[licensePlanKey] || LICENSE_PLANS.basic;
  const normalizedSlots = Math.min(100, Math.max(1, Number.parseInt(slots || "1", 10) || 1));
  const addons = Math.max(0, normalizedSlots - 1);
  if (licensePlanKey === "pro" && termKey === "lifetime") {
    return null;
  }
  const cloudExtraSlots = Math.max(0, proPlayerCap - licensePlan.cloudIncludedSlots);
  const cloudAddonBundles = licensePlanKey === "pro" && termKey !== "lifetime"
    ? Math.ceil(cloudExtraSlots / licensePlan.cloudSlotBundleSize)
    : 0;
  const cloudPrice = (licensePlan.cloudBase[termKey] || 0)
    + (cloudAddonBundles * (licensePlan.cloudAddon[termKey] || 0));
  return plan.base + (addons * plan.addon) + cloudPrice;
}

function updatePricingCalculator() {
  if (!elements.calcSlots || !elements.calcLicenseTerm || !elements.calcEstimatePrice) {
    return;
  }

  const licensePlanKey = getLicensePlanKey();
  updatePricingFormFields(licensePlanKey);
  const licensePlan = LICENSE_PLANS[licensePlanKey] || LICENSE_PLANS.basic;
  const termKey = getBillingTerm();
  const term = PRICING[termKey] || PRICING.monthly;
  const slots = Math.min(100, Math.max(1, Number.parseInt(elements.calcSlots.value || "1", 10) || 1));
  const proPlayerCap = getProPlayerCap(licensePlanKey);
  const total = getPlanPrice(termKey, slots, licensePlanKey, proPlayerCap);

  if (total == null) {
    elements.calcEstimatePrice.textContent = "Basic only";
    elements.calcEstimateMeta.textContent = "Lifetime licenses are only available as Basic.";
    if (elements.calcEstimateNote) {
      elements.calcEstimateNote.textContent = "";
    }
    return;
  }

  elements.calcEstimatePrice.textContent = formatMoney(total);
  const packageText = `${slots} package${slots === 1 ? "" : "s"}`;
  const proText = licensePlanKey === "pro" ? `, max ${proPlayerCap} players` : "";
  elements.calcEstimateMeta.textContent = `${licensePlan.label} ${term.label.toLowerCase()} for ${packageText}${proText}.`;
  if (elements.calcEstimateNote) {
    elements.calcEstimateNote.textContent = "";
  }
}

function hasAcceptedPurchaseTerms() {
  return Boolean(elements.termsAccept?.checked && elements.digitalDeliveryAccept?.checked);
}

function setPurchaseTermsStatus(message, type = "error") {
  if (!elements.purchaseTermsStatus) {
    return;
  }

  elements.purchaseTermsStatus.textContent = message || "";
  elements.purchaseTermsStatus.className = `status-banner ${type}`;
  elements.purchaseTermsStatus.classList.toggle("hidden", !message);
}

function focusMissingPurchaseTerm() {
  const target = elements.termsAccept?.checked
    ? elements.digitalDeliveryAccept
    : elements.termsAccept;

  window.setTimeout(() => {
    target?.focus({ preventScroll: true });
  }, 220);
}

function openPurchaseModal(url) {
  state.pendingPurchaseUrl = url || "https://paypal.me/Cerials";
  setPurchaseTermsStatus("");
  openModal(elements.purchaseModal);
  window.setTimeout(() => {
    if (!hasAcceptedPurchaseTerms()) {
      focusMissingPurchaseTerm();
      return;
    }
    elements.continuePurchaseButton?.focus({ preventScroll: true });
  }, 80);
}

function closePurchaseModal() {
  closeModal(elements.purchaseModal);
}

function continuePurchase() {
  if (hasAcceptedPurchaseTerms()) {
    const purchaseUrl = state.pendingPurchaseUrl || "https://paypal.me/Cerials";
    closePurchaseModal();
    window.open(purchaseUrl, "_blank", "noopener,noreferrer");
    return;
  }

  setPurchaseTermsStatus("Please accept the Terms and the digital delivery / withdrawal acknowledgement before opening PayPal.");
  focusMissingPurchaseTerm();
}

function handlePurchaseEntryClick(event) {
  event.preventDefault();
  const entry = event.currentTarget;
  const purchaseUrl = entry.dataset.purchaseUrl || entry.getAttribute("href") || "https://paypal.me/Cerials";
  closeMobileMenu();
  openPurchaseModal(purchaseUrl);
}

function initPurchaseTermsGate() {
  const purchaseLinks = [...document.querySelectorAll("[data-purchase-url], [data-requires-terms='true']")];
  purchaseLinks.forEach((link) => {
    link.addEventListener("click", handlePurchaseEntryClick);
  });

  [elements.termsAccept, elements.digitalDeliveryAccept].filter(Boolean).forEach((input) => {
    input.addEventListener("change", () => {
      if (hasAcceptedPurchaseTerms()) {
        setPurchaseTermsStatus("");
      }
    });
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
    panel.innerHTML = html`
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

function initHeaderScrollState() {
  if (!elements.topbar) {
    return;
  }

  const update = () => {
    elements.topbar.classList.toggle("is-scrolled", window.scrollY > 12);
  };

  update();
  window.addEventListener("scroll", update, { passive: true });
}

function initRevealEffects() {
  const targets = [...document.querySelectorAll(".landing-grid > .panel, .site-footer")];
  if (targets.length === 0) {
    return;
  }

  const prefersReducedMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches;
  targets.forEach((target, index) => {
    target.classList.add("reveal-on-scroll");
    target.style.transitionDelay = prefersReducedMotion ? "0ms" : `${Math.min(index * 35, 160)}ms`;
  });

  if (prefersReducedMotion || !("IntersectionObserver" in window)) {
    targets.forEach((target) => target.classList.add("is-visible"));
    return;
  }

  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) {
        return;
      }
      entry.target.classList.add("is-visible");
      observer.unobserve(entry.target);
    });
  }, {
    threshold: 0.12
  });

  targets.forEach((target) => observer.observe(target));
}

function formatConsoleTime(lineIndex) {
  const baseSeconds = (19 * 60 * 60) + (51 * 60) + 49;
  const totalSeconds = baseSeconds + lineIndex;
  const hours = String(Math.floor(totalSeconds / 3600) % 24).padStart(2, "0");
  const minutes = String(Math.floor(totalSeconds / 60) % 60).padStart(2, "0");
  const seconds = String(totalSeconds % 60).padStart(2, "0");
  return `[${hours}:${minutes}:${seconds} INFO]:`;
}

function appendNovaConsoleLine(container, entry, index) {
  container.querySelector(".nova-console-cursor-line")?.remove();

  const line = document.createElement("div");
  line.className = `nova-console-line is-${entry.tone || "info"}`;

  const time = document.createElement("span");
  time.className = "nova-console-time";
  time.textContent = formatConsoleTime(index);

  const message = document.createElement("span");
  message.className = "nova-console-message";
  message.textContent = entry.text;

  line.append(time, message);
  container.appendChild(line);
  container.scrollTop = container.scrollHeight;
}

function appendNovaConsoleCursor(container, lineIndex) {
  container.querySelector(".nova-console-cursor-line")?.remove();

  const line = document.createElement("div");
  line.className = "nova-console-line nova-console-cursor-line";

  const time = document.createElement("span");
  time.className = "nova-console-time";
  time.textContent = formatConsoleTime(lineIndex);

  const message = document.createElement("span");
  message.className = "nova-console-message";
  message.textContent = "";

  const cursor = document.createElement("span");
  cursor.className = "nova-console-cursor";
  message.appendChild(cursor);
  line.append(time, message);
  container.appendChild(line);
}

function initNovaConsole() {
  const container = elements.novaConsoleLog;
  if (!container) {
    return;
  }

  const prefersReducedMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches;
  let lineIndex = 0;

  const run = () => {
    const entry = NOVA_CONSOLE_LINES[lineIndex % NOVA_CONSOLE_LINES.length];
    appendNovaConsoleLine(container, entry, lineIndex);
    lineIndex += 1;

    while (container.children.length > 70) {
      container.firstElementChild?.remove();
    }

    appendNovaConsoleCursor(container, lineIndex);
    window.setTimeout(run, prefersReducedMotion ? 1600 : entry.delay);
  };

  run();
}

elements.mobileMenuButton?.addEventListener("click", toggleMobileMenu);
elements.closeMobileMenuButton?.addEventListener("click", closeMobileMenu);
elements.mobileNavOverlay?.addEventListener("click", closeMobileMenu);
document.addEventListener("click", handleCardInfoHintClick, true);
document.addEventListener("click", handleCopyButtonClick);
elements.lookupForm.addEventListener("submit", lookupLicense);
elements.openDeviceManagerButton?.addEventListener("click", openDeviceManagerModal);
elements.openInstanceViewerButton?.addEventListener("click", openInstanceViewerModal);
elements.openWebhookSettingsButton?.addEventListener("click", openWebhookSettingsModal);
elements.openSecurityModalButton?.addEventListener("click", openSecurityModal);
elements.closeDeviceManagerButton?.addEventListener("click", () => closeModal(elements.deviceManagerModal));
elements.closeInstanceViewerButton?.addEventListener("click", () => closeModal(elements.instanceViewerModal));
elements.closeWebhookSettingsButton?.addEventListener("click", () => closeModal(elements.webhookSettingsModal));
elements.closeSecurityModalButton?.addEventListener("click", () => closeModal(elements.securityModal));
elements.openBlacklistPickerButton?.addEventListener("click", openBlacklistPickerModal);
elements.closeBlacklistPickerButton?.addEventListener("click", () => closeModal(elements.blacklistPickerModal));
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
elements.authAttemptsList?.addEventListener("click", handleAuthAttemptClick);
elements.securityDeviceList?.addEventListener("click", handleSecurityListClick);
elements.securityInstanceList?.addEventListener("click", handleSecurityListClick);
elements.submitResetButton.addEventListener("click", submitReset);
elements.clearSelectionButton.addEventListener("click", clearSelection);
elements.openChecksButton?.addEventListener("click", openChecksModal);
elements.closeChecksButton?.addEventListener("click", closeChecksModal);
elements.openVersionsButton?.addEventListener("click", openVersionsModal);
elements.closeVersionsButton?.addEventListener("click", closeVersionsModal);
elements.openTeamButton?.addEventListener("click", openTeamModal);
elements.closeTeamButton?.addEventListener("click", closeTeamModal);
elements.closePurchaseModalButton?.addEventListener("click", closePurchaseModal);
elements.cancelPurchaseButton?.addEventListener("click", closePurchaseModal);
elements.continuePurchaseButton?.addEventListener("click", continuePurchase);
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
elements.teamModal?.addEventListener("click", (event) => {
  if (event.target === elements.teamModal) {
    closeTeamModal();
  }
});
[
  elements.purchaseModal,
  elements.deviceManagerModal,
  elements.instanceViewerModal,
  elements.webhookSettingsModal,
  elements.securityModal,
  elements.blacklistPickerModal,
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
  if (elements.actionConfirmModal && !elements.actionConfirmModal.classList.contains("hidden")) {
    closeActionConfirm(false);
    return;
  }

  if (elements.purchaseModal && !elements.purchaseModal.classList.contains("hidden")) {
    closePurchaseModal();
    return;
  }

  if (elements.blacklistPickerModal && !elements.blacklistPickerModal.classList.contains("hidden")) {
    closeModal(elements.blacklistPickerModal);
    return;
  }

  if (elements.securityModal && !elements.securityModal.classList.contains("hidden")) {
    closeModal(elements.securityModal);
    return;
  }

  if (elements.webhookSettingsModal && !elements.webhookSettingsModal.classList.contains("hidden")) {
    closeModal(elements.webhookSettingsModal);
    return;
  }

  if (elements.instanceViewerModal && !elements.instanceViewerModal.classList.contains("hidden")) {
    closeModal(elements.instanceViewerModal);
    return;
  }

  if (elements.deviceManagerModal && !elements.deviceManagerModal.classList.contains("hidden")) {
    closeModal(elements.deviceManagerModal);
    return;
  }

  if (elements.downloadsModal && !elements.downloadsModal.classList.contains("hidden")) {
    closeDownloadsModal();
    return;
  }

  if (elements.versionsModal && !elements.versionsModal.classList.contains("hidden")) {
    closeVersionsModal();
    return;
  }

  if (elements.teamModal && !elements.teamModal.classList.contains("hidden")) {
    closeTeamModal();
    return;
  }

  if (elements.checksModal && !elements.checksModal.classList.contains("hidden")) {
    closeChecksModal();
  }
});
window.addEventListener("resize", () => {
  if (window.innerWidth > 780) {
    closeMobileMenu();
  }
});
if (elements.calcSlots && elements.calcLicenseTerm) {
  [elements.calcLicensePlan, elements.calcLicenseTerm, elements.calcSlots, elements.calcCloudSlots].filter(Boolean).forEach((input) => {
    input.addEventListener("input", updatePricingCalculator);
    input.addEventListener("change", updatePricingCalculator);
  });
  updatePricingCalculator();
}
initCheckItems();
initPurchaseTermsGate();
initAnchorScroll();
initHeaderScrollState();
initRevealEffects();
initNovaConsole();
hydrateCardInfoDots(document);
