package deposit.common;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ResourceLinkEncoderTest {
    private static final String NS="00000000-0000-4000-8000-000000000001";
    private static final String RESOURCE="00000000-0000-4000-8000-000000000002";
    @Test void preservesConfiguredPrefixAndUsesOnlyCanonicalIdentifiers(){
        assertEquals("https://deposit.example/base/resources/"+NS+"/"+RESOURCE,ResourceLinkEncoder.url("https://deposit.example/base/",NS,RESOURCE));
    }
    @Test void markdownEscapesLabelAndUrlDelimiters(){
        assertEquals("[a\\[b\\]\\\\c.csv](https://deposit.example/resources/"+NS+"/"+RESOURCE+")",ResourceLinkEncoder.markdown("https://deposit.example",NS,RESOURCE,"a[b]\\c.csv"));
    }
    @Test void refusesQueryAndCredentialBearingBases(){
        assertThrows(ExceptionHandler.DepositException.class,()->ResourceLinkEncoder.url("https://user:secret@example.test/?token=x",NS,RESOURCE));
    }
}
