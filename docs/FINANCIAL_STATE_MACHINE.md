# FINX — Financial State Machines & Transition Validation

## 1. Golden Rule of Financial Transitions
> FinX NEVER marks a transaction successful merely because a frontend request asserts success. Only verified server-side bank confirmation (via webhook signature or authenticated polling) can transition financial states.

---

## 2. Deal Lifecycle State Machine

```
   [DRAFT] ───────────────► [INVITED]
      │                         │
      ▼                         ▼
[NEGOTIATING] ─────────► [AWAITING_SIGNATURE]
                                │ Both parties sign
                                ▼
                           [ACTIVE]
                                │ Bank confirms deposit
                                ▼
                            [FUNDED]
                                │ Milestones in progress
                                ▼
                          [IN_PROGRESS]
                                │ All milestones settled
                                ▼
                           [COMPLETED]

Branches:
Any active stage ────────► [DISPUTED] ───► [RESOLVED / COMPLETED]
Any non-funded stage ────► [CANCELLED]
```

---

## 3. Milestone Lifecycle State Machine

```
  [DRAFT]
     │
     ▼
[PENDING_FUNDING] ──────► [FUNDED]
                             │ Work started
                             ▼
                        [IN_PROGRESS]
                             │ Deliverables submitted
                             ▼
                         [SUBMITTED]
                             │ Under buyer review
                             ▼
                        [UNDER_REVIEW]
                        ┌────┴────┐
           Approved by  │         │ Rejected by
           Buyer        ▼         ▼ Buyer
                    [APPROVED]   [REJECTED] ──► [IN_PROGRESS]
                        │
                        │ 10-Gate Settlement Disbursed
                        ▼
                   [COMPLETED]
```

---

## 4. Payment Instruction State Machine

```
   [CREATED]
       │
       ▼
  [SUBMITTED]
       │
       ▼
 [PROCESSING]
   ┌───┴───┐
   │       │
   ▼       ▼
[SUCCESS] [FAILED]
   │
   ▼
[REVERSED] (via dispute/chargeback)
```

Illegal transitions (e.g., `FAILED -> SUCCESS`) are strictly rejected by the backend state validator unless explicitly part of a formal administrative recovery run.

---

## 5. Settlement Instruction State Machine

```
   [PENDING]
       │
       ▼
 [PROCESSING]
   ┌───┴───┐
   │       │
   ▼       ▼
[SETTLED] [FAILED]
   │
   ▼
[REVERSED]
```
