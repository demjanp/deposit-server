package deposit.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class DepositInfo {
	public static final String version;
	
	static {
		Properties properties = new Properties();
		try (InputStream input = DepositInfo.class.getClassLoader().getResourceAsStream("version.properties")) {
			if (input == null) {
				throw new RuntimeException("version.properties file not found in the classpath");
			}
			properties.load(input);
		} catch (IOException ex) {
			throw new RuntimeException("Failed to load version.properties file", ex);
		}
		version = properties.getProperty("git.commit.id.describe", "unknown-version");
	}
}
