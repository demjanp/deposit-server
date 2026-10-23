import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Verifies Maven's effective version against the reachable Git tag and writes build-derived paths. */
public class ValidateGitVersion {
    private static final Pattern TAG = Pattern.compile("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected repository root, effective Maven version and harness root");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        String version = args[1];
        Path harnessRoot = Path.of(args[2]).toAbsolutePath().normalize();
        if (version == null || !TAG.matcher(version).matches() && !version.matches("\\d+\\.\\d+\\.\\d+-(?:[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)-post\\.\\d+\\.g[0-9a-fA-F]{7}(?:-DIRTY)?-SNAPSHOT") || version.contains("${"))
            throw new IllegalStateException("Maven did not resolve a Git-derived Deposit version: " + version);
        if (!Files.isDirectory(root.resolve(".git")) && !Files.isRegularFile(root.resolve(".git")))
            throw new IllegalStateException("Versioned builds require a Git checkout");
        if (!run(root, "rev-parse", "--is-shallow-repository").equals("false"))
            throw new IllegalStateException("Versioned builds require complete Git history");

        List<String> exactTags = acceptedTags(run(root, "tag", "--points-at", "HEAD"));
        if (exactTags.size() > 1)
            throw new IllegalStateException("HEAD has multiple accepted version tags");
        String exactTag = exactTags.isEmpty() ? null : exactTags.getFirst();

        String described = null;
        for (String commit : lines(run(root, "rev-list", "--first-parent", "HEAD"))) {
            List<String> candidates = acceptedTags(run(root, "tag", "--points-at", commit));
            if (candidates.size() > 1)
                throw new IllegalStateException("First-parent commit has multiple accepted version tags: " + commit);
            if (!candidates.isEmpty()) {
                described = candidates.getFirst();
                break;
            }
        }
        if (described == null)
            throw new IllegalStateException("No supported Deposit version tag exists on the first-parent history");

        List<String> status = lines(run(root, "status", "--porcelain", "--untracked-files=all"));
        boolean dirty = !status.isEmpty();
        String expected;
        if (exactTag != null) {
            expected = dirty ? exactTag + "-DIRTY-SNAPSHOT" : exactTag;
        } else {
            int distance = Integer.parseInt(run(root, "rev-list", "--first-parent", "--count", described + "..HEAD"));
            if (distance < 1) throw new IllegalStateException("Untagged HEAD must be after its selected version tag");
            String fullCommit = run(root, "rev-parse", "--verify", "HEAD^{commit}");
            String shortCommit = fullCommit.substring(0, 7);
            expected = described + "-post." + distance + ".g" + shortCommit + (dirty ? "-DIRTY" : "") + "-SNAPSHOT";
        }
        if (!version.equals(expected))
            throw new IllegalStateException("Maven version " + version + " differs from Git-derived version " + expected);

        Path harnessBuild = harnessRoot.resolve("build");
        Files.createDirectories(harnessBuild);
        String metadata = "version=" + version + "\n"
                + "pluginFile=deposit-plugin-" + version + ".jar\n"
                + "probeFile=deposit-runtime-probe-" + version + ".jar\n"
                + "archiveFile=deposit-server-" + version + ".zip\n";
        Files.writeString(harnessBuild.resolve("deposit-build.properties"), metadata, StandardCharsets.UTF_8);
    }

    private static String accepted(String value) {
        return TAG.matcher(value).matches() ? value : null;
    }

    private static List<String> acceptedTags(String value) {
        List<String> result = new ArrayList<>();
        for (String line : lines(value)) if (accepted(line) != null) result.add(line);
        return result;
    }

    private static List<String> lines(String value) {
        return value.isBlank() ? List.of() : value.lines().toList();
    }

    private static String run(Path root, String... command) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        args.add("git");
        args.addAll(List.of(command));
        Process process = new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exit = process.waitFor();
        if (exit != 0) throw new IllegalStateException("Git command failed (" + String.join(" ", command) + "): " + output);
        return output;
    }
}
