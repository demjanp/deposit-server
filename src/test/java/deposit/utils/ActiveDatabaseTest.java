package deposit.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ActiveDatabaseTest {

    @BeforeEach
    public void setup() {
        ActiveDatabase.set(null);
    }

    @Test
    public void set() {
        String expectedDatabase = "testDB";
        ActiveDatabase.set(expectedDatabase);
        assertEquals(expectedDatabase, ActiveDatabase.get());
    }

    @Test
    public void getSet() {
        assertNull(ActiveDatabase.get());
    }

    @Test
    public void setTwice() {
        String initialDatabase = "initialDB";
        String expectedDatabase = "newDB";
        ActiveDatabase.set(initialDatabase);
        ActiveDatabase.set(expectedDatabase);
        assertEquals(expectedDatabase, ActiveDatabase.get());
    }
}