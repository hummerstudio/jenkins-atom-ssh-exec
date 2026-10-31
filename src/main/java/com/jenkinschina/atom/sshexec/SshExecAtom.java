package com.jenkinschina.atom.sshexec;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Run a shell command on another machine over SSH.
 *
 * <p>ssh-steps does this with a Groovy DSL around the JSch library; the transferable part is "run these lines over
 * there and tell me what happened". The command goes to {@code bash -s} on stdin rather than through a quoted
 * argument, so quotes, newlines and {@code $} survive untouched.
 */
public class SshExecAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String host = context.getParam("host", "").trim();
        String user = context.getParam("user", "").trim();
        String port = context.getParam("port", "22").trim();
        String command = context.getParam("command", "");
        String keyFile = context.getParam("keyFile", "").trim();
        String key = context.getSecret("key");
        String knownHosts = context.getParam("knownHostsFile", "").trim();
        String strict = context.getParam("strictHostKeyChecking", "accept-new").trim();
        boolean failOnError = context.getBooleanParam("failOnError", true);
        int expected = context.getIntParam("expectedExitCode", 0);
        int timeoutSeconds = Math.max(5, Math.min(context.getIntParam("timeoutSeconds", 120), 3600));

        if (host.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "host is required");
        }
        if (user.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "user is required");
        }
        if (command.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40003, "command is required");
        }
        if (!port.matches("\\d+")) {
            return AtomResult.error(ErrorType.USER, 40004, "port must be a number");
        }

        Path keyPath = null;
        boolean keyFromSecret = false;
        try {
            String keyOption = null;
            if (!keyFile.isEmpty()) {
                keyPath = workspace.resolve(keyFile).normalize();
                if (!Files.isRegularFile(keyPath)) {
                    return AtomResult.error(ErrorType.USER, 40005, "no private key at " + keyFile);
                }
                keyOption = keyPath.toString();
            } else if (key != null && !key.isBlank()) {
                keyPath = Files.createTempFile("atom-ssh-", ".key");
                Files.writeString(keyPath, key.endsWith("\n") ? key : key + "\n", StandardCharsets.UTF_8);
                setOwnerOnly(keyPath);
                keyOption = keyPath.toString();
                keyFromSecret = true;
            }

            List<String> argv = new ArrayList<>();
            argv.add("ssh");
            argv.add("-p");
            argv.add(port);
            argv.add("-o");
            argv.add("BatchMode=yes");
            argv.add("-o");
            argv.add("StrictHostKeyChecking=" + (strict.isEmpty() ? "accept-new" : strict));
            argv.add("-o");
            argv.add("ConnectTimeout=10");
            if (!knownHosts.isEmpty()) {
                Path known = workspace.resolve(knownHosts).normalize();
                Files.createDirectories(known.getParent() == null ? workspace : known.getParent());
                if (!Files.exists(known)) {
                    Files.writeString(known, "", StandardCharsets.UTF_8);
                }
                argv.add("-o");
                argv.add("UserKnownHostsFile=" + known);
            }
            if (keyOption != null) {
                argv.add("-i");
                argv.add(keyOption);
            }
            argv.add(user + "@" + host);
            argv.add("bash -s");   // the script arrives on stdin, so nothing needs quoting

            context.log("[ssh-exec] " + user + "@" + host + ":" + port + " -> " + firstLine(command));
            long started = System.currentTimeMillis();
            Result result = run(argv, command.getBytes(StandardCharsets.UTF_8), timeoutSeconds);
            long duration = System.currentTimeMillis() - started;
            if (!result.stdout.isBlank()) {
                context.log(result.stdout.stripTrailing());
            }
            if (!result.stderr.isBlank()) {
                context.log("[stderr] " + result.stderr.stripTrailing());
            }

            boolean timedOut = result.exit == 124 && result.stderr.startsWith("timed out");
            if (result.exit != expected && !failOnError) {
                context.log("[ssh-exec] exit " + result.exit + " (expected " + expected
                        + "), not failing the build because failOnError is off");
                return AtomResult.success()
                        .data("exitCode", String.valueOf(result.exit))
                        .data("durationMs", String.valueOf(duration))
                        .data("stdoutBytes", String.valueOf(result.stdout.getBytes(StandardCharsets.UTF_8).length));
            }
            if (result.exit != expected) {
                String detail = result.stderr.isBlank() ? result.stdout : result.stderr;
                ErrorType type = timedOut ? ErrorType.ENVIRONMENT
                        : result.exit == 255 ? ErrorType.USER   // ssh itself failed: key, host, port
                                : ErrorType.USER;
                AtomResult failure = AtomResult.error(type, 40006, (timedOut ? "" : "the command exited " + result.exit
                        + " (expected " + expected + "): ") + oneLine(tail(detail)));
                return failure
                        .data("exitCode", String.valueOf(result.exit))
                        .data("durationMs", String.valueOf(duration));
            }
            return AtomResult.success()
                    .data("exitCode", String.valueOf(result.exit))
                    .data("durationMs", String.valueOf(duration))
                    .data("stdoutBytes", String.valueOf(result.stdout.getBytes(StandardCharsets.UTF_8).length));
        } finally {
            if (keyPath != null && keyFromSecret) {
                Files.deleteIfExists(keyPath);
            }
        }
    }

    private static String firstLine(String value) {
        return value.lines().findFirst().orElse("").trim();
    }

    private static String tail(String value) {
        if (value == null) {
            return "";
        }
        String[] lines = value.stripTrailing().split("\n");
        int from = Math.max(0, lines.length - 5);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    private static String oneLine(String value) {
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() > 400 ? flat.substring(0, 400) + "…" : flat;
    }

    private static void setOwnerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException ignored) {
            // a filesystem without POSIX modes: ssh will complain about the key, not us
        }
    }

    private record Result(int exit, String stdout, String stderr) {}

    private static Result run(List<String> argv, byte[] stdin, int timeoutSeconds) throws Exception {
        Process process = new ProcessBuilder(argv).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outReader = pump(process.getInputStream(), out);
        Thread errReader = pump(process.getErrorStream(), err);
        try (OutputStream input = process.getOutputStream()) {
            input.write(stdin);
        } catch (IOException e) {
            // the far side may close early (bad key, host down); the exit code says what happened
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return new Result(124, out.toString(StandardCharsets.UTF_8), "timed out after " + timeoutSeconds + "s");
        }
        outReader.join(5000);
        errReader.join(5000);
        return new Result(process.exitValue(), out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private static Thread pump(java.io.InputStream stream, ByteArrayOutputStream sink) {
        Thread thread = new Thread(() -> {
            try (stream) {
                stream.transferTo(sink);
            } catch (IOException ignored) {
                // the process went away; the exit code is the signal
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
