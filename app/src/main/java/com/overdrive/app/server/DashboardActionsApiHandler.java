package com.overdrive.app.server;

import com.overdrive.app.config.UnifiedConfigManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * User-defined dashboard action buttons ("custom actions").
 *
 * <ul>
 *   <li>{@code GET  /api/dashboard/actions} — return the saved actions:
 *       {@code { "success": true, "actions": [ { "label", "icon"?, "path", "payload"?, "confirm"? }, … ] }}</li>
 *   <li>{@code PUT  /api/dashboard/actions} — replace the list from the request body
 *       {@code { "actions": [ … ] }} and return the sanitized result.</li>
 * </ul>
 *
 * <p>Each action renders as a button in the dashboard's Quick-controls card that
 * POSTs to {@code path} (with optional {@code payload}) via the same
 * {@code BYD.dashboard.command} helper the built-in buttons use. So a custom
 * action can drive any existing server endpoint — e.g. a specific vehicle
 * control, or {@code /api/automations/test/<id>} to run a saved automation's
 * actions (the physical-key {@code kind:automation} equivalent, but as a soft
 * button).
 *
 * <p><b>Why server-side.</b> The built-in grid lives in static HTML that is
 * re-extracted from the APK on every start, so hand-edits don't survive
 * restarts/updates. Storing custom actions in {@link UnifiedConfigManager}
 * (under {@code dashboard.actions}) makes them durable and portable across
 * clients.
 *
 * <p><b>Safety.</b> {@code path} is required to be a same-origin relative path
 * ({@code "/api/…"}), never an absolute URL, so a button can only ever hit the
 * local server. The list is capped and each field length-bounded.
 */
public class DashboardActionsApiHandler {

    private static final int MAX_ACTIONS = 24;
    private static final int MAX_LEN = 256;

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (!path.equals("/api/dashboard/actions")) {
            return false;
        }

        if ("GET".equals(method)) {
            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("actions", UnifiedConfigManager.getDashboardActions());
            HttpResponse.sendJson(out, resp.toString());
            return true;
        }

        if ("PUT".equals(method) || "POST".equals(method)) {
            JSONArray incoming = null;
            try {
                JSONObject req = new JSONObject(body == null ? "{}" : body);
                incoming = req.optJSONArray("actions");
            } catch (Exception ignored) {
                // fall through to the null check below
            }
            if (incoming == null) {
                HttpResponse.sendJsonError(out, "missing 'actions' array");
                return true;
            }
            JSONArray sanitized = sanitize(incoming);
            boolean ok = UnifiedConfigManager.setDashboardActions(sanitized);
            JSONObject resp = new JSONObject();
            resp.put("success", ok);
            resp.put("actions", sanitized);
            HttpResponse.sendJson(out, resp.toString());
            return true;
        }

        HttpResponse.sendError(out, 405, "Method Not Allowed");
        return true;
    }

    /**
     * Keep only well-formed entries and enforce the safety rules: a non-empty
     * label and a same-origin relative {@code path}. Unknown fields are dropped;
     * the list is capped at {@link #MAX_ACTIONS}.
     */
    private static JSONArray sanitize(JSONArray in) throws org.json.JSONException {
        JSONArray result = new JSONArray();
        for (int i = 0; i < in.length() && result.length() < MAX_ACTIONS; i++) {
            JSONObject a = in.optJSONObject(i);
            if (a == null) {
                continue;
            }
            String label = a.optString("label", "").trim();
            String p = a.optString("path", "").trim();
            if (label.isEmpty() || label.length() > MAX_LEN) {
                continue;
            }
            // Same-origin relative API path only — never an absolute or protocol-relative URL.
            if (!p.startsWith("/") || p.startsWith("//") || p.length() > MAX_LEN) {
                continue;
            }
            JSONObject clean = new JSONObject();
            clean.put("label", label);
            clean.put("path", p);
            String icon = a.optString("icon", "").trim();
            if (!icon.isEmpty() && icon.length() <= MAX_LEN) {
                clean.put("icon", icon);
            }
            Object payload = a.opt("payload");
            if (payload != null && payload != JSONObject.NULL) {
                clean.put("payload", payload);
            }
            if (a.optBoolean("confirm", false)) {
                clean.put("confirm", true);
            }
            result.put(clean);
        }
        return result;
    }
}
