const state = {
  licenseKey: "",
  licenseUser: "",
  devices: [],
  downloads: [],
  downloadLicenseKey: "",
  downloadLicenseUser: "",
  pendingDownloadJarId: null,
  downloadCooldownUntil: 0,
  downloadCooldownTimerId: null
};

const PRICING = {
  monthly: { label: "Monthly", base: 5, addon: 1 },
  yearly: { label: "Yearly", base: 30, addon: 5 },
  lifetime: { label: "Lifetime", base: 75, addon: 10 }
};

const elements = {
  lookupForm: document.getElementById("lookupForm"),
  licenseUserInput: document.getElementById("licenseUserInput"),
  licenseKey: document.getElementById("licenseKey"),
  lookupButton: document.getElementById("lookupButton"),
  lookupStatus: document.getElementById("lookupStatus"),
  lookupResult: document.getElementById("lookupResult"),
  licenseDisplayName: document.getElementById("licenseDisplayName"),
  licenseUser: document.getElementById("licenseUser"),
  resetCooldown: document.getElementById("resetCooldown"),
  deviceCountChip: document.getElementById("deviceCountChip"),
  devicesList: document.getElementById("devicesList"),
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
    card.innerHTML = `
      <label class="device-check">
        <input class="device-selector" type="checkbox" value="${device.id}">
        <div>
          <h5>${escapeHtml(device.deviceName || "Unknown Device")}</h5>
          <div class="device-chip-row">
            <span class="device-chip">${device.online ? "Online" : "Offline"}</span>
            <span class="device-chip">${device.openSessionCount || 0} open session${device.openSessionCount === 1 ? "" : "s"}</span>
          </div>
          <p class="device-meta">
            Last username: ${escapeHtml(device.lastUsername || "unknown")}<br>
            First seen: ${escapeHtml(formatDate(device.firstSeenAt))}<br>
            Last seen: ${escapeHtml(formatDate(device.lastSeenAt))}<br>
            HWID: ${escapeHtml(device.hwidHash)}
          </p>
        </div>
      </label>
    `;
    fragment.appendChild(card);
  }

  elements.devicesList.appendChild(fragment);
  document.querySelectorAll(".device-selector").forEach((input) => {
    input.addEventListener("change", updateActionState);
  });
  updateActionState();
}

function renderLookupResult(payload) {
  const license = payload.license || {};
  elements.lookupResult.classList.remove("hidden");
  elements.licenseDisplayName.textContent = license.displayName || license.key || "Unknown license";
  elements.licenseUser.textContent = license.customerUsername || "Unbound";
  elements.resetCooldown.textContent = payload.cooldownRemainingMs > 0
    ? `${formatDuration(payload.cooldownRemainingMs)} remaining`
    : "Ready now";
  renderDevices(payload.devices || []);
}

function clearResult() {
  elements.lookupResult.classList.add("hidden");
  elements.devicesList.innerHTML = "";
  state.devices = [];
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
    const payload = await postJson("/api/reset/lookup", { licenseKey, username: licenseUser });
    renderLookupResult(payload);
    showStatus("License loaded. Select the devices you want to reset.", "success");
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

  setBusy(elements.submitResetButton, true, "Resetting...");

  try {
    const payload = await postJson("/api/reset/submit", {
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
    elements.downloadsList.innerHTML = `<div class="download-empty">No jars are available right now.</div>`;
    updateDownloadCooldownUi();
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const jar of state.downloads) {
    const buttonLabel = jar.requiresBuyerLicense ? "Buyer download" : "Download";
    const card = document.createElement("article");
    card.className = "download-card";
    card.innerHTML = `
      <div class="download-card-head">
        <div>
          <h4>${escapeHtml(jar.displayName || jar.downloadName || jar.originalName || "Nova jar")}</h4>
          <div class="download-card-meta">
            ${getDownloadAccessBadge(jar)}
            <span class="download-badge subtle">${escapeHtml(jar.originalName || jar.downloadName || "jar")}</span>
            <span class="download-badge subtle">${escapeHtml(formatBytes(jar.fileSize || 0))}</span>
            <span class="download-badge subtle">Updated ${escapeHtml(formatDate(jar.updatedAt))}</span>
          </div>
        </div>
        <button class="button button-primary" type="button" data-download-jar-id="${jar.id}">${buttonLabel}</button>
      </div>
      <p>${escapeHtml(jar.notes || "Official Nova jar download.")}</p>
    `;
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

async function downloadJar(jarId, button) {
  const jar = state.downloads.find((entry) => entry.id === jarId);
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

  downloadJar(jarId, button);
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
  await downloadJar(jar.id, button);
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
    });
  });
}

elements.lookupForm.addEventListener("submit", lookupLicense);
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
document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape") {
    return;
  }

  if (elements.downloadsModal && !elements.downloadsModal.classList.contains("hidden")) {
    closeDownloadsModal();
  }
  if (elements.checksModal && !elements.checksModal.classList.contains("hidden")) {
    closeChecksModal();
  }
  if (elements.versionsModal && !elements.versionsModal.classList.contains("hidden")) {
    closeVersionsModal();
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
