(function (config) {
    // YACD's query overrides can change a selected controller's host but retain its secret.
    // Strip only controller overrides on this bundled origin, before YACD parses them.
    var current = new URL(window.location.href);
    ["hostname", "port", "secret"].forEach(function (key) { current.searchParams.delete(key); });
    if (current.href !== window.location.href) history.replaceState(history.state, "", current.href);
    var key = "yacd.metacubex.one";
    try {
        var state = {};
        try { state = JSON.parse(localStorage.getItem(key) || "{}"); } catch (_) {}
        if (!state || typeof state !== "object" || Array.isArray(state)) state = {};
        var controllers = Array.isArray(state.clashAPIConfigs) ? state.clashAPIConfigs : [];
        function isLocalController(entry) {
            return entry && typeof entry.baseURL === "string" &&
                entry.baseURL.replace(/\/$/, "") === config.endpoint;
        }
        var index = controllers.findIndex(isLocalController);
        if (index < 0) {
            index = controllers.length;
            controllers.push({baseURL: config.endpoint, secret: config.secret, addedAt: 0});
        }
        controllers.filter(isLocalController).forEach(function (entry) {
            entry.baseURL = config.endpoint;
            entry.secret = config.secret;
        });
        state.clashAPIConfigs = controllers;
        var route = current.hash.split("?")[0].replace(/\/+$/, "");
        var legacy = current.hash === "" || current.hash === "#" ||
            route === "#/backend" || route === "#/setup";
        var selected = state.selectedClashAPIConfigIndex;
        if (legacy || !Number.isInteger(selected) || selected < 0 || selected >= controllers.length) {
            state.selectedClashAPIConfigIndex = index;
        }
        localStorage.setItem(key, JSON.stringify(state));
        // The bundled router declares '/' as its overview and '/backend' as controller setup.
        if (legacy) current.hash = "/";
        if (current.href !== window.location.href) history.replaceState(history.state, "", current.href);
    } catch (_) {
        document.addEventListener("DOMContentLoaded", function () {
            var message = document.createElement("p");
            message.setAttribute("role", "alert");
            message.textContent = config.storageError;
            document.body.insertBefore(message, document.body.firstChild);
        }, {once: true});
    }
})(__OWNBOX_YACD_CONFIG__);
