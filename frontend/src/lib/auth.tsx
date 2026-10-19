import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, getToken, setToken, setUnauthorizedHandler } from "./api";

export type Role = "ADMIN" | "MANAGER" | "OPERATOR" | "VIEWER";

export interface User {
  id: number;
  email: string;
  fullName: string;
  roles: Role[];
}

/**
 * UI permissions mirror the server's @PreAuthorize rules (security/Roles.java). They only hide controls;
 * the server enforces every rule independently.
 */
export type Permission = "read" | "write" | "manage" | "admin";

export function can(user: Pick<User, "roles"> | null | undefined, p: Permission): boolean {
  if (!user) return false;
  const r = new Set(user.roles);
  switch (p) {
    case "admin":
      return r.has("ADMIN");
    case "manage":
      return r.has("ADMIN") || r.has("MANAGER");
    case "write":
      return r.has("ADMIN") || r.has("MANAGER") || r.has("OPERATOR");
    case "read":
      return r.size > 0;
  }
}

interface AuthState {
  user: User | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

/** Exported for tests, which supply a signed-in user directly. */
export const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(Boolean(getToken()));

  const logout = useCallback(() => {
    setToken(null);
    setUser(null);
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(logout);
    if (!getToken()) return;
    api
      .get<User>("/api/auth/me")
      .then(setUser)
      .catch(() => logout())
      .finally(() => setLoading(false));
  }, [logout]);

  const login = useCallback(async (email: string, password: string) => {
    const res = await api.post<{ token: string; user: User }>("/api/auth/login", { email, password });
    setToken(res.token);
    setUser(res.user);
  }, []);

  const value = useMemo(() => ({ user, loading, login, logout }), [user, loading, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth outside AuthProvider");
  return ctx;
}

/** Renders children only when the signed-in user has the permission. */
export function Can({ perm, children, fallback = null }: { perm: Permission; children: ReactNode; fallback?: ReactNode }) {
  const { user } = useAuth();
  return <>{can(user, perm) ? children : fallback}</>;
}
