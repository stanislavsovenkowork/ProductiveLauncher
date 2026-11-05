using System.Text.Json;

namespace Atlas;

public static class AtlasMetadataStore {
 static readonly JsonSerializerOptions Json=new(){WriteIndented=true,PropertyNameCaseInsensitive=true};
 public static async Task<AtlasMetadata> Ensure(Snapshot snapshot,CancellationToken ct){
  var directory=Path.Combine(snapshot.Root,".atlas");Directory.CreateDirectory(directory);
  await WriteIfChanged(Path.Combine(directory,"index.json"),JsonSerializer.Serialize(new{version=1,solution=snapshot.ProjectPath,generatedBy="Atlas",files=new[]{"architecture.md","methods.json","pipelines.json"}},Json),ct);
  var methods=snapshot.Classes.SelectMany(c=>c.Methods.Select(m=>new{id=m.Id,type=c.FullName,method=m.Name,signature=m.Signature,file=m.Location.File,line=m.Location.Line})).OrderBy(m=>m.type).ThenBy(m=>m.method).ToList();
  await WriteIfChanged(Path.Combine(directory,"methods.json"),JsonSerializer.Serialize(new{version=1,generatedBy="Atlas",methods},Json),ct);
  await WriteIfMissing(Path.Combine(directory,"architecture.md"),$"# {snapshot.ProjectName}\n\nЭтот каталог хранит дополнительный контекст Atlas. Исходный код не генерируется и не меняется.\n\n- `pipelines.json` — явные последовательности методов для карты.\n- `methods.json` — автоматически обновляемый индекс методов с их точными идентификаторами.\n- `index.json` — индекс формата и решения.\n\nДобавь последовательность в `pipelines.json` в виде `{{ \"id\": \"checkout\", \"name\": \"Оформление заказа\", \"description\": \"…\", \"steps\": [\"METHOD_ID\", \"METHOD_ID\"] }}`. Идентификаторы берутся из `methods.json`. Atlas покажет только существующие вызовы между соседними шагами.\n",ct);
  await WriteIfMissing(Path.Combine(directory,"pipelines.json"),"{\n  \"version\": 1,\n  \"pipelines\": []\n}\n",ct);
  return await Read(snapshot,ct);
 }
 public static async Task<AtlasMetadata> Read(Snapshot snapshot,CancellationToken ct){
  var directory=Path.Combine(snapshot.Root,".atlas");var file=Path.Combine(directory,"pipelines.json");
  if(!File.Exists(file))return AtlasMetadata.Empty(directory);
  try{
   await using var stream=File.OpenRead(file);var document=await JsonSerializer.DeserializeAsync<PipelineDocument>(stream,Json,ct);
   var methods=snapshot.Classes.SelectMany(c=>c.Methods).Select(m=>m.Id).ToHashSet();
   var pipelines=(document?.Pipelines??[]).Where(p=>!string.IsNullOrWhiteSpace(p.Name)).Select(p=>new AtlasPipeline(string.IsNullOrWhiteSpace(p.Id)?Guid.NewGuid().ToString("N"):p.Id,p.Name!,p.Description,(p.Steps??[]).Where(methods.Contains).Distinct().ToList())).Where(p=>p.Steps.Count>0).ToList();
   return new AtlasMetadata(directory,pipelines);
  }catch(JsonException){return AtlasMetadata.Empty(directory);}
 }
 static async Task WriteIfMissing(string path,string contents,CancellationToken ct){if(File.Exists(path))return;await File.WriteAllTextAsync(path,contents,ct);}
 static async Task WriteIfChanged(string path,string contents,CancellationToken ct){if(File.Exists(path)&&await File.ReadAllTextAsync(path,ct)==contents)return;await File.WriteAllTextAsync(path,contents,ct);}
 sealed record PipelineDocument(int Version,List<PipelineDocumentEntry>? Pipelines);
 sealed record PipelineDocumentEntry(string? Id,string? Name,string? Description,List<string>? Steps);
}
