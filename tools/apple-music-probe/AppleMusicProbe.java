import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Standalone feasibility probe. The terminal plays the role of the game controller. */
public final class AppleMusicProbe {
    private final Path assets;
    private final String developerToken;
    private final String pairing = UUID.randomUUID().toString();
    private final long started = System.nanoTime();
    private final String origin;
    private String session;
    private long lastSync;
    private int sequence;
    private String command = "idle";
    private String report = "waiting for browser";

    private AppleMusicProbe(Path assets, String token, int port) {
        this.assets = assets;
        this.developerToken = token;
        this.origin = "http://127.0.0.1:" + port;
    }

    public static void main(String[] args) throws Exception {
        String token = System.getenv("DANGO_AM_DEVELOPER_TOKEN");
        if (token == null || !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) {
            System.err.println("Set DANGO_AM_DEVELOPER_TOKEN to a signed developer JWT before running.");
            System.exit(2);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AppleMusicProbe probe = new AppleMusicProbe(Paths.get(args[0]), token, server.getAddress().getPort());
        server.createContext("/", probe::handle);
        server.start();
        try {
            URI url = URI.create(probe.origin + "/#" + probe.pairing);
            if (args.length > 1 && args[1].equals("--no-browser")) System.out.println(url);
            else Desktop.getDesktop().browse(url);
            probe.console();
        } finally {
            server.stop(0);
        }
    }

    private void console() {
        System.out.println("Commands: load <catalog song ID>, play, pause, seek <seconds>, stop, logout, status, quit");
        try (Scanner input = new Scanner(System.in, "UTF-8")) {
            while (input.hasNextLine()) {
                String line = input.nextLine().trim();
                if (line.equals("quit")) return;
                synchronized (this) {
                    if (line.equals("status")) {
                        System.out.println(session != null && elapsed(lastSync) > 12 ? "browser disconnected" : report);
                    } else if (line.matches("load [0-9]{1,24}|play|pause|seek [0-9]{1,5}|stop|logout")) {
                        command = line;
                        sequence++;
                        if (line.equals("stop") || line.equals("logout")) report = "stopped positionMs=0 durationMs=0";
                    } else {
                        System.out.println("Unknown command.");
                    }
                }
            }
        }
    }

    private synchronized void handle(HttpExchange request) throws IOException {
        try {
            if (!origin.substring("http://".length()).equals(request.getRequestHeaders().getFirst("Host"))) {
                reply(request, 403, "");
                return;
            }
            String path = request.getRequestURI().getRawPath();
            String method = request.getRequestMethod();
            if (method.equals("GET") && (path.equals("/") || path.equals("/probe.js"))) {
                String file = path.equals("/") ? "index.html" : "probe.js";
                String type = path.equals("/") ? "text/html" : "application/javascript";
                request.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; "
                    + "script-src 'self' https://js-cdn.music.apple.com; style-src 'unsafe-inline' https://*.apple.com; "
                    + "connect-src 'self' https://*.apple.com https://*.itunes.apple.com https://*.mzstatic.com; "
                    + "frame-src https://*.apple.com; media-src blob: https://*.apple.com https://*.mzstatic.com; "
                    + "worker-src blob:; img-src https://*.mzstatic.com data:; base-uri 'none'; frame-ancestors 'none'");
                send(request, 200, type, Files.readAllBytes(assets.resolve(file)));
                return;
            }
            if (!method.equals("POST") || !origin.equals(request.getRequestHeaders().getFirst("Origin"))) {
                reply(request, 403, "");
                return;
            }
            String authorization = request.getRequestHeaders().getFirst("Authorization");
            if (path.equals("/pair") && session == null && elapsed(started) < 120
                    && ("Bearer " + pairing).equals(authorization)) {
                session = UUID.randomUUID().toString();
                lastSync = System.nanoTime();
                reply(request, 200, session);
                return;
            }
            if (session == null || elapsed(lastSync) > 12 || !("Bearer " + session).equals(authorization)) {
                reply(request, 403, "");
                return;
            }
            if (path.equals("/token")) {
                reply(request, 200, developerToken);
            } else if (path.equals("/sync")) {
                String[] fields = readReport(request.getRequestBody()).split(" ");
                if (fields.length != 5 || !String.join(" ", fields).matches("[0-9]{1,9} [01] [0-9]{1,4} [0-9]{1,9} [0-9]{1,9}")) {
                    reply(request, 400, "");
                    return;
                }
                lastSync = System.nanoTime();
                if (Integer.parseInt(fields[0]) == sequence) {
                    report = "authorized=" + fields[1] + " libraryCount=" + fields[2]
                        + " positionMs=" + fields[3] + " durationMs=" + fields[4];
                }
                reply(request, 200, sequence + " " + command);
            } else {
                reply(request, 404, "");
            }
        } finally {
            request.close();
        }
    }

    private static String readReport(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < 64; i++) {
            int value = input.read();
            if (value < 0) return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
            bytes.write(value);
        }
        return "";
    }

    private static long elapsed(long from) {
        return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - from);
    }

    private static void reply(HttpExchange request, int status, String text) throws IOException {
        send(request, status, "text/plain", text.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange request, int status, String type, byte[] data) throws IOException {
        request.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        request.getResponseHeaders().set("Cache-Control", "no-store");
        request.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        request.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        request.sendResponseHeaders(status, data.length == 0 ? -1 : data.length);
        if (data.length != 0) request.getResponseBody().write(data);
    }
}
