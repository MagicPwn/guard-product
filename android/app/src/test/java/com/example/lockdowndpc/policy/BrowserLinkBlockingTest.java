package com.example.lockdowndpc.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.List;
import java.util.Set;

public final class BrowserLinkBlockingTest {
    private static final Set<String> BROWSERS =
            Set.of("com.heytap.browser", "com.android.chrome");

    @Test
    public void noBrowserCandidateIsVerified() {
        assertNull(BrowserLinkBlocking.evaluate(
                "http", List.of("com.example.allowed-app"), BROWSERS, Set.of()));
    }

    @Test
    public void containedBrowserCandidateIsVerified() {
        assertNull(BrowserLinkBlocking.evaluate(
                "https",
                List.of("com.heytap.browser", "com.example.allowed-app"),
                BROWSERS,
                Set.of("com.heytap.browser")));
    }

    @Test
    public void remainingBrowserIsNamedInTheFailure() {
        assertEquals(
                "link-filter-target-unverified:http:com.heytap.browser",
                BrowserLinkBlocking.evaluate(
                        "http",
                        List.of("com.heytap.browser"),
                        BROWSERS,
                        Set.of()));
    }

    @Test
    public void schemeIsRequired() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BrowserLinkBlocking.evaluate("", List.of(), BROWSERS, Set.of()));
    }
}
