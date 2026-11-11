import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const tracked = execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8' }).split('\0').filter(Boolean);
if (!tracked.includes('README.md') || !tracked.some(path => path.startsWith('vehicle/src/'))) {
    throw new Error('Expected source files are missing from the index');
}
const excluded = /(?:^|\/)(?:local\.properties|\.env(?:\..*)?|[^/]+\.(?:jks|p12|clixml|keystore|aar)|verification|work|\.unlazy)(?:$|\/)/i;
const secret = /-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----|github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|Co-Authored-By:|Generated with (?:Codex|Claude|ChatGPT)/i;
for (const path of tracked) {
    if (excluded.test(path)) throw new Error(`Unexpected staged path: ${path}`);
    if (/\.(?:java|gradle|kts|xml|md|properties|ps1|bat|mjs)$/i.test(path) && !path.endsWith('check-repository.mjs')) {
        if (secret.test(readFileSync(path, 'utf8'))) throw new Error(`Unexpected credential or attribution: ${path}`);
    }
}
console.log(`REPOSITORY_CHECKS_PASSED files=${tracked.length}`);
