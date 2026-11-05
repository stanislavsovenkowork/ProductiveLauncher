using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.MSBuild;
using Microsoft.CodeAnalysis.Text;
using System.Diagnostics;
namespace Atlas;
public sealed class WorkspaceService:BackgroundService {
 readonly SemaphoreSlim gate=new(1,1);MSBuildWorkspace? workspace;Solution? solution;string? projectPath;string fingerprint="";List<Issue> loadIssues=[];
 public Snapshot? Current{get;private set;}public Snapshot? Preview{get;private set;}public string? LastError{get;private set;}public bool Busy{get;private set;}
 public static string Resolve(string input){var p=Path.GetFullPath(input.Trim());if(Directory.Exists(p)){var options=Directory.GetFiles(p).Where(f=>Path.GetExtension(f) is ".sln" or ".slnx").ToArray();if(options.Length==0)options=Directory.GetFiles(p,"*.csproj");if(options.Length!=1)throw new ArgumentException("Укажите конкретный .sln, .slnx или .csproj: в папке найдено "+options.Length+" проектов/решений.");p=options[0];}if(!File.Exists(p)||Path.GetExtension(p) is not (".sln" or ".slnx" or ".csproj"))throw new ArgumentException("Файл .sln, .slnx или .csproj не найден.");return GitReader.Canonical(p);}
 public async Task<Snapshot> Open(string input,CancellationToken ct){var path=Resolve(input);await gate.WaitAsync(ct);Busy=true;MSBuildWorkspace? next=null;try{
  var warnings=new List<Issue>();next=await LoadWorkspace(path,ct,warnings);var loaded=next.CurrentSolution;
  if(NeedsRestore(loaded)){
   next.Dispose();next=null;
   await Restore(path,ct);
   warnings=[];next=await LoadWorkspace(path,ct,warnings);loaded=next.CurrentSolution;
  }
  var snapshot=await Analyzer.Analyze(loaded,path,warnings,ct);snapshot=snapshot with{Metadata=await AtlasMetadataStore.Ensure(snapshot,ct)};workspace?.Dispose();workspace=next;next=null;solution=loaded;projectPath=path;loadIssues=warnings;Current=snapshot;Preview=null;LastError=null;fingerprint=await Fingerprint();return snapshot;
 }catch(Exception ex){LastError=ex.Message;throw;}finally{next?.Dispose();Busy=false;gate.Release();}}
 static async Task<MSBuildWorkspace> LoadWorkspace(string path,CancellationToken ct,List<Issue> warnings){var result=MSBuildWorkspace.Create();result.WorkspaceFailed+=(_,e)=>warnings.Add(new("warning",e.Diagnostic.Message));try{if(Path.GetExtension(path)==".csproj")await result.OpenProjectAsync(path,cancellationToken:ct);else await result.OpenSolutionAsync(path,cancellationToken:ct);return result;}catch{result.Dispose();throw;}}
 static bool NeedsRestore(Solution loaded)=>loaded.Projects.Where(p=>p.Language==LanguageNames.CSharp&&p.FilePath!=null).Any(p=>!File.Exists(Path.Combine(Path.GetDirectoryName(p.FilePath!)!,"obj","project.assets.json")));
 static async Task Restore(string path,CancellationToken ct){
  var start=new ProcessStartInfo("dotnet"){UseShellExecute=false,RedirectStandardOutput=true,RedirectStandardError=true,CreateNoWindow=true};start.ArgumentList.Add("restore");start.ArgumentList.Add(path);start.ArgumentList.Add("--nologo");
  using var process=Process.Start(start)??throw new InvalidOperationException("Не удалось запустить dotnet restore.");
  var stdout=process.StandardOutput.ReadToEndAsync(ct);var stderr=process.StandardError.ReadToEndAsync(ct);await process.WaitForExitAsync(ct);var output=(await stdout)+"\n"+(await stderr);
  if(process.ExitCode!=0)throw new InvalidOperationException("Не удалось восстановить зависимости проекта:\n"+output.Trim());
 }
 public Snapshot PublishPreview(PreviewRequest request){if(Current==null)throw new ArgumentException("Сначала откройте проект.");Preview=PreviewBuilder.Build(Current,request);return Preview;}
 public void ClearPreview()=>Preview=null;
 public async Task<object> Revisions(){if(projectPath==null)throw new ArgumentException("Сначала откройте проект.");var top=await GitReader.Top(Path.GetDirectoryName(projectPath)!);var raw=await GitReader.Run(top,"log","-20","--format=%H%x09%s")??"";return new{commits=raw.Split('\n',StringSplitOptions.RemoveEmptyEntries).Select(line=>line.Split('\t',2)).Where(x=>x.Length==2).Select(x=>new{hash=x[0],subject=x[1]}).ToArray(),branch=(await GitReader.Run(top,"branch","--show-current"))?.Trim()};}
 public async Task<Snapshot> Compare(string targetRef,string? baseRef,CancellationToken ct){
  if(projectPath==null)throw new ArgumentException("Сначала откройте проект.");
  await gate.WaitAsync(ct);Busy=true;
  try{
   var top=await GitReader.Top(Path.GetDirectoryName(projectPath)!);
   var number=0;var isPull=targetRef.StartsWith('#')&&int.TryParse(targetRef[1..],out number);
   var target=isPull?await GitReader.Pull(top,number):await GitReader.Commit(top,targetRef);
   var baseline=string.IsNullOrWhiteSpace(baseRef)?isPull?await GitReader.Commit(top,(await GitReader.Run(top,"merge-base","HEAD",target))?.Trim()??throw new ArgumentException("Общая база PR не найдена.")):await GitReader.Commit(top,target+"^"):await GitReader.Commit(top,baseRef);
   var repositoryKey=Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(top)))[..12];
   var checkout=Path.Combine(Path.GetTempPath(),"atlas-revisions",repositoryKey,target);
   if(!Directory.Exists(checkout)){Directory.CreateDirectory(Path.GetDirectoryName(checkout)!);if(await GitReader.Run(top,"worktree","add","--detach",checkout,target)==null)throw new ArgumentException("Не удалось открыть снимок коммита в отдельной рабочей копии.");}
   var path=Path.Combine(checkout,Path.GetRelativePath(top,projectPath));
   var comparison=await GitReader.Compare(top,checkout,baseline,target);
   using var revision=MSBuildWorkspace.Create();var warnings=new List<Issue>();revision.WorkspaceFailed+=(_,e)=>warnings.Add(new("warning",e.Diagnostic.Message));
   var loaded=Path.GetExtension(path)==".csproj"?(await revision.OpenProjectAsync(path,cancellationToken:ct)).Solution:await revision.OpenSolutionAsync(path,cancellationToken:ct);
   var snapshot=await Analyzer.Analyze(loaded,path,warnings,ct,comparison);
   var subject=(await GitReader.Run(top,"log","-1","--format=%s",target))?.Trim();
   return snapshot with{DiffTitle=subject,DiffBase=baseline,DiffTarget=target,Metadata=await AtlasMetadataStore.Read(snapshot,ct)};
  }finally{Busy=false;gate.Release();}
 }
 public async Task<object> DiffFile(string file,string? baseRef,string? targetRef){
  if(projectPath==null)throw new ArgumentException("Сначала откройте проект.");
  var top=await GitReader.Top(Path.GetDirectoryName(projectPath)!);
  var target=string.IsNullOrWhiteSpace(targetRef)?null:await GitReader.Commit(top,targetRef);
  var baseline=await GitReader.Commit(top,string.IsNullOrWhiteSpace(baseRef)?"HEAD":baseRef);
  var root=target==null?top:Path.Combine(Path.GetTempPath(),"atlas-revisions",Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(top)))[..12],target);
  var full=Path.GetFullPath(file);
  var relative=Path.GetRelativePath(root,full);
  if(Path.IsPathRooted(relative)||relative==".."||relative.StartsWith(".."+Path.DirectorySeparatorChar)||Path.GetExtension(full)!=".cs"||!File.Exists(full))throw new ArgumentException("Файл вне открытого проекта.");
  var gitPath=relative.Replace(Path.DirectorySeparatorChar,'/');
  var original=await GitReader.Run(top,"show",baseline+":"+gitPath)??"";
  return new {original,modified=await File.ReadAllTextAsync(full),file=gitPath};
 }
 IEnumerable<string> Files(){if(projectPath==null)return [];var roots=(solution?.Projects.Select(p=>Path.GetDirectoryName(p.FilePath!)!)??[]).Append(Path.GetDirectoryName(projectPath)!).Distinct();var found=new HashSet<string>();var options=new EnumerationOptions{RecurseSubdirectories=false,IgnoreInaccessible=true,AttributesToSkip=FileAttributes.ReparsePoint};foreach(var root in roots){var stack=new Stack<string>();stack.Push(root);while(stack.Count>0){var dir=stack.Pop();foreach(var child in Directory.EnumerateDirectories(dir,"*",options)){if(Path.GetFileName(child)==".atlas"){foreach(var metadata in Directory.EnumerateFiles(child,"*",options))if(Path.GetFileName(metadata) is "architecture.md" or "index.json" or "methods.json" or "pipelines.json")found.Add(metadata);continue;}if(Path.GetFileName(child) is not ("bin" or "obj" or ".git" or "node_modules"))stack.Push(child);}foreach(var f in Directory.EnumerateFiles(dir,"*",options)){if(Path.GetExtension(f) is ".cs" or ".csproj" or ".sln" or ".slnx" or ".props" or ".targets"||Path.GetFileName(f)=="global.json")found.Add(f);}}}return found.Order();}
 async Task<string> Fingerprint(){var files=string.Join("|",Files().Select(f=>f+":"+new FileInfo(f).LastWriteTimeUtc.Ticks+":"+new FileInfo(f).Length));var status=projectPath==null?"":await GitReader.Run(Path.GetDirectoryName(projectPath)!,"status","--porcelain=v1","-z","--untracked-files=all");var head=projectPath==null?"":await GitReader.Run(Path.GetDirectoryName(projectPath)!,"rev-parse","HEAD");return files+status+head;}
 protected override async Task ExecuteAsync(CancellationToken stop){while(!stop.IsCancellationRequested){try{await Task.Delay(1500,stop);if(projectPath==null||Busy)continue;var now=await Fingerprint();if(now==fingerprint)continue;await Open(projectPath,stop);}catch(OperationCanceledException)when(stop.IsCancellationRequested){break;}catch(Exception ex){LastError=ex.Message;await Task.Delay(1500,stop);}}}
 public override void Dispose(){workspace?.Dispose();gate.Dispose();base.Dispose();}
}
