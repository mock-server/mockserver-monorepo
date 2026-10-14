package org.mockserver.matchers;

/**
 * Per request, how many more EXPECTATION_NOT_MATCHED entries matchers may write under {@code maxLoggedMatchFailures},
 * and how many they withheld once it was used up. Shared by every {@link MatchDifference} of one serving scan,
 * which is single-threaded, so plain fields suffice.
 */
public final class NotMatchedLogBudget {

    private int left;
    private int withheld;

    private NotMatchedLogBudget(int limit) {
        this.left = limit;
    }

    /**
     * A budget of {@code limit} entries, or null (no limit) for a null or negative limit.
     */
    public static NotMatchedLogBudget of(Integer limit) {
        return limit == null || limit < 0 ? null : new NotMatchedLogBudget(limit);
    }

    boolean hasRoom() {
        return left > 0;
    }

    void written() {
        left--;
    }

    void withheld() {
        withheld++;
    }

    /**
     * How many did-not-match entries were withheld because the budget was used up.
     */
    public int getWithheld() {
        return withheld;
    }
}
