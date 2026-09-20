package dev.ping.run;

import dev.ping.BuildInfo;
import dev.ping.auth.TokenCache;
import dev.ping.http.HttpEngine;
import dev.ping.rpc.RpcException;
import dev.ping.run.report.Reporter;
import dev.ping.store.YamlStore;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code ping-core run <collection>}: the runner without Electron.
 *
 * <p>Hand-rolled argv on purpose. A parsing library would bring reflection into a native
 * image, and this grammar is one subcommand and six flags.
 *
 * <p>Exit codes: {@value #EXIT_OK} every request passed, {@value #EXIT_FAILED} an assertion
 * failed or a request got no response, {@value #EXIT_USAGE} the run could not start.
 * The report goes to stdout (or {@code -o}); diagnostics go to stderr, so
 * {@code ping-core run c -r junit > report.xml} stays clean.
 */
public final class Cli {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    /** Secrets arrive as {@code PING_SECRET_<NAME>}, the CLI's stand-in for {@code safeStorage}. */
    static final String SECRET_PREFIX = "PING_SECRET_";

    private static final String USAGE = """
            Usage: ping-core run <collection-dir> [options]

            Runs every request in a collection folder, in sidebar order.

            Options:
              -e, --env NAME          environment to use (name, or file name without .yaml)
              -r, --reporter NAME     human (default), json or junit
              -o, --output FILE       write the report to FILE instead of stdout
                  --var NAME=VALUE    set a variable; outranks every other scope. Repeatable
                  --help              show this help
                  --version           show the version

            Secrets: any PING_SECRET_<NAME> environment variable becomes the variable NAME.
            Prefer these to --var, which is visible in the process list.

            Exit codes: 0 all passed, 1 an assertion failed or a request errored,
            2 the run could not start.

            With no arguments, ping-core serves JSON-RPC on stdin/stdout for the desktop app.
            """;

    private Cli() {
    }

    public static int run(String[] args, Map<String, String> environment, PrintStream out, PrintStream err) {
        if (args.length == 0 || isHelp(args[0])) {
            (args.length == 0 ? err : out).print(USAGE);
            return args.length == 0 ? EXIT_USAGE : EXIT_OK;
        }
        if (args[0].equals("--version")) {
            out.println("ping-core " + BuildInfo.VERSION);
            return EXIT_OK;
        }
        if (!args[0].equals("run")) {
            return usage(err, "Unknown command: " + args[0]);
        }

        String collectionDir = null;
        String env = null;
        String reporterName = "human";
        String output = null;
        Map<String, String> variables = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(SECRET_PREFIX) && key.length() > SECRET_PREFIX.length()) {
                variables.put(key.substring(SECRET_PREFIX.length()), entry.getValue());
            }
        }

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--help", "-h" -> {
                    out.print(USAGE);
                    return EXIT_OK;
                }
                case "-e", "--env" -> {
                    if (i + 1 >= args.length) return usage(err, arg + " needs a value");
                    env = args[++i];
                }
                case "-r", "--reporter" -> {
                    if (i + 1 >= args.length) return usage(err, arg + " needs a value");
                    reporterName = args[++i];
                }
                case "-o", "--output" -> {
                    if (i + 1 >= args.length) return usage(err, arg + " needs a value");
                    output = args[++i];
                }
                case "--var" -> {
                    if (i + 1 >= args.length) return usage(err, "--var needs NAME=VALUE");
                    String pair = args[++i];
                    int eq = pair.indexOf('=');
                    if (eq <= 0) return usage(err, "--var needs NAME=VALUE, got \"" + pair + "\"");
                    variables.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
                default -> {
                    if (arg.startsWith("-")) return usage(err, "Unknown option: " + arg);
                    if (collectionDir != null) return usage(err, "Unexpected argument: " + arg);
                    collectionDir = arg;
                }
            }
        }

        if (collectionDir == null) {
            return usage(err, "A collection folder is required");
        }
        Reporter reporter = Reporter.named(reporterName);
        if (reporter == null) {
            return usage(err, "Unknown reporter \"" + reporterName + "\"; use human, json or junit");
        }
        Path directory = Path.of(collectionDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(directory) || directory.getParent() == null) {
            return usage(err, "Not a collection folder: " + collectionDir);
        }

        RunResult result;
        try {
            Runner runner = new Runner(new YamlStore(), new HttpEngine(new TokenCache()));
            result = runner.run(directory.getParent(), directory.getFileName().toString(),
                    new RunOptions(env, variables, true), null);
        } catch (RpcException e) {
            return usage(err, e.getMessage());
        }

        if (result.total() == 0) {
            err.println("warning: " + collectionDir + " contains no requests");
        }
        try {
            if (output == null) {
                reporter.write(result, out);
            } else {
                try (PrintStream file = new PrintStream(
                        Files.newOutputStream(Path.of(output)), false, StandardCharsets.UTF_8)) {
                    reporter.write(result, file);
                }
            }
        } catch (IOException e) {
            return usage(err, "Could not write " + output + ": " + e.getMessage());
        }
        return result.succeeded() ? EXIT_OK : EXIT_FAILED;
    }

    private static boolean isHelp(String arg) {
        return arg.equals("--help") || arg.equals("-h") || arg.equals("help");
    }

    private static int usage(PrintStream err, String message) {
        err.println("error: " + message);
        err.println("Run \"ping-core --help\" for usage.");
        return EXIT_USAGE;
    }
}
