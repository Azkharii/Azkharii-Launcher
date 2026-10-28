package com.cinematic.tvlauncher;

public final class UpdateVersionPolicyTest {
    public static void runTests() {
        check(UpdateVersionPolicy.parseVersionCodeFromTag("v1.0.167") == 167L, "parses legacy generated tags");
        check(UpdateVersionPolicy.parseVersionCodeFromTag("1.2.9") == 9L, "parses unprefixed version");
        check(UpdateVersionPolicy.parseVersionCodeFromTag("v1.0.5+1000000005") == 1000000005L, "parses monotonic code suffix");
        check(UpdateVersionPolicy.parseVersionCodeFromTag("v1.0.1000000004") == 1000000004L, "parses legacy long version tag");
        check(UpdateVersionPolicy.parseVersionCodeFromTag("not-a-version") == -1L, "rejects unversioned tag");
        check(UpdateVersionPolicy.parseVersionCodeFromTag("v1.0.0") == -1L, "rejects zero code");
        check(UpdateVersionPolicy.parseVersionCodeFromTag(null) == -1L, "rejects null tag");
        check(UpdateVersionPolicy.formatDisplayVersion("1.0.1000000003").equals("1.0.3"), "compacts installed legacy versionName");
        check(UpdateVersionPolicy.formatDisplayVersion("v1.0.1000000004").equals("1.0.4"), "compacts legacy release tag");
        check(UpdateVersionPolicy.formatDisplayVersion("v1.0.5+1000000005").equals("1.0.5"), "hides build metadata from new tag");
        check(UpdateVersionPolicy.formatDisplayVersion("Cinematic TV Launcher 1.0.1000000004").equals("1.0.4"), "compacts legacy release title");
        check(UpdateVersionPolicy.formatDisplayVersion("2.1.9").equals("2.1.9"), "preserves ordinary semantic version");
        check(UpdateVersionPolicy.formatDisplayVersion(null).equals("—"), "handles missing display version");
        check(UpdateVersionPolicy.isNewer(1000000005L, 1000000004L), "accepts newer monotonic version code");
        check(!UpdateVersionPolicy.isNewer(1000000004L, 1000000004L), "does not reinstall same version");
        check(!UpdateVersionPolicy.isNewer(1000000003L, 1000000004L), "never downgrades");
        System.out.println("  [PASS] UpdateVersionPolicyTest (15 assertions)");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
