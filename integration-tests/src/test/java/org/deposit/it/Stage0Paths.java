package org.deposit.it;

import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Properties;

/** Resolves Stage 0 paths from the active isolated installation configuration. */
final class Stage0Paths {
    private static final Path ROOT=Path.of("C:/data_processed/Deposit_v2/stage0/runs/stage0-host");
    private Stage0Paths(){}
    static Path secretRoot(){
        Path config=ROOT.resolve("neo4j/conf/deposit.properties");
        try(var input=Files.newInputStream(config)){
            Properties values=new Properties();values.load(input);String configured=values.getProperty("secretRoot");
            return configured==null?config.getParent():Path.of(configured);
        }catch(java.io.IOException failure){throw new UncheckedIOException("Cannot read Stage 0 secret configuration",failure);}
    }
    static Path bootstrapSecret(){return secretRoot().resolve("deposit-bootstrap.secret");}
}
