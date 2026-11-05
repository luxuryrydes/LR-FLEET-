import { animate, motion, useReducedMotion } from "framer-motion";
import { useEffect, useRef, useState, type ReactNode } from "react";

/** One easing for the whole app: quick start, soft landing (matches the Figma prototype). */
export const EASE = [0.22, 1, 0.36, 1] as const;
export const SPRING = { type: "spring", stiffness: 420, damping: 32, mass: 0.8 } as const;

/** Each page fades and rises in when the route changes. */
export function PageTransition({ id, children }: { id: string; children: ReactNode }) {
  // a new page always opens at its top, not at the scroll position of the page before
  useEffect(() => { window.scrollTo({ top: 0 }); }, [id]);
  return (
    <motion.div key={id} initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.32, ease: EASE }}>
      {children}
    </motion.div>
  );
}

/** Children rise in one after another (dashboard tiles, login panel). */
export function Stagger({ children, className, delay = 0, step = 0.06 }: { children: ReactNode; className?: string; delay?: number; step?: number }) {
  return (
    <motion.div className={className} initial="hidden" animate="show"
                variants={{ hidden: {}, show: { transition: { staggerChildren: step, delayChildren: delay } } }}>
      {children}
    </motion.div>
  );
}

export const riseItem = {
  hidden: { opacity: 0, y: 14 },
  show: { opacity: 1, y: 0, transition: { duration: 0.4, ease: EASE } },
};

/**
 * Text like "12,345 km", "₹6.42" or "17" counts up from zero to its number; anything without a number is shown as is.
 * The final text is always exactly the text given, so formatting never drifts.
 */
export function CountUp({ text, duration = 0.9 }: { text: string; duration?: number }) {
  const reduce = useReducedMotion();
  const m = /^(\D*?)(-?[\d,]+(?:\.\d+)?)(.*)$/s.exec(text);
  const [shown, setShown] = useState(reduce || !m ? text : `${m[1]}0${m[3]}`);
  const last = useRef(text);
  useEffect(() => {
    if (reduce || !m) { setShown(text); return; }
    const target = Number(m[2].replace(/,/g, ""));
    const decimals = m[2].includes(".") ? m[2].split(".")[1].length : 0;
    const grouped = m[2].includes(",");
    const fmt = (v: number) => (grouped ? v.toLocaleString("en-IN", { minimumFractionDigits: decimals, maximumFractionDigits: decimals }) : v.toFixed(decimals));
    const from = last.current === text ? 0 : Number((/-?[\d,]+(?:\.\d+)?/.exec(last.current)?.[0] ?? "0").replace(/,/g, ""));
    last.current = text;
    const c = animate(from, target, { duration, ease: EASE, onUpdate: (v) => setShown(`${m[1]}${fmt(v)}${m[3]}`), onComplete: () => setShown(text) });
    return () => c.stop();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text, reduce]);
  return <>{shown}</>;
}

const PRESSABLE = ".btn, .kpi, .nav-link, .tab, .doc-btn, .link-btn, .icon-btn, .status-legend-item, .attention-item, .fuel-card";

/**
 * Press feedback for every button and tile in the app, without touching each page: a short spring "squeeze" on
 * pointer-down, driven by framer-motion. Skipped for disabled buttons and when the user prefers reduced motion.
 */
export function usePressFeedback() {
  useEffect(() => {
    const reduce = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    if (reduce) return;
    const onDown = (e: PointerEvent) => {
      const el = (e.target as HTMLElement | null)?.closest?.(PRESSABLE) as HTMLElement | null;
      if (!el || (el as HTMLButtonElement).disabled) return;
      animate(el, { scale: [1, 0.965, 1] }, { duration: 0.28, ease: EASE });
    };
    document.addEventListener("pointerdown", onDown);
    return () => document.removeEventListener("pointerdown", onDown);
  }, []);
}
