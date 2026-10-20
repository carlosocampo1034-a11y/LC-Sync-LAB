package com.lc.technician;

import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LoginPinTest {

    @Test
    public void emptyPinIsRejected() {
        assertNull(LoginActivity.resolveRole(""));
    }

    @Test
    public void nullPinIsRejected() {
        assertNull(LoginActivity.resolveRole(null));
    }

    @Test
    public void unknownPinIsRejected() {
        assertNull(LoginActivity.resolveRole("not-a-pin"));
    }
}
