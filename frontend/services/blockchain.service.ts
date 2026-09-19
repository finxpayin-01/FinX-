import { api } from "@/lib/api";

export interface ChainConfig {
    configured: boolean;
    ready?: boolean;
    status?: "BLOCKCHAIN_DISABLED" | "RPC_UNAVAILABLE" | "WRONG_CHAIN" | "ESCROW_CONTRACT_MISSING" | "FACTORY_NOT_CONFIGURED" | "FACTORY_NO_BYTECODE" | "TOKEN_CONTRACT_MISSING" | "TOKEN_NOT_CONFIGURED" | "TOKEN_NO_BYTECODE" | "SIGNER_NOT_CONFIGURED" | "CREATOR_NOT_AUTHORIZED" | "ONRAMP_NOT_CONFIGURED" | "READY" | string;
    enabled?: boolean;
    chainId: string;
    network: string;
    token: string;
    symbol: string;
    decimals: number;
    confirmations: number;
    testnet: boolean;
    explorer: string;
    signer?: string;
    factory?: string;
    creator?: string;
    rpcConfigured?: boolean;
    rpcConnected?: boolean;
    escrowContractConfigured?: boolean;
    tokenConfigured?: boolean;
    signerConfigured?: boolean;
    onrampConfigured?: boolean;
    walletVerificationAvailable?: boolean;
    escrowCreationAvailable?: boolean;
}
export interface ChainTransaction {
    id: string; operation: string; status: string; from_address: string; to_address: string;
    calldata: string; chain_id: string; tx_hash?: string; confirmations: string; error_code?: string;
    actor_id: string; deal_id?: string; created_at?: string;
}
export interface ChainEscrow {
    exists: boolean; config: ChainConfig;
    escrow?: Record<string, string>;
    allocations?: { milestone_id: string; amount_atomic: string; chain_status: string }[];
    transactions?: ChainTransaction[];
}
export type ChainOperation = "CREATE" | "ACCEPT" | "ALLOWANCE" | "FUND" | "SUBMIT" | "APPROVE" | "RELEASE" | "REFUND" | "DISPUTE" | "CANCEL" | "RESOLVE_VENDOR" | "RESOLVE_BUYER";
const key = () => crypto.randomUUID();

export const blockchainService = {
    get: async (deal: string): Promise<ChainEscrow> => (await api.get(`/blockchain/deals/${deal}`)).data.data,
    readiness: async (): Promise<ChainConfig> => (await api.get("/blockchain/readiness")).data.data,
    create: async (deal: string, allocations: { milestoneId: string; amount: string }[]) =>
        (await api.post(`/blockchain/deals/${deal}`, { allocations }, { headers: { "Idempotency-Key": key() } })).data.data,
    intent: async (deal: string, operation: ChainOperation, milestoneId?: string, reason?: string): Promise<ChainTransaction> =>
        (await api.post(`/blockchain/deals/${deal}/intents`, { operation, milestoneId, reason }, { headers: { "Idempotency-Key": key() } })).data.data,
    attach: async (id: string, txHash: string) => (await api.post(`/blockchain/transactions/${id}`, { txHash })).data.data,
    replace: async (id: string, txHash: string) => (await api.post(`/blockchain/transactions/${id}/replacement`, { txHash })).data.data,
    funding: async (deal: string) => (await api.post(`/blockchain/deals/${deal}/funding-intent`, {}, { headers: { "Idempotency-Key": key() } })).data.data,
    challenge: async (address: string) => (await api.post("/blockchain/wallet/challenge", { address })).data.data as { verified: boolean; message?: string },
    verify: async (signature: string) => (await api.post("/blockchain/wallet/verify", { signature })).data.data,
    admin: async () => (await api.get("/blockchain/admin")).data.data as { escrows: Record<string,string>[]; transactions: ChainTransaction[]; rpcHealth: string; providerStatus: string },
    
    /**
     * Inspects on-chain contract bytecode using Polygon Amoy RPC.
     * Returns "0x" if not deployed or no bytecode exists.
     */
    getBytecode: async (address?: string, rpcUrl?: string): Promise<string> => {
        if (!address || !/^0x[0-9a-fA-F]{40}$/.test(address) || address === "0x0000000000000000000000000000000000000000") {
            return "0x";
        }
        const rpc = rpcUrl || "https://polygon-amoy-bor-rpc.publicnode.com";
        try {
            const resp = await fetch(rpc, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    jsonrpc: "2.0",
                    method: "eth_getCode",
                    params: [address, "latest"],
                    id: 1,
                }),
            });
            const json = await resp.json();
            return (json.result as string) || "0x";
        } catch {
            if (typeof window !== "undefined" && (window as unknown as { ethereum?: Eip1193 }).ethereum) {
                try {
                    const code = await (window as unknown as { ethereum: Eip1193 }).ethereum.request({
                        method: "eth_getCode",
                        params: [address, "latest"],
                    }) as string;
                    return code || "0x";
                } catch {
                    return "0x";
                }
            }
            return "0x";
        }
    },

    /**
     * Checks if creator has CREATOR_ROLE on the factory contract on-chain.
     * keccak256("CREATOR_ROLE") = 0xa49807205ce4d355092ef5a8a18f56e8913cf4a201fbe287825b095693c21775
     * hasRole(bytes32,address) selector = 0x91d14854
     */
    hasCreatorRole: async (factoryAddress?: string, creatorAddress?: string, rpcUrl?: string): Promise<boolean> => {
        if (!factoryAddress || !creatorAddress || !/^0x[0-9a-fA-F]{40}$/.test(factoryAddress) || !/^0x[0-9a-fA-F]{40}$/.test(creatorAddress)) {
            return false;
        }
        const rpc = rpcUrl || "https://polygon-amoy-bor-rpc.publicnode.com";
        const role = "a49807205ce4d355092ef5a8a18f56e8913cf4a201fbe287825b095693c21775";
        const account = creatorAddress.toLowerCase().replace(/^0x/, "").padStart(64, "0");
        const data = `0x91d14854${role}${account}`;
        try {
            const resp = await fetch(rpc, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    jsonrpc: "2.0",
                    method: "eth_call",
                    params: [{ to: factoryAddress, data }, "latest"],
                    id: 1,
                }),
            });
            const json = await resp.json();
            return typeof json.result === "string" && json.result.endsWith("1");
        } catch {
            return false;
        }
    }
};

export function formatToken(atomic: string, decimals: number): string {
    const digits = BigInt(atomic).toString().padStart(decimals + 1, "0");
    if (!decimals) return digits;
    const fraction = digits.slice(-decimals).replace(/0+$/, "");
    return digits.slice(0, -decimals) + (fraction ? `.${fraction}` : "");
}

interface Eip1193 { request(args: { method: string; params?: unknown[] }): Promise<unknown> }
export interface TransactionSigner {
    account(): Promise<string>;
    signMessage(message: string): Promise<string>;
    send(tx: ChainTransaction): Promise<string>;
}
export class BrowserWalletSigner implements TransactionSigner {
    private provider(): Eip1193 {
        const provider = (window as unknown as { ethereum?: Eip1193 }).ethereum;
        if (!provider) throw new Error("WALLET_NOT_FOUND: No browser wallet is available. Install MetaMask or another Web3 wallet.");
        return provider;
    }
    async account() {
        const accounts = await this.provider().request({ method: "eth_requestAccounts" }) as string[];
        if (!accounts[0]) throw new Error("WALLET_NOT_FOUND: No wallet account selected.");
        return accounts[0];
    }
    async signMessage(message: string) {
        const address = await this.account();
        const bytes = Array.from(new TextEncoder().encode(message)).map(n => n.toString(16).padStart(2,"0")).join("");
        return await this.provider().request({ method: "personal_sign", params: [`0x${bytes}`, address] }) as string;
    }
    async send(tx: ChainTransaction) {
        const address = await this.account();
        if (address.toLowerCase() !== tx.from_address.toLowerCase()) throw new Error(`WALLET_NOT_AUTHORIZED: Select the wallet authorized for this transaction (${tx.from_address}).`);
        const chainIdHex = await this.provider().request({ method: "eth_chainId" }) as string;
        if (BigInt(chainIdHex) !== BigInt(tx.chain_id)) {
            throw new Error(`WRONG_NETWORK: Connected to chain ID ${BigInt(chainIdHex)}, expected ${tx.chain_id} (Polygon Amoy).`);
        }
        const request = { from: address, to: tx.to_address, data: tx.calldata, value: "0x0" };
        let gas: unknown;
        try {
            gas = await this.provider().request({ method: "eth_estimateGas", params: [request] });
        } catch (err: unknown) {
            const e = err as { message?: string };
            const m = (e.message || "").toLowerCase();
            if (m.includes("revert") || m.includes("-32603") || m.includes("execution reverted")) {
                throw new Error(`TRANSACTION_REVERTED: Gas estimation failed: transaction would revert on-chain.`);
            }
            throw err;
        }

        try {
            return await this.provider().request({ method: "eth_sendTransaction", params: [{ ...request, gas }] }) as string;
        } catch (err: unknown) {
            const e = err as { code?: number; message?: string };
            const msg = (e.message || "").toLowerCase();
            if (e.code === 4001 || msg.includes("reject") || msg.includes("denied") || msg.includes("decline") || msg.includes("user cancelled")) {
                throw new Error("TRANSACTION_REJECTED: User rejected the transaction signature.");
            }
            if (msg.includes("revert") || msg.includes("-32603") || msg.includes("execution reverted")) {
                throw new Error("TRANSACTION_REVERTED: Transaction reverted on-chain.");
            }
            throw err;
        }
    }
}
