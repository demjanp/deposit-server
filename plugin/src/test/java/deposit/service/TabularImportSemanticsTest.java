package deposit.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class TabularImportSemanticsTest {
    @Test void missingNullAndEmptyStringAreBlankButWhitespaceIsSupplied() {
        assertTrue(TabularImportSemantics.isBlankCell(false, "supplied despite absent presence"));
        assertTrue(TabularImportSemantics.isBlankCell(true, null));
        assertTrue(TabularImportSemantics.isBlankCell(true, ""));
        assertFalse(TabularImportSemantics.isBlankCell(true, " "));
        assertFalse(TabularImportSemantics.isBlankCell(true, "\t\n"));
    }

    @Test void recordedBlankFieldsAreIgnoredAsMatchingKeys() {
        List<String> blanks = List.of("identifier", "code");
        assertTrue(TabularImportSemantics.shouldIgnoreMatchingField(blanks, "identifier"));
        assertTrue(TabularImportSemantics.shouldIgnoreMatchingField(blanks, "code"));
        assertFalse(TabularImportSemantics.shouldIgnoreMatchingField(blanks, "note"));
    }
}
