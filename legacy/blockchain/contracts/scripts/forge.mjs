import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';

// Launch the integrity-pinned platform binary directly: upstream 1.7.1's
// npm wrapper does not propagate the child exit status, masking test failures.
const require = createRequire(import.meta.url);
const arch = { x64: 'amd64', arm64: 'arm64' }[process.arch];
if (!arch || !['linux', 'darwin', 'win32'].includes(process.platform)) {
  throw new Error(`Unsupported Foundry platform: ${process.platform}/${process.arch}`);
}
const pkg = `@foundry-rs/forge-${process.platform}-${arch}`;
const binary = join(dirname(require.resolve(`${pkg}/package.json`)), 'bin', process.platform === 'win32' ? 'forge.exe' : 'forge');
const result = spawnSync(binary, process.argv.slice(2), { stdio: 'inherit', windowsHide: true });
if (result.error) throw result.error;
process.exit(result.status ?? 1);
