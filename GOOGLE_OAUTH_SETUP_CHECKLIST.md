# Google OAuth 2.0 Configuration Checklist for FinX

This document provides the exact configuration requirements for setting up and verifying Google OAuth 2.0 for the FinX platform in Google Cloud Console.

---

## 1. Google Cloud Console Configuration

### A. OAuth 2.0 Client Type
- **Application type**: `Web application`
- **Application name**: `FinX Platform`

---

### B. Authorized JavaScript Origins
Add the following origins under **Authorized JavaScript origins**:

1. **Local Development**:
   ```
   http://localhost:3000
   ```
2. **Local Alternative (if applicable)**:
   ```
   http://127.0.0.1:3000
   ```
3. **Hosted Staging / Production**:
   ```
   https://finx-frontend.onrender.com
   ```

---

### C. Authorized Redirect URIs
Add the following redirect URIs under **Authorized redirect URIs** (Google redirects here after user authorization):

1. **Local Development (MANDATORY for Local)**:
   ```
   http://localhost:8080/api/auth/google/callback
   ```
2. **Hosted Staging (MANDATORY for Hosted Render)**:
   ```
   https://finx-backend-vq5b.onrender.com/api/auth/google/callback
   ```

> **CRITICAL**: The redirect URI in Google Cloud Console MUST match byte-for-byte with the backend's configured `GOOGLE_REDIRECT_URI`. Any discrepancy (such as http vs https, port numbers, or trailing slashes) causes Google to reject authorization with `Error 400: redirect_uri_mismatch`.

---

### D. OAuth Consent Screen Scopes
Ensure the following standard OAuth 2.0 scopes are enabled on the Google OAuth consent screen:
- `openid` (Associate you with your personal info on Google)
- `https://www.googleapis.com/auth/userinfo.email` (`email` — View your email address)
- `https://www.googleapis.com/auth/userinfo.profile` (`profile` — See your personal info)

---

### E. Publishing Status & Test Users
- If your Google Cloud OAuth app is in **Testing** status:
  - You must navigate to **Google Cloud Console → APIs & Services → OAuth consent screen → Test users**.
  - Click **+ ADD USERS** and enter the Gmail or Google Workspace email addresses that will be used for testing.
  - Accounts not added as test users will receive an `Access blocked: FinX has not completed the Google verification process` error when attempting to sign in.
- Production verification/approval is NOT required while the application is in development or internal staging.

---

## 2. Environment Variables Reference

### Backend (`backend/.env` or Hosted Environment):
| Variable Name | Local Development Value | Hosted Staging (Render) Value | Description |
|---|---|---|---|
| `GOOGLE_CLIENT_ID` | `<Google OAuth Web Client ID>` | `<Google OAuth Web Client ID>` | Public Client ID from Google Cloud Console |
| `GOOGLE_CLIENT_SECRET` | `<Google OAuth Client Secret>` | `<Google OAuth Client Secret>` | Private Client Secret from Google Cloud Console |
| `GOOGLE_REDIRECT_URI` | `http://localhost:8080/api/auth/google/callback` | `https://finx-backend-vq5b.onrender.com/api/auth/google/callback` | OAuth redirect URI registered in Google Console |
| `FRONTEND_OAUTH_CALLBACK_URL` | `http://localhost:3000/auth/callback/google` | `https://finx-frontend.onrender.com/auth/callback/google` | Frontend page that receives tokens upon login completion |
| `FRONTEND_URL` | `http://localhost:3000` | `https://finx-frontend.onrender.com` | Base frontend URL |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://127.0.0.1:3000` | `https://finx-frontend.onrender.com` | Allowed CORS origins for frontend requests |

### Frontend (`frontend/.env.local` or Hosted Environment):
| Variable Name | Local Development Value | Hosted Staging (Render) Value | Description |
|---|---|---|---|
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080/api` | `https://finx-backend-vq5b.onrender.com/api` | Base URL of backend REST API |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | `<Google OAuth Web Client ID>` | `<Google OAuth Web Client ID>` | Client ID exposed to frontend |

---

## 3. FinX Authoritative Google OAuth Flow

```text
Browser
  ↓  1. Clicks "Sign in with Google"
GET /api/auth/google/login (Backend)
  ↓  2. Issues cryptographically secure state cookie, 302 redirects
accounts.google.com/o/oauth2/v2/auth (Google Consent Screen)
  ↓  3. User signs in and consents
GET /api/auth/google/callback?code=...&state=... (Backend)
  ↓  4. Backend verifies state cookie, exchanges code with oauth2.googleapis.com
  ↓  5. Backend fetches userinfo from googleapis.com/oauth2/v3/userinfo
  ↓  6. Backend provisions/finds user in FinX database and generates JWT tokens
  ↓  7. 302 Redirect to Frontend with secure hash fragment (#accessToken=...&refreshToken=...&role=...)
http://localhost:3000/auth/callback/google
  ↓  8. Frontend scrubs hash fragment from browser history immediately
GET /api/auth/me (Backend)
  ↓  9. Validates token and loads user role
Role Dashboard (/corporate, /vendor, /project-manager, /finance, /admin)
```
