/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.scm.invariants;

import com.beust.jcommander.Parameter;
import org.apache.commons.lang3.SystemUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The compiler may have large start-up time. In some cases the
 * <a href="https://lcamtuf.blogspot.com/2014/10/fuzzing-binaries-without-execve.html">fork-server</a>
 * approach can be applied. Specifically, the compiler should be
 * <ul>
 *     <li>single-threaded (w.r.t. OS threads)</li>
 *     <li>not reading input files during start-up</li>
 * </ul>
 *
 * Protocol:
 * <ul>
 *     <li>
 *         Unlike in the traditional AFL forkserver, all communication with JVM
 *         goes through the same <b>stdout</b> and <b>stderr</b> streams
 *     </li>
 *     <li>
 *         Forkserver communications are distinguished from regular compiler output
 *         via {@link #FORKSERVER_TO_SCM_MARKER} immediately followed by reply and
 *         '\n' character
 *     </li>
 *     <li>
 *         JVM process always read output streams until both are terminated via
 *         {@link #FORKSERVER_TO_SCM_MARKER}, so fork child process timeout is essential
 *         to return from the fork child to main forkserver loop
 *     </li>
 *     <li>
 *         Spawning a fork server child is performed by writing a single byte (with any value)
 *         to <b>stdin</b> after {@link #FORKSERVER_TO_SCM_MARKER} was read.
 *     </li>
 *     <li>
 *         The initial forkserver reply should be {@link #FORKSERVER_INIT_REPLY} on <b>stdout</b>,
 *         after that every fork child execution is summarized with reply containing
 *         decimal exit code. Reply on <b>stderr</b> should always be empty (i.e., just
 *         {@link #FORKSERVER_TO_SCM_MARKER} followed by a newline character).
 *     </li>
 * </ul>
 */
public abstract class AbstractForkServerAwareProcessInvariant extends AbstractExternalProcessInvariant {
    /**
     * Variable used by the system linker for shared object preloading.
     */
    private final static String PRELOAD_VAR = "LD_PRELOAD";

    /**
     * Environment variable name: timeout (in seconds) for forkserver child.
     */
    private final static String SCM_TIMEOUT_VAR = "__SCM_TIMEOUT";

    /**
     * Environment variable name: i-th input file (0-indexed).
     *
     * Should be set for <b>continuous</b> range i in [0, N], first absent
     * variable terminates the list.
     */
    private final static String SCM_INPUT_VAR_FORMAT = "__SCM_INPUT_%d";

    /**
     * Marker string signifying the end of output stream for <b>current</b> child
     * or readiness for the first spawn.
     *
     * Should be printed independently on <b>stdout</b> and <b>stderr</b>.
     */
    private final static String FORKSERVER_TO_SCM_MARKER = "## FORKSERVER -> SCM ##";

    /**
     * Marker for the initial forkserver reply.
     */
    private final static String FORKSERVER_INIT_REPLY = "INIT";

    /**
     * The size of buffer for a single output line read by this class.
     */
    private final static int MAX_LINE_LENGTH = 65536;

    protected abstract static class AbstractConfigurationWithForkServer extends AbstractConfiguration {
        @Parameter(names = "--forkserver", description = "Use forkserver (Linux-only)")
        private boolean useForkserver;

        @Parameter(names = "--forkserver-child-timeout", description = "Timeout for a single fork server child process")
        private int timeoutSec = 1;
    }

    protected abstract static class AbstractFactoryWithForkServer extends AbstractFactory {
        AbstractFactoryWithForkServer(String name) {
            super(name);
        }
    }

    private final boolean useForkserver;
    private final int timeoutSec;

    /**
     * Path to compiled native shared object to be injected into the compiler.
     */
    private Path preloadedObject;

    private Process forkServer;
    private OutputStream forkServerStdin;
    private BufferedReader forkServerStdout;
    private BufferedReader forkServerStderr;

    /**
     * Misc files to be cleaned up just before JVM termination.
     */
    private final List<Path> deleteAtExit = new ArrayList<>();

    protected AbstractForkServerAwareProcessInvariant(AbstractConfigurationWithForkServer configuration) {
        super(configuration);
        useForkserver = configuration.useForkserver;
        timeoutSec = configuration.timeoutSec;
        Runtime.getRuntime().addShutdownHook(createCleanUpHook());
    }

    private Thread createCleanUpHook() {
        return new Thread() {
            @Override
            public void run() {
                for (Path path: deleteAtExit) {
                    try {
                        Files.delete(path);
                    } catch (IOException ex) {
                        // do nothing
                    }
                }
                if (forkServer != null) {
                    forkServer.destroy();
                }
            }
        };
    }

    protected Charset getCharset() {
        // We need some charset since we are parsing output streams.
        // Particular invariants can request some specific one,
        // but any ASCII-compatible one is enough for forkserver operation.
        return Charset.defaultCharset();
    }

    /**
     * Reads all lines of output until the {@link #FORKSERVER_TO_SCM_MARKER}.
     *
     * @param reader         A stream to read from
     * @param stdoutContents An output buffer to place lines into
     * @return A forkserver reply string (the sequence of characters
     *         after {@link #FORKSERVER_TO_SCM_MARKER} until '\n')
     *         or <code>null</code> on unexpected EOF
     * @throws IOException
     */
    private String readUntilMarker(BufferedReader reader, List<String> stdoutContents) throws IOException {
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                return null;
            }
            int indexOfMarker = line.indexOf(FORKSERVER_TO_SCM_MARKER);
            if (indexOfMarker == -1) {
                stdoutContents.add(line);
            } else {
                stdoutContents.add(line.substring(0, indexOfMarker));
                return line.substring(indexOfMarker + FORKSERVER_TO_SCM_MARKER.length());
            }
        }
    }

    /**
     * Compiles the preloaded shared object on the user's Linux system.
     *
     * @return A path to the resulted .so-file
     * @throws IOException
     */
    private Path compilePreloadedObject() throws IOException {
        try {
            Path input = Files.createTempFile("pmd-scm-forkserver-", ".c");
            deleteAtExit.add(input);
            Path output = Files.createTempFile("pmd-scm-forkserver-", ".so");
            deleteAtExit.add(output);
            Files.copy(getClass().getResourceAsStream("forksrv-preload.c"), input, StandardCopyOption.REPLACE_EXISTING);
            String compiler = System.getenv("CC");
            if (compiler == null) {
                compiler = "cc";
            }
            Process compilerProcess = new ProcessBuilder()
                    .command(compiler, "--shared", "-fPIC", input.toString(), "-o", output.toString())
                    .inheritIO()
                    .start();
            int exitCode = compilerProcess.waitFor();
            if (exitCode != 0) {
                throw new IOException("Cannot compile forkserver preloaded object: compiler exited with code " + exitCode);
            }
            return output;
        } catch (Exception ex) {
            throw new IOException("Cannot compile forkserver preloaded object", ex);
        }
    }

    @Override
    protected ProcessBuilder createProcessBuilder() {
        if (useForkserver) {
            // Cannot set via ProcessBuilder.environment, otherwise /bin/sh itself
            // will be killed on fork(), execve(), etc.
            String setEnvString = PRELOAD_VAR + "=" + preloadedObject.toAbsolutePath().toString();
            ProcessBuilder pb = new ProcessBuilder();
            pb.command("/bin/sh", "-c", setEnvString + " " + getCompilerCommandLine());
            Map<String, String> environment = pb.environment();
            environment.put(SCM_TIMEOUT_VAR, Integer.toString(timeoutSec));
            List<Path> scratchFiles = ops.getScratchFileNames();
            for (int i = 0; i < scratchFiles.size(); ++i) {
                environment.put(String.format(SCM_INPUT_VAR_FORMAT, i), scratchFiles.get(i).toString());
            }
            return pb;
        } else {
            return super.createProcessBuilder();
        }
    }

    @Override
    public void initialize(InvariantOperations ops) throws IOException {
        super.initialize(ops);
        if (useForkserver) {
            // Check that OS is supported
            if (!SystemUtils.IS_OS_LINUX) {
                throw new IllegalArgumentException("Forkserver is requested on an unsupported OS");
            }

            // Start the forkserver
            preloadedObject = compilePreloadedObject();
            forkServer = createProcessBuilder().start();
            forkServerStdin = forkServer.getOutputStream();
            forkServerStdout = new BufferedReader(new InputStreamReader(forkServer.getInputStream(), getCharset()));
            forkServerStderr = new BufferedReader(new InputStreamReader(forkServer.getErrorStream(), getCharset()));

            // Read startup messages
            List<String> stdoutContents = new ArrayList<>();
            List<String> stderrContents = new ArrayList<>();
            String msgStdOut = readUntilMarker(forkServerStdout, stdoutContents);
            String msgStdErr = readUntilMarker(forkServerStderr, stderrContents);

            // Check that the forkserver answered as expected
            if (!FORKSERVER_INIT_REPLY.equals(msgStdOut) || !"".equals(msgStdErr)) {
                System.err.println("Fork server did not start properly, check your command line.");
                System.err.println("Possible causes:");
                System.err.println("  * the compiler process tried to spawn thread or subprocess");
                System.err.println("  * the compiler have not touched any input file");
                System.err.println("STDOUT:");
                for (String line: stdoutContents) {
                    System.err.println(line);
                }
                System.err.println("STDERR:");
                for (String line: stderrContents) {
                    System.err.println(line);
                }
                throw new IOException("Invalid forkserver reply: stdout[" + msgStdOut + "], stderr[" + msgStdErr + "]");
            } else {
                System.out.println("Connected to fork server.\n");
            }
        }
    }

    protected abstract boolean testSatisfied(int exitCode, List<String> stdout, List<String> stderr);

    // should be called after receiving previous reply
    private void spawnForkChild() throws IOException {
        byte[] dummy = new byte[1];
        forkServerStdin.write(dummy);
        forkServerStdin.flush();
    }

    @Override
    protected boolean testSatisfied() throws Exception {
        if (useForkserver) {
            spawnForkChild();

            // Read the entire fork child output streams
            List<String> stdoutContents = new ArrayList<>();
            List<String> stderrContents = new ArrayList<>();
            String msgStdOut = readUntilMarker(forkServerStdout, stdoutContents);
            String msgStdErr = readUntilMarker(forkServerStderr, stderrContents);

            // Check that the forkserver is still functioning correctly
            if (msgStdOut == null || msgStdErr == null) {
                throw new IOException("Forkserver terminated unexpectedly");
            }
            String errorMessage = "Invalid forkserver reply: stdout[" + msgStdOut + "], stderr[" + msgStdErr + "]";
            if (!msgStdErr.isEmpty()) {
                throw new IOException(errorMessage);
            }

            // Parse the forkserver reply to get exit code
            int exitCode;
            try {
                exitCode = Integer.parseInt(msgStdOut);
            } catch (NumberFormatException ex) {
                throw new IOException(errorMessage, ex);
            }
            return testSatisfied(exitCode, stdoutContents, stderrContents);
        } else {
            return testSatisfied(createProcessBuilder());
        }
    }
}
