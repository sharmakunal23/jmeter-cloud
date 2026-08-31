-- ─────────────────────────────────────────────────────────────────────────────
-- V7 — settle historical workflow executions on the verdict the code now uses.
--
-- Until this migration an execution forgave a FAILED task whose node declared
-- an ON_FAILURE branch, so a run whose load test failed was stored SUCCEEDED
-- while the email that same branch sent said FAILED. The rule is now "any task
-- failed → FAILED", shared by the chip and the email (WorkflowEngine.outcomeOf).
--
-- STATE is a derived summary of ORCH_WORKFLOW_TASK, which this migration does
-- not touch: the record of what happened is intact, and only the summary that
-- disagreed with it is corrected. Rows that already agree are left alone, so
-- re-running this changes nothing.
--
-- STATE_REASON is composed to the same shape WorkflowEngine.terminalReason
-- produces — failures as "name: why" joined by "; ", then the tasks that never
-- ran — and tasks are ordered by NODE_ID because that is the order the
-- repository reads them in.
-- ─────────────────────────────────────────────────────────────────────────────

UPDATE ORCH_WORKFLOW_EXECUTION e
   SET e.STATE = 'FAILED',
       e.STATE_REASON = (
           SELECT SUBSTR(
                    CASE WHEN f.failures IS NOT NULL AND s.skipped IS NOT NULL
                         THEN f.failures || ' | ' || s.skipped
                         ELSE COALESCE(f.failures, s.skipped) END, 1, 4000)
             FROM (SELECT LISTAGG(t.NAME ||
                                  CASE WHEN t.ERROR_REASON IS NULL THEN ''
                                       ELSE ': ' || t.ERROR_REASON END,
                                  '; ') WITHIN GROUP (ORDER BY t.NODE_ID) AS failures
                     FROM ORCH_WORKFLOW_TASK t
                    WHERE t.EXECUTION_ID = e.EXECUTION_ID
                      AND t.STATE = 'FAILED') f,
                  (SELECT CASE WHEN COUNT(*) = 0 THEN NULL
                               ELSE COUNT(*) || ' task(s) did not run: ' ||
                                    LISTAGG(t.NAME, ', ')
                                        WITHIN GROUP (ORDER BY t.NODE_ID) END AS skipped
                     FROM ORCH_WORKFLOW_TASK t
                    WHERE t.EXECUTION_ID = e.EXECUTION_ID
                      AND t.STATE = 'SKIPPED') s
       )
 WHERE e.STATE = 'SUCCEEDED'
   AND EXISTS (SELECT 1
                 FROM ORCH_WORKFLOW_TASK t
                WHERE t.EXECUTION_ID = e.EXECUTION_ID
                  AND t.STATE = 'FAILED');

COMMIT;
