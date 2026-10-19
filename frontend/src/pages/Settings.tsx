import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, type Row } from "../lib/api";
import { can, useAuth } from "../lib/auth";
import { dateTime } from "../lib/format";
import { Card, ErrorBox, Notice, PageHeader, Spinner } from "../components/ui";

export function SettingsPage() {
  const { user } = useAuth();
  const admin = can(user, "admin");
  const qc = useQueryClient();
  const r = useQuery({ queryKey: ["settings"], queryFn: () => api.get<Row[]>("/api/settings") });
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [error, setError] = useState<unknown>(null);
  const [saved, setSaved] = useState<string | null>(null);

  const save = async (key: string) => {
    setError(null);
    setSaved(null);
    try {
      await api.put(`/api/settings/${encodeURIComponent(key)}`, { value: edits[key] });
      setSaved(key);
      setEdits((e) => {
        const n = { ...e };
        delete n[key];
        return n;
      });
      qc.invalidateQueries({ queryKey: ["settings"] });
    } catch (e) {
      setError(e);
    }
  };

  const groups = new Map<string, Row[]>();
  for (const s of r.data ?? []) {
    const g = (s.settingKey as string).split(".")[0];
    groups.set(g, [...(groups.get(g) ?? []), s]);
  }
  const titles: Record<string, string> = { service: "Service intervals", gps: "GPS data", fuel: "Fuel accounting & limits", mileage: "Mileage plausibility & benchmarks",
    alert: "Alert thresholds", docs: "Document reminders" };

  return (
    <>
      <PageHeader title="Business-rule settings" subtitle={admin ? "Changes apply to new calculations immediately and are audited." : "Read-only. Ask an administrator to change thresholds."} />
      <ErrorBox error={error ?? r.error} />
      {saved && <Notice kind="success">Saved {saved}. Use “Recalculate” on a vehicle or wait for the nightly run for alerts to reflect it.</Notice>}
      {r.isLoading && <Spinner />}
      {Array.from(groups.entries()).map(([g, rows]) => (
        <Card key={g} title={titles[g] ?? g}>
          <table className="table">
            <thead><tr><th>Setting</th><th>Description</th><th style={{ width: 220 }}>Value</th><th>Updated</th>{admin && <th />}</tr></thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.settingKey}>
                  <td className="mono small">{s.settingKey}</td>
                  <td>{s.description}</td>
                  <td>
                    {admin ? (
                      <input aria-label={s.settingKey} value={edits[s.settingKey] ?? s.settingValue}
                             onChange={(e) => setEdits({ ...edits, [s.settingKey]: e.target.value })} />
                    ) : <strong>{s.settingValue}</strong>}
                  </td>
                  <td className="small muted">{dateTime(s.updatedAt)}</td>
                  {admin && <td><button className="btn btn-small" disabled={edits[s.settingKey] === undefined} onClick={() => save(s.settingKey)}>Save</button></td>}
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      ))}
    </>
  );
}
