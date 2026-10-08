// Bank links dashboard. Plain JS, no build step. Loaded as an external script so the CSP
// can stay script-src 'self' https://cdn.plaid.com with no inline code.
(function () {
  "use strict";

  const base = document.body.dataset.base || "/plaid/";
  const STORAGE_KEY = "plaidLinkSession";

  function csrfHeaders() {
    const header = document.querySelector('meta[name="csrf-header"]');
    const token = document.querySelector('meta[name="csrf-token"]');
    return header && token ? { [header.content]: token.content } : {};
  }

  function showMessage(text) {
    const el = document.getElementById("message");
    if (!el) {
      window.alert(text);
      return;
    }
    el.textContent = text;
    el.hidden = false;
  }

  async function api(path, body) {
    const response = await fetch(base + path, {
      method: "POST",
      credentials: "same-origin",
      headers: Object.assign({ "Content-Type": "application/json" }, csrfHeaders()),
      body: JSON.stringify(body || {}),
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(data.error || "Request failed (" + response.status + ")");
    }
    return data;
  }

  // Plaid Link. The link token and flow are kept in sessionStorage so that, for a bank that
  // uses OAuth, /oauth-return can re-open Link with the same token to finish the flow.
  function openLink(token, receivedRedirectUri) {
    const handler = window.Plaid.create({
      token: token,
      receivedRedirectUri: receivedRedirectUri || undefined,
      onSuccess: async function (publicToken) {
        const state = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || "{}");
        sessionStorage.removeItem(STORAGE_KEY);
        try {
          if (state.mode === "repair") {
            await api("api/items/" + state.itemId + "/repaired");
            window.location.assign(base);
          } else {
            const result = await api("api/exchange", {
              publicToken: publicToken,
              mode: state.mode,
              replacesItemId: state.itemId || null,
            });
            window.location.assign(base + "items/" + result.itemId + "/mapping");
          }
        } catch (e) {
          showMessage(e.message);
        }
      },
      onExit: function (error) {
        sessionStorage.removeItem(STORAGE_KEY);
        if (error) {
          showMessage("Plaid Link closed: " + (error.error_code || "error"));
        }
      },
    });
    handler.open();
  }

  async function startLink(mode, itemId) {
    const result = await api("api/link-token", { mode: mode, itemId: itemId || null });
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ linkToken: result.linkToken, mode: mode, itemId: itemId || null }));
    openLink(result.linkToken, null);
  }

  document.addEventListener("click", async function (event) {
    const button = event.target.closest("button[data-action]");
    if (!button) return;
    const itemId = button.dataset.itemId ? Number(button.dataset.itemId) : null;
    try {
      if (button.dataset.action === "link") {
        button.disabled = true;
        await startLink(button.dataset.mode, itemId);
      } else if (button.dataset.action === "review-merge") {
        button.disabled = true;
        await api("api/reviews/" + button.dataset.reviewId + "/merge", { fireflyId: button.dataset.fireflyId });
        window.location.reload();
      } else if (button.dataset.action === "review-import") {
        button.disabled = true;
        await api("api/reviews/" + button.dataset.reviewId + "/import");
        window.location.reload();
      } else if (button.dataset.action === "review-delete") {
        if (!window.confirm("Delete this transaction in Firefly? This cannot be undone.")) return;
        button.disabled = true;
        await api("api/reviews/" + button.dataset.reviewId + "/delete");
        window.location.reload();
      } else if (button.dataset.action === "review-dismiss") {
        button.disabled = true;
        await api("api/reviews/" + button.dataset.reviewId + "/dismiss");
        window.location.reload();
      } else if (button.dataset.action === "retire") {
        if (!window.confirm("Retire this Item? It is removed at Plaid and can no longer sync. This cannot be undone.")) return;
        await api("api/items/" + itemId + "/retire");
        window.location.reload();
      }
    } catch (e) {
      showMessage(e.message);
    } finally {
      button.disabled = false;
    }
  });

  document.addEventListener("submit", async function (event) {
    const form = event.target;
    if (form.classList.contains("backfill")) {
      event.preventDefault();
      const accountIds = Array.from(form.querySelectorAll('input[name="accountId"]:checked')).map((el) => Number(el.value));
      try {
        await api("api/backfills", {
          itemId: Number(form.dataset.itemId),
          accountIds: accountIds,
          days: Number(form.elements.days.value),
          timeoutSeconds: Number(form.elements.timeoutHours.value) * 3600,
          dryRun: form.elements.dryRun.checked,
        });
        window.location.reload();
      } catch (e) {
        showMessage(e.message);
      }
    } else if (form.id === "mapping") {
      event.preventDefault();
      const accounts = Array.from(form.querySelectorAll("tr[data-account-id]")).map(function (row) {
        const selected = row.querySelector('select[name="fireflyAccountId"]').value;
        return {
          accountId: Number(row.dataset.accountId),
          fireflyAccountId: selected ? Number(selected) : null,
          enabled: row.querySelector('input[name="enabled"]').checked,
        };
      });
      try {
        await api("api/items/" + form.dataset.itemId + "/mapping", { accounts: accounts });
        window.location.assign(base);
      } catch (e) {
        showMessage(e.message);
      }
    }
  });

  // Timestamps are rendered in the server's zone (UTC); show them in the browser's instead.
  document.querySelectorAll("time[datetime]").forEach(function (el) {
    const date = new Date(el.dateTime);
    if (isNaN(date)) return;
    const pad = (n) => String(n).padStart(2, "0");
    el.textContent = date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate()) +
      " " + pad(date.getHours()) + ":" + pad(date.getMinutes());
  });

  if (document.body.dataset.page === "oauth-return") {
    const state = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || "{}");
    if (state.linkToken) {
      openLink(state.linkToken, window.location.href);
    } else {
      showMessage("This bank link session has expired. Start it again from the dashboard.");
    }
  }
})();
