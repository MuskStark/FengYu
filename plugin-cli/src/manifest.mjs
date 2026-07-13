import fs from 'node:fs/promises'; import path from 'node:path';
export async function readManifest(root) { return JSON.parse(await fs.readFile(path.join(root, 'manifest.json'), 'utf8')) }
export async function validate(root) {
  const errors=[]; let m; try{m=await readManifest(root)}catch(e){return [`manifest.json: ${e.message}`]}
  if(m.schemaVersion!==1)errors.push('schemaVersion must be 1');
  if(!/^[a-z0-9]+(?:[.-][a-z0-9]+)+$/.test(m.id??''))errors.push('id must be a lowercase reverse-domain identifier');
  if(!/^\d+\.\d+\.\d+(?:[-+].+)?$/.test(m.version??''))errors.push('version must use semantic versioning');
  if(!m.name?.trim())errors.push('name is required'); if(!m.ui?.entry)errors.push('ui.entry is required');
  else try{const entry=path.resolve(root,m.ui.entry);if(!entry.startsWith(path.resolve(root)+path.sep))errors.push('ui.entry escapes package root');else await fs.access(entry)}catch{errors.push(`UI entry does not exist: ${m.ui.entry}`)}
  if(m.backend&&!['json-rpc-2.0'].includes(m.backend.protocol))errors.push('backend.protocol must be json-rpc-2.0');
  if(m.backend?.command){const match=m.backend.command.match(/(?:^|\s)-jar\s+(?:\"([^\"]+)\"|'([^']+)'|(\S+))/);if(match){const jar=match[1]??match[2]??match[3];try{await fs.access(path.resolve(root,jar))}catch{errors.push(`backend JAR does not exist: ${jar}`)}}}
  const allowed=new Set(['files.read','files.write','network','clipboard.read','clipboard.write','notifications']);
  for(const p of m.permissions??[])if(!allowed.has(p))errors.push(`unknown permission: ${p}`);
  const names=new Set();for(const t of m.aiTools??[]){if(!t.name||names.has(t.name))errors.push(`invalid or duplicate AI tool: ${t.name}`);names.add(t.name);try{JSON.parse(t.inputSchema)}catch{errors.push(`invalid inputSchema for ${t.name}`)}}
  return errors;
}
