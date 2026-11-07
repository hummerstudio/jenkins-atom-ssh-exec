package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ssh atom against a throwaway sshd, so the quoting, the exit code and the timeout are all exercised for real.
 * This is what {@code tools/smoke.sh} did, with the same sshd config and the same commands.
 */
class SshExecIT {

    private static Path sshdDir;
    private static int port;
    private static String user;
    private static String privateKey;
    private static boolean sshdUp;

    private Atom atom;

    @BeforeAll
    static void startSshd() throws Exception {
        user = System.getProperty("user.name");
        sshdDir = Files.createTempDirectory("atom-sshd");
        port = freePort();

        run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", sshdDir.resolve("host_key").toString());
        run("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", sshdDir.resolve("user_key").toString());
        Files.copy(sshdDir.resolve("user_key.pub"), sshdDir.resolve("authorized_keys"));
        Files.setPosixFilePermissions(sshdDir.resolve("authorized_keys"),
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        privateKey = Files.readString(sshdDir.resolve("user_key"), StandardCharsets.UTF_8);

        Files.writeString(sshdDir.resolve("sshd_config"), """
                Port %d
                ListenAddress 127.0.0.1
                HostKey %s
                PidFile %s
                AuthorizedKeysFile %s
                PasswordAuthentication no
                UsePAM no
                StrictModes no
                """.formatted(port, sshdDir.resolve("host_key"), sshdDir.resolve("sshd.pid"),
                sshdDir.resolve("authorized_keys")), StandardCharsets.UTF_8);

        Process sshd = new ProcessBuilder("/usr/sbin/sshd", "-f", sshdDir.resolve("sshd_config").toString(),
                "-E", sshdDir.resolve("sshd.log").toString())
                .redirectErrorStream(true)
                .start();
        sshd.waitFor(10, TimeUnit.SECONDS);

        for (int attempt = 0; attempt < 40 && !sshdUp; attempt++) {
            Process probe = new ProcessBuilder("ssh", "-p", String.valueOf(port),
                    "-i", sshdDir.resolve("user_key").toString(),
                    "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no",
                    "-o", "UserKnownHostsFile=/dev/null", user + "@127.0.0.1", "true")
                    .redirectErrorStream(true).start();
            sshdUp = probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0;
            if (!sshdUp) {
                Thread.sleep(200);
            }
        }
    }

    @AfterAll
    static void stopSshd() {
        Path pid = sshdDir == null ? null : sshdDir.resolve("sshd.pid");
        if (pid != null && Files.isRegularFile(pid)) {
            try {
                long value = Long.parseLong(Files.readString(pid).trim());
                new ProcessBuilder("kill", String.valueOf(value)).start().waitFor(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // the sshd is gone already
            }
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void run(String... argv) throws Exception {
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        process.waitFor(30, TimeUnit.SECONDS);
    }

    private Atom configured(String command) {
        return atom.input("{\"host\": \"127.0.0.1\", \"port\": \"" + port + "\", \"user\": \"" + user + "\", "
                        + "\"command\": " + Json.write(command) + ", \"knownHostsFile\": \"known_hosts\"}")
                .file(".secrets.json", Json.write(Map.of("key", privateKey)));
    }

    @Test
    @DisplayName("a plain command runs, its output reaches the log and the exit code is 0")
    void runsACommand() throws Exception {
        AtomResult run = configured("echo hello-from-remote").run();

        run.assertOk();
        assertTrue(run.console().contains("hello-from-remote"), "the remote output must be in the log:\n" + run.console());
        assertEquals("0", run.field("exitCode"));
    }

    @Test
    @DisplayName("spaces and single quotes survive: the script goes over stdin, not through an argument")
    void keepsTheQuoting() throws Exception {
        AtomResult run = configured("X=\"a b  c\"; echo \"$X\" | tr -s \" \" \"_\"; echo \"it's fine\"").run();

        run.assertOk();
        assertTrue(run.console().contains("a_b_c"), "spaces and quotes should be preserved verbatim:\n" + run.console());
        assertTrue(run.console().contains("it's fine"), "a single quote must not break the script");
    }

    @Test
    @DisplayName("a multi-line script keeps its structure and every loop iteration runs")
    void runsMultiLineScripts() throws Exception {
        AtomResult run = configured("set -e\nfor i in 1 2 3; do echo \"step $i\"; done\ntest -d /tmp").run();

        run.assertOk();
        long steps = run.console().lines().filter(line -> line.contains("step ")).count();
        assertEquals(3, steps, "each line of the loop should have run:\n" + run.console());
    }

    @Test
    @DisplayName("a non-zero exit fails the step and carries the output")
    void reportsANonZeroExit() throws Exception {
        AtomResult run = configured("echo \"about to fail\"; echo \"the reason is X\" >&2; exit 3").run();

        run.assertRefused();
        assertEquals("error", run.status());
        assertTrue(run.field("message").contains("exited 3 (expected 0)"), run.field("message"));
        assertTrue(run.field("message").contains("the reason is X"), "stderr should be quoted in the failure");
        assertEquals("3", run.field("exitCode"), "the exit code must still be exported");
    }

    @Test
    @DisplayName("an expected non-zero code is not a failure")
    void acceptsAnExpectedExitCode() throws Exception {
        AtomResult run = configured("exit 7").input("expectedExitCode", "7").run();

        run.assertOk();
        assertEquals("success", run.status());
    }

    @Test
    @DisplayName("failOnError=false keeps the build green but still reports the code")
    void honoursFailOnError() throws Exception {
        AtomResult run = configured("echo nope >&2; exit 9").input("failOnError", "false").run();

        run.assertOk();
        assertEquals("success", run.status());
        assertEquals("9", run.field("exitCode"));
    }

    @Test
    @DisplayName("a timeout kills the command instead of hanging the build")
    void stopsAtTheTimeout() throws Exception {
        long started = System.currentTimeMillis();
        AtomResult run = configured("sleep 30").input("timeoutSeconds", "5").run();
        long elapsed = (System.currentTimeMillis() - started) / 1000;

        run.assertRefused();
        assertEquals("error", run.status());
        assertTrue(run.field("message").contains("timed out after 5s"), run.field("message"));
        assertTrue(elapsed < 25, "the run should have stopped near the timeout, took " + elapsed + "s");
    }

    @Test
    @DisplayName("ssh itself failing (nothing listening) is reported as such")
    void reportsAnSshFailure() throws Exception {
        AtomResult run = atom.file(".secrets.json", Json.write(Map.of("key", privateKey)))
                .input("{\"host\": \"127.0.0.1\", \"port\": \"1\", \"user\": \"nobody\", \"command\": \"true\"}")
                .run();

        run.assertRefused();
        assertTrue(run.field("message").contains("exited 255"), "an ssh-level failure should say so: "
                + run.field("message"));
    }

    @Test
    @DisplayName("a key file that is not there is refused by name, and an empty command is refused")
    void refusesBadInput() throws Exception {
        AtomResult noKey = atom.input("{\"host\": \"127.0.0.1\", \"port\": \"" + port + "\", \"user\": \"" + user
                        + "\", \"command\": \"true\", \"keyFile\": \"deploy_key\"}")
                .run();
        noKey.assertRefused();
        assertEquals("40005", noKey.field("errorCode"), noKey.field("message"));
        assertTrue(noKey.field("message").contains("no private key at deploy_key"), noKey.field("message"));

        AtomResult empty = configured("").run();
        empty.assertRefused();
        assertTrue(empty.field("message").contains("command is required"), empty.field("message"));
        assertFalse(empty.console().contains("ssh: "), "nothing may be sent for an empty command");
    }
}
