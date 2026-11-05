using System.Text;

namespace Atlas;

public static class PreviewBuilder {
 public static Snapshot Build(Snapshot source,PreviewRequest request){
  if(string.IsNullOrWhiteSpace(request.Title))throw new ArgumentException("Укажите название архитектурного черновика.");
  if(request.Classes.Count>100||request.Relations.Count>300||request.Affected.Count>200)throw new ArgumentException("Черновик слишком велик.");
  var known=source.Classes.ToDictionary(c=>c.Id);
  var planned=new List<ClassNode>();
  foreach(var proposed in request.Classes){
   if(string.IsNullOrWhiteSpace(proposed.Id)||string.IsNullOrWhiteSpace(proposed.Name)||string.IsNullOrWhiteSpace(proposed.Project))throw new ArgumentException("У планируемого типа нужны id, name и project.");
   var id="plan:"+proposed.Id.Trim();
   if(known.ContainsKey(id))throw new ArgumentException("Повторяется id типа: "+proposed.Id);
   var project=source.Projects.FirstOrDefault(p=>p.Id==proposed.Project||p.Name==proposed.Project)??throw new ArgumentException("Проект не найден: "+proposed.Project);
   var relative=(proposed.Path??"").Replace('\\','/');
   if(relative.StartsWith('/')||relative.Split('/').Any(part=>part is ".." or ".")||!relative.EndsWith(".cs",StringComparison.OrdinalIgnoreCase))throw new ArgumentException("Укажите путь нового .cs файла относительно решения.");
   var file=Path.GetFullPath(Path.Combine(source.Root,relative));
   if(!file.StartsWith(source.Root+Path.DirectorySeparatorChar,StringComparison.Ordinal))throw new ArgumentException("Файл вне решения.");
   var fullName=string.IsNullOrWhiteSpace(proposed.Namespace)?proposed.Name:proposed.Namespace+"."+proposed.Name;
   var description=proposed.Description?.Trim()??"";
   var methods=(proposed.Methods??[]).Select((m,i)=>new MethodNode(id+":method:"+i,m.Name,m.Signature??m.Name+"()",m.Returns??"void","// Планируемый метод: "+(m.Description??m.Signature??m.Name),m.Description??"","planned",new SourceLocation(file,1,1),"planned")).ToList();
   var code="// Архитектурный черновик — файла пока нет.\n// "+description.Replace("\n","\n// ")+"\n"+(proposed.Kind=="interface"?"public interface ":"public class ")+proposed.Name+" {\n"+string.Join("\n",methods.Select(m=>"    // "+m.Description+"\n    "+m.Signature+";"))+"\n}";
   var node=new ClassNode(id,proposed.Name,fullName,proposed.Kind=="interface"?"interface":"class",proposed.Namespace??project.Name,project.Name,code,"",code.Split('\n').Length,[new SourceLocation(file,1,code.Split('\n').Length)],methods,"planned"){ProjectId=project.Id};
   known.Add(id,node);planned.Add(node);
  }
  var affected=request.Affected.Distinct().ToHashSet();
  foreach(var id in affected)if(!known.ContainsKey(id)||id.StartsWith("plan:"))throw new ArgumentException("Затронутый тип не найден: "+id);
  var focus=new HashSet<string>(affected);foreach(var c in planned)focus.Add(c.Id);
  var relations=new List<GraphEdge>();
  foreach(var (relation,i) in request.Relations.Select((r,i)=>(r,i))){
   if(!known.ContainsKey(relation.Source)||!known.ContainsKey(relation.Target))throw new ArgumentException("Связь указывает на неизвестный тип: "+relation.Source+" → "+relation.Target);
   var kind=relation.Kind is "implements" or "inherits" or "constructs" or "calls"?relation.Kind:"uses";
   relations.Add(new GraphEdge("plan:edge:"+i,relation.Source,relation.Target,relation.Source,relation.Target,kind,"planned",1));
   focus.Add(relation.Source);focus.Add(relation.Target);
  }
  var classes=source.Classes.Select(c=>affected.Contains(c.Id)?c with{Status="affected"}:c).Concat(planned).ToList();
  return source with{Version="preview:"+Guid.NewGuid().ToString("N"),UpdatedAt=DateTimeOffset.UtcNow,Classes=classes,Edges=source.Edges.Concat(relations).ToList(),PreviewTitle=request.Title.Trim(),PreviewSummary=request.Summary?.Trim(),PreviewFocusIds=focus.ToList()};
 }
}
