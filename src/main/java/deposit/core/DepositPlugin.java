package deposit.core;

import org.neo4j.procedure.Description;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.UserFunction;
import org.neo4j.procedure.Mode;

import java.util.stream.Stream;

public class DepositPlugin {

    // Procedure to return the version of the deposit plugin
    @Procedure(name = "deposit.version", mode = Mode.READ)
    @Description("Returns the version of the deposit plugin")
    public Stream<VersionResult> getVersion() {
        return Stream.of(new VersionResult(SharedConfig.VERSION));
    }

    // Inner class to represent the result of the version procedure
    public static class VersionResult {
        public final String version;

        public VersionResult(String version) {
            this.version = version;
        }
    }
}
