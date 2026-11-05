import {spawn,spawnSync} from 'node:child_process';
import {existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import os from 'node:os';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const local=path.join(os.homedir(),'.local/share/atlas-dotnet/dotnet');
const dotnet=process.env.ATLAS_DOTNET||(existsSync(local)?local:'dotnet');
const env={...process.env,DOTNET_CLI_TELEMETRY_OPTOUT:'1',DOTNET_NOLOGO:'1',...(path.isAbsolute(dotnet)?{DOTNET_ROOT:path.dirname(dotnet),PATH:path.dirname(dotnet)+path.delimiter+process.env.PATH}:{})};
function run(bin,args){const p=spawnSync(bin,args,{cwd:root,env,stdio:'inherit'});if(p.error){console.error('Не найден .NET SDK 10. Установите его или задайте ATLAS_DOTNET.');process.exit(1)}if(p.status!==0)process.exit(p.status??1)}
run(dotnet,['build','server/Atlas.Server.csproj','--nologo']);
run(dotnet,['restore','samples/Commerce/Commerce.csproj','--nologo']);
run(process.platform==='win32'?'npm.cmd':'npm',['run','build']);
const child=spawn(dotnet,['run','--no-build','--project','server/Atlas.Server.csproj'],{cwd:root,env,stdio:'inherit'});
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>child.kill(signal));
child.on('exit',code=>process.exit(code??0));
