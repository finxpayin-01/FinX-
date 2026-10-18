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
const regex = /(?:api\.(?:get|post|put|patch|delete)|fetch)\s*(?:<[^>]+>)?\s*\(\s*[`"']([^`"'$]+)/g;
const calls = [];

frontendFiles.forEach(file => {
    const content = fs.readFileSync(file, 'utf8');
    let match;
    while ((match = regex.exec(content)) !== null) {
        calls.push({ file: path.relative('.', file), endpoint: match[1] });
    }
});

console.log('Total API calls found in frontend:', calls.length);
const endpointMap = {};
calls.forEach(c => {
    if (!endpointMap[c.endpoint]) endpointMap[c.endpoint] = [];
    endpointMap[c.endpoint].push(c.file);
});

console.log(JSON.stringify(endpointMap, null, 2));
