package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DispatchStatusTest {

    @Test
    void terminalStatesAreTerminal() {
        assertTrue(DispatchStatus.isTerminal(DispatchStatus.SUCCEEDED));
        assertTrue(DispatchStatus.isTerminal(DispatchStatus.FAILED));
        assertTrue(DispatchStatus.isTerminal(DispatchStatus.TIMEOUT));
        assertTrue(DispatchStatus.isTerminal(DispatchStatus.CANCELED));
    }

    @Test
    void nonTerminalStatesAreNotTerminal() {
        assertFalse(DispatchStatus.isTerminal(DispatchStatus.PENDING));
        assertFalse(DispatchStatus.isTerminal(DispatchStatus.PACKAGING));
        assertFalse(DispatchStatus.isTerminal(DispatchStatus.DISPATCHED));
        assertFalse(DispatchStatus.isTerminal(DispatchStatus.ACKED));
        assertFalse(DispatchStatus.isTerminal(DispatchStatus.RUNNING));
    }

    @Test
    void nullIsNotTerminal() {
        assertFalse(DispatchStatus.isTerminal(null));
    }
}
