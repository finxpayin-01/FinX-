"use client";

import { useEffect, useState } from "react";
import { useAuth } from "@/components/auth/AuthProvider";
import { TrustScore, trustError, trustService } from "@/services/trust.service";
import { TrustScoreCard } from "./TrustScoreCard";

export function MyTrustScore() {
  const { user } = useAuth();
  const [score, setScore] = useState<TrustScore | null>(null);
  const [error, setError] = useState("");
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (!user) return;
    let active = true;
    trustService.mine().then(({ organizationId }) => trustService.score(organizationId)).then(value => { if (active) { setScore(value); setError(""); } })
      .catch(e => { if (active) setError(trustError(e)); });
    return () => { active = false; };
  }, [user, attempt]);
  if (error) return <div role="alert" className="rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm">{error} <button className="underline" onClick={() => setAttempt(n => n + 1)}>Retry</button></div>;
  return score ? <TrustScoreCard score={score} /> : <p role="status" className="p-4 text-sm text-slate-500">Loading trust score…</p>;
}
