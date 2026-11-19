package com.example.lockdowndpc.policy;

import java.util.Collection;
import java.util.Set;

/**
 * Decides whether a resolved HTTP/HTTPS link can reach an unusable browser.
 *
 * <p>Android may keep returning a browser from the resolver after the package
 * has been hidden or suspended. Those states prevent the browser from
 * launching, so the audit treats them as contained instead of requiring the
 * resolver to name the Guard page.
 */
public final class BrowserLinkBlocking {
    public static final String TARGET_UNVERIFIED_ERROR = "link-filter-target-unverified:";

    private BrowserLinkBlocking() {}

    /** Returns a failure for a live browser candidate, or null when contained. */
    public static String evaluate(
            String scheme,
            Collection<String> resolvedPackages,
            Set<String> browserPackages,
            Set<String> containedBrowserPackages
    ) {
        if (scheme == null || scheme.isBlank()) {
            throw new IllegalArgumentException("A scheme is required");
        }
        if (resolvedPackages == null || browserPackages == null) {
            return null;
        }
        for (String packageName : resolvedPackages) {
            if (packageName != null
                    && browserPackages.contains(packageName)
                    && (containedBrowserPackages == null
                        || !containedBrowserPackages.contains(packageName))) {
                return TARGET_UNVERIFIED_ERROR + scheme + ":" + packageName;
            }
        }
        return null;
    }
}
