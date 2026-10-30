import { useState } from "react";
import { NavLink, Outlet, useLocation } from "react-router-dom";
import { can, useAuth, type Permission } from "../lib/auth";
import { label } from "../lib/format";
import { Icon } from "./Icon";

const NAV: { to: string; label: string; icon: string; perm?: Permission; section?: string }[] = [
  { to: "/", label: "Dashboard", icon: "dashboard", section: "Overview" },
  { to: "/monthly-mileage", label: "Monthly mileage", icon: "gauge" },
  { to: "/alerts", label: "Alerts", icon: "bell" },
  { to: "/vehicles", label: "Vehicles", icon: "car", section: "Fleet" },
  { to: "/gps", label: "GPS kilometres", icon: "route" },
  { to: "/fuel", label: "Fuel & energy", icon: "fuel" },
  { to: "/maintenance", label: "Maintenance", icon: "wrench" },
  { to: "/service-due", label: "Vehicle service", icon: "service" },
  { to: "/documents", label: "Vehicle documents", icon: "file" },
  { to: "/imports", label: "Imports", icon: "upload", perm: "write", section: "Data" },
  { to: "/reports", label: "Reports", icon: "chart" },
  { to: "/masters", label: "Drivers & vendors", icon: "users" },
  { to: "/users", label: "Users & roles", icon: "shield", perm: "admin", section: "Admin" },
  { to: "/settings", label: "Settings", icon: "settings", perm: "read" },
  { to: "/audit", label: "Audit log", icon: "list", perm: "manage" },
];

export function Layout() {
  const { user, logout } = useAuth();
  const [open, setOpen] = useState(false);
  const { pathname } = useLocation();
  const current = [...NAV].reverse().find((n) => (n.to === "/" ? pathname === "/" : pathname.startsWith(n.to)));
  return (
    <div className={`shell ${open ? "nav-open" : ""}`}>
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark"><Icon name="taxi" size={20} strokeWidth={2} /></span>
          <div>
            <strong>LR Fleet</strong>
            <span>LR Taxi Services Pvt Ltd</span>
          </div>
        </div>
        <nav onClick={() => setOpen(false)}>
          {NAV.filter((n) => !n.perm || can(user, n.perm)).map((n) => (
            <div key={n.to}>
              {n.section && <div className="nav-section">{n.section}</div>}
              <NavLink to={n.to} end={n.to === "/"} className={({ isActive }) => (isActive ? "nav-link active" : "nav-link")}>
                <span className="nav-icon" aria-hidden="true"><Icon name={n.icon} /></span>
                {n.label}
              </NavLink>
            </div>
          ))}
        </nav>
        <div className="sidebar-foot">Fleet mileage, fuel &amp; maintenance</div>
      </aside>
      <div className="main">
        <header className="topbar">
          <button className="btn-icon menu-btn" onClick={() => setOpen(!open)} aria-label="Menu"><Icon name="menu" size={22} /></button>
          <div className="topbar-title">{current?.label ?? ""}</div>
          <div className="spacer" />
          <div className="user-chip">
            <span className="avatar">{user?.fullName?.charAt(0) ?? "?"}</span>
            <div>
              <div className="name">{user?.fullName}</div>
              <div className="muted small">{user?.roles.map((r) => label(r)).join(", ")}</div>
            </div>
          </div>
          <button className="btn btn-small" onClick={logout}><Icon name="logout" size={15} /> Sign out</button>
        </header>
        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
