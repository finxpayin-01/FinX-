# FINX Backend - Core Foundation

## 1. Overview
FINX is a B2B milestone-based fiat escrow platform connecting **Buyers (Clients)**, **Sellers (Service Providers)**, and **Administrators**.

This backend service delivers the core Tuesday foundation:
* Clean B2B fiat escrow domain model (Buyer, Seller, Admin).
* Role-based authorization: `BUYER`, `SELLER`, `ADMIN`.
* Dual-token authentication with JWT access tokens and cryptographically hashed, rotatable refresh tokens.
* Immutable audit logging.
* Production-ready Docker Compose orchestration (PostgreSQL 16, Redis 7, Spring Boot 3).
* OpenAPI/Swagger 3 documentation.

---

## 2. Tech Stack
* **Java**: 25 LTS
* **Framework**: Spring Boot 3.4.3
* **Security**: Spring Security 6 + JJWT 0.12.6
* **Database**: PostgreSQL 16 (H2 for automated tests)
* **Migrations**: Flyway
* **Cache & Queues**: Redis 7
* **Documentation**: Springdoc OpenAPI 2.8.5
* **Build Tool**: Apache Maven 3.9.9 (with included Maven Wrapper `mvnw`)
* **Containers**: Docker & Docker Compose

---

## 3. Project Structure
```text
backend/
├── src/main/java/com/finx/
│   ├── FinxApplication.java        # Spring Boot Entrypoint
│   ├── auth/                       # Register, Login, Refresh, Logout, Tokens
│   ├── user/                       # User entity, repository, service
│   ├── security/                   # Spring Security, JWT filter, Rate limiter
│   ├── audit/                      # Immutable audit log engine
│   ├── config/                     # OpenAPI, Redis, CORS
│   ├── exception/                  # GlobalExceptionHandler, Custom exceptions
│   ├── common/                     # ApiResponse, ErrorResponse, Role, UserStatus
│   ├── health/                     # System health probe (/api/health)
│   ├── deal/                       # [Wednesday Placeholder] Deal Management
│   ├── milestone/                  # [Wednesday Placeholder] Milestones
│   ├── payment/                    # [Wednesday Placeholder] Fiat Gateway
│   ├── escrow/                     # [Wednesday Placeholder] Escrow Ledger
│   └── notification/               # [Wednesday Placeholder] Redis Pub/Sub
├── src/main/resources/
│   ├── db/migration/
│   │   └── V1__create_users_and_auth_tables.sql
│   ├── application.yml
│   └── application-test.yml
├── Dockerfile
├── .dockerignore
├── .gitignore
├── .env.example
├── pom.xml
└── README.md
```

---

## 4. Quick Start (Local Development)

### Prerequisites
* Java 25+ JDK installed
* PostgreSQL 16 running on port 5432 (or run via Docker)
* Redis 7 running on port 6379 (optional for basic auth)

### Running with Docker Compose (Recommended)
From the repository root:
```bash
docker compose up --build
```
This launches:
1. **PostgreSQL** on `localhost:5432` with healthcheck.
2. **Redis** on `localhost:6379` with healthcheck.
3. **FINX Spring Boot Backend** on `localhost:8080` (waits for Postgres and Redis to become healthy).

### Running Manually via Maven
```bash
cd backend
.\mvnw.cmd clean test
.\mvnw.cmd spring-boot:run
```

---

## 5. API Reference & Swagger UI

Once running, interactive API documentation is accessible at:
* **Swagger UI**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
* **OpenAPI JSON**: [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs)

### Authentication Endpoints
| Method | Path | Access | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/auth/register` | Public | Register new `BUYER` or `SELLER` (ADMIN prohibited) |
| `POST` | `/api/auth/login` | Public | Login with email and password |
| `POST` | `/api/auth/refresh` | Public | Rotate refresh token for new access & refresh tokens |
| `POST` | `/api/auth/logout` | Public | Revoke active refresh token |
| `GET` | `/api/auth/me` | Authenticated | Retrieve authenticated user profile |
| `GET` | `/api/health` | Public | Health check probe |

---

## 6. Frontend Integration Contract (For Mahasuf)

All API responses follow the standard JSON envelope:

### Success Envelope
```json
{
  "success": true,
  "message": "Operation successful",
  "data": { ... },
  "timestamp": "2026-09-08T15:30:00Z"
}
```

### Error Envelope
```json
{
  "success": false,
  "status": 400,
  "error": "Bad Request",
  "message": "Validation Failed",
  "path": "/api/auth/register",
  "validationErrors": {
    "password": "Password must be at least 8 characters long"
  },
  "timestamp": "2026-09-08T15:30:00Z"
}
```

### Example Auth Request & Response
`POST /api/auth/register`
```json
{
  "name": "Acme Corp",
  "email": "buyer@acme.com",
  "password": "SecurePassword123!",
  "role": "BUYER"
}
```

Response (`201 Created`):
```json
{
  "success": true,
  "message": "User registered successfully",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "4x_aBcDeF...",
    "tokenType": "Bearer",
    "expiresIn": 900,
    "user": {
      "id": "c1f71f65-8b3d-4c7b-b5d1-9fbef3223abc",
      "name": "Acme Corp",
      "email": "buyer@acme.com",
      "role": "BUYER",
      "status": "ACTIVE",
      "createdAt": "2026-09-08T15:30:00Z"
    }
  },
  "timestamp": "2026-09-08T15:30:00Z"
}
```

---

## 7. Running Automated Tests
```bash
cd backend
.\mvnw.cmd clean test
```
Tests cover:
* Successful Buyer & Seller registration.
* Public Admin registration rejection.
* Duplicate email rejection.
* Valid & invalid login credentials.
* Suspended user account handling.
* Cryptographic JWT claims, expiration, and signature tampering.
* Refresh token rotation and revoked token reuse detection.
* End-to-end user lifecycle (`Register -> Login -> /api/auth/me -> Refresh -> Logout`).
