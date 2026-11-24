package com.fastexplorer.ui;

import java.awt.event.ActionEvent;

/**
 * When an editable combo should start a search. Selecting a history row or pressing Enter
 * while the popup is open must not count as a search.
 */
public final class SearchTriggers {
    private SearchTriggers() {
    }

    /**
     * True only for an edit commit ({@code comboBoxEdited}) with the popup closed and no
     * programmatic text change in progress.
     */
    public static boolean enterInClosedField(ActionEvent event, boolean adjusting, boolean popupVisible) {
        if (event == null || adjusting || popupVisible) {
            return false;
        }
        return "comboBoxEdited".equals(event.getActionCommand());
    }
}
