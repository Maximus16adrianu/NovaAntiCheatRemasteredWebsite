const state = {
  licenseKey: "",
  devices: []
};

const PRICING = {
  monthly: { label: "Monthly", base: 2, addon: 0.5 },
  yearly: { label: "Yearly", base: 15, addon: 2 },
  lifetime: { label: "Lifetime", base: 30, addon: 5 }
};

const elements = {
  lookupForm: document.getElementById("lookupForm"),
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
  calcBestMeta: document.getElementById("calcBestMeta")
};

function showStatus(message, tone = "info") {
  elements.lookupStatus.textContent = message;
  elements.lookupStatus.className = `status-banner ${tone}`;
}

function clearStatus() {
  elements.lookupStatus.className = "status-banner hidden";
  elements.lookupStatus.textContent = "";
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
  return `EUR ${Number(value || 0).toFixed(2)}`;
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

  const licenseKey = elements.licenseKey.value.trim().toUpperCase();
  if (!licenseKey) {
    showStatus("Enter a license key first.", "error");
    return;
  }

  state.licenseKey = licenseKey;
  setBusy(elements.lookupButton, true, "Looking up...");

  try {
    const payload = await postJson("/api/reset/lookup", { licenseKey });
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
  if (selectedIds.length === 0) {
    showStatus("Select at least one device to reset.", "error");
    return;
  }

  setBusy(elements.submitResetButton, true, "Resetting...");

  try {
    const payload = await postJson("/api/reset/submit", {
      licenseKey: state.licenseKey,
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
if (elements.calcSlots && elements.calcMonths) {
  [elements.calcSlots, elements.calcMonths].forEach((input) => {
    input.addEventListener("input", updatePricingCalculator);
    input.addEventListener("change", updatePricingCalculator);
  });
  updatePricingCalculator();
}
initCheckItems();
initAnchorScroll();
