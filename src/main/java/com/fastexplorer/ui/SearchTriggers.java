package com.fastexplorer.ui;

import java.awt.event.ActionEvent;

/**
 * When an editable combo should start a search. Selecting a history row, pressing Tab
 * (focus move), or pressing Enter while the popup is open must not count as a search.
 */
public final class SearchTriggers {
    private SearchTriggers() {
    }

    /**
     * True only for an Enter-key edit commit ({@code comboBoxEdited}) with the popup closed
     * and no programmatic text change in progress. Tab / focus-loss commits pass
     * {@code enterKey == false}.
     */
    public static boolean enterInClosedField(
            ActionEvent event,
            boolean adjusting,
            boolean popupVisible,
            boolean enterKey
    ) {
        if (!enterKey || event == null || adjusting || popupVisible) {
            return false;
        }
        return "comboBoxEdited".equals(event.getActionCommand());
    }
}
