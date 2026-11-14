package de.muffin.universalprotect;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** Sends alerts, staff actions and startup errors to Discord webhooks. JDK only, never blocks the caller. */
public final class DiscordNotifier {

    public record Conf(boolean enabled, String alertsUrl, String auditUrl, int minLevel,
                       Set<String> auditEvents, boolean startupErrors, String serverName) {
        public static Conf off() { return new Conf(false, "", "", 2, Set.of(), false, ""); }
    }

    public record Reply(int status, long retryAfterMs) {}

    public interface Transport { Reply post(String url, String json) throws Exception; }

    private record Job(String url, String json) {}

    private static final int QUEUE_MAX = 100;
    private static final long MIN_GAP_MS = 600L;
    private static final long MAX_WAIT_MS = 30_000L;
    private static final int MAX_ATTEMPTS = 3;

    private final Logger log;
    private final Transport transport;
    private final BlockingQueue<Job> queue = new LinkedBlockingQueue<>(QUEUE_MAX);
    private final AtomicInteger pending = new AtomicInteger();
    private final Thread worker;
    private volatile Conf conf = Conf.off();
    private volatile boolean running = true;
    private volatile long lastWarn;

    public DiscordNotifier(Logger log) { this(log, httpTransport()); }

    DiscordNotifier(Logger log, Transport transport) {
        this.log = log;
        this.transport = transport;
        this.worker = new Thread(this::loop, "UniversalProtect-Discord");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public void reload(Conf c) { conf = c == null ? Conf.off() : c; }

    public static boolean validUrl(String u) {
        if (u == null || u.isBlank()) return false;
        try {
            URI uri = URI.create(u.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1) return false;
            String h = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean host = h.equals("discord.com") || h.equals("discordapp.com") || h.equals("ptb.discord.com") || h.equals("canary.discord.com");
            String p = uri.getPath() == null ? "" : uri.getPath();
            return host && p.startsWith("/api/webhooks/");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** level: 1 = LOW, 2 = MEDIUM, 3 = HIGH. Only sent when level >= discord.min-level. */
    public void alert(String player, String rule, int level, String label, String summary, String world, int x, int y, int z) {
        try {
            Conf c = conf;
            if (!c.enabled() || level < c.minLevel()) return;
            StringBuilder f = new StringBuilder();
            field(f, "World", code(world), true);
            field(f, "Position", code(x + " " + y + " " + z), true);
            String w = world == null ? "" : world.replace("`", "").trim();
            if (!w.isEmpty()) field(f, "Teleport", code("/up tp " + w + " " + x + " " + y + " " + z), false);
            int color = level >= 3 ? 0xE74C3C : level == 2 ? 0xF39C12 : 0xF1C40F;
            enqueue(c.alertsUrl(), embed("\u26a0 " + label + " \u00b7 " + rule, "**" + md(player) + "** " + md(summary), color, f, c));
        } catch (RuntimeException ignored) { }
    }

    /** Staff action; only sent when the subcommand is listed in discord.audit-events. */
    public void audit(String staff, String action, String details, String world, int x, int y, int z) {
        try {
            Conf c = conf;
            if (!c.enabled() || action == null || !c.auditEvents().contains(action.toLowerCase(Locale.ROOT))) return;
            String args = details == null ? "" : details.replace("`", "").trim();
            String cmd = ("/up " + action + (args.isEmpty() ? "" : " " + args));
            StringBuilder f = new StringBuilder();
            field(f, "Staff", md(staff), true);
            if (world != null && !world.isBlank()) field(f, "Position", code(world + " " + x + " " + y + " " + z), true);
            enqueue(c.auditUrl(), embed("\ud83d\udee0 Staff action: " + action, "**" + md(staff) + "** ran " + code(cut(cmd, 300)), 0x3498DB, f, c));
        } catch (RuntimeException ignored) { }
    }

    /** Startup problems (e.g. database unreachable). Goes to the audit webhook, falls back to the alerts webhook. */
    public void startupError(String title, String detail) {
        try {
            Conf c = conf;
            if (!c.enabled() || !c.startupErrors()) return;
            String url = validUrl(c.auditUrl()) ? c.auditUrl() : c.alertsUrl();
            enqueue(url, embed("\u274c " + title, md(cut(detail == null ? "" : detail, 1000)), 0x992D22, new StringBuilder(), c));
        } catch (RuntimeException ignored) { }
    }

    /** Waits up to 4 s for queued messages, then stops the worker. */
    public void shutdown() {
        running = false;
        long end = System.currentTimeMillis() + 4000L;
        while (pending.get() > 0 && System.currentTimeMillis() < end) {
            try { Thread.sleep(50L); } catch (InterruptedException e) { break; }
        }
        worker.interrupt();
    }

    private void enqueue(String url, String json) {
        if (!running || !validUrl(url)) return;
        pending.incrementAndGet();
        if (!queue.offer(new Job(url.trim(), json))) {
            pending.decrementAndGet();
            warn("Discord queue is full, dropping a message.");
        }
    }

    private void loop() {
        while (true) {
            Job j;
            try { j = queue.poll(500, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { return; }
            if (j == null) {
                if (!running) return;
                continue;
            }
            try {
                deliver(j);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                warn("Discord delivery failed: " + e.getClass().getSimpleName());
            } finally {
                pending.decrementAndGet();
            }
            try { Thread.sleep(MIN_GAP_MS); } catch (InterruptedException e) { return; }
        }
    }

    private void deliver(Job j) throws InterruptedException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Reply r;
            try {
                r = transport.post(j.url(), j.json());
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) { warn("Discord request failed: " + e.getClass().getSimpleName()); return; }
                Thread.sleep(2000L);
                continue;
            }
            int s = r.status();
            if (s >= 200 && s < 300) return;
            if (s == 429 || s >= 500) {
                if (attempt == MAX_ATTEMPTS) { warn("Discord is not accepting messages (HTTP " + s + "), message dropped."); return; }
                Thread.sleep(s == 429 ? Math.min(MAX_WAIT_MS, Math.max(1000L, r.retryAfterMs())) : 2000L);
                continue;
            }
            warn("Discord rejected the message (HTTP " + s + "). Check the webhook URL in config.yml.");
            return;
        }
    }

    private void warn(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastWarn < 300_000L) return;
        lastWarn = now;
        log.warning("[Discord] " + msg);
    }

    private static String embed(String title, String desc, int color, StringBuilder fields, Conf c) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"username\":\"UniversalProtect\",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{");
        sb.append("\"title\":\"").append(json(cut(title, 250))).append("\",");
        sb.append("\"description\":\"").append(json(cut(desc, 3900))).append("\",");
        sb.append("\"color\":").append(color).append(',');
        if (fields.length() > 0) sb.append("\"fields\":[").append(fields).append("],");
        String name = c.serverName() == null ? "" : c.serverName().trim();
        if (!name.isEmpty()) sb.append("\"footer\":{\"text\":\"").append(json(cut(name, 100))).append("\"},");
        sb.append("\"timestamp\":\"").append(Instant.now()).append("\"}]}");
        return sb.toString();
    }

    private static void field(StringBuilder sb, String name, String value, boolean inline) {
        if (sb.length() > 0) sb.append(',');
        sb.append("{\"name\":\"").append(json(name)).append("\",\"value\":\"").append(json(cut(value, 1000)))
                .append("\",\"inline\":").append(inline).append('}');
    }

    private static String code(String s) {
        String v = s == null || s.isBlank() ? "-" : s.replace("`", "'");
        return "`" + v + "`";
    }

    private static String md(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ("\\*_~|`>".indexOf(ch) >= 0) sb.append('\\');
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int end = max - 1;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end) + "\u2026";
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch)); else sb.append(ch); }
            }
        }
        return sb.toString();
    }

    private static Transport httpTransport() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return (url, body) -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            long wait = 0L;
            String ra = res.headers().firstValue("Retry-After").orElse("");
            if (!ra.isEmpty()) {
                try { wait = (long) (Double.parseDouble(ra) * 1000.0); } catch (NumberFormatException ignored) { }
            }
            return new Reply(res.statusCode(), wait);
        };
    }
}
