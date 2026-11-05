using Microsoft.CodeAnalysis;
using System.Text.RegularExpressions;
using System.Xml.Linq;
namespace Atlas;
public static class ProjectFacts {
 public static string Key(Project p,string root)=>System.IO.Path.GetRelativePath(root,p.FilePath??p.Name).Replace('\\','/');
 public static List<ProjectNode> Read(Solution solution,string path) {
  var root=System.IO.Path.GetDirectoryName(path)!;
  var folders=new Dictionary<string,List<string>>(StringComparer.OrdinalIgnoreCase);
  if(System.IO.Path.GetExtension(path)==".sln") {
   var text=File.ReadAllText(path);var entries=new Dictionary<string,(string Name,string Path,bool Folder)>();var parents=new Dictionary<string,string>();
   foreach(Match m in Regex.Matches(text,"Project\\(\"([^\"]+)\"\\) = \"([^\"]+)\", \"([^\"]+)\", \"([^\"]+)\""))entries[m.Groups[4].Value]=(m.Groups[2].Value,m.Groups[3].Value,m.Groups[1].Value.Contains("2150E333",StringComparison.OrdinalIgnoreCase));
   var nested=Regex.Match(text,"GlobalSection\\(NestedProjects\\).*?EndGlobalSection",RegexOptions.Singleline).Value;
   foreach(Match m in Regex.Matches(nested,@"(\{[^}]+\})\s*=\s*(\{[^}]+\})"))parents[m.Groups[1].Value]=m.Groups[2].Value;
   foreach(var (id,e) in entries.Where(e=>!e.Value.Folder)){var chain=new List<string>();var seen=new HashSet<string>();var at=id;while(parents.TryGetValue(at,out var parent)&&seen.Add(parent)&&entries.TryGetValue(parent,out var group)){chain.Insert(0,group.Name);at=parent;}folders[e.Path.Replace('\\','/')]=chain;}
  } else if(System.IO.Path.GetExtension(path)==".slnx") {
   var xml=XDocument.Load(path);foreach(var project in xml.Descendants("Project")){var p=project.Attribute("Path")?.Value;if(p!=null)folders[p.Replace('\\','/')]=project.Ancestors("Folder").Reverse().Select(f=>f.Attribute("Name")?.Value.Trim('/')??"").Where(s=>s!="").ToList();}
  }
  return solution.Projects.Select(p=>{
   var id=Key(p,root);bool test=Regex.IsMatch(p.Name,@"(^|[.\-_])(Tests?|IntegrationTests)([.\-_]|$)",RegexOptions.IgnoreCase);
   if(p.FilePath!=null){try{var xml=XDocument.Load(p.FilePath);test|=xml.Descendants().Any(e=>e.Name.LocalName=="IsTestProject"&&e.Value.Equals("true",StringComparison.OrdinalIgnoreCase)||e.Name.LocalName=="PackageReference"&&(e.Attribute("Include")?.Value=="Microsoft.NET.Test.Sdk"));}catch{}}
   return new ProjectNode(id,p.Name,id,System.IO.Path.GetDirectoryName(id)?.Replace('\\','/')??"",p.ProjectReferences.Select(r=>solution.GetProject(r.ProjectId)).Where(r=>r!=null).Select(r=>Key(r!,root)).ToList(),test,folders.GetValueOrDefault(id,[]));
  }).ToList();
 }
}
