package com.cinematic.tvlauncher;

public final class UpdateVersionPolicyTest {
    public static void runTests() {
        check(UpdateVersionPolicy.parseVersionCodeFromTag("v1.0.167") == 167L, "parses generated release tag");
        check(UpdateVersionPolicy.parseVersionCodeFromTag("1.2.9") == 9L, "parses unprefixed version");
        check(UpdateVersionPolicy.parseVersionCodeFromTag("not-a-version") == -1L, "rejects unversioned tag");
        check(UpdateVersionPolicy.parseVersionCodeFromTag("v1.0.0") == -1L, "rejects zero code");
        check(UpdateVersionPolicy.parseVersionCodeFromTag(null) == -1L, "rejects null tag");
        check(UpdateVersionPolicy.isNewer(168L, 167L), "accepts newer version");
        check(!UpdateVersionPolicy.isNewer(167L, 167L), "does not reinstall same version");
        check(!UpdateVersionPolicy.isNewer(166L, 167L), "never downgrades");
        System.out.println("  [PASS] UpdateVersionPolicyTest (8 assertions)");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
