# FINX — Bank-Integrated B2B Payment & Trust Infrastructure

> **Build trust. Fund progress. Settle with confidence.**

FinX is a centralized B2B milestone-payment, verification, reconciliation, and trust infrastructure platform connecting corporate buyers and enterprise vendors through structured milestone agreements and bank integrations.

---

## ⚖️ Important Legal & Regulatory Demarcation

> **NOTICE**: FinX is a technology infrastructure platform. FinX itself does NOT hold a banking license, nor does it act as a regulated escrow agent. All physical fund custody, nodal/escrow accounts, settlement, and regulated payment operations are performed through appropriately licensed banking/payment partners according to the final legal structure. FinX provides the trust, verification, agreement execution, double-entry ledger bookkeeping, and milestone verification software layer.

---

## 🏛️ Architecture Direction

```
BUSINESS → FINX → BANK/PAYMENT INFRASTRUCTURE → FINX LEDGER → MILESTONE WORKFLOW → BANK SETTLEMENT
```

FinX has completely removed blockchain/Web3 and Razorpay from the active product architecture:
- **No Cryptocurrency / No Wallets**: Quarantined smart contracts and ethers/Web3 dependencies into `/legacy`.
- **No Razorpay**: Replaced with standardized `BankingProvider` virtual account (VAN) generation and automated bank webhooks.
- **Centralized B2B Multi-Tenant Model**: Corporate entities (`Organization`), authorized signatories, and masked corporate bank accounts.
- **Verification Trust Layer**: Corporate KYB (PAN, GSTIN, CIN), Director KYC, and Penny-Drop bank account verification.
- **Versioned Deal Agreements**: Dual-party digital contract signing with SHA-256 document hashing and invariant validation ($\sum \text{Milestones} = \text{Deal Amount}$).
- **Internal Double-Entry Ledger**: Balanced financial bookkeeping (`Total Debits == Total Credits`) using `BigDecimal` / `NUMERIC(19,4)`.
- **10-Gate Settlement Authorization**: Payout disbursements to vendor bank accounts are gated by 10 server-side security checks.
- **Automated Bank Reconciliation**: Nightly and on-demand reconciliation runs correlating internal transaction records with bank settlement statements.
- **Real-Time Risk Engine**: Automated threat evaluation gating automated payouts for high-risk counterparties.

---

## 🛠️ Technology Stack

- **Frontend**: Next.js 14, React, TypeScript, Tailwind CSS, Lucide Icons
- **Backend**: Java 21, Spring Boot 3.4.3, Spring Security (JWT), Spring Data JPA, Hibernate, Maven, Flyway
- **Database**: PostgreSQL / Supabase PostgreSQL (15 versioned Flyway migrations)
- **Cache & Queues**: Redis (Rate limiting, notification queuing)
- **Deployment**: Docker, Render-compatible containerized deployment

---

## ⚙️ Environment Variables

### Backend Configuration (`backend/src/main/resources/application.yml` or `.env`)
```bash
# Application Server
PORT=8080
SPRING_PROFILES_ACTIVE=dev

# PostgreSQL Database
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/finx_db
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=postgres

# Redis
SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=6379

# Security & JWT
JWT_SECRET=your_256bit_or_greater_secret_key_here
JWT_ACCESS_TOKEN_EXPIRATION_MS=900000
JWT_REFRESH_TOKEN_EXPIRATION_MS=604800000

# Banking Abstraction
# Set BANK_PROVIDER=mock for local sandbox testing, or partner name for production
BANK_PROVIDER=mock
BANK_DEFAULT_CURRENCY=INR
BANK_API_BASE_URL=https://api.bankingpartner.com/v1
BANK_CLIENT_ID=your_bank_client_id
BANK_CLIENT_SECRET=your_bank_client_secret
BANK_WEBHOOK_SECRET=your_bank_webhook_secret
```

### Frontend Configuration (`frontend/.env.local`)
```bash
NEXT_PUBLIC_API_URL=http://localhost:8080/api
NEXT_PUBLIC_BANK_ENVIRONMENT=SANDBOX
NEXT_PUBLIC_DEFAULT_CURRENCY=INR
```

---

## 🧪 Testing & Verification

### Running Complete Backend Test Suite
```bash
cd backend
./mvnw clean test
```
*Current test suite: **179 automated tests passing with 0 failures and 0 errors**.*

### Running Bank-Integrated End-to-End Test
```bash
cd backend
./mvnw test -Dtest=BankIntegratedEndToEndTest
```

### Running Frontend Type-Check & Build
```bash
cd frontend
npm install
npx tsc --noEmit
npm run build
```

---

## 📚 Architectural Documentation

Comprehensive technical documentation is maintained in the [`docs/`](docs/) directory:
- [System Architecture](docs/ARCHITECTURE.md)
- [Banking Abstraction & Partner Integration](docs/BANKING_ARCHITECTURE.md)
- [KYC & KYB Trust Verification Layer](docs/KYC_KYB.md)
- [Internal Double-Entry Ledger](docs/LEDGER.md)
- [Automated Bank Reconciliation Engine](docs/RECONCILIATION.md)
- [Security Controls & Risk Governance](docs/SECURITY.md)
- [Financial State Machines](docs/FINANCIAL_STATE_MACHINE.md)
- [Complete End-to-End B2B Flow](docs/END_TO_END_FLOW.md)
- [Legacy Migration & Quarantine Report](docs/LEGACY_MIGRATION.md)
