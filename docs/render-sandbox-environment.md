# Render Sandbox Environment Deployment

The following values MUST be entered directly into the **Backend Service** Environment settings inside the Render Dashboard. Do NOT commit these values or expose them to the Next.js Frontend configurations.

## Onramper Integration
- ONRAMP_PROVIDER=onramper
- ONRAMP_ENVIRONMENT=sandbox
- ONRAMP_API_KEY=[Insert from Onramper Dev Dashboard]
- ONRAMP_V2_PRIVATE_KEY=[Insert matching Ed25519 Private Key]
- ONRAMP_WEBHOOK_SECRET=[Insert Webhook HMAC setup token]

## Blockchain Integration
- FINX_RPC_URL=https://rpc-amoy.polygon.technology
- FINX_CHAIN_ID=80002
- FINX_ESCROW_CONTRACT_ADDRESS=[Run 'forge create' to testnet Deployer and enter address here]
- USDC_CONTRACT_ADDRESS=0x41e94eb019c0762f9bfcf9fb1e58725bfb0e7582
- FINX_REQUIRED_CONFIRMATIONS=2
