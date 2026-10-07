import org.adskip.core.*;
import java.util.*;

/** Behavioral regressions: fake time and publisher observations, no Android or network. */
public final class RuntimeRegressionSelfTest {
    static final BiliVideoKey KEY = new BiliVideoKey("BV1xx411c7mD", "1");
    static int passed, failed;
    static SegmentRule rule(String id, long start, long end) {
        return new SegmentRule(KEY, start, end, 100000, id, "sponsor", SegmentAction.SKIP, "test", "1");
    }
    static SkipEngine.PlaybackSnapshot snap(long gen, long pos, long time, float speed) {
        return new SkipEngine.PlaybackSnapshot("session", KEY, gen, pos, 100000, speed, true, true, time);
    }
    static SkipEngine engine() { return new SkipEngine(new SkipEngine.Policy(1500, 0, 1500)); }
    static void check(boolean result, String message) { if (!result) throw new AssertionError(message); }
    static SkipEngine.Directive dispatch(SkipEngine engine, List<SegmentRule> rules, float speed) {
        SkipEngine.Directive entry = engine.onPlayback(snap(1, 9000, 0, speed), rules);
        return engine.onTimer(snap(1, 10000, (long) (1000 / speed), speed), rules, entry.getTimerPlan().getId());
    }
    static void test(String name, Runnable task) {
        try { task.run(); passed++; System.out.println("PASS " + name); }
        catch (AssertionError ex) { failed++; System.out.println("FAIL " + name + ": " + ex.getMessage()); }
    }
    public static void main(String[] args) {
        test("R1 natural playback is not seek confirmation", () -> {
            SkipEngine e = engine(); List<SegmentRule> rules = List.of(rule("short", 10000, 11000));
            SkipEngine.Directive seek = dispatch(e, rules, 1);
            SkipEngine.Directive result = e.onTimer(snap(1, 11500, 2500, 1), rules, seek.getTimerPlan().getId());
            check(result.getOutcome() != SkipEngine.Outcome.CONFIRMED, "ignored seek falsely confirmed");
            check(!e.isUndoAvailable(snap(1, 11500, 2500, 1)), "false Undo receipt");
        });
        for (long start : new long[]{15000, 20000}) test("R2 connected interval " + start, () -> {
            SkipEngine e = engine(); List<SegmentRule> rules = List.of(rule("a", 10000, 20000), rule("b", start, 30000));
            SkipEngine.Directive seek = dispatch(e, rules, 1);
            check(seek.getSeekTargetMs() == 30000, "must cover connected authorized interval");
            e.onTimer(snap(1, 30000, 2500, 1), rules, seek.getTimerPlan().getId());
            LocalAuditLog.Entry confirmed = e.localAuditLog().entries().stream()
                    .filter(event -> event.getStatus() == LocalAuditLog.Status.CONFIRMED).findFirst().orElseThrow();
            check(confirmed.getRules().size() == 2 && confirmed.getSkippedDurationMs() == 20000,
                    "merged receipt lost original rules or displayed duration");
            e.requestUndo(snap(1, 30100, 2600, 1));
            check(e.isSegmentSuppressedInCurrentCycle("a") && e.isSegmentSuppressedInCurrentCycle("b"), "Undo must suppress every member");
        });
        test("R3 Undo rearms later segment without callback", () -> {
            SkipEngine e = engine(); List<SegmentRule> rules = List.of(rule("a", 10000, 20000), rule("b", 30000, 40000));
            SkipEngine.Directive seek = dispatch(e, rules, 1);
            e.onTimer(snap(1, 20000, 2500, 1), rules, seek.getTimerPlan().getId());
            SkipEngine.Directive undo = e.requestUndo(snap(1, 20100, 2600, 1));
            SkipEngine.Directive result = e.onTimer(snap(2, 10500, 4100, 1), rules, undo.getTimerPlan().getId());
            check(result.getOutcome() == SkipEngine.Outcome.UNDO_CONFIRMED, "Undo must confirm");
            check(result.getTimerPlan() != null && result.getTimerPlan().getSegmentId().equals("b"), "future timer lost");
        });
        test("R4 changed generation replaces confirmation timer", () -> {
            SkipEngine e = engine(); List<SegmentRule> rules = List.of(rule("a", 10000, 20000));
            SkipEngine.Directive seek = dispatch(e, rules, 1);
            SkipEngine.Directive change = e.onPlayback(snap(2, 10200, 1200, 1), rules);
            long activeTimer = change.getTimerPlan() == null ? seek.getTimerPlan().getId() : change.getTimerPlan().getId();
            SkipEngine.Directive result = e.onTimer(snap(2, 11500, 2500, 1), rules, activeTimer);
            check(!result.getReason().equals("stale-timer"), "physical and logical timer diverged");
            check(result.getOutcome() == SkipEngine.Outcome.FAILED, "readback must terminate");
        });
        test("R5 double speed Undo follows elapsed playback", () -> {
            SkipEngine e = engine(); List<SegmentRule> rules = List.of(rule("a", 10000, 20000));
            SkipEngine.Directive seek = dispatch(e, rules, 2);
            e.onTimer(snap(1, 21000, 2000, 2), rules, seek.getTimerPlan().getId());
            SkipEngine.Directive undo = e.requestUndo(snap(1, 21200, 2100, 2));
            SkipEngine.Directive result = e.onTimer(snap(2, 13000, 3600, 2), rules, undo.getTimerPlan().getId());
            check(result.getOutcome() == SkipEngine.Outcome.UNDO_CONFIRMED, "successful 2x Undo rejected");
        });
        test("normal gap is never included in jump", () -> {
            SkipEngine e = engine();
            check(dispatch(e, List.of(rule("a", 10000, 20000), rule("b", 20001, 30000)), 1).getSeekTargetMs() == 20000, "normal content skipped");
        });
        test("stale publisher position never becomes fresh through extrapolation", () -> {
            SkipEngine e = engine(); List<SegmentRule> rules = List.of(rule("a", 10000, 20000));
            SkipEngine.Directive seek = dispatch(e, rules, 1);
            SkipEngine.PlaybackSnapshot stale = new SkipEngine.PlaybackSnapshot("session", KEY, 1,
                    20000, 100000, 1, true, true, 12000, 10000, 1000);
            check(e.onTimer(stale, rules, seek.getTimerPlan().getId()).getOutcome() == SkipEngine.Outcome.FAILED,
                    "old raw sample must not confirm");
        });
        test("unavailable publisher timestamp cannot confirm", () -> {
            SkipEngine e = engine(); List<SegmentRule> rules = List.of(rule("a", 10000, 20000));
            SkipEngine.Directive seek = dispatch(e, rules, 1);
            SkipEngine.PlaybackSnapshot unknown = new SkipEngine.PlaybackSnapshot("session", KEY, 1,
                    20000, 100000, 1, true, true, 2500, 20000, -1);
            check(e.onTimer(unknown, rules, seek.getTimerPlan().getId()).getOutcome() == SkipEngine.Outcome.FAILED,
                    "missing evidence must fail closed");
        });
        test("first publisher callback after dispatch can confirm a real jump", () -> {
            SkipEngine e = engine(); List<SegmentRule> rules = List.of(rule("a", 10000, 20000));
            SkipEngine.Directive entry = e.onPlayback(snap(1, 9000, 0, 1), rules);
            SkipEngine.PlaybackSnapshot joinedLate = new SkipEngine.PlaybackSnapshot("session", KEY, 1,
                    10000, 100000, 1, true, true, 1000, -1, -1);
            SkipEngine.Directive seek = e.onTimer(joinedLate, rules, entry.getTimerPlan().getId());
            SkipEngine.PlaybackSnapshot fresh = new SkipEngine.PlaybackSnapshot("session", KEY, 2,
                    20200, 100000, 1, true, true, 2500, 20000, 2300);
            check(e.onTimer(fresh, rules, seek.getTimerPlan().getId()).getOutcome()
                    == SkipEngine.Outcome.CONFIRMED, "fresh post-dispatch callback rejected after late attachment");
        });
        test("late attachment still rejects pre-dispatch and natural observations", () -> {
            for (long[] evidence : new long[][]{{20000, 900}, {11500, 2500}, {20000, 1000}}) {
                SkipEngine e = engine(); List<SegmentRule> rules = List.of(rule("a", 10000, 20000));
                SkipEngine.Directive entry = e.onPlayback(snap(1, 9000, 0, 1), rules);
                SkipEngine.PlaybackSnapshot joinedLate = new SkipEngine.PlaybackSnapshot("session", KEY, 1,
                        10000, 100000, 1, true, true, 1000, -1, -1);
                SkipEngine.Directive seek = e.onTimer(joinedLate, rules, entry.getTimerPlan().getId());
                SkipEngine.PlaybackSnapshot after = new SkipEngine.PlaybackSnapshot("session", KEY, 1,
                        20000, 100000, 1, true, true, 2500, evidence[0], evidence[1]);
                check(e.onTimer(after, rules, seek.getTimerPlan().getId()).getOutcome()
                        == SkipEngine.Outcome.FAILED, "late attachment admitted non-causal evidence");
            }
        });
        test("temporary suspension preserves Undo suppression", () -> {
            SkipEngine e = engine(); List<SegmentRule> rules = List.of(rule("a", 10000, 20000));
            SkipEngine.Directive seek = dispatch(e, rules, 1);
            e.onTimer(snap(1, 20000, 2500, 1), rules, seek.getTimerPlan().getId());
            e.requestUndo(snap(1, 20100, 2600, 1));
            e.suspend();
            check(e.onPlayback(snap(3, 9000, 6000, 1), rules).getTimerPlan() == null, "suppressed segment rearmed");
            SkipEngine.PlaybackSnapshot other = new SkipEngine.PlaybackSnapshot("other", KEY, 1, 9000,
                    100000, 1, true, true, 7000);
            check(e.onPlayback(other, rules).getTimerPlan() != null, "new session must have independent cycle");
        });
        test("ignored old timer leaves current timer intact", () -> {
            SkipEngine e = engine(); List<SegmentRule> rules = List.of(rule("a", 10000, 20000));
            SkipEngine.Directive entry = e.onPlayback(snap(1, 9000, 0, 1), rules);
            check(e.onTimer(snap(1, 9500, 500, 1), rules, -9).shouldRetainCurrentTimer(), "stale timer cancelled live work");
            check(e.onTimer(snap(1, 10000, 1000, 1), rules, entry.getTimerPlan().getId()).getOutcome()
                    == SkipEngine.Outcome.SEEK, "valid timer lost");
        });
        test("only supplied authorized rules form connected chain", () -> {
            SkipEngine e = engine(); List<SegmentRule> rules = List.of(rule("a", 10000, 20000),
                    rule("b", 15000, 22000), rule("c", 22000, 30000), rule("d", 31000, 40000));
            check(dispatch(e, rules, 1).getSeekTargetMs() == 30000, "chain or normal gap incorrect");
        });
        test("Undo failure still schedules next segment", () -> {
            SkipEngine e = engine(); List<SegmentRule> rules = List.of(rule("a", 10000, 20000), rule("b", 30000, 40000));
            SkipEngine.Directive seek = dispatch(e, rules, 1);
            e.onTimer(snap(1, 20000, 2500, 1), rules, seek.getTimerPlan().getId());
            SkipEngine.Directive undo = e.requestUndo(snap(1, 20100, 2600, 1));
            SkipEngine.Directive result = e.onTimer(snap(1, 21600, 4100, 1), rules, undo.getTimerPlan().getId());
            check(result.getOutcome() == SkipEngine.Outcome.UNDO_FAILED && result.getTimerPlan() != null,
                    "failed Undo lost future schedule");
        });
        test("HTTP errors and malformed results are not empty annotations", () -> {
            for (SegmentFetchResult fetched : List.of(SegmentFetchResult.httpError("test", "1"),
                    SegmentFetchResult.malformed("test", "1"), SegmentFetchResult.empty("test", "1"))) {
                SegmentRepository.Result result = new SegmentRepository(request -> fetched).loadAutomaticSkips(
                        new SegmentRequest(new SessionGeneration("test", KEY, 1)),
                        CidEvidence.exactSinglePage(KEY), 100000, CategoryPolicy.defaults(), ignored -> true);
                check(QueryState.from(result) == (fetched.getKind() == SegmentFetchResult.Kind.HTTP_200_EMPTY
                        ? QueryState.EMPTY : QueryState.SERVICE_ERROR), "query classification wrong");
            }
        });
        test("disabled category cannot extend connected skip", () -> {
            List<CrowdSegmentRecord> records = List.of(
                    new CrowdSegmentRecord(KEY.getBvid(), KEY.getCidString(), "a".repeat(64), "sponsor", "skip", "10", "20", "100"),
                    new CrowdSegmentRecord(KEY.getBvid(), KEY.getCidString(), "b".repeat(64), "filler", "skip", "20", "30", "100"));
            for (boolean enabled : new boolean[]{false, true}) {
                CategoryPolicy policy = CategoryPolicy.defaults().withAutomaticSkipEnabled(CategoryPolicy.Category.FILLER, enabled);
                SegmentRepository.Result result = new SegmentRepository(r -> SegmentFetchResult.json(records, "test", "1"))
                        .loadAutomaticSkips(new SegmentRequest(new SessionGeneration("test", KEY, 1)),
                                CidEvidence.exactSinglePage(KEY), 100000, policy, g -> true);
                check(result.getRules().size() == (enabled ? 2 : 1), "fixture rules did not pass production parser");
                check(dispatch(engine(), result.getRules(), 1).getSeekTargetMs() == (enabled ? 30000 : 20000),
                        "connected interval ignored category policy");
            }
        });
        test("transport failures rearm later rules without a callback", () -> {
            List<SegmentRule> rules = List.of(rule("a", 10000, 20000), rule("b", 30000, 40000));
            SkipEngine e = engine();
            SkipEngine.Directive seek = dispatch(e, rules, 1);
            SkipEngine.Directive failure = e.onSeekDispatchFailed(seek.getAttemptId(), "test",
                    snap(1, 10000, 1000, 1), rules);
            check(failure.getOutcome() == SkipEngine.Outcome.FAILED
                    && failure.getTimerPlan().getSegmentId().equals("b"), "dispatch failure lost next entry");
            check(e.onSeekDispatchFailed(seek.getAttemptId(), "late").shouldRetainCurrentTimer(),
                    "duplicate failure cancelled next timer");
            e = engine(); seek = dispatch(e, rules, 1);
            e.onTimer(snap(1, 20000, 2500, 1), rules, seek.getTimerPlan().getId());
            e.requestUndo(snap(1, 20100, 2600, 1));
            failure = e.onUndoDispatchFailed("test", snap(1, 20100, 2600, 1), rules);
            check(failure.getOutcome() == SkipEngine.Outcome.UNDO_FAILED
                    && failure.getTimerPlan().getSegmentId().equals("b"), "Undo dispatch failure lost next entry");
            check(e.isSegmentSuppressedInCurrentCycle("a"), "failed Undo lost user suppression");
            check(e.onUndoDispatchFailed("late").shouldRetainCurrentTimer(), "late Undo failure cancelled next timer");
        });
        test("suspension cannot repeatedly retry an unconfirmed seek", () -> {
            SkipEngine e = engine(); List<SegmentRule> rules = List.of(rule("a", 10000, 20000));
            dispatch(e, rules, 1); e.suspend();
            check(e.onPlayback(snap(2, 9000, 3000, 1), rules).getTimerPlan() == null,
                    "suspension retried same attempt");
        });
        test("new session and changed speed cannot confirm an old seek", () -> {
            for (boolean newSession : new boolean[]{true, false}) {
                SkipEngine e = engine(); List<SegmentRule> rules = List.of(rule("a", 10000, 20000));
                SkipEngine.Directive seek = dispatch(e, rules, 1);
                SkipEngine.PlaybackSnapshot changed = new SkipEngine.PlaybackSnapshot(
                        newSession ? "other" : "session", KEY, 2, 20000, 100000,
                        newSession ? 1 : 2, true, true, 2500);
                check(e.onTimer(changed, rules, seek.getTimerPlan().getId()).getOutcome()
                        != SkipEngine.Outcome.CONFIRMED, "unproven transition confirmed old seek");
                check(!e.isUndoAvailable(changed), "unproven transition produced receipt");
            }
        });
        System.out.println("Runtime regressions: " + passed + " passed, " + failed + " failed");
        if (failed > 0) throw new AssertionError("runtime regressions failed");
    }
}
