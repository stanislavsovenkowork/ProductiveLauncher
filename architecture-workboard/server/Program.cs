using Atlas;
using Microsoft.Build.Locator;
using Microsoft.Extensions.FileProviders;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
MSBuildLocator.RegisterDefaults();
var builder=WebApplication.CreateBuilder(args);
var port=Environment.GetEnvironmentVariable("ATLAS_PORT")??"4318";
builder.WebHost.UseUrls("http://127.0.0.1:"+port);
builder.Services.AddSingleton<WorkspaceService>();builder.Services.AddHostedService(p=>p.GetRequiredService<WorkspaceService>());
var app=builder.Build();
app.Use(async(ctx,next)=>{var host=ctx.Request.Host.Host;if(host is not ("127.0.0.1" or "localhost")){ctx.Response.StatusCode=403;return;}var origin=ctx.Request.Headers.Origin.ToString();if(origin!=""&&origin!="http://127.0.0.1:"+port&&origin!="http://127.0.0.1:4319"){ctx.Response.StatusCode=403;return;}if(ctx.Request.Method=="POST"&&ctx.Request.ContentType?.StartsWith("application/json")!=true){ctx.Response.StatusCode=415;return;}try{await next(ctx);}catch(ArgumentException ex){ctx.Response.StatusCode=400;await ctx.Response.WriteAsJsonAsync(new{error=ex.Message});}catch(Exception ex){ctx.Response.StatusCode=500;await ctx.Response.WriteAsJsonAsync(new{error=ex.Message});}});
app.MapGet("/api/status",(WorkspaceService s)=>new{open=s.Current!=null,busy=s.Busy,error=s.LastError,version=s.Current?.Version,previewVersion=s.Preview?.Version});
app.MapGet("/api/graph",(WorkspaceService s)=>s.Current is {} graph?Results.Ok(graph):Results.NotFound(new{error="Откройте C#-проект."}));
app.MapGet("/api/preview",(WorkspaceService s)=>s.Preview is {} preview?Results.Ok(preview):Results.NotFound(new{error="Нет архитектурного черновика."}));
app.MapPost("/api/preview",(PreviewRequest request,WorkspaceService service)=>Results.Ok(service.PublishPreview(request)));
app.MapDelete("/api/preview",(WorkspaceService service)=>{service.ClearPreview();return Results.Ok(new{cleared=true});});
app.MapPost("/api/open",async(OpenRequest request,WorkspaceService service,CancellationToken ct)=>Results.Ok(await service.Open(request.Path,ct)));
app.MapGet("/api/revisions",async(WorkspaceService service)=>Results.Ok(await service.Revisions()));
app.MapPost("/api/compare",async(CompareRequest request,WorkspaceService service,CancellationToken ct)=>Results.Ok(await service.Compare(request.Target,request.Base,ct)));
app.MapPost("/api/diff-file",async(DiffFileRequest request,WorkspaceService service)=>Results.Ok(await service.DiffFile(request.File,request.Base,request.Target)));
var appRoot=Path.GetFullPath(Path.Combine(app.Environment.ContentRootPath,".."));if(!Directory.Exists(Path.Combine(appRoot,"src")))appRoot=app.Environment.ContentRootPath;
string? stateFile(WorkspaceService service){if(service.Current==null)return null;var key=Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(service.Current.ProjectPath)));return Path.Combine(appRoot,".atlas",key+".json");}
app.MapGet("/api/view",async(WorkspaceService s)=>{var file=stateFile(s);return file!=null&&File.Exists(file)?Results.Text(await File.ReadAllTextAsync(file),"application/json"):Results.Json(new{});});
app.MapPost("/api/view",async(HttpContext ctx,WorkspaceService s)=>{var file=stateFile(s);if(file==null)return Results.BadRequest(new{error="Сначала откройте проект."});if(ctx.Request.ContentLength>2_000_000)return Results.BadRequest();using var json=await JsonDocument.ParseAsync(ctx.Request.Body);Directory.CreateDirectory(Path.GetDirectoryName(file)!);var temporary=file+".tmp";await File.WriteAllTextAsync(temporary,json.RootElement.GetRawText());File.Move(temporary,file,true);return Results.Ok(new{saved=true});});
app.MapGet("/api/sample",()=>new{path=Path.Combine(appRoot,"samples","Commerce","Commerce.csproj")});
var web=Path.Combine(appRoot,"build");if(Directory.Exists(web)){app.UseDefaultFiles(new DefaultFilesOptions{FileProvider=new PhysicalFileProvider(web)});app.UseStaticFiles(new StaticFileOptions{FileProvider=new PhysicalFileProvider(web)});app.MapFallbackToFile("index.html",new StaticFileOptions{FileProvider=new PhysicalFileProvider(web)});}
await app.RunAsync();
