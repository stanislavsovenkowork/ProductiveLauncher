const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const code = fs.readFileSync('macos-study/site-protection/extension/domain-policy.js', 'utf8');
const policy = vm.runInNewContext(code + '\nSiteDomains');
const selected = ['youtube.com'];
for (const host of ['youtube.com', 'www.youtube.com', 'm.youtube.com', 'a.b.youtube.com', 'WWW.YOUTUBE.COM', 'www.youtube.com.'])
  assert.equal(policy.matches(host, selected, true), true, host);
for (const host of ['notyoutube.com', 'youtube.com.evil.test', 'not.youtube.com.evil.test', 'evil.test'])
  assert.equal(policy.matches(host, selected, true), false, host);
assert.equal(policy.matches('youtube.com', selected, false), true);
assert.equal(policy.matches('youtube.com.', selected, false), true);
assert.equal(policy.matches('www.youtube.com', selected, false), false);
assert.equal(policy.matches('m.youtube.com', selected, false), false);
assert.equal(policy.matches('youtube.com', selected, undefined), false);
for (const protectedName of ['gitlab.eskhata.com', 'api.gitlab.eskhata.com', 'openai.com', 'api.openai.com', 'chatgpt.com', 'x.chatgpt.com']) {
  assert.equal(policy.validList([protectedName], true), false, protectedName);
  assert.equal(policy.validList([protectedName], false), false, protectedName);
  assert.equal(policy.matches(protectedName, [protectedName], true), false, protectedName);
}
assert.equal(policy.validList(['eskhata.com'], true), false);
assert.equal(policy.validList(['eskhata.com'], false), true);
assert.equal(policy.matches('eskhata.com', ['eskhata.com'], false), true);
assert.equal(policy.matches('gitlab.eskhata.com', ['eskhata.com'], false), false);
for (const bad of [['*.youtube.com'], ['Youtube.com'], ['youtube.com/path'], ['youtube.com:443'], ['youtube.com','youtube.com'], [false], ['-bad.example']])
  assert.equal(policy.validList(bad, true), false, JSON.stringify(bad));
assert.equal(policy.validList([], false), true);
assert.equal(policy.validList([], undefined), false);
console.log('PASS: legacy exact vs opt-in subdomains, DNS label boundaries, protected hosts/parents and malformed policies.');
