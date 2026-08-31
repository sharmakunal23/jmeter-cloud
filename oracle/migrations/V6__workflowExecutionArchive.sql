-- V6__workflowExecutionArchive.sql — archiving a workflow run (WORKFLOWS, 2026-08-31).
-- Operators accumulate executions fast; archiving gets a finished one out of the
-- way, and deleting is the separate, deliberate second step. The same
-- soft-delete shape ORCH_RUN.HIDDEN_AT already uses, so both surfaces say
-- "Archive" and mean the same thing.

ALTER TABLE ORCH_WORKFLOW_EXECUTION ADD (
    HIDDEN_AT  TIMESTAMP(3) WITH TIME ZONE
);

-- Only a finished execution can be archived: hiding one the engine is still
-- advancing would take it off every list while it kept running.
ALTER TABLE ORCH_WORKFLOW_EXECUTION ADD CONSTRAINT ORCH_WORKFLOW_EXECUTION_HIDDEN_CHK
    CHECK (HIDDEN_AT IS NULL OR STATE <> 'RUNNING');

-- The history reads are "this workflow, newest first, not archived"; putting
-- HIDDEN_AT in the index keeps that a range scan rather than a filter after it.
DROP INDEX ORCH_WORKFLOW_EXECUTION_WF_IDX;
CREATE INDEX ORCH_WORKFLOW_EXECUTION_WF_IDX
    ON ORCH_WORKFLOW_EXECUTION (WORKFLOW_ID, HIDDEN_AT, STARTED_AT DESC);

COMMENT ON COLUMN ORCH_WORKFLOW_EXECUTION.HIDDEN_AT IS
    'Archived when set — hidden from the default history, still readable by id, and the only rows a delete will accept.';
