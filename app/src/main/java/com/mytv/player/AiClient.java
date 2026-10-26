package com.mytv.player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Any OpenAI-compatible chat API: OpenRouter, Groq, Together, Gemini, Claude, OpenAI, Ollama / LM Studio on a PC … */
public final class AiClient {
    private AiClient() {
    }

    public static final String[][] PRESETS = {
            {"OpenRouter", "https://openrouter.ai/api/v1", "google/gemma-3-12b-it"},
            {"Groq", "https://api.groq.com/openai/v1", "llama-3.1-8b-instant"},
            {"Together AI", "https://api.together.xyz/v1", "google/gemma-3-12b-it"},
            {"Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash"},
            {"Anthropic Claude", "https://api.anthropic.com/v1", "claude-haiku-4-5"},
            {"OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"},
            {"Ollama on my PC (free)", "http://192.168.1.20:11434/v1", "gemma3:12b"},
            {"LM Studio on my PC (free)", "http://192.168.1.20:1234/v1", "gemma-3-12b-it"},
            {"Custom", "", ""}};

    public static String base() {
        return Store.getStr("ai_base", "").trim().replaceAll("/+$", "");
    }

    public static String model() {
        return Store.getStr("ai_model", "").trim();
    }

    public static String key() {
        return SecretBox.open(Store.getStr("ai_key", ""));
    }

    public static boolean ready() {
        return !base().isEmpty() && !model().isEmpty();
    }

    /** One question → the model's text answer. Run on a background thread. */
    public static String chat(String system, String user, int maxTokens) throws Exception {
        if (!ready()) throw new Exception("AI is not set up: tap the AI settings and choose a provider");
        JSONObject body = new JSONObject();
        body.put("model", model());
        body.put("temperature", 0.2);
        body.put("max_tokens", maxTokens);
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", system));
        msgs.put(new JSONObject().put("role", "user").put("content", user));
        body.put("messages", msgs);
        HttpURLConnection c = (HttpURLConnection) new URL(base() + "/chat/completions").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(90000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        String k = key();
        if (!k.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + k);
        c.setRequestProperty("HTTP-Referer", "https://github.com/TAMILTIMON/MyTvPlay2");
        c.setRequestProperty("X-Title", "TamilCastPlay");
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.toString().getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n, "UTF-8"));
            in.close();
        }
        if (code >= 400) {
            String m = sb.toString();
            try {
                JSONObject e = new JSONObject(m);
                Object er = e.opt("error");
                m = er instanceof JSONObject ? ((JSONObject) er).optString("message", m) : e.optString("message", m);
            } catch (Exception ignored) {
            }
            throw new Exception("AI error " + code + ": " + (m.length() > 200 ? m.substring(0, 200) : m));
        }
        JSONObject r = new JSONObject(sb.toString());
        return r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim();
    }

    /** First {...} block of a reply (models sometimes add words or ``` around JSON). */
    public static JSONObject json(String reply) {
        if (reply == null) return null;
        int a = reply.indexOf('{'), b = reply.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            return new JSONObject(reply.substring(a, b + 1));
        } catch (Exception e) {
            return null;
        }
    }
}
