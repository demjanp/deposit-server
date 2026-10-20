package org.deposit.it;

import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Properties;

/** Resolves test harness paths from the active isolated installation configuration. */
final class TestInstallationPaths {
    private static final Path HARNESS_ROOT=Path.of(requiredProperty("deposit.test.root")).toAbsolutePath().normalize();
    private static final Path SERVER_ROOT=Path.of(requiredProperty("deposit.server.root")).toAbsolutePath().normalize();
    private TestInstallationPaths(){}
    private static String requiredProperty(String name){String value=System.getProperty(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing required integration test path property: "+name);return value;}
    static Path harnessRoot(){return HARNESS_ROOT;}
    static Path serverRoot(){return SERVER_ROOT;}
    static Path runRoot(){return HARNESS_ROOT.resolve("runs/test-host");}
    static Path secretRoot(){
        Path config=runRoot().resolve("neo4j/conf/deposit.properties");
        try(var input=Files.newInputStream(config)){
            Properties values=new Properties();values.load(input);String configured=values.getProperty("secretRoot");
            return configured==null?config.getParent():Path.of(configured);
        }catch(java.io.IOException failure){throw new UncheckedIOException("Cannot read test harness secret configuration",failure);}
    }
    static Path bootstrapSecret(){return secretRoot().resolve("deposit-bootstrap.secret");}
}
