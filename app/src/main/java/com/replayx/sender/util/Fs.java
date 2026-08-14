package com.replayx.sender.util;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;
import com.replayx.sender.security.C;

/** Helper fino pra falar com a API REST do Firestore (mesmo estilo do Combo Replay). */
public final class Fs {
    private Fs() {}

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
        try {
            String q = "?key=" + C.k() + (updateMaskQuery != null ? "&" + updateMaskQuery : "");
            URL url = new URL(base() + "/" + path + q);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
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
            c.disconnect();
            return code >= 200 && code < 300;
        } catch (Exception e) { return false; }
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
            c.disconnect();
            if (code != 200) return new JSONArray();
            return new JSONArray(resp);
        } catch (Exception e) { return new JSONArray(); }
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
