package com.replayx.sender.util;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import com.replayx.sender.security.C;

/** Helper fino pra falar com a API REST do Firestore (mesmo estilo do Combo Replay). */
public final class Fs {
    private Fs() {}

    private static volatile boolean lastQueryNetworkError = false;
    private static volatile int lastQueryHttpCode = 0;
    private static volatile String lastQueryError = "";
    private static volatile int lastPatchHttpCode = 0;
    private static volatile String lastPatchError = "";

    public static boolean lastQueryNetworkError() {
        return lastQueryNetworkError;
    }

    public static String lastQueryDiagnostic(String operation) {
        return diagnostic(operation, lastQueryHttpCode, lastQueryError);
    }

    public static int lastPatchHttpCode() {
        return lastPatchHttpCode;
    }

    public static String lastPatchError() {
        return lastPatchError;
    }

    public static String lastPatchDiagnostic(String operation) {
        return diagnostic(operation, lastPatchHttpCode, lastPatchError);
    }

    private static String diagnostic(String operation, int code, String raw) {
        String message = "";
        try {
            JSONObject root = new JSONObject(raw == null ? "" : raw);
            JSONObject error = root.optJSONObject("error");
            if (error != null) message = error.optString("message", "");
        } catch (Exception ignored) { }
        if (message.isEmpty()) message = raw == null ? "" : raw;
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        message = message.replaceAll("(?i)(key=)[^&\\s]+", "$1[oculto]");
        if (message.isEmpty()) message = "sem corpo de resposta";
        if (code == 403) {
            message += " — publique as Firestore Rules compatíveis com slots no Firebase Console";
        }
        if (message.length() > 360) message = message.substring(0, 360) + "…";
        return operation + " (HTTP " + code + "): " + message;
    }

    private static String base() {
        return "https://firestore.googleapis.com/v1/projects/" + C.p() + "/databases/(default)/documents";
    }

    public static JSONObject str(String v) throws Exception { return new JSONObject().put("stringValue", v); }
    public static JSONObject num(long v) throws Exception { return new JSONObject().put("integerValue", String.valueOf(v)); }
    public static JSONObject bool(boolean v) throws Exception { return new JSONObject().put("booleanValue", v); }
    public static JSONObject ts(long epochSec) throws Exception {
        return new JSONObject().put("timestampValue", java.time.Instant.ofEpochSecond(epochSec).toString());
    }
    public static JSONObject nul() throws Exception { return new JSONObject().put("nullValue", JSONObject.NULL); }

    public static String getStr(JSONObject fields, String key, String def) {
        try {
            if (!fields.has(key)) return def;
            JSONObject f = fields.getJSONObject(key);
            return f.has("stringValue") ? f.getString("stringValue") : def;
        } catch (Exception e) { return def; }
    }
    public static long getLong(JSONObject fields, String key, long def) {
        try {
            if (!fields.has(key)) return def;
            JSONObject f = fields.getJSONObject(key);
            return f.has("integerValue") ? Long.parseLong(f.getString("integerValue")) : def;
        } catch (Exception e) { return def; }
    }
    public static boolean getBool(JSONObject fields, String key, boolean def) {
        try {
            if (!fields.has(key)) return def;
            JSONObject f = fields.getJSONObject(key);
            return f.has("booleanValue") ? f.getBoolean("booleanValue") : def;
        } catch (Exception e) { return def; }
    }
    public static Long getTsSec(JSONObject fields, String key) {
        try {
            if (!fields.has(key)) return null;
            JSONObject f = fields.getJSONObject(key);
            if (!f.has("timestampValue")) return null;
            return java.time.Instant.parse(f.getString("timestampValue")).getEpochSecond();
        } catch (Exception e) { return null; }
    }

    /** GET de um documento único. Devolve null se não existir/erro. */
    public static JSONObject getDoc(String path) {
        try {
            URL url = new URL(base() + "/" + path + "?key=" + C.k());
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            int code = c.getResponseCode();
            String body = readAll(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            c.disconnect();
            if (code != 200) return null;
            JSONObject doc = new JSONObject(body);
            return doc.has("fields") ? doc.getJSONObject("fields") : new JSONObject();
        } catch (Exception e) { return null; }
    }

    /** PATCH (cria ou sobrescreve campos) de um documento único. */
    public static boolean patchDoc(String path, JSONObject fields) {
        return patchDoc(path, fields, null);
    }
    public static boolean patchDoc(String path, JSONObject fields, String updateMaskQuery) {
        return patchDoc(path, fields, updateMaskQuery, null);
    }

    public static boolean patchDoc(String path, JSONObject fields, String updateMaskQuery, String updateTime) {
        // License registration sends a field mask. Use Commit directly for masked
        // writes so the app never exposes the flaky documents.patch HTTP 400 path.
        // The Commit body is the same operation but uses Firestore's canonical
        // relative document resource name and a JSON fieldPaths array.
        if (updateMaskQuery != null && !updateMaskQuery.isEmpty()) {
            boolean committed = commitDoc(path, fields, updateMaskQuery, updateTime);
            if (committed) return true;
            int commitCode = lastPatchHttpCode;
            String commitError = lastPatchError;
            boolean patched = patchDocOnce(path, fields, updateMaskQuery, updateTime);
            if (!patched && commitError != null && !commitError.isEmpty()) {
                lastPatchError = "COMMIT " + commitCode + ": " + commitError
                        + "\nPATCH " + lastPatchHttpCode + ": " + lastPatchError;
                // Keep the primary Commit result visible to LicenseManager.
                lastPatchHttpCode = commitCode;
            }
            return patched;
        }
        return patchDocOnce(path, fields, updateMaskQuery, updateTime);
    }

    private static boolean patchDocOnce(String path, JSONObject fields, String updateMaskQuery, String updateTime) {
        lastPatchHttpCode = 0;
        lastPatchError = "";
        HttpURLConnection c = null;
        try {
            StringBuilder query = new StringBuilder("?key=")
                    .append(java.net.URLEncoder.encode(C.k(), "UTF-8"));
            if (updateMaskQuery != null && !updateMaskQuery.isEmpty()) {
                // O Firestore REST aceita um parâmetro updateMask.fieldPaths por campo.
                // Reencode cada par para preservar corretamente a query em qualquer proxy.
                for (String part : updateMaskQuery.split("&")) {
                    if (part == null || part.isEmpty()) continue;
                    int equals = part.indexOf('=');
                    String name = equals > 0 ? part.substring(0, equals) : part;
                    String value = equals > 0 ? part.substring(equals + 1) : "";
                    query.append('&')
                            .append(java.net.URLEncoder.encode(name, "UTF-8"))
                            .append('=')
                            .append(java.net.URLEncoder.encode(value, "UTF-8"));
                }
            }
            if (updateTime != null && !updateTime.isEmpty()) {
                query.append("&currentDocument.updateTime=")
                        .append(java.net.URLEncoder.encode(updateTime, "UTF-8"));
            }
            String q = query.toString();
            URL url = new URL(base() + "/" + path + q);
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("PATCH");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            JSONObject body = new JSONObject().put("fields", fields);
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            String response = readAll(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            lastPatchHttpCode = code;
            lastPatchError = response == null ? "" : response;
            return code >= 200 && code < 300;
        } catch (Exception e) {
            lastPatchError = e.getClass().getSimpleName();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static boolean commitDoc(String path, JSONObject fields, String updateMaskQuery, String updateTime) {
        HttpURLConnection c = null;
        try {
            JSONObject update = new JSONObject()
                    .put("name", "projects/" + C.p() + "/databases/(default)/documents/" + path)
                    .put("fields", fields);
            JSONObject write = new JSONObject().put("update", update);
            List<String> maskFields = parseMaskFields(updateMaskQuery);
            if (!maskFields.isEmpty()) {
                JSONArray fieldPaths = new JSONArray();
                for (String field : maskFields) fieldPaths.put(field);
                // updateMask belongs to Write, not to the Document object.
                write.put("updateMask", new JSONObject().put("fieldPaths", fieldPaths));
            }
            if (updateTime != null && !updateTime.isEmpty()) {
                write.put("currentDocument", new JSONObject().put("updateTime", updateTime));
            }
            JSONObject body = new JSONObject().put("writes", new JSONArray().put(write));

            URL url = new URL(base() + ":commit?key="
                    + java.net.URLEncoder.encode(C.k(), "UTF-8"));
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            String response = readAll(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            lastPatchHttpCode = code;
            lastPatchError = response == null ? "" : response;
            return code >= 200 && code < 300;
        } catch (Exception e) {
            lastPatchHttpCode = 0;
            lastPatchError = e.getClass().getSimpleName();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static List<String> parseMaskFields(String updateMaskQuery) throws Exception {
        List<String> result = new ArrayList<>();
        if (updateMaskQuery == null || updateMaskQuery.isEmpty()) return result;
        for (String part : updateMaskQuery.split("&")) {
            if (part == null || part.isEmpty()) continue;
            int equals = part.indexOf('=');
            String value = equals >= 0 ? part.substring(equals + 1) : part;
            value = URLDecoder.decode(value, "UTF-8");
            if (!value.isEmpty() && !result.contains(value)) result.add(value);
        }
        return result;
    }

    public static boolean deleteDoc(String path) {
        try {
            URL url = new URL(base() + "/" + path + "?key=" + C.k());
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("DELETE");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 300;
        } catch (Exception e) { return false; }
    }

    /**
     * Query simples: acha documentos de uma coleção onde campo == valor,
     * ordenado por createdAt desc (se existir), limitado.
     */
    public static JSONArray query(String collection, String whereField, String whereValueStr, int limit) {
        lastQueryNetworkError = false;
        lastQueryHttpCode = 0;
        lastQueryError = "";
        try {
            JSONObject fieldFilter = new JSONObject()
                .put("field", new JSONObject().put("fieldPath", whereField))
                .put("op", "EQUAL")
                .put("value", str(whereValueStr));
            JSONObject structuredQuery = new JSONObject()
                .put("from", new JSONArray().put(new JSONObject().put("collectionId", collection)))
                .put("where", new JSONObject().put("fieldFilter", fieldFilter))
                .put("limit", limit);
            JSONObject body = new JSONObject().put("structuredQuery", structuredQuery);

            URL url = new URL(base() + ":runQuery?key=" + C.k());
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            String resp = readAll(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
            lastQueryHttpCode = code;
            lastQueryError = resp == null ? "" : resp;
            c.disconnect();
            if (code != 200) { lastQueryNetworkError = true; return new JSONArray(); }
            return new JSONArray(resp);
        } catch (Exception e) {
            lastQueryNetworkError = true;
            lastQueryError = e.getClass().getSimpleName();
            return new JSONArray();
        }
    }

    public static String docIdFromName(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('/');
        return i >= 0 ? name.substring(i + 1) : name;
    }

    private static String readAll(java.io.InputStream is) throws Exception {
        if (is == null) return "";
        Scanner s = new Scanner(is, "UTF-8");
        StringBuilder sb = new StringBuilder();
        while (s.hasNextLine()) sb.append(s.nextLine());
        s.close();
        return sb.toString();
    }
}
