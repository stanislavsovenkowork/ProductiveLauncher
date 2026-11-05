using System.Diagnostics;
namespace Atlas;
public static class GitReader {
 public static string Canonical(string value){var full=Path.GetFullPath(value);var current=Path.GetPathRoot(full)!;foreach(var part in full[current.Length..].Split(Path.DirectorySeparatorChar,StringSplitOptions.RemoveEmptyEntries)){current=Path.Combine(current,part);FileSystemInfo info=Directory.Exists(current)?new DirectoryInfo(current):new FileInfo(current);if(info.LinkTarget!=null)current=info.ResolveLinkTarget(true)?.FullName??current;}return current;}

 public static async Task<string?> Run(string cwd,params string[] args){
  try{var start=new ProcessStartInfo("git"){WorkingDirectory=cwd,RedirectStandardOutput=true,RedirectStandardError=true};foreach(var a in args)start.ArgumentList.Add(a);using var p=Process.Start(start)!;var text=p.StandardOutput.ReadToEndAsync();var errors=p.StandardError.ReadToEndAsync();using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(args.FirstOrDefault()=="fetch"?60:15));try{await p.WaitForExitAsync(timeout.Token);}catch{p.Kill(true);return null;}await errors;return p.ExitCode==0?await text:null;}catch{return null;}
 }
 public static async Task<(GitInfo Info,Dictionary<string,string> States)> Read(string folder){
  var top=(await Run(folder,"rev-parse","--show-toplevel"))?.Trim();var states=new Dictionary<string,string>();if(top is null)return(new(false,null,"HEAD",[],[]),states);
  var branch=(await Run(top,"branch","--show-current"))?.Trim();var raw=await Run(top,"status","--porcelain=v1","-z","--untracked-files=all")??"";var chunks=raw.Split('\0');var removed=new List<string>();
  for(int i=0;i<chunks.Length;i++){var part=chunks[i];if(part.Length<4)continue;var xy=part[..2];var name=part[3..];var absolute=Canonical(Path.Combine(top,name));if(xy.Contains('R')||xy.Contains('C')){if(i+1<chunks.Length){var old=Canonical(Path.Combine(top,chunks[++i]));if(xy.Contains('R'))removed.Add(old);}}if(xy.Contains('D'))removed.Add(absolute);else states[absolute]=xy=="??"||xy.Contains('A')?"new":"changed";}
  return(new(true,branch,"HEAD",states.Keys.ToList(),removed),states);
 }
 public static async Task<string> Top(string folder)=>(await Run(folder,"rev-parse","--show-toplevel"))?.Trim()??throw new ArgumentException("Git-репозиторий не найден.");
 public static async Task<string> Commit(string folder,string reference){
  if(string.IsNullOrWhiteSpace(reference)||reference.StartsWith('-')||reference.Contains('\n'))throw new ArgumentException("Укажите коммит, ветку или PR #123.");
  var hash=(await Run(folder,"rev-parse","--verify","--end-of-options",reference+"^{commit}"))?.Trim();
  return hash is {Length:40}&&hash.All(Uri.IsHexDigit)?hash:throw new ArgumentException("Git-ссылка не найдена: "+reference);
 }
 public static async Task<string> Pull(string folder,int number){
  if(number<=0)throw new ArgumentException("Некорректный номер PR.");
  var fetched=await Run(folder,"fetch","--no-tags","origin","pull/"+number+"/head");
  if(fetched==null)throw new ArgumentException("Не удалось получить PR #"+number+". Проверьте доступ к origin.");
  return await Commit(folder,"FETCH_HEAD");
 }
 public static async Task<(GitInfo Info,Dictionary<string,string> States)> Compare(string sourceRoot,string targetRoot,string baseline,string target){
  var raw=await Run(sourceRoot,"diff","--name-status","-z","--find-renames",baseline,target,"--")??throw new ArgumentException("Не удалось сравнить коммиты.");
  var parts=raw.Split('\0');var changed=new Dictionary<string,string>();var removed=new List<string>();
  for(var i=0;i<parts.Length-1;){var status=parts[i++];if(status.Length==0)break;var old=parts[i++];var path=old;if(status[0] is 'R' or 'C'){if(i>=parts.Length)break;path=parts[i++];if(status[0]=='R')removed.Add(Canonical(Path.Combine(targetRoot,old)));}
   if(status[0]=='D')removed.Add(Canonical(Path.Combine(targetRoot,path)));else changed[Canonical(Path.Combine(targetRoot,path))]=status[0]=='A'?"new":"changed";
  }
  return(new(true,null,baseline,changed.Keys.ToList(),removed),changed);
 }
}
