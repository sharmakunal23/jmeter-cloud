import { Link } from "react-router-dom";

import type { WorkflowValidation } from "../../api/workflows";

/**
 * Peak workers per cluster against what the group reserves.
 *
 * <p>"Peak" is the most the graph can ever want at once — the tasks that can
 * genuinely run together, not the sum of every load test — and `tasks` names
 * them, so a number that looks too high explains itself.
 */
export function CapacityPanel({
  validation, groupId,
}: {
  validation: WorkflowValidation | null;
  groupId: string;
}) {
  if (!validation) {
    return (
      <div className="card">
        <h2>Workers</h2>
        <p className="ink-soft">Checking…</p>
      </div>
    );
  }
  if (validation.capacity.length === 0) {
    return (
      <div className="card">
        <h2>Workers</h2>
        <p className="ink-soft">
          No load tests, so this workflow reserves no workers.
        </p>
      </div>
    );
  }

  return (
    <div className="card">
      <h2>Workers at peak</h2>
      <table className="miniTable">
        <thead>
          <tr>
            <th scope="col">Cluster</th>
            <th scope="col" className="num">Peak</th>
            <th scope="col" className="num">Reserved</th>
          </tr>
        </thead>
        <tbody>
          {validation.capacity.map((c) => (
            <tr key={c.region} className={c.fits ? undefined : "isOver"}>
              <td className="mono">{c.region}</td>
              <td className="num mono" title={c.tasks.length > 0 ? `at once: ${c.tasks.join(" + ")}` : undefined}>
                {c.peakWorkers}
              </td>
              <td className="num mono">{c.reserved}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {validation.capacity.some((c) => !c.fits) ? (
        <p className="ink-warn" style={{ fontSize: "0.85rem" }}>
          Over the reservation — this workflow is refused at launch.{" "}
          <Link to={`/capacity/groups/${encodeURIComponent(groupId)}`}>Reserve more</Link>, or give the
          load tests fewer workers.
        </p>
      ) : (
        <p className="ink-soft" style={{ fontSize: "0.85rem" }}>
          Fits the group's reservation.
        </p>
      )}
    </div>
  );
}
