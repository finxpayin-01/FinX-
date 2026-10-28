/**
 * FINX — Secure Development-Only Account Credential Recovery Tool
 *
 * This utility allows developers to set/recover a known password for a local development account
 * (such as demo.buyer@example.com or demo.seller@example.com) without manipulating production accounts.
 *
 * Usage:
 *   node scripts/dev-account-recovery.mjs [email] [newPassword]
 *
 * Examples:
 *   node scripts/dev-account-recovery.mjs demo.buyer@example.com Password123!
 *   node scripts/dev-account-recovery.mjs demo.seller@example.com Password123!
 */

const targetEmail = process.argv[2] || 'demo.buyer@example.com';
const newPassword = process.argv[3] || 'Password123!';
const API_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api';

console.log('====================================================');
console.log('   FINX Dev Account Password Recovery Utility       ');
console.log('====================================================\n');

// 1. Safety Guard: Disallow execution against production
if (API_URL.includes('onrender.com') || API_URL.includes('finx.com') || (!API_URL.includes('localhost') && !API_URL.includes('127.0.0.1'))) {
    console.error(`[BLOCKED] Production safety guard: Cannot run recovery against non-local host: ${API_URL}`);
    process.exit(1);
}

async function recoverViaDevApi() {
    console.log(`[1/2] Connecting to local backend Dev Recovery API (${API_URL}/auth/dev/recover-password)...`);
    const res = await fetch(`${API_URL}/auth/dev/recover-password`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: targetEmail, newPassword })
    });

    if (!res.ok) {
        const errJson = await res.json().catch(() => ({}));
        throw new Error(`Dev recovery failed (HTTP ${res.status}): ${errJson.message || res.statusText}`);
    }

    const data = await res.json();
    return data.data;
}

async function main() {
    try {
        const user = await recoverViaDevApi();
        console.log('\n\x1b[32m[SUCCESS]\x1b[0m Password reset successfully!');
        console.log(`Email:    ${user.email}`);
        console.log(`Role:     ${user.role}`);
        console.log(`Name:     ${user.name}`);
        console.log(`Password: ${newPassword}`);
        console.log('\nYou can now log in at: http://localhost:3000/login\n');
    } catch (err) {
        console.error(`\x1b[31m[FAILED]\x1b[0m ${err.message}`);
        console.log('\nEnsure the backend is running with "dev" profile on http://localhost:8080:');
        console.log('  cd backend; .\\mvnw.cmd spring-boot:run');
        process.exit(1);
    }
}

main();

