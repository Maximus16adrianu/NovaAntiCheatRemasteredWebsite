const ADMIN_KEY_STORAGE = "novaac_admin_api_key";
const PRICING = {
  monthly: { label: "Monthly", base: 5, addon: 1 },
  yearly: { label: "Yearly", base: 30, addon: 5 },
  lifetime: { label: "Lifetime", base: 75, addon: 10 }
};

const state = {
  apiKey: localStorage.getItem(ADMIN_KEY_STORAGE) || "",
  licenses: [],
  jars: [],
  selectedLicenseId: null,
  selectedDevices: new Set(),
  selectedInstances: new Set(),
  draggingJarId: null,
  formLicense: null
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
  jarAccessField: document.getElementById("jarAccessField"),
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

function showStatus(element, message, tone = "info") {
  element.textContent = message;
  element.className = `status-banner ${tone}`;
}

function clearStatus(element) {
  element.textContent = "";
  element.className = "status-banner hidden";
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

function badge(label, tone = "") {
  const className = tone ? `badge ${tone}` : "badge";
  return `<span class="${className}">${esc(label)}</span>`;
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

function computeFormPricing() {
  const licenseType = elements.licenseTypeField.value || "monthly";
  const pricing = PRICING[licenseType] || PRICING.monthly;
  const slotCap = getFormSlotCap();
  const addonBundles = Math.max(0, slotCap - 1);
  const total = pricing.base + (addonBundles * pricing.addon);

  elements.pricePreviewTotal.textContent = formatMoney(total);
  elements.pricePreviewMeta.textContent = `${pricing.label} base ${formatMoney(pricing.base)} + ${addonBundles} add-on pack${addonBundles === 1 ? "" : "s"} at ${formatMoney(pricing.addon)}`;
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
  elements.summaryType.textContent = license.licenseTypeLabel || "Unknown";
  elements.summaryLimits.textContent = `HWIDs ${license.activeDeviceCount}/${license.maxSlots} | Instances ${license.onlineInstanceCount}/${license.maxSlots}`;
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
  elements.maxSlotsField.value = "1";
  elements.resetIntervalField.value = "30";
  elements.notesField.value = "";
  elements.activeField.checked = true;
  elements.licenseFormHeading.textContent = "Create license";
  elements.saveLicenseButton.textContent = "Save license";
  elements.deleteLicenseButton.disabled = true;
  renderSummary(null);
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
  elements.maxSlotsField.value = String(license.maxSlots || 1);
  elements.resetIntervalField.value = String(license.resetIntervalDays || 30);
  elements.notesField.value = license.notes || "";
  elements.activeField.checked = Boolean(license.active);
  elements.licenseFormHeading.textContent = `Edit ${license.key}`;
  elements.saveLicenseButton.textContent = "Update license";
  elements.deleteLicenseButton.disabled = false;
  renderSummary(license);
  computeFormPricing();
  updateExpiryPreview();
}

function filteredLicenses() {
  const searchTerm = elements.licenseSearch.value.trim().toLowerCase();
  if (!searchTerm) {
    return state.licenses;
  }

  return state.licenses.filter((license) => (
    [license.key, license.customerUsername, license.displayName, license.licenseTypeLabel]
      .filter(Boolean)
      .some((value) => value.toLowerCase().includes(searchTerm))
  ));
}

function renderLicenses() {
  const licenses = filteredLicenses();
  elements.licensesTableBody.innerHTML = "";

  if (licenses.length === 0) {
    elements.licensesTableBody.innerHTML = `<tr class="empty-row"><td colspan="7">No licenses found.</td></tr>`;
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const license of licenses) {
    const row = document.createElement("tr");
    const extendAction = canExtendLicense(license)
      ? `<button class="mini-button" data-action="extend" data-license-id="${license.id}">${esc(getExtendLabel(license))}</button>`
      : "";

    row.innerHTML = `
      <td>
        <strong>${esc(license.key)}</strong>
        <small>${esc(license.displayName || "")}</small>
      </td>
      <td>${esc(license.customerUsername || "Unbound")}</td>
      <td>
        ${esc(getLicenseTypeText(license))}
        <small>${esc(license.licenseType === "lifetime" ? "No expiry" : formatExpiryDate(license.expiresAt))}</small>
      </td>
      <td>
        HWIDs ${license.activeDeviceCount}/${license.maxSlots}
        <small>Instances ${license.onlineInstanceCount}/${license.maxSlots}</small>
      </td>
      <td>
        ${buildStatusBadge(license)}
        <small>${license.openSessionCount ? `${license.openSessionCount} open session${license.openSessionCount === 1 ? "" : "s"}` : "No open sessions"}</small>
      </td>
      <td>${esc(formatMoney(license.pricing?.recommendedPriceEur || 0))}</td>
      <td>
        <div class="inline-actions">
          <button class="mini-button primary" data-action="open" data-license-id="${license.id}">Open</button>
          ${extendAction}
          <button class="mini-button danger" data-action="delete" data-license-id="${license.id}">Delete</button>
        </div>
      </td>
    `;
    fragment.appendChild(row);
  }

  elements.licensesTableBody.appendChild(fragment);
}

function setEmptyDeviceRows() {
  elements.devicesTableBody.innerHTML = `<tr class="empty-row"><td colspan="8">No devices for the selected license.</td></tr>`;
}

function setEmptyInstanceRows() {
  elements.instancesTableBody.innerHTML = `<tr class="empty-row"><td colspan="8">No instances for the selected license.</td></tr>`;
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
    const row = document.createElement("tr");
    row.innerHTML = `
      <td class="pick-cell"><input class="device-selector" type="checkbox" value="${device.id}"></td>
      <td><strong>${esc(device.deviceName || "Unknown device")}</strong></td>
      <td>${device.online ? badge("Online", "success") : badge(device.active ? "Active" : "Reset", device.active ? "" : "warning")}</td>
      <td>${esc(String(device.openSessionCount || 0))}</td>
      <td>${esc(device.lastUsername || "unknown")}</td>
      <td>${esc(formatDate(device.firstSeenAt))}</td>
      <td>${esc(formatDate(device.lastSeenAt))}</td>
      <td><small>${esc(device.hwidHash)}</small></td>
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
    row.innerHTML = `
      <td class="pick-cell"><input class="instance-selector" type="checkbox" value="${instance.id}"></td>
      <td>
        <strong>${esc(instance.instanceName || "Unknown instance")}</strong>
        <small>${esc(instance.instanceUuid || instance.instanceHash || "")}</small>
      </td>
      <td>${instance.online ? badge("Online", "success") : badge(instance.active ? "Stored" : "Reset", instance.active ? "" : "warning")}</td>
      <td>${esc(String(instance.openSessionCount || 0))}</td>
      <td>${esc(instance.deviceName || "Unknown device")}</td>
      <td>${esc(instance.lastServerName || "Unknown server")}</td>
      <td>${esc(formatDate(instance.firstSeenAt))}</td>
      <td>${esc(formatDate(instance.lastSeenAt))}</td>
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
    elements.sessionsTableBody.innerHTML = `<tr class="empty-row"><td colspan="7">No sessions recorded yet.</td></tr>`;
    return;
  }

  const fragment = document.createDocumentFragment();
  for (const session of sessions) {
    const row = document.createElement("tr");
    row.innerHTML = `
      <td>
        <strong>${esc(session.licenseKey)}</strong>
        <small>${esc(session.customerUsername || "")}</small>
      </td>
      <td>
        ${esc(session.instanceName || "Unknown instance")}
        <small>${esc(session.instanceHash || "")}</small>
      </td>
      <td>${esc(session.deviceName || "Unknown device")}</td>
      <td>
        ${esc(session.serverName || "Unknown server")}
        <small>${esc(session.pluginVersion || "unknown")}</small>
      </td>
      <td>${session.online ? badge("Online", "success") : badge(session.staleClosed ? "Timed out" : "Closed", session.staleClosed ? "warning" : "")}</td>
      <td>${esc(formatDate(session.lastHeartbeatAt))}</td>
      <td>${esc(session.closeReason || "-")}</td>
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
  elements.jarCountChip.textContent = `${jars.length} jar${jars.length === 1 ? "" : "s"}`;
  elements.jarList.innerHTML = "";

  if (jars.length === 0) {
    elements.jarList.innerHTML = `<div class="empty-jar-list">No jars uploaded yet.</div>`;
    return;
  }

  const fragment = document.createDocumentFragment();
  jars.forEach((jar, index) => {
    const item = document.createElement("article");
    item.className = "jar-item";
    item.draggable = true;
    item.dataset.jarId = String(jar.id);
    item.innerHTML = `
      <div class="jar-item-head">
        <div>
          <strong>${esc(jar.originalName || jar.downloadName || jar.displayName || "Jar")}</strong>
          <small>${esc(formatBytes(jar.fileSize || 0))} | Updated ${esc(formatDate(jar.updatedAt))}</small>
        </div>
        <div class="jar-item-badges">
          <span class="jar-access-badge ${jar.accessScope === "public" ? "public" : "buyers"}">${esc(jar.accessLabel || "Buyers only")}</span>
          <span class="drag-badge">Drag to reorder</span>
          <span class="order-badge">#${index + 1}</span>
        </div>
      </div>
      <div class="jar-item-fields">
        <label>
          <span>Public name</span>
          <input class="jar-name-input" type="text" value="${esc(jar.displayName || "")}" autocomplete="off" spellcheck="false">
        </label>
        <label>
          <span>Public note</span>
          <textarea class="jar-note-input" rows="3" spellcheck="false">${esc(jar.notes || "")}</textarea>
        </label>
        <label>
          <span>Download access</span>
          <select class="jar-access-input">
            <option value="buyers"${jar.accessScope === "buyers" ? " selected" : ""}>Buyers only</option>
            <option value="public"${jar.accessScope === "public" ? " selected" : ""}>Everybody</option>
          </select>
        </label>
      </div>
      <div class="actions-row">
        <button class="mini-button primary" type="button" data-action="save-jar" data-jar-id="${jar.id}">Save</button>
        <button class="mini-button danger" type="button" data-action="delete-jar" data-jar-id="${jar.id}">Delete jar</button>
      </div>
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
  const accessScope = elements.jarAccessField.value || "buyers";
  const query = new URLSearchParams({
    displayName,
    notes,
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
  const accessScope = item.querySelector(".jar-access-input")?.value || "buyers";

  setButtonBusy(sourceButton, true, "Saving...");
  try {
    await adminRequest(`/api/admin/jars/${jarId}`, {
      method: "PATCH",
      body: { displayName, notes, accessScope }
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
  if (!window.confirm(`Delete ${label}? This removes the stored jar file from the server.`)) {
    return;
  }

  setButtonBusy(sourceButton, true, "Deleting...");
  try {
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

  if (button.dataset.action === "save-jar") {
    await saveJar(jarId, button);
    return;
  }

  if (button.dataset.action === "delete-jar") {
    await deleteJarById(jarId, button);
  }
}

function handleJarDragStart(event) {
  const item = event.target.closest(".jar-item[data-jar-id]");
  if (!item) {
    return;
  }

  state.draggingJarId = Number.parseInt(item.dataset.jarId || "", 10);
  if (!Number.isFinite(state.draggingJarId)) {
    state.draggingJarId = null;
    return;
  }

  item.classList.add("dragging");
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

async function loadLicenseDetail(licenseId) {
  state.selectedLicenseId = licenseId;
  elements.refreshDevicesButton.disabled = false;
  elements.refreshInstancesButton.disabled = false;
  updateResetButtons();

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
  elements.devicesHeading.textContent = "Devices";
  elements.devicesSubheading.textContent = "Open a license to inspect registered HWIDs.";
  elements.instancesHeading.textContent = "Instances";
  elements.instancesSubheading.textContent = "Open a license to inspect registered server instances.";
  elements.refreshDevicesButton.disabled = true;
  elements.refreshInstancesButton.disabled = true;
  setEmptyDeviceRows();
  setEmptyInstanceRows();
  updateResetButtons();
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
  await Promise.all([loadOverview(), loadLicenses(), loadSessions(), loadJars()]);
  syncEditedLicenseForm();

  if (state.selectedLicenseId != null) {
    const stillExists = state.licenses.some((entry) => entry.id === state.selectedLicenseId);
    if (stillExists) {
      await loadLicenseDetail(state.selectedLicenseId);
    } else {
      clearInspectorState();
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
  clearJarDragState();
  localStorage.removeItem(ADMIN_KEY_STORAGE);
  elements.apiKey.value = "";
  closeDownloadKeyModal();
  resetLicenseForm();
  resetJarUploadForm();
  clearInspectorState();
  elements.licensesTableBody.innerHTML = "";
  elements.sessionsTableBody.innerHTML = "";
  if (elements.jarList) {
    elements.jarList.innerHTML = `<div class="empty-jar-list">No jars uploaded yet.</div>`;
  }
  if (elements.jarCountChip) {
    elements.jarCountChip.textContent = "0 jars";
  }
  showLoggedOutUi();
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
  setButtonBusy(elements.saveLicenseButton, true, "Saving...");

  const licenseId = elements.licenseId.value.trim();
  const body = {
    key: elements.licenseKeyField.value.trim() || undefined,
    customerUsername: elements.customerUsernameField.value.trim(),
    displayName: elements.displayNameField.value.trim(),
    licenseType: elements.licenseTypeField.value,
    maxSlots: getFormSlotCap(),
    resetIntervalDays: Number.parseInt(elements.resetIntervalField.value, 10),
    notes: elements.notesField.value.trim(),
    active: elements.activeField.checked
  };

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

  if (!window.confirm(`Delete ${label}? This removes the license, devices, instances and sessions.`)) {
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
  showStatus(elements.adminStatus, `Opened ${getLicenseLabel(license)}.`, "info");
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

  if (action === "extend") {
    setButtonBusy(button, true, "Extending...");
    try {
      const payload = await adminRequest(`/api/admin/licenses/${licenseId}/extend`, { method: "POST" });
      await refreshDashboard();
      showStatus(elements.adminStatus, `${license.key} now ends on ${formatExpiryDate(payload.license?.expiresAt)}.`, "success");
    } catch (error) {
      showStatus(elements.adminStatus, error.message, "error");
    } finally {
      setButtonBusy(button, false, "Extending...");
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

elements.loginForm.addEventListener("submit", onLogin);
elements.logoutButton.addEventListener("click", logout);
elements.licenseForm.addEventListener("submit", submitLicenseForm);
elements.deleteLicenseButton.addEventListener("click", deleteCurrentLicense);
elements.resetFormButton.addEventListener("click", resetLicenseForm);
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
elements.downloadKeyForm.addEventListener("submit", submitBackupDownload);
elements.cancelDownloadButton.addEventListener("click", closeDownloadKeyModal);
elements.downloadKeyModal.addEventListener("click", (event) => {
  if (event.target === elements.downloadKeyModal) {
    closeDownloadKeyModal();
  }
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && !elements.downloadKeyModal.classList.contains("hidden")) {
    closeDownloadKeyModal();
  }
});

[elements.licenseTypeField, elements.maxSlotsField].forEach((input) => {
  input.addEventListener("input", () => {
    computeFormPricing();
    updateExpiryPreview();
  });
  input.addEventListener("change", () => {
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
