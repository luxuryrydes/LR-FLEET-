import type { ReactNode } from "react";
import type { Row } from "../lib/api";
import { Empty, Spinner } from "./ui";
import { SelectAll, SelectOne, type Picked, type Selection } from "./VehicleDelete";

export interface Column {
  key: string;
  header: string;
  render?: (row: Row) => ReactNode;
  sortable?: boolean;
  align?: "right" | "left" | "center";
  width?: string;
}

interface Props {
  columns: Column[];
  rows: Row[] | undefined;
  loading?: boolean;
  total?: number;
  page?: number;
  size?: number;
  sort?: string;
  onSort?: (sort: string) => void;
  onPage?: (page: number) => void;
  onRowClick?: (row: Row) => void;
  rowKey?: (row: Row, i: number) => string | number;
  empty?: ReactNode;
  /** Adds a tick-box column; `pick` says which vehicle a row is. */
  select?: { sel: Selection; pick: (row: Row) => Picked };
}

/** Server-side paginated, sortable table: the browser only ever holds one page of records. */
export function DataTable({ columns, rows, loading, total, page = 0, size = 25, sort, onSort, onPage, onRowClick, rowKey, empty, select }: Props) {
  const [sortKey, sortDir] = (sort ?? "").split(",");
  const pages = total !== undefined ? Math.max(1, Math.ceil(total / size)) : 1;
  return (
    <div className="table-wrap">
      <div className="table-scroll">
        <table className="table">
          <thead>
            <tr>
              {select && <th className="check-col"><SelectAll sel={select.sel} rows={(rows ?? []).map(select.pick)} /></th>}
              {columns.map((c) => {
                const active = sortKey === c.key;
                return (
                  <th key={c.key} style={{ textAlign: c.align ?? "left", width: c.width }}>
                    {c.sortable && onSort ? (
                      <button className="th-sort" onClick={() => onSort(`${c.key},${active && sortDir !== "desc" ? "desc" : "asc"}`)}>
                        {c.header}
                        <span className="sort-ind" aria-hidden="true">{active ? (sortDir === "desc" ? "▼" : "▲") : "↕"}</span>
                      </button>
                    ) : (
                      c.header
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {rows?.map((r, i) => (
              <tr key={rowKey ? rowKey(r, i) : (r.id as number) ?? i} onClick={onRowClick ? () => onRowClick(r) : undefined}
                  className={[onRowClick ? "clickable" : "", select && select.sel.has(select.pick(r).id) ? "row-picked" : ""].join(" ").trim() || undefined}>
                {select && <td className="check-col"><SelectOne sel={select.sel} v={select.pick(r)} /></td>}
                {columns.map((c) => (
                  <td key={c.key} style={{ textAlign: c.align ?? "left" }}>
                    {c.render ? c.render(r) : r[c.key] ?? "—"}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        {loading && <Spinner />}
        {!loading && rows && rows.length === 0 && (empty ?? <Empty />)}
      </div>
      {total !== undefined && onPage && (
        <div className="pager">
          <span className="muted">
            {total === 0 ? "0 records" : `${(page * size + 1).toLocaleString("en-IN")}–${Math.min(total, (page + 1) * size).toLocaleString("en-IN")} of ${total.toLocaleString("en-IN")}`}
          </span>
          <div className="pager-buttons">
            <button className="btn btn-small" disabled={page <= 0} onClick={() => onPage(page - 1)}>‹ Previous</button>
            <span className="muted">Page {page + 1} of {pages}</span>
            <button className="btn btn-small" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>Next ›</button>
          </div>
        </div>
      )}
    </div>
  );
}
