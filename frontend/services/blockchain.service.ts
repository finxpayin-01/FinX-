import { api } from "@/lib/api";

export interface ChainConfig {
    configured: boolean; chainId: string; network: string; token: string; symbol: string;
    decimals: number; confirmations: number; testnet: boolean; explorer: string;
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
        if (!provider) throw new Error("No browser wallet is available.");
        return provider;
    }
    async account() {
        const accounts = await this.provider().request({ method: "eth_requestAccounts" }) as string[];
        if (!accounts[0]) throw new Error("No wallet account selected.");
        return accounts[0];
    }
    async signMessage(message: string) {
        const address = await this.account();
        const bytes = Array.from(new TextEncoder().encode(message)).map(n => n.toString(16).padStart(2,"0")).join("");
        return await this.provider().request({ method: "personal_sign", params: [`0x${bytes}`, address] }) as string;
    }
    async send(tx: ChainTransaction) {
        const address = await this.account();
        if (address.toLowerCase() !== tx.from_address.toLowerCase()) throw new Error("Select the wallet authorized for this transaction.");
        const chainId = await this.provider().request({ method: "eth_chainId" }) as string;
        if (BigInt(chainId) !== BigInt(tx.chain_id)) throw new Error(`Select network ${tx.chain_id} in your wallet.`);
        const request = { from: address, to: tx.to_address, data: tx.calldata, value: "0x0" };
        const gas = await this.provider().request({ method: "eth_estimateGas", params: [request] });
        // Wallet owns nonce allocation, fee estimation, signing and broadcast.
        return await this.provider().request({ method: "eth_sendTransaction", params: [{ ...request, gas }] }) as string;
    }
}
