package io.github.konove.notmytodo.channel;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The process Claude Code starts as the "notmytodo" MCP server. It answers the MCP handshake
 * itself, so Claude Code sees a healthy server whether or not an IDE is running. Once the IDE
 * on the other end of a local socket has proved it knows the project's token (the token itself
 * never crosses the socket), it writes to Claude Code every line that IDE sends.
 *
 * Plain Java, JDK only: it runs outside the IDE with nothing but the plugin's own classes on
 * the class path, so it must not use Kotlin or any library.
 */
public final class BridgeMain {
    public static final String NAME = "notmytodo";
    /** What the Claude Code command line contains when this server is loaded as a channel. */
    public static final String FLAG = "server:" + NAME;

    private static final String DEFAULT_PROTOCOL = "\"2025-06-18\"";
    private static final String INSTRUCTIONS =
        "Events from the notmytodo channel are requests from the user, sent from their IDE, to fix TODO items. "
            + "Act on each one as you would on a message the user typed. They are one-way: no reply is expected.";

    private BridgeMain() {}

    public static void main(String[] args) throws IOException {
        Path cacheDir = Path.of(args.length > 0 ? args[0] : defaultCacheDir());
        Path cwd = Path.of("").toRealPath();
        long retryMs = Long.getLong("notmytodo.retryMs", 3000);
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);

        Thread link = new Thread(() -> relay(cacheDir, cwd, retryMs, out), "notmytodo-ide-link");
        link.setDaemon(true);
        link.start();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = in.readLine()) != null; ) {
            String reply = reply(line);
            if (reply != null) {
                write(out, reply);
            } else if (!line.isBlank() && field(line, "jsonrpc") == null) {
                System.err.println("notmytodo: ignored a line that is not JSON-RPC");
            }
        }
        // Claude Code closed our input: the session is over. The link thread is a daemon.
    }

    /** Both threads write to Claude Code; a line must never be cut in two by the other. */
    private static void write(PrintStream out, String line) {
        synchronized (out) {
            out.print(line);
            out.print('\n');
            out.flush();
        }
    }

    /**
     * Connects to the IDE, again and again. Each connection is a mutual challenge-response with
     * the token from the port file: the bridge sends a fresh nonce, the listener must answer with
     * its proof and a nonce of its own, and only then does the bridge answer in kind and pass on
     * every line the listener sends. A listener that fails, or is silent for 5 s, is dropped and
     * nothing it sent reaches Claude Code.
     */
    private static void relay(Path cacheDir, Path cwd, long retryMs, PrintStream out) {
        String flag = flag(ancestors());
        SecureRandom random = new SecureRandom();
        while (true) {
            Path file = find(cacheDir, cwd);
            if (file != null) {
                try {
                    List<String> lines = Files.readAllLines(file);
                    int port = Integer.parseInt(lines.get(0).trim());
                    String token = lines.get(1).trim();
                    try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                        byte[] nonce = new byte[16];
                        random.nextBytes(nonce);
                        String nonceB = HexFormat.of().formatHex(nonce);
                        Writer to = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                        to.write(nonceB + "\n");
                        to.flush();
                        BufferedReader ide = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        socket.setSoTimeout(5000);
                        String theirProof = ide.readLine();
                        String nonceH = ide.readLine();
                        byte[] expected = proof(token, "ide", nonceB).getBytes(StandardCharsets.UTF_8);
                        boolean proven = theirProof != null && nonceH != null && !nonceH.isBlank()
                            && MessageDigest.isEqual(expected, theirProof.getBytes(StandardCharsets.UTF_8));
                        // Not the IDE we were told about: say nothing more and relay nothing.
                        if (proven) {
                            socket.setSoTimeout(0);
                            to.write(proof(token, "bridge", nonceH) + "\n" + cwd + "\n" + flag + "\n");
                            to.flush();
                            for (String line; (line = ide.readLine()) != null; ) write(out, line);
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    // No IDE behind this file, or it went away. Try again below.
                }
            }
            try {
                Thread.sleep(retryMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** The reply to one line from Claude Code, or null when none is due: a notification, a response, or not JSON-RPC. */
    public static String reply(String line) {
        String id = field(line, "id");
        String method = field(line, "method");
        if (id == null || method == null || method.length() < 2 || method.charAt(0) != '"') return null;
        String result;
        switch (method.substring(1, method.length() - 1)) {
            case "initialize" -> {
                String version = field(field(line, "params"), "protocolVersion");
                result = "{\"protocolVersion\":" + (version == null || version.charAt(0) != '"' ? DEFAULT_PROTOCOL : version)
                    + ",\"capabilities\":{\"experimental\":{\"claude/channel\":{}}}"
                    + ",\"serverInfo\":{\"name\":\"" + NAME + "\",\"version\":\"1\"}"
                    + ",\"instructions\":\"" + INSTRUCTIONS + "\"}";
            }
            case "ping" -> result = "{}";
            case "tools/list" -> result = "{\"tools\":[]}";
            case "prompts/list" -> result = "{\"prompts\":[]}";
            case "resources/list" -> result = "{\"resources\":[]}";
            default -> {
                return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}";
            }
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
    }

    /**
     * The raw JSON text of the top-level member {@code key} of the object {@code json}: a string
     * with its quotes, a number, an object and so on. Null when there is no such member or
     * {@code json} is not an object. Members of nested objects are never returned.
     */
    public static String field(String json, String key) {
        if (json == null) return null;
        int i = skipSpace(json, 0);
        if (i >= json.length() || json.charAt(i) != '{') return null;
        i++;
        while (true) {
            i = skipSpace(json, i);
            if (i >= json.length() || json.charAt(i) != '"') return null;
            int nameEnd = stringEnd(json, i);
            if (nameEnd < 0) return null;
            String name = json.substring(i + 1, nameEnd - 1);
            i = skipSpace(json, nameEnd);
            if (i >= json.length() || json.charAt(i) != ':') return null;
            i = skipSpace(json, i + 1);
            int valueEnd = valueEnd(json, i);
            if (valueEnd < 0) return null;
            if (name.equals(key)) return json.substring(i, valueEnd);
            i = skipSpace(json, valueEnd);
            if (i >= json.length() || json.charAt(i) != ',') return null;
            i++;
        }
    }

    private static int skipSpace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** The index after the closing quote of the string that starts at {@code start}, or -1. */
    private static int stringEnd(String s, int start) {
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') i++;
            else if (c == '"') return i + 1;
        }
        return -1;
    }

    /** The index after the value that starts at {@code start}, or -1. */
    private static int valueEnd(String s, int start) {
        if (start >= s.length()) return -1;
        char first = s.charAt(start);
        if (first == '"') return stringEnd(s, start);
        if (first == '{' || first == '[') {
            int depth = 0;
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"') {
                    int end = stringEnd(s, i);
                    if (end < 0) return -1;
                    i = end - 1;
                } else if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) return i + 1;
                }
            }
            return -1;
        }
        int i = start;
        while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) i++;
        return i > start ? i : -1;
    }

    /** The name of the port file for the project at {@code path}. The IDE and the bridge must agree on it. */
    public static String key(String path) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Proves knowledge of {@code token} without revealing it: lower-case hex of HMAC-SHA256 over
     * {@code role + " " + nonce}, keyed with the token. The nonce makes each answer good for one
     * challenge only; the role ("ide" or "bridge") keeps one side's answer from being replayed
     * as the other's. The IDE and the bridge must both use this method.
     */
    public static String proof(String token, String role, String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((role + " " + nonce).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Where the port files are kept, unless the caller says otherwise. */
    public static String defaultCacheDir() {
        String given = System.getProperty("notmytodo.cacheDir");
        if (given != null && !given.isBlank()) return given;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        String base;
        if (os.contains("win")) base = env("LOCALAPPDATA", home + "\\AppData\\Local");
        else if (os.contains("mac")) base = home + "/Library/Caches";
        else base = env("XDG_CACHE_HOME", home + "/.cache");
        return Path.of(base, "not-my-todo").toString();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** The port file of the nearest project at or above {@code cwd}, or null when no IDE has one open. */
    public static Path find(Path cacheDir, Path cwd) {
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            Path file = cacheDir.resolve(key(dir.toString()));
            if (Files.isRegularFile(file)) return file;
        }
        return null;
    }

    /**
     * Whether Claude Code was started with the channel flag, from the command lines of this
     * process's parents, nearest first; an empty entry is one that could not be read.
     * Without the flag Claude Code drops what this server sends, so the IDE needs to know.
     */
    public static String flag(List<Optional<String>> ancestors) {
        boolean unknown = ancestors.isEmpty();
        for (Optional<String> commandLine : ancestors) {
            if (commandLine.isEmpty()) unknown = true;
            else if (commandLine.get().contains(FLAG)) return "yes";
        }
        return unknown ? "unknown" : "no";
    }

    /** Claude Code is the parent, or the parent's parent when a launcher script sits in between. */
    private static List<Optional<String>> ancestors() {
        List<Optional<String>> result = new ArrayList<>();
        Optional<ProcessHandle> process = ProcessHandle.current().parent();
        for (int i = 0; i < 2 && process.isPresent(); i++) {
            ProcessHandle.Info info = process.get().info();
            result.add(info.commandLine().or(() -> info.arguments().map(a -> String.join(" ", a))));
            process = process.get().parent();
        }
        return result;
    }
}
