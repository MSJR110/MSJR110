// Builds assets/emoji/all.txt from Unicode's emoji-test.txt:
//   "@<group>" lines, then one emoji per line; emoji with skin tones carry their 5 uniform-tone variants after a tab.
const fs = require('fs');
const [src, out] = process.argv.slice(2);
const TONES = [0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF];
const isTone = c => c >= 0x1F3FB && c <= 0x1F3FF;
const groups = []; let cur = null;
const variants = new Map(); // key (no tones, no FE0F) -> [5]
const order = [];
for (const line of fs.readFileSync(src, 'utf8').split('\n')) {
  if (line.startsWith('# group:')) { cur = { name: line.slice(8).trim(), items: [] }; groups.push(cur); continue; }
  if (!line || line.startsWith('#')) continue;
  const m = line.match(/^([0-9A-F ]+);\s*fully-qualified/);
  if (!m) continue;
  const cps = m[1].trim().split(/\s+/).map(h => parseInt(h, 16));
  const tones = cps.filter(isTone);
  const key = cps.filter(c => !isTone(c) && c !== 0xFE0F).join(',');
  const str = String.fromCodePoint(...cps);
  if (tones.length === 0) { cur.items.push({ key, str }); continue; }
  if (tones.every(t => t === tones[0])) {
    if (!variants.has(key)) variants.set(key, []);
    variants.get(key)[TONES.indexOf(tones[0])] = str;
  }
}
const lines = [];
let n = 0, nt = 0;
for (const g of groups) {
  if (g.name === 'Component') continue;
  lines.push('@' + g.name);
  let items = g.items;
  if (g.name === 'Flags') {
    const ir = items.findIndex(i => i.str === '\u{1F1EE}\u{1F1F7}');
    if (ir > 0) { const [x] = items.splice(ir, 1); items.unshift(x); }
  }
  for (const it of items) {
    const v = variants.get(it.key);
    if (v && v.length === 5 && v.every(Boolean)) { lines.push(it.str + '\t' + v.join(' ')); nt++; } else lines.push(it.str);
    n++;
  }
}
fs.writeFileSync(out, lines.join('\n') + '\n');
console.log(n, 'emoji,', nt, 'with skin tones,', groups.length, 'groups');
