# Onramper V2 Signing & Webhook Configuration

## Transaction V2 Signing
Current official Onramper Checkout Protocol requires secure server-to-server intent generation.

*   **Key Algorithm**: Ed25519 or SHA-256 (Depending on current exact dashboard restriction)
*   **Key Encoding**: Hex or Base64 (Standard Java security implementation required for private Ed25519 instances)
*   **Request Canonicalization**: URL-encoded parameters mapping EXACT intent constraints.
*   **Timestamp Requirements**: Nonce/Timestamp delta <= 60 seconds (X-Onramper-Timestamp).
*   **Body Hashing**: SHA-256 for Payload.
*   **Public-key Registration**: MUST be managed centrally through Onramper's dashboard. ONRAMP_V2_PRIVATE_KEY remains purely backend-only in Render.
*   **Rotation Procedure**: Dashboard deletion -> Local generation -> Provider propagation -> Render rotation.

## Webhooks
*   **Endpoint Setup**: Must map to https://<FINX_BACKEND_DOMAIN>/api/webhooks/onramp (Cannot be localhost; use Ngrok for local webhook proxy mapping to Sandbox tests).
*   **Signature Enforcement**: X-Onramper-Webhook-Signature string comparison using ONRAMP_WEBHOOK_SECRET provided during dashboard onboarding.
