package com.hmdp.service;

import com.hmdp.config.ChatBusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OperatorAuthServiceTest {

    @Test
    void shouldAcceptMatchingOperatorKey() {
        OperatorAuthService service = new OperatorAuthService("operator-secret");
        assertDoesNotThrow(() -> service.verify("operator-secret"));
    }

    @Test
    void shouldRejectMissingOrInvalidOperatorKey() {
        assertThrows(ChatBusinessException.class,
                () -> new OperatorAuthService("").verify("anything"));
        assertThrows(ChatBusinessException.class,
                () -> new OperatorAuthService("operator-secret").verify("wrong"));
    }
}
