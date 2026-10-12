package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import deposit.common.ResourceFiles;
import org.junit.jupiter.api.Test;

class TabularLimitsTest {
    @Test void encodedContentLimitAcceptsTheBoundaryAndRejectsTheNextByte(){
        assertDoesNotThrow(()->TabularService.requireContentLimit(ResourceFiles.MAX_BYTES,"content limit"));
        var failure=assertThrows(RuntimeException.class,()->TabularService.requireContentLimit(ResourceFiles.MAX_BYTES+1,"content limit"));
        assertTrue(failure.getMessage().contains("LIMIT_EXCEEDED"));
    }

    @Test void xlsxDimensionsIncludeTheHeaderAndEnforceBothAxes(){
        assertDoesNotThrow(()->TabularService.requireXlsxDimensions(1_048_575L,16_384));
        var rowFailure=assertThrows(RuntimeException.class,()->TabularService.requireXlsxDimensions(1_048_576L,1));
        assertTrue(rowFailure.getMessage().contains("LIMIT_EXCEEDED"));
        var columnFailure=assertThrows(RuntimeException.class,()->TabularService.requireXlsxDimensions(0,16_385));
        assertTrue(columnFailure.getMessage().contains("LIMIT_EXCEEDED"));
    }
}
