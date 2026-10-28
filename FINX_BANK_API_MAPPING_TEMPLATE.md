# FinX & Bank API Data Mapping Template

This template specifies the contract fields required by FinX's banking provider boundary (`PartnerBankingProvider` / `BankApiContract`). The partner banking integration team must map each canonical FinX field to their institutional API request, response, webhook, and statement formats.

---

## 1. Payout Dispatch Mapping (Outbound API)

| Canonical FinX Field | Data Type | Description / Constraints | Bank Specific Field Name | Bank Field Type & Format | Notes / Constraints |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `providerName` | String | e.g. `HDFC`, `ICICI`, `AXIS`, `YESBANK` | *[Bank to fill]* | String | Identifier assigned to bank partner |
| `providerAccountId` | String | Bank account number of nodal/escrow pool | *[Bank to fill]* | String (Numeric) | Source debit account |
| `operationKey` | String | Stable idempotency key: `FINX-REL-{uuid}` | *[Bank to fill]* | String (Max 64 chars) | Must map to bank idempotency header/field |
| `amount` | BigDecimal | Transfer amount (2-4 decimal places) | *[Bank to fill]* | Decimal / String | e.g. `50000.00` |
| `currency` | String | ISO 4217 code, e.g. `INR` | *[Bank to fill]* | String (3 chars) | Standard: `INR` |
| `beneficiaryAccountNumber` | String | Destination account number | *[Bank to fill]* | String (Numeric) | Vendor settlement account |
| `beneficiaryIfsc` | String | Destination bank IFSC code | *[Bank to fill]* | String (11 chars) | Standard Indian IFSC code |
| `beneficiaryName` | String | Destination account holder legal name | *[Bank to fill]* | String | Max length required by bank |
| `paymentMode` | String | `IMPS`, `NEFT`, `RTGS` | *[Bank to fill]* | String / Enum | Bank routing preference |
| `narration` | String | Description: `FINX-REL-{uuid}` | *[Bank to fill]* | String | Max characters allowed in narration |

---

## 2. Dispatch Response Mapping (Inbound Gateway Response)

| Canonical FinX Field | Data Type | Description | Bank Specific Field Name | Expected Values / Format | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `providerSettlementId` | String | Bank's internal reference ID | *[Bank to fill]* | String | Used for tracking and status polling |
| `providerStatus` | String | Gateway acknowledgment status | *[Bank to fill]* | `ACCEPTED`, `PENDING`, `REJECTED`, etc. | Must distinguish synchronous acceptance from rejection |
| `providerStatusCode` | String | Machine-readable status/error code | *[Bank to fill]* | String / Integer | e.g. `200`, `00`, `ERR_INSUFFICIENT_FUNDS` |
| `providerStatusMessage`| String | Human-readable status description | *[Bank to fill]* | String | Gateway message |
| `gatewayTimestamp` | Instant | Timestamp of bank gateway ACK | *[Bank to fill]* | ISO 8601 UTC / Epoch ms | Clearing timestamp |

---

## 3. Webhook Notification Mapping (Inbound Push Event)

| Canonical FinX Field | Data Type | Description | Bank Specific Field Name | Format / Example | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `webhookEventId` | String | Unique event ID for deduplication | *[Bank to fill]* | String / UUID | Required for replay protection |
| `webhookEventType` | String | Event type identifier | *[Bank to fill]* | e.g. `PAYOUT.SUCCESS`, `PAYOUT.FAILED` | Event discriminator |
| `operationKey` | String | FinX reference sent in dispatch | *[Bank to fill]* | `FINX-REL-...` | Must echo back client operation key |
| `providerSettlementId` | String | Bank transaction reference | *[Bank to fill]* | String | Bank internal ID |
| `utr` | String | Unique Transaction Reference | *[Bank to fill]* | String (16-22 chars alphanumeric) | Mandatory for `SUCCESS` and finality |
| `settlementStatus` | String | Authoritative lifecycle status | *[Bank to fill]* | `SUCCESS`, `FAILURE`, `REVERSED` | Determines FinX state machine transition |
| `settlementTimestamp` | Instant | Timestamp of final settlement | *[Bank to fill]* | ISO 8601 UTC / Epoch ms | Value date & time |
| `failureReasonCode` | String | Rejection/failure reason code | *[Bank to fill]* | String | Mandatory when status is `FAILURE` |
| `failureDescription` | String | Detailed failure explanation | *[Bank to fill]* | String | e.g. `Beneficiary account closed` |
| `signatureHeader` | String | HMAC / RSA signature header | *[Bank to fill]* | Header name, e.g. `X-Signature` | Header used for cryptographic validation |

---

## 4. Status Inquiry Mapping (Outbound Polling API)

| Canonical FinX Field | Request / Response | Description | Bank Specific Field Name | Notes |
| :--- | :--- | :--- | :--- | :--- |
| `providerSettlementId` | Request Param | Bank transaction reference | *[Bank to fill]* | Query parameter |
| `operationKey` | Request Param | FinX operation reference | *[Bank to fill]* | Alternate query identifier |
| `status` | Response | Current lifecycle state | *[Bank to fill]* | `SUCCESS`, `PENDING`, `FAILED` |
| `utr` | Response | Central clearing UTR | *[Bank to fill]* | Returned once settled |
| `clearingTimestamp` | Response | Settlement timestamp | *[Bank to fill]* | Clearing timestamp |

---

## 5. Bank Statement Line Mapping (Reconciliation Feed)

| Canonical FinX Field | Data Type | Description | Bank Statement Field Name | Format / Sample |
| :--- | :--- | :--- | :--- | :--- |
| `providerAccountId` | String | Account number of the pool account | *[Bank to fill]* | e.g. `001234567890` |
| `statementTransactionId`| String | Unique statement line record ID | *[Bank to fill]* | String |
| `transactionDate` | LocalDate | Booking / value date | *[Bank to fill]* | `YYYY-MM-DD` |
| `transactionTimestamp`| Instant | Transaction execution time | *[Bank to fill]* | `YYYY-MM-DD HH:mm:ss` |
| `direction` | String | Debit or Credit | *[Bank to fill]* | `DR` / `CR` |
| `amount` | BigDecimal | Transaction amount | *[Bank to fill]* | Numeric, 2 decimal places |
| `currency` | String | ISO 4217 code | *[Bank to fill]* | `INR` |
| `utr` | String | Central clearing reference number | *[Bank to fill]* | e.g. `CMS1234567890123` |
| `narration` | String | Transaction narration/description | *[Bank to fill]* | Contains `FINX-REL-...` |
| `balanceAfter` | BigDecimal | Running account balance | *[Bank to fill]* | Numeric |

---

## 6. Signoff & Approvals

- **FinX Integration Lead**: ____________________ Date: _________
- **Bank Technical Architect**: ____________________ Date: _________
