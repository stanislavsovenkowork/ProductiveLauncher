namespace Atlas;
public record SourceLocation(string File, int Line, int EndLine);
public record MethodNode(string Id,string Name,string Signature,string Returns,string Code,string Description,string Kind,SourceLocation Location,string Status);
public record ClassNode(string Id,string Name,string FullName,string Kind,string Group,string Project,string Code,string Fields,int Lines,List<SourceLocation> Locations,List<MethodNode> Methods,string Status) {
 public string ProjectId {get;init;}="";
 public string? ContainingTypeId {get;init;}
 public string Accessibility {get;init;}="";
 public bool Generated {get;init;}
}
public record ProjectNode(string Id,string Name,string Path,string Directory,List<string> References,bool IsTest,List<string> SolutionFolders);
public record GraphEdge(string Id,string Source,string Target,string SourceClass,string TargetClass,string Kind,string Dispatch,int Count) {
 public int? Order {get;init;}
}
public record Issue(string Severity,string Message,string? File=null,int? Line=null);
public record AtlasPipeline(string Id,string Name,string? Description,List<string> Steps);
public record AtlasMetadata(string Directory,List<AtlasPipeline> Pipelines) { public static AtlasMetadata Empty(string directory)=>new(directory,[]); }
public record GitInfo(bool Available,string? Branch,string Baseline,List<string> ChangedFiles,List<string> DeletedFiles);
public record Snapshot(string ProjectName,string Root,string ProjectPath,string Version,DateTimeOffset UpdatedAt,List<ClassNode> Classes,List<GraphEdge> Edges,List<Issue> Diagnostics,int UnresolvedCalls,GitInfo Git) {
 public List<ProjectNode> Projects {get;init;}=[];
 public int SchemaVersion {get;init;}=2;
 public string? DiffTitle {get;init;}
 public string? DiffBase {get;init;}
 public string? DiffTarget {get;init;}
 public string? PreviewTitle {get;init;}
 public string? PreviewSummary {get;init;}
 public List<string> PreviewFocusIds {get;init;}=[];
 public AtlasMetadata Metadata {get;init;}=AtlasMetadata.Empty("");
}
public record OpenRequest(string Path);
public record CompareRequest(string Target,string? Base);
public record DiffFileRequest(string File,string? Base,string? Target);
public record PreviewClass(string Id,string Name,string Project,string Path,string? Namespace,string? Kind,string? Description,List<PreviewMethod>? Methods);
public record PreviewMethod(string Name,string? Signature,string? Returns,string? Description);
public record PreviewRelation(string Source,string Target,string? Kind,string? Description);
public record PreviewRequest(string Title,string? Summary,List<PreviewClass> Classes,List<string> Affected,List<PreviewRelation> Relations);
