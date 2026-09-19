import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';

const require = createRequire(import.meta.url);
const arch = { x64: 'amd64', arm64: 'arm64' }[process.arch];
if (!arch || !['linux', 'darwin', 'win32'].includes(process.platform)) {
  throw new Error(`Unsupported Foundry platform: ${process.platform}/${process.arch}`);
}
const pkg = `@foundry-rs/cast-${process.platform}-${arch}`;
const binary = join(dirname(require.resolve(`${pkg}/package.json`)), 'bin', process.platform === 'win32' ? 'cast.exe' : 'cast');
const result = spawnSync(binary, process.argv.slice(2), { stdio: 'inherit', windowsHide: true });
if (result.error) throw result.error;
process.exit(result.status ?? 1);
