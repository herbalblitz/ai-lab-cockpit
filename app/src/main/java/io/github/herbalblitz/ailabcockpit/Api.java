package io.github.herbalblitz.ailabcockpit;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Minimal HTTP client for the lab bridge. Every method is asynchronous and
 * delivers its result on the main thread. No third-party networking library.
 */
public final class Api {

    public interface Cb<T> {
        void ok(T value);

        void err(String message);
    }

    private static final int CONNECT_TIMEOUT = 8_000;
    private static final int READ_TIMEOUT = 30_000;
    private static final int CHAT_TIMEOUT = 300_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile String base = "";
    private volatile String token = "";

    public void configure(String url, String bridgeToken) {
        base = Store.cleanUrl(url);
        token = bridgeToken == null ? "" : bridgeToken.trim();
    }

    public String base() {
        return base;
    }

    public void close() {
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ API

    public void health(Cb<JSONObject> cb) {
        call("GET", "/health", null, false, READ_TIMEOUT, new Cb<String>() {
            @Override
            public void ok(String value) {
                cb.ok(parseObject(value, "health"));
            }

            @Override
            public void err(String message) {
                cb.err(message);
            }
        });
    }

    public void status(Cb<JSONObject> cb) {
        call("GET", "/api/lab/status", null, true, READ_TIMEOUT, new Cb<String>() {
            @Override
            public void ok(String value) {
                cb.ok(parseObject(value, "status"));
            }

            @Override
            public void err(String message) {
                cb.err(message);
            }
        });
    }

    public void experiments(Cb<JSONArray> cb) {
        call("GET", "/api/experiments", null, true, READ_TIMEOUT, new Cb<String>() {
            @Override
            public void ok(String value) {
                try {
                    JSONObject root = new JSONObject(value);
                    JSONArray data = root.optJSONArray("data");
                    cb.ok(data == null ? new JSONArray() : data);
                } catch (JSONException e) {
                    cb.err("Unexpected experiments response");
                }
            }

            @Override
            public void err(String message) {
                cb.err(message);
            }
        });
    }

    public void templates(Cb<JSONArray> cb) {
        call("GET", "/api/templates", null, true, READ_TIMEOUT, new Cb<String>() {
            @Override
            public void ok(String value) {
                try {
                    JSONObject root = new JSONObject(value);
                    JSONArray data = root.optJSONArray("data");
                    cb.ok(data == null ? new JSONArray() : data);
                } catch (JSONException e) {
                    cb.err("Unexpected templates response");
                }
            }

            @Override
            public void err(String message) {
                cb.err(message);
            }
        });
    }

    public void skills(Cb<JSONArray> cb) {
        call("GET", "/api/skills", null, true, READ_TIMEOUT, new Cb<String>() {
            @Override
            public void ok(String value) {
                try {
                    JSONObject root = new JSONObject(value);
                    JSONArray data = root.optJSONArray("data");
                    cb.ok(data == null ? new JSONArray() : data);
                } catch (JSONException e) {
                    cb.err("Unexpected skills response");
                }
            }

            @Override
            public void err(String message) {
                cb.err(message);
            }
        });
    }

    public void createExperiment(String title, String command, String target,
                                 int timeoutSec, Cb<JSONObject> cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("title", title);
            body.put("command", command);
            body.put("target", target);
            body.put("timeout_sec", timeoutSec);
        } catch (JSONException ignored) {
        }
        call("POST", "/api/experiments", body, true, READ_TIMEOUT, new Cb<String>() {
            @Override
            public void ok(String value) {
                try {
                    cb.ok(new JSONObject(value).optJSONObject("experiment"));
                } catch (JSONException e) {
                    cb.err("Unexpected create response");
                }
            }

            @Override
            public void err(String message) {
                cb.err(message);
            }
        });
    }

    public void experiment(String id, Cb<JSONObject> cb) {
        call("GET", "/api/experiments/" + id, null, true, READ_TIMEOUT, new Cb<String>() {
            @Override
            public void ok(String value) {
                cb.ok(parseObject(value, "experiment"));
            }

            @Override
            public void err(String message) {
                cb.err(message);
            }
        });
    }

    public void log(String id, Cb<String> cb) {
        call("GET", "/api/experiments/" + id + "/log?tail=60000", null, true,
                READ_TIMEOUT, cb);
    }

    public void cancel(String id, Cb<JSONObject> cb) {
        JSONObject body = new JSONObject();
        call("POST", "/api/experiments/" + id + "/cancel", body, true,
                READ_TIMEOUT, new Cb<String>() {
                    @Override
                    public void ok(String value) {
                        cb.ok(parseObject(value, "cancel"));
                    }

                    @Override
                    public void err(String message) {
                        cb.err(message);
                    }
                });
    }

    public void alerts(double since, Cb<JSONArray> cb) {
        call("GET", "/api/alerts?since=" + since, null, true, READ_TIMEOUT,
                new Cb<String>() {
                    @Override
                    public void ok(String value) {
                        try {
                            JSONArray data = new JSONObject(value).optJSONArray("data");
                            cb.ok(data == null ? new JSONArray() : data);
                        } catch (JSONException e) {
                            cb.err("Unexpected alerts response");
                        }
                    }

                    @Override
                    public void err(String message) {
                        cb.err(message);
                    }
                });
    }

    public void chat(JSONArray messages, String model, Cb<JSONObject> cb) {
        chat(messages, model, null, cb);
    }

    public void chat(JSONArray messages, String model, String skill, Cb<JSONObject> cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("messages", messages);
            body.put("model", model);
            if (skill != null && !skill.trim().isEmpty()) body.put("skill", skill);
        } catch (JSONException ignored) {
        }
        call("POST", "/api/chat", body, true, CHAT_TIMEOUT, new Cb<String>() {
            @Override
            public void ok(String value) {
                cb.ok(parseObject(value, "chat"));
            }

            @Override
            public void err(String message) {
                cb.err(message);
            }
        });
    }

    // ------------------------------------------------------------- transport

    private void call(String method, String path, JSONObject body, boolean auth,
                      int readTimeout, Cb<String> cb) {
        final String urlBase = base;
        final String bearer = token;
        if (urlBase == null || urlBase.isEmpty()) {
            main.post(() -> cb.err("No bridge URL configured"));
            return;
        }
        try {
            pool.execute(() -> {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(urlBase + path);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod(method);
                    conn.setConnectTimeout(CONNECT_TIMEOUT);
                    conn.setReadTimeout(readTimeout);
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setRequestProperty("User-Agent", "ai-lab-cockpit/" + BuildInfo.VERSION);
                    if (auth && bearer != null && !bearer.isEmpty()) {
                        conn.setRequestProperty("Authorization", "Bearer " + bearer);
                    }
                    if (body != null) {
                        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                        conn.setDoOutput(true);
                        conn.setFixedLengthStreamingMode(bytes.length);
                        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        try (OutputStream out = conn.getOutputStream()) {
                            out.write(bytes);
                        }
                    }
                    int code = conn.getResponseCode();
                    InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    String text = read(stream);
                    if (code >= 200 && code < 300) {
                        main.post(() -> cb.ok(text));
                    } else {
                        String message = friendly(code, text, urlBase);
                        main.post(() -> cb.err(message));
                    }
                } catch (SocketTimeoutException e) {
                    main.post(() -> cb.err("Backend timed out"));
                } catch (ConnectException e) {
                    main.post(() -> cb.err("Cannot reach " + urlBase));
                } catch (UnknownHostException e) {
                    main.post(() -> cb.err("Unknown host: " + urlBase));
                } catch (IOException e) {
                    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    main.post(() -> cb.err(message));
                } catch (RuntimeException e) {
                    main.post(() -> cb.err("Request failed: " + e.getClass().getSimpleName()));
                } finally {
                    if (conn != null) conn.disconnect();
                }
            });
        } catch (RejectedExecutionException e) {
            main.post(() -> cb.err("Client is shutting down"));
        }
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                out.append(buffer, 0, read);
            }
        }
        return out.toString();
    }

    private static JSONObject parseObject(String value, String what) {
        try {
            return new JSONObject(value);
        } catch (JSONException e) {
            JSONObject out = new JSONObject();
            try {
                out.put("error", "Unexpected " + what + " response");
            } catch (JSONException ignored) {
            }
            return out;
        }
    }

    private static String friendly(int code, String body, String base) {
        String detail = "";
        try {
            JSONObject object = new JSONObject(body);
            Object error = object.opt("error");
            if (error instanceof JSONObject) {
                detail = ((JSONObject) error).optString("message", "");
            } else if (error != null) {
                detail = String.valueOf(error);
            }
        } catch (JSONException ignored) {
            detail = body == null ? "" : body.trim();
        }
        if (code == 401 || code == 403) return "Invalid bridge token";
        if (code == 404) return detail.isEmpty() ? "Not found" : detail;
        if (code >= 500 && detail.isEmpty()) return "Bridge error (HTTP " + code + ")";
        if (!detail.isEmpty()) return detail;
        return "HTTP " + code + " from " + base;
    }
}
