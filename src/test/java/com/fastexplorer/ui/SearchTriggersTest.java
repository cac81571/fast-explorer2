package com.fastexplorer.ui;

import org.junit.jupiter.api.Test;

import java.awt.event.ActionEvent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchTriggersTest {
    @Test
    void enterWithClosedPopupStartsASearch() {
        ActionEvent edited = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "comboBoxEdited");
        assertTrue(SearchTriggers.enterInClosedField(edited, false, false, true));
    }

    @Test
    void tabOrFocusLossDoesNotStartASearch() {
        ActionEvent edited = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "comboBoxEdited");
        assertFalse(SearchTriggers.enterInClosedField(edited, false, false, false));
    }

    @Test
    void historySelectionAndOpenPopupDoNotStartASearch() {
        ActionEvent changed = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "comboBoxChanged");
        ActionEvent edited = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "comboBoxEdited");
        assertFalse(SearchTriggers.enterInClosedField(changed, false, false, true));
        assertFalse(SearchTriggers.enterInClosedField(edited, false, true, true));
        assertFalse(SearchTriggers.enterInClosedField(edited, true, false, true));
        assertFalse(SearchTriggers.enterInClosedField(null, false, false, true));
    }
}
