# FINX

### Milestone-Based Payments for Modern Businesses

FINX helps businesses manage project payments with confidence by connecting **milestones, deliverables, approvals, and payments** in one transparent workflow.

> **Build trust. Fund progress. Release with confidence.**

---

## 🚀 Why FINX?

B2B projects often fail because payment and delivery are disconnected.

Businesses face:

* Unclear project progress
* Delayed vendor payments
* Upfront payment risk
* Manual invoice and milestone tracking
* Poor visibility into fund movement
* Difficult dispute resolution

FINX brings all project stakeholders into one controlled payment workflow.

---

## 💡 How It Works

```text
Create Project
      ↓
Define Milestones
      ↓
Assign Vendor
      ↓
Fund Milestone
      ↓
Vendor Submits Work
      ↓
Project Manager Approves
      ↓
Payment Release Authorized
      ↓
Vendor Gets Paid
```

FINX is designed to make every payment traceable to a specific project milestone.

---

## 👥 Built for Every Stakeholder

| Role                    | Main Responsibility                                     |
| ----------------------- | ------------------------------------------------------- |
| **Corporate / Buyer**   | Creates projects, funds milestones, and tracks payments |
| **Vendor / Freelancer** | Completes work and submits deliverables                 |
| **Project Manager**     | Reviews work and approves milestones                    |
| **FINX Admin**          | Manages releases, disputes, and platform operations     |
| **Finance Team**        | Tracks transactions, settlements, and reports           |

---

## ✨ Core Features

### Project Management

* Create and manage B2B projects
* Assign vendors
* Define budgets and deadlines
* Track project status
* View project participants

### Milestone-Based Workflow

* Create project milestones
* Start and submit work
* Upload deliverables
* Review and approve submissions
* Reject or request changes
* Track milestone completion

### Payment Management

* Create payment orders
* Integrate Razorpay Checkout for fiat
* Fiat-to-crypto on-ramp via Onramper
* Verify payments and webhooks server-side
* Blockchain settlement verification via Web3j
* Track payment status and history

### Escrow-Style Release Workflow

* Connect payments to milestones
* Require project approval
* Authorize releases through operations
* Prevent duplicate releases
* Track release history
* Manage disputes

### Admin and Finance

* User management
* Project monitoring
* Payment monitoring
* Release management
* Transaction reconciliation
* Settlement tracking
* Invoice records
* Financial reports
* Immutable audit logs

---

## 🏗️ Product Architecture

```text
                         ┌──────────────────────┐
                         │      FINX Web App    │
                         │       Next.js        │
                         └──────────┬───────────┘
                                    │
                                    ▼
                         ┌──────────────────────┐
                         │      REST APIs       │
                         │     Spring Boot      │
                         └──────────┬───────────┘
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        ▼                           ▼                           ▼
┌─────────────────┐       ┌─────────────────┐       ┌─────────────────┐
│ Auth & Roles    │       │ Project Engine  │       │ Payment Engine  │
│ JWT / RBAC      │       │ Milestones      │       │ Razorpay        │
└─────────────────┘       └─────────────────┘       └─────────────────┘
        │                           │                           │
        └───────────────────────────┼───────────────────────────┘
                                    ▼
                         ┌──────────────────────┐
                         │     PostgreSQL       │
                         └──────────────────────┘
                                    │
                         ┌──────────────────────┐
                         │        Redis         │
                         └──────────────────────┘
```

---

## 🛠️ Tech Stack

| Layer           | Technology                          |
| --------------- | ----------------------------------- |
| Frontend        | Next.js, React, TypeScript          |
| Styling         | Tailwind CSS                        |
| Backend         | Java, Spring Boot, Node.js          |
| Security        | Spring Security, JWT                |
| Database        | Supabase PostgreSQL                 |
| Cache           | Redis                               |
| Payments/Escrow | Razorpay, Onramper, Web3j           |
| Infrastructure  | Docker, Docker Compose              |
| Deployment      | Render                              |
| Version Control | GitHub                              |

---

## 📂 Repository Structure

```text
finx/
├── frontend/
│   ├── app/
│   │   ├── auth/
│   │   ├── buyer/
│   │   ├── vendor/
│   │   ├── project-manager/
│   │   ├── admin/
│   │   └── finance/
│   ├── components/
│   ├── services/
│   ├── hooks/
│   ├── lib/
│   └── types/
│
├── backend/
│   ├── src/main/java/com/finx/
│   │   ├── auth/
│   │   ├── user/
│   │   ├── project/
│   │   ├── milestone/
│   │   ├── payment/
│   │   ├── escrow/
│   │   ├── dispute/
│   │   ├── finance/
│   │   ├── notification/
│   │   └── audit/
│   └── pom.xml
│
├── blockchain/
│   ├── contracts/
│   └── docs/
│
├── docker-compose.yml
├── .env.example
└── README.md
```

---

## ⚡ Getting Started

### Prerequisites

Install the following before running FINX:

* Node.js 18+
* Java 17+
* Maven
* Supabase PostgreSQL (or local instance)
* Redis
* Docker
* Razorpay test account

### Clone the Repository

```bash
git clone https://github.com/your-username/finx.git
cd finx
```

### Start PostgreSQL and Redis

```bash
docker compose up -d postgres redis
```

### Configure Environment Variables

```bash
cp .env.example .env
```

Example configuration:

```env
DATABASE_URL=jdbc:postgresql://localhost:5432/finx
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=your_password

JWT_SECRET=your_secure_jwt_secret

RAZORPAY_KEY_ID=your_razorpay_key_id
RAZORPAY_KEY_SECRET=your_razorpay_key_secret
RAZORPAY_WEBHOOK_SECRET=your_webhook_secret

REDIS_HOST=localhost
REDIS_PORT=6379

NEXT_PUBLIC_API_URL=http://localhost:8080/api
NEXT_PUBLIC_RAZORPAY_KEY_ID=your_razorpay_public_key
```

### Run Backend

```bash
cd backend
./mvnw spring-boot:run
```

Backend:

```text
http://localhost:8080
```

### Run Frontend

```bash
cd frontend
npm install
npm run dev
```

Frontend:

```text
http://localhost:3000
```

---

## 🔐 Security Principles

FINX is designed around secure financial workflows.

* Payment amounts are validated by the backend.
* Frontend payment status is never trusted.
* Razorpay signatures are verified server-side.
* Webhooks are validated before processing.
* Idempotency prevents duplicate payment actions.
* Release operations are protected against duplication.
* Role-based and resource-level authorization is enforced.
* Secrets are stored in environment variables.
* Sensitive information is excluded from logs.
* Audit logs are append-only.
* Database transactions protect financial state changes.
* Production traffic must use HTTPS.

---

## 🧪 Testing & Verification

### Automated End-to-End Suite (190 assertions across 28 phases)
To run the comprehensive live integration and security hardening test suite:
```bash
node test-e2e-workflow.mjs
```
This executes and verifies:
1. Google OAuth security & config sanitization
2. Registration, login, and JWT rotation
3. Cross-user IDOR protections (Buyer A vs B, Seller A vs B)
4. Deal creation, acceptance, and cancellation lifecycle
5. Milestone progress (Pending -> In Progress -> Under Review -> Approved)
6. Deliverable validation & size constraints (>4000 characters rejected)
7. Razorpay order creation and HMAC-SHA256 signature verification
8. Fiat escrow ledger double-entry funding and release
9. Double-release rejection (HTTP 400 Bad Request)
10. Legal disputes & arbitration (Freeze deal, admin ruling, resolution notes)
11. Complete append-only audit trail logging (15 distinct business events)
12. Zero floating-point monetary precision (Strict BigDecimal & DECIMAL(15, 2))
13. Complete frontend route integrity across all 45 production pages

### Backend Unit & Integration Tests (38/38 classes passing)
```bash
cd backend
./mvnw clean test
```

### Backend Executable Packaging
```bash
cd backend
./mvnw clean package -DskipTests
```

### Frontend Typecheck & Production Build
```bash
cd frontend
npx tsc --noEmit
npm run lint
npm run build
```

---

## 📖 API Documentation (OpenAPI / Swagger)
Interactive API specifications and contract schemas are generated dynamically by SpringDoc OpenAPI:
* **JSON Schema:** `http://localhost:8080/v3/api-docs`
* **Swagger UI:** `http://localhost:8080/swagger-ui/index.html`

---

## 🛡️ Failure & Disaster Recovery Architecture
FINX is engineered to fail safely under adverse infrastructure conditions:
* **Database Disconnection:** HikariCP connection pool manages backoff and auto-reconnection; Spring `@Transactional` boundaries guarantee zero partial ledger records or orphaned payments.
* **Redis Cache Unavailability:** Token-bucket rate limiting gracefully fails open or logs warnings without interrupting critical escrow settlements.
* **Gateway Outage:** In case of upstream Razorpay timeouts, pending payments remain in `PENDING` state and can be safely retried; escrow balance is never incremented without a cryptographically verified signature.
* **Service Restarts:** All business state (users, deals, milestones, ledgers, audit trail) is persisted in Supabase PostgreSQL; services recover cleanly with zero in-memory data loss.


---

## 🔑 Default Development Accounts
| Role | Email | Password | Purpose |
|------|-------|----------|---------|
| **Administrator** | `admin@finx.com` | `Admin@Finx2026!` | Seeded on initial startup for audit, admin dashboard & dispute arbitration |
| **Buyer (Corporate)** | Dynamic via UI / Test | Chosen at registration | Create deals, fund milestones, approve deliverables, release escrow |
| **Seller (Vendor)** | Dynamic via UI / Test | Chosen at registration | Accept deals, start work, submit deliverables, receive payouts |

---

## 🗺️ Roadmap

### Current MVP

* [x] Authentication and role management
* [x] Corporate dashboard
* [x] Vendor dashboard
* [x] Project manager dashboard
* [x] Admin dashboard
* [x] Finance dashboard
* [x] Project management
* [x] Milestone workflow
* [x] Razorpay payment integration
* [x] Release management
* [x] Audit logs

### Next

* [x] Automated notifications (Event-driven async)
* [x] Advanced dispute resolution
* [ ] Vendor ratings
* [ ] Invoice automation
* [ ] Advanced analytics
* [ ] KYC/KYB integrations
* [ ] Enterprise approval workflows
* [ ] ERP integrations
* [ ] White-label platform
* [ ] Regulated financial partner integration

---

## 📈 Product Metrics

FINX tracks metrics that demonstrate business value:

* Projects created
* Active businesses
* Active vendors
* Milestones completed
* Payment success rate
* Total payment volume
* Average release time
* Dispute rate
* Repeat customer rate

---

## 💰 Business Model

Potential revenue streams include:

* Transaction fees
* Business subscriptions
* Enterprise plans
* Premium analytics
* Vendor management tools
* API access
* White-label workflows
* Reconciliation and finance automation

Pricing will depend on payment gateway fees, compliance requirements, operational costs, refunds, and dispute handling.

---

## ⚠️ Compliance Notice

FINX is an MVP payment workflow platform.

FINX must not represent itself as a regulated escrow provider without the required authorization or a compliant regulated partner. Before production launch, the platform may require:

* KYC and KYB
* AML controls
* Payment regulations
* Escrow or payment-partner compliance
* GST and tax compliance
* Data protection controls
* Financial reconciliation
* Legal review of fund custody and release operations

Use Razorpay test mode during development.

---

## 🤝 Team

### Gowri — Backend and Infrastructure

* Spring Boot APIs
* Database architecture
* Authentication and authorization
* Payment services
* Escrow workflow
* Redis
* Deployment

### Mahasuf — Frontend and Integration

* Next.js application
* Role-based dashboards
* Project and milestone interfaces
* Razorpay Checkout
* API integration
* UI testing
* Product experience

---

## 🌍 Vision

FINX aims to become the trusted payment infrastructure for milestone-driven business relationships.

**From project creation to final settlement, FINX makes every step visible, verifiable, and accountable.**

---

## 📄 License

This project is currently intended for MVP, educational, and hackathon development.

A suitable open-source or commercial license should be added before public production release.

---

## 📬 Contact

For collaboration, partnerships, or product discussions, contact the FINX team.

**FINX — Fund Progress. Release Trust.**
