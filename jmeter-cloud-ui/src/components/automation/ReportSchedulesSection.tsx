import { useState } from "react";

import { type CronJobSummary } from "../../api/automation";
import { CreateReportScheduleDialog } from "../CreateReportScheduleDialog";
import { EmailPreviewModal } from "../EmailPreviewModal";
import { SchedulesSection } from "./SchedulesSection";

/**
 * Platform-wide report schedules (INFRA_READINESS + DAILY_REPORT) — the
 * Automation page's middle section.
 *
 * <p>The only section with its own row action: <b>Preview</b> renders the email
 * exactly as the fire would send it, because "send it and look" is not an
 * acceptable way to check a report that goes to a distribution list.
 */

const KIND_LABEL: Record<string, string> = {
  INFRA_READINESS: "Infra readiness",
  DAILY_REPORT: "Daily report",
};

export function ReportSchedulesSection({ jobs, onChanged }: {
  jobs: CronJobSummary[];
  onChanged: () => void;
}) {
  const [previewJob, setPreviewJob] = useState<CronJobSummary | null>(null);

  return (
    <>
      <SchedulesSection
        title="Platform reports"
        info="Emails a platform-wide report on a schedule."
        addLabel="Report schedule"
        empty={<>No report schedules. Add one to email the daily infra-readiness report (all backends plus
               every application&apos;s 24h health) or the daily performance report.</>}
        jobs={jobs}
        columns={[
          { header: "Report", cell: (j) => KIND_LABEL[j.kind] ?? j.kind },
          {
            header: "Recipients",
            className: "schedulesSection__recipients",
            cell: (j) => j.recipients ? j.recipients : <em className="ink-soft">server default</em>,
          },
        ]}
        fireLabel="Send now"
        fireBody={<>This emails the report immediately, in addition to its normal cadence.</>}
        deleteBody={<>This permanently removes the report schedule. This can&apos;t be undone.</>}
        rowExtras={(j, busy) => (
          <button type="button" className="btn btn--ghost btn--sm" disabled={busy}
                  onClick={() => setPreviewJob(j)} title="See what this email looks like">
            Preview
          </button>
        )}
        renderDialog={({ editing, onClose, onSaved }) => (
          <CreateReportScheduleDialog editing={editing} onClose={onClose} onCreated={onSaved} />
        )}
        onChanged={onChanged}
      />

      {previewJob && (
        <EmailPreviewModal
          kind={previewJob.kind}
          customSubject={previewJob.customSubject ?? undefined}
          customIntro={previewJob.customIntro ?? undefined}
          onClose={() => setPreviewJob(null)}
        />
      )}
    </>
  );
}
