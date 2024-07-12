package deposit.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.net.URL;

public class DepositInfo {

    public static String version;
    public static boolean initialized = false;

    static {
        loadVersion();
    }

    public static void loadVersion() {
        if (initialized) return;

        try {
            URL url = DepositInfo.class.getClassLoader().getResource("version.properties");
            if (url == null) {
                version = "unknown-version";
                System.out.println("Properties file not found, setting version to 'unknown-version'");
            } else {
                try (InputStream input = url.openStream()) {
                    Properties prop = new Properties();
                    prop.load(input);
                    version = prop.getProperty("git.commit.id.describe", "unknown-version");
                    System.out.println("Loaded properties file from " + url + ", version is " + version);
                }
            }
        } catch (IOException ex) {
            version = "unknown-version";
            System.out.println("Error loading properties file, setting version to 'unknown-version'");
        }

        initialized = true;
    }
}
