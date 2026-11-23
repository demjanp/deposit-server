package org.deposit.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.net.InetAddress;
import java.net.URI;

/** Fail closed before discovery declares the development plugin ready. */
public final class RuntimeReadiness {
    private RuntimeReadiness() {}
    public static void requireReady() {
        String file = System.getProperty("deposit.config");
        if (file == null) throw new IllegalStateException("Deposit configuration is required");
        Properties config = new Properties();
        try (var input = Files.newInputStream(Path.of(file))) {
            config.load(input);
            Set<String> required=Set.of("resourceRoot","exportRoot","download.listenAddress","download.port","download.publicBaseUrl","secretRoot");
            if (!config.stringPropertyNames().containsAll(required)
                || !Set.of("resourceRoot","exportRoot","download.listenAddress","download.port","download.publicBaseUrl","download.tlsKeyStore","download.tlsKeyStorePasswordFile","secretRoot","operationRestoreTime").containsAll(config.stringPropertyNames())) {
                throw new IllegalStateException("Invalid Deposit configuration keys");
            }
            boolean hasKeyStore=config.stringPropertyNames().contains("download.tlsKeyStore");
            boolean hasPasswordFile=config.stringPropertyNames().contains("download.tlsKeyStorePasswordFile");
            if(hasKeyStore!=hasPasswordFile)
                throw new IllegalStateException("Both download TLS settings are required together");
            String addressText=config.getProperty("download.listenAddress").trim();
            if(!addressText.equalsIgnoreCase("localhost")
                && !(addressText.matches("[0-9.]+")||addressText.matches("[0-9a-fA-F:.]+")))
                throw new IllegalStateException("download.listenAddress must be an IP address");
            InetAddress address=InetAddress.getByName(addressText);
            int port=Integer.parseInt(config.getProperty("download.port").trim());
            if(port<1||port>65535)throw new IllegalStateException("download.port is invalid");
            URI publicUrl=URI.create(config.getProperty("download.publicBaseUrl").trim());
            if(!publicUrl.isAbsolute()||publicUrl.getHost()==null||publicUrl.getUserInfo()!=null||publicUrl.getRawQuery()!=null||publicUrl.getRawFragment()!=null
                ||!("http".equalsIgnoreCase(publicUrl.getScheme())||"https".equalsIgnoreCase(publicUrl.getScheme())))
                throw new IllegalStateException("download.publicBaseUrl is invalid");
            if("http".equalsIgnoreCase(publicUrl.getScheme())&&!address.isLoopbackAddress())
                throw new IllegalStateException("Plain HTTP download listener must bind to loopback");
            if("http".equalsIgnoreCase(publicUrl.getScheme())) {
                InetAddress publicAddress=InetAddress.getByName(publicUrl.getHost());
                int publicPort=publicUrl.getPort()<0?80:publicUrl.getPort();
                if(!publicAddress.isLoopbackAddress()||publicPort!=port)throw new IllegalStateException("Loopback HTTP public URL must use the configured listener port");
            }
            if(hasKeyStore&& !"https".equalsIgnoreCase(publicUrl.getScheme()))throw new IllegalStateException("Direct TLS requires an HTTPS public URL");
            if(!address.isLoopbackAddress()&&(!"https".equalsIgnoreCase(publicUrl.getScheme())||!hasKeyStore))
                throw new IllegalStateException("Remote download listener requires direct TLS and an HTTPS public URL");
            Path exportRoot=Path.of(config.getProperty("exportRoot"));
            if(!exportRoot.isAbsolute()||!Files.isDirectory(exportRoot)||!Files.isWritable(exportRoot))
                throw new IllegalStateException("Deposit exportRoot must be an absolute writable directory");
            Path secretRoot=Path.of(config.getProperty("secretRoot"));
            if(!secretRoot.isAbsolute()||!Files.isDirectory(secretRoot)||Files.isSymbolicLink(secretRoot)||!Files.isWritable(secretRoot))
                throw new IllegalStateException("Deposit secretRoot must be an absolute writable directory");
            if(hasKeyStore) {
                Path keyStore=Path.of(config.getProperty("download.tlsKeyStore"));
                Path passwordFile=Path.of(config.getProperty("download.tlsKeyStorePasswordFile"));
                if(!keyStore.isAbsolute()||!Files.isRegularFile(keyStore)||Files.isSymbolicLink(keyStore)||!passwordFile.isAbsolute()||!Files.isRegularFile(passwordFile)||Files.isSymbolicLink(passwordFile))
                    throw new IllegalStateException("Download TLS material is unavailable");
            }
            Path root = Path.of(config.getProperty("resourceRoot"));
            if (!root.isAbsolute() || !Files.isDirectory(root) || !Files.isWritable(root)) {
                throw new IllegalStateException("Deposit resourceRoot must be an absolute writable directory");
            }
            for (String directory : new String[]{"staging", "ocfl", "ocfl-work"}) {
                Path child = root.resolve(directory);
                if (!Files.isDirectory(child) || !Files.getFileStore(root).equals(Files.getFileStore(child))) {
                    throw new IllegalStateException("Deposit resource directories must share one filesystem");
                }
            }
            new deposit.common.ResourceFiles();
            Class.forName("org.neo4j.gis.spatial.SpatialDatabaseService");
            // Eagerly link the published index implementation. Merely finding the
            // service class misses superclass ABI changes until the first write.
            Class.forName("org.neo4j.gis.spatial.rtree.Envelope");
            Class.forName("org.neo4j.gis.spatial.index.LayerRTreeIndex");
            Class.forName("org.locationtech.jts.io.WKTReader");
            Class.forName("io.ocfl.core.OcflRepositoryBuilder");
        } catch (IOException | ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Deposit runtime dependencies or configuration are unavailable", e);
        }
    }
}
