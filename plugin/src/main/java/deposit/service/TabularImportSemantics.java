package deposit.service;

import java.util.Collection;

/** Blank-cell rules shared by tabular row expansion and import matching. */
final class TabularImportSemantics {
    private TabularImportSemantics() {}

    static boolean isBlankCell(boolean present, Object value) {
        return !present || value == null || value instanceof String text && text.isEmpty();
    }

    static boolean shouldIgnoreMatchingField(Collection<?> blankFields, String field) {
        return blankFields.contains(field);
    }
}
