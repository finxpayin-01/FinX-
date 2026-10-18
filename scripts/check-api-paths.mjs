import fs from 'node:fs';
import path from 'node:path';

function getFiles(dir, exts = ['.ts', '.tsx']) {
    let results = [];
    const list = fs.readdirSync(dir);
    list.forEach(file => {
        const fullPath = path.join(dir, file);
        const stat = fs.statSync(fullPath);
        if (stat && stat.isDirectory()) {
            if (!file.startsWith('.') && file !== 'node_modules') {
                results = results.concat(getFiles(fullPath, exts));
            }
        } else if (exts.some(ext => file.endsWith(ext))) {
            results.push(fullPath);
        }
    });
    return results;
}

const frontendFiles = getFiles('frontend');
// Matches central api method calls: api.get, api.post, etc. handling nested TypeScript generics
const regex = /\bapi\.(?:get|post|put|patch|delete)[^(]*\(\s*[`"'](\/api\/[^`"']*)/g;

let violations = [];

frontendFiles.forEach(file => {
    const content = fs.readFileSync(file, 'utf8');
    let match;
    while ((match = regex.exec(content)) !== null) {
        violations.push({
            file: path.relative('.', file),
            endpoint: match[1],
        });
    }
});

if (violations.length > 0) {
    console.error(`FAILED: Found ${violations.length} central API calls with forbidden leading '/api/' prefix:`);
    violations.forEach(v => console.error(`  - ${v.file}: ${v.endpoint}`));
    process.exit(1);
} else {
    console.log('PASS: Zero central api calls with forbidden leading /api/ prefix found.');
    process.exit(0);
}
