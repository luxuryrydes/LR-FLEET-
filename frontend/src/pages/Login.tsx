import { motion } from "framer-motion";
import { useState } from "react";
import { useAuth } from "../lib/auth";
import { ErrorBox, Field } from "../components/ui";
import { EASE, riseItem, SPRING, Stagger } from "../components/motion";

const HIGHLIGHTS = ["Monthly mileage", "Document expiry", "Service due", "Fuel ₹ per km"];

export function LoginPage() {
  const { login } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email.trim(), password);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-page">
      <section className="login-brand" aria-hidden="true">
        <motion.span className="login-glow" animate={{ x: [0, 40, 0], y: [0, -30, 0] }} transition={{ duration: 14, repeat: Infinity, ease: "easeInOut" }} />
        <Stagger className="login-brand-inner" delay={0.1} step={0.08}>
          <motion.div variants={riseItem} className="login-mark">LR</motion.div>
          <motion.div variants={riseItem} className="login-overline">LR Taxi Services Pvt Ltd</motion.div>
          <motion.h2 variants={riseItem} className="login-hero">Every vehicle, every kilometre, every rupee — in one place.</motion.h2>
          <motion.p variants={riseItem} className="login-copy">
            Mileage, fuel, maintenance, documents and service for the whole fleet, live from the office.
          </motion.p>
          <motion.div variants={riseItem} className="login-pills">
            {HIGHLIGHTS.map((h) => <span key={h} className="login-pill">{h}</span>)}
          </motion.div>
        </Stagger>
      </section>

      <div className="login-side">
        <motion.form className="login-card" onSubmit={submit} initial={{ opacity: 0, y: 24, scale: 0.98 }} animate={{ opacity: 1, y: 0, scale: 1 }}
                     transition={{ ...SPRING, delay: 0.15 }}>
          <img src="/favicon.svg" alt="" width={44} height={44} className="login-logo" />
          <h1>Welcome back</h1>
          <p className="muted">Sign in to LR Fleet Manager with your own account.</p>
          <ErrorBox error={error} />
          <Field label="Email" required>
            <input type="email" autoComplete="username" placeholder="name@company.com" value={email} onChange={(e) => setEmail(e.target.value)} required />
          </Field>
          <Field label="Password" required hint="5 wrong tries lock the account for 15 minutes.">
            <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          </Field>
          <div className="form-actions">
            <motion.button className="btn btn-primary btn-block btn-lg" disabled={busy} whileHover={busy ? undefined : { y: -1 }} transition={{ duration: 0.2, ease: EASE }}>
              {busy ? <><span className="spin spin-light" aria-hidden="true" /> Signing in…</> : "Sign in"}
            </motion.button>
          </div>
        </motion.form>
      </div>
    </div>
  );
}
