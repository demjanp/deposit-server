package deposit.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class SharedConfig {
    public static final String VERSION;

    static {
        Properties properties = new Properties();
        try (InputStream input = SharedConfig.class.getClassLoader().getResourceAsStream("version.properties")) {
            if (input == null) {
                throw new RuntimeException("version.properties file not found in the classpath");
            }
            properties.load(input);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to load version.properties file", ex);
        }
        VERSION = properties.getProperty("git.commit.id.describe", "unknown-version");
    }
}
