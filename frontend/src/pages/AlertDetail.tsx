import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, type Row } from "../lib/api";
import { Can, can, useAuth } from "../lib/auth";
import { date, dateTime, label } from "../lib/format";
import { Badge, Card, ErrorBox, Field, PageHeader, Spinner } from "../components/ui";

function linkFor(c: Row, vehicleId?: number): string | null {
  switch (c.type) {
    case "VEHICLE": return `/vehicles/${c.id}`;
    case "IMPORT_BATCH": return `/imports/${c.id}`;
    case "FUEL_TRANSACTION": return vehicleId ? `/vehicles/${vehicleId}` : "/fuel";
    case "GPS_KM": return `/gps?vehicle=&from=&to=`;
    case "MAINTENANCE_RECORD": return vehicleId ? `/vehicles/${vehicleId}` : "/maintenance";
    case "EFFICIENCY_INTERVAL": return vehicleId ? `/vehicles/${vehicleId}` : null;
    case "ODOMETER_READING": return vehicleId ? `/vehicles/${vehicleId}` : null;
    case "DOCUMENT": return vehicleId ? `/documents?vehicleId=${vehicleId}` : "/documents";
    default: return null;
  }
}

export function AlertDetailPage() {
  const { id } = useParams();
  const { user } = useAuth();
  const qc = useQueryClient();
  const a = useQuery({ queryKey: ["alert", id], queryFn: () => api.get<Row>(`/api/alerts/${id}`) });
  const users = useQuery({ queryKey: ["users"], queryFn: () => api.get<Row[]>("/api/users"), enabled: can(user, "admin") });
  const [note, setNote] = useState("");
  const [assignee, setAssignee] = useState("");
  const [error, setError] = useState<unknown>(null);

  const act = async (path: string, body: Row) => {
    setError(null);
    try {
      await api.post(`/api/alerts/${id}/${path}`, body);
      setNote("");
      qc.invalidateQueries({ queryKey: ["alert", id] });
      qc.invalidateQueries({ queryKey: ["alerts"] });
    } catch (e) {
      setError(e);
    }
  };

  if (a.isLoading) return <Spinner />;
  if (!a.data) return <ErrorBox error={a.error} />;
  const d = a.data;
  const rule = d.ruleExplanation as Row;
  const open = ["OPEN", "ACKNOWLEDGED", "IN_PROGRESS"].includes(d.status);
  return (
    <>
      <PageHeader title={d.title} subtitle={`${label(d.alertType)} · first detected ${dateTime(d.firstDetectedAt)} · last ${dateTime(d.lastDetectedAt)}`}
        actions={<><Badge value={d.severity} /><Badge value={d.status} /></>} />
      <ErrorBox error={error} />
      <div className="grid grid-2">
        <Card title="Why this alert was triggered">
          <p>{d.message}</p>
          <dl className="kv">
            <dt>Rule</dt><dd>{rule?.rule}</dd>
            <dt>Threshold</dt><dd>{rule?.threshold}</dd>
            <dt>Observed</dt><dd><strong>{rule?.observed}</strong></dd>
            {rule?.period && <><dt>Window</dt><dd>{date(rule.period.from)} to {date(rule.period.to)}</dd></>}
            {d.vehicleId && <><dt>Vehicle</dt><dd><Link to={`/vehicles/${d.vehicleId}`} className="mono">{d.registrationNumber}</Link></dd></>}
            <dt>Assigned to</dt><dd>{d.assignedToName ?? "—"}</dd>
            {d.resolutionNote && <><dt>Resolution</dt><dd>{d.resolutionNote} {d.resolvedByName && `(${d.resolvedByName}, ${dateTime(d.resolvedAt)})`}</dd></>}
          </dl>
          <p className="muted small">Alerts flag data for investigation. Check the contributing records and data quality before drawing conclusions.</p>
        </Card>
        <Card title="Contributing records">
          {(d.contributingRecords as Row[]).length === 0 && <span className="muted">None recorded.</span>}
          <ul>
            {(d.contributingRecords as Row[]).map((c, i) => {
              const to = linkFor(c, d.vehicleId);
              return <li key={i}>{to ? <Link to={to}>{c.label}</Link> : c.label} <span className="muted small">({label(c.type)} #{c.id})</span></li>;
            })}
          </ul>
        </Card>
      </div>
      <Card title="Investigation">
        <Can perm="write">
          <Field label="Note"><textarea value={note} onChange={(e) => setNote(e.target.value)} placeholder="What was checked, who was contacted, outcome…" /></Field>
          <div className="actions" style={{ marginTop: 10 }}>
            <button className="btn" disabled={!note.trim()} onClick={() => act("notes", { note })}>Add note</button>
            {d.status === "OPEN" && <button className="btn" onClick={() => act("acknowledge", { note })}>Acknowledge</button>}
            <Can perm="manage">
              {can(user, "admin") && users.data && (
                <>
                  <select value={assignee} onChange={(e) => setAssignee(e.target.value)} style={{ width: 200 }} aria-label="Assign to">
                    <option value="">Assign to…</option>
                    {users.data.filter((u) => u.isActive).map((u) => <option key={u.id} value={u.id}>{u.fullName}</option>)}
                  </select>
                  <button className="btn" disabled={!assignee} onClick={() => act("assign", { userId: Number(assignee), note })}>Assign</button>
                </>
              )}
              {!can(user, "admin") && <button className="btn" onClick={() => act("assign", { userId: user!.id, note })}>Assign to me</button>}
              {open && <button className="btn btn-primary" disabled={!note.trim()} onClick={() => act("status", { status: "RESOLVED", note })}>Resolve</button>}
              {open && <button className="btn" disabled={!note.trim()} onClick={() => act("status", { status: "DISMISSED", note })}>Dismiss</button>}
              {!open && <button className="btn" onClick={() => act("status", { status: "OPEN", note })}>Re-open</button>}
            </Can>
          </div>
        </Can>
        <h3 style={{ fontSize: 14, marginTop: 18 }}>History</h3>
        <table className="table">
          <thead><tr><th>When</th><th>Event</th><th>By</th><th>Note</th></tr></thead>
          <tbody>
            {(d.events as Row[]).map((e) => (
              <tr key={e.id}><td>{dateTime(e.createdAt)}</td><td><Badge value={e.eventType === "RESOLVED" ? "RESOLVED" : "HEADERS_READY"} text={label(e.eventType)} /></td><td>{e.userName ?? "System"}</td><td>{e.note ?? "—"}</td></tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}
