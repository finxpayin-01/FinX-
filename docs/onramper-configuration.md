# Onramper Configuration Guide

For the FinX backend to securely interface with Onramper V2, strict deployment properties are managed securely. 

## Onramper Integration Secrets (BACKEND ONLY)

These belong exclusively in backend configuration scopes, normally mapped via Render Environment settings. NEVER put these in frontend projects.

*   ONRAMP_API_KEY: API Key required for querying intents and supported assets.
*   ONRAMP_V2_PRIVATE_KEY: Private Key matching your registered public key on Onramper. Required for executing V2 intent signatures and preventing IDORs/intent spoofing.
*   ONRAMP_WEBHOOK_SECRET: Supplied by the Onramper developer dashboard; required for constant-time HMAC-SHA256 verification of incoming event streams.

*Note: Onramper public-key onboarding means you will generate an Ed25519 key pair, hand the Public Key to Onramper, and place the Private Key into ONRAMP_V2_PRIVATE_KEY.*

## Blockchain Network Specifications

These settings mandate settlement verification boundaries and must ALL pertain to the exact SAME network segment. Setting different networks will produce a mismatch failure on startup.

*   FINX_RPC_URL: Dedicated node access (Alchemy, Infura, etc.) representing network state.
*   FINX_CHAIN_ID: Identifier natively compared at startup explicitly against eth_chainId on the FINX_RPC_URL.
*   FINX_ESCROW_CONTRACT_ADDRESS: The deployed proxy or logic smart contract handling funds routing.
*   USDC_CONTRACT_ADDRESS: The targeted mapped token routing (specifically isolated and matched to FINX_CHAIN_ID).
*   FINX_REQUIRED_CONFIRMATIONS: Finality buffer (default set securely to 2 block ticks).
