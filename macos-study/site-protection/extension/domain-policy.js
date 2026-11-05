// Browser-side defence in depth. Domain configuration is supplied by the
// authenticated agent; this code never sends page addresses back to it.
const SiteDomains = (() => {
  const protectedHosts = ['gitlab.eskhata.com', 'openai.com', 'chatgpt.com'];
  const under = (host, domain) => host === domain || host.endsWith('.' + domain);
  function valid(domain, includeSubdomains) {
    if (typeof domain !== 'string' || domain !== domain.toLowerCase() || domain.length > 253) return false;
    const labels = domain.split('.');
    if (labels.length < 2 || !/[a-z]/.test(labels[labels.length - 1])) return false;
    if (!labels.every(label => label.length > 0 && label.length <= 63 && /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(label))) return false;
    // A selected ancestor would include protected infrastructure as well.
    return !protectedHosts.some(host => under(domain, host) || (includeSubdomains && under(host, domain)));
  }
  function validList(domains, includeSubdomains) {
    return Array.isArray(domains) && domains.length <= 32 &&
      domains.reduce((n, d) => n + (typeof d === 'string' ? d.length : 2049), 0) <= 2048 &&
      new Set(domains).size === domains.length && typeof includeSubdomains === 'boolean' && domains.every(domain => valid(domain, includeSubdomains));
  }
  function matches(hostname, domains, includeSubdomains) {
    if (!validList(domains, includeSubdomains) || typeof hostname !== 'string') return false;
    const host = hostname.toLowerCase().replace(/\.$/, '');
    if (protectedHosts.some(protectedHost => under(host, protectedHost))) return false;
    return domains.some(domain => includeSubdomains ? under(host, domain) : host === domain);
  }
  return Object.freeze({validList, matches});
})();
