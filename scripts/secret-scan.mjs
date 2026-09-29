import { execSync } from 'child_process';
import fs from 'fs';

function scanDiff() {
  console.log('====================================================');
  console.log('FINX SECRETS & CREDENTIALS DIFF SCANNER');
  console.log('====================================================\n');

  const diff = execSync('git diff 9857d09', { maxBuffer: 20 * 1024 * 1024 }).toString();
  const lines = diff.split('\n');

  const secretPatterns = [
    { type: 'JWT Token', regex: /eyJ[A-Za-z0-9-_=]+\.[A-Za-z0-9-_=]+\.?[A-Za-z0-9-_.+/=]*/g },
    { type: 'Private Key', regex: /-----BEGIN [A-Z ]*PRIVATE KEY-----/g },
    { type: 'AWS Key', regex: /AKIA[0-9A-Z]{16}/g },
    { type: 'Google API/OAuth Key', regex: /AIza[0-9A-Za-z-_]{35}/g },
    { type: 'Generic API Key / Secret Key', regex: /(?:api[_-]?key|secret[_-]?key|client[_-]?secret)\s*[:=]\s*["']([^"']{8,})["']/gi },
    { type: 'Hardcoded Database Password', regex: /(?:password|passwd|pwd)\s*[:=]\s*["']([^"']{6,})["']/gi },
    { type: 'Supabase Secret', regex: /sbp_[a-zA-Z0-9]{40}/g },
    { type: 'Private URL with credentials', regex: /https?:\/\/[^:\s]+:[^@\s]+@/g }
  ];

  let currentFile = 'unknown';
  const findings = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.startsWith('diff --git')) {
      const parts = line.split(' ');
      currentFile = parts[2].replace(/^a\//, '');
      continue;
    }
    // Only inspect added lines
    if (line.startsWith('+') && !line.startsWith('+++')) {
      const addedContent = line.slice(1).trim();

      for (const p of secretPatterns) {
        p.regex.lastIndex = 0;
        let match;
        while ((match = p.regex.exec(addedContent)) !== null) {
          // Filter out obvious false positives, variable placeholders, dummy strings, test files
          const matchedVal = match[1] || match[0];

          // Check if placeholder or env variable reference
          if (matchedVal.includes('${') || matchedVal.includes('process.env') || matchedVal === 'password' || matchedVal === 'Password123!') {
            // Test mock password in test files
            if (currentFile.includes('src/test/') && matchedVal === 'Password123!') {
              continue;
            }
          }

          // Check if mock dummy values
          if (matchedVal.includes('MOCK-') || matchedVal.includes('EXAMPLE') || matchedVal.includes('placeholder')) {
            continue;
          }

          findings.push({
            file: currentFile,
            type: p.type,
            lineNum: i + 1,
            isTestFile: currentFile.includes('test') || currentFile.includes('scripts/'),
            matchedSnippet: matchedVal.substring(0, 4) + '****'
          });
        }
      }
    }
  }

  console.log(`TOTAL SUSPICIOUS TOKENS IDENTIFIED: ${findings.length}`);
  const productionFindings = findings.filter(f => !f.isTestFile);
  console.log(`PRODUCTION CODE FINDINGS: ${productionFindings.length}`);
  console.log(`TEST CODE FINDINGS: ${findings.filter(f => f.isTestFile).length}\n`);

  for (const f of findings) {
    console.log(`[${f.isTestFile ? 'TEST' : 'PROD'}] FILE: ${f.file} | TYPE: ${f.type}`);
  }

  if (productionFindings.length > 0) {
    console.log('\n⚠️ WARNING: Potential secrets in production files detected!');
  } else {
    console.log('\n✅ NO PRODUCTION SECRETS, API KEYS, OR PRIVATE CREDENTIALS COMMITTED OR MODIFIED.');
  }

  return findings;
}

scanDiff();
