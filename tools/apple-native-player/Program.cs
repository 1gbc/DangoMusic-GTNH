using System.Net.Http.Headers;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        if(args.Contains("--drm-self-test"))return MediaProbe.Run();
        if (args.Contains("--self-test"))
        {
            if(!HideOnClose(CloseReason.UserClosing,false)||HideOnClose(CloseReason.ApplicationExitCall,true))return 9;
            if (ValidLink("https://music.apple.com/#secret")) return 2;
            if (ValidLink("http://localhost:1234/#" + new string('a',64))) return 3;
            if (!ValidLink("http://127.0.0.1:1234/#" + new string('a',64))) return 4;
            try { return string.IsNullOrEmpty(CoreWebView2Environment.GetAvailableBrowserVersionString()) ? 5 : 0; }
            catch { return 6; }
        }
        int startupStage=10;
        try
        {
            using var input=new StreamReader(Console.OpenStandardInput(),new UTF8Encoding(false,true));
            startupStage=11;
            // Pairing credentials arrive through a private parent/child pipe, never argv or disk.
            var bootstrap=JsonNode.Parse(input.ReadLine() ?? "")!;
            startupStage=12;
            string link=bootstrap["link"]!.GetValue<string>();
            string profile=bootstrap["profile"]!.GetValue<string>();
            if (!ValidLink(link)||!Path.IsPathFullyQualified(profile)) return 7;
            startupStage=13;
            ApplicationConfiguration.Initialize();
            startupStage=14;
            int parentPid=bootstrap["parentPid"]?.GetValue<int>()??0;
            if(parentPid<=0)return 8;
            startupStage=15;
            Application.Run(new PlayerWindow(new Uri(link), profile,parentPid));
            return 0;
        }
        catch { return 100+startupStage; } // Stage codes only; never log account data or bootstrap.
    }
    internal static bool ValidLink(string value) => Uri.TryCreate(value,UriKind.Absolute,out var u)
        && u.Scheme=="http" && u.Host=="127.0.0.1" && u.Port>0 && u.AbsolutePath=="/" && u.Query=="" && u.UserInfo==""
        && System.Text.RegularExpressions.Regex.IsMatch(u.Fragment,"^#[a-f0-9]{64}$");
    internal static bool HideOnClose(CloseReason reason,bool exiting) => reason==CloseReason.UserClosing&&!exiting;
}

internal sealed class PlayerWindow : Form
{
    private readonly WebView2 web = new() { Dock=DockStyle.Fill };
    private readonly Label status = new() { AutoSize=true,Text="首次使用请登录 Apple Music；完整播放和隐藏窗口效果需要验证。" };
    private readonly System.Windows.Forms.Timer timer = new() { Interval=700 };
    private readonly HttpClient http=new() { Timeout=TimeSpan.FromSeconds(3) };
    private readonly Uri link;
    private readonly string profile;
    private readonly int parentPid;
    private readonly string adapter;
    private readonly Dictionary<string,TaskCompletionSource<JsonNode>> replies=new();
    private string session="",commandError="";
    private long ack;
    private DateTime lastOk=DateTime.UtcNow;
    private bool polling,ready,closing;
    private bool loginRequested,wasAuthorized;
    private bool exitRequested;
    private DateTime pageLoadedAt=DateTime.UtcNow;
    private JsonNode? commandResult;
    private CoreWebView2Environment? environment;
    private readonly HashSet<Form> loginPopups = new();
    private string lastPlaybackFailure="";
    private bool regionChecked;
    private Task<JsonNode>? pendingLoad;
    private string pendingSongId="";
    private string lastLoadingStage="";
    private void BackgroundHost() {
        // Keep the media controller visible to WebView2 without showing a music UI.
        // Hiding the native control can suspend media initialization in the SDK.
        ShowInTaskbar=false;Opacity=0;Location=new Point(-32000,-32000);
    }
    private void OfficialAuthSurface(JsonNode outcome) {
        if(outcome["loginRoute"]?.GetValue<string>()!="website"||loginPopups.Count>0)return;
        Text="Apple 官方账号授权 · 完成后返回 DangoMusic";
        var area=Screen.PrimaryScreen!.WorkingArea;
        Location=new Point(area.Left+(area.Width-Width)/2,area.Top+(area.Height-Height)/2);
        Opacity=1;Activate();
    }
    private void RecordDiagnostic(string operation,string error,JsonNode? state=null)
    {
        // Fixed metadata only: no account identifiers, titles, tokens, URLs or responses.
        try {
            if(operation=="load")lastPlaybackFailure=error;
            var report=new JsonObject { ["utc"]=DateTime.UtcNow.ToString("O"),["operation"]=operation,
                ["lastPlaybackFailure"]=lastPlaybackFailure,
                ["stateError"]=state?["error"]?.DeepClone(),
                ["previewOnly"]=state?["previewOnly"]?.DeepClone(),
                ["error"]=error,["authorized"]=state?["authorized"]?.DeepClone(),
                ["authState"]=state?["authState"]?.DeepClone(),["playing"]=state?["playing"]?.DeepClone(),
                ["positionMs"]=state?["positionMs"]?.DeepClone() };
            File.WriteAllText(Path.Combine(AppContext.BaseDirectory,"diagnostics.json"),report.ToJsonString(),new UTF8Encoding(false));
        } catch { }
    }
    public PlayerWindow(Uri link,string profile,int parentPid)
    {
        this.link=link;this.profile=profile;this.parentPid=parentPid;
        Text="DangoMusic · Apple Music 后台播放组件";Width=1100;Height=780;
        ShowInTaskbar=false;Opacity=0;StartPosition=FormStartPosition.Manual;Location=new Point(-32000,-32000);
        var toolbar=new FlowLayoutPanel {Dock=DockStyle.Top,AutoSize=true,Padding=new Padding(6)};
        var hide=new Button {Text="隐藏窗口，回到游戏",AutoSize=true}; hide.Click+=(_,_)=>BackgroundHost();
        toolbar.Controls.Add(hide);toolbar.Controls.Add(status);Controls.Add(web);Controls.Add(toolbar);
        using var stream=typeof(PlayerWindow).Assembly.GetManifestResourceStream("adapter.js")!;
        adapter=new StreamReader(stream).ReadToEnd();
        Shown+=async(_,_)=>{BackgroundHost();await Initialize();};
        timer.Tick+=async(_,_)=>await Poll();
        timer.Start(); // Parent-exit cleanup must also work during failed initialization/login.
        FormClosing+=(_,e)=>{
            if(Program.HideOnClose(e.CloseReason,exitRequested)){e.Cancel=true;BackgroundHost();return;}
            closing=true;timer.Stop();
            foreach(var popup in loginPopups.ToArray())popup.Close();
            http.Dispose();web.Dispose();
        };
    }
    private static bool AppleNavigation(string url) => Uri.TryCreate(url,UriKind.Absolute,out var u) && u.Scheme=="https"
        && (u.Host=="apple.com"||u.Host.EndsWith(".apple.com",StringComparison.OrdinalIgnoreCase));
    private async Task Initialize()
    {
        try
        {
            Directory.CreateDirectory(profile);
            // Playback is initiated by an explicit game button rather than a DOM click.
            environment=await CoreWebView2Environment.CreateAsync(null,profile,
                new CoreWebView2EnvironmentOptions("--autoplay-policy=no-user-gesture-required --disable-background-timer-throttling --disable-renderer-backgrounding --disable-backgrounding-occluded-windows"));
            web.CreationProperties=new CoreWebView2CreationProperties{ProfileName="Default",IsInPrivateModeEnabled=false};
            await web.EnsureCoreWebView2Async(environment);
            web.CoreWebView2.Settings.AreDevToolsEnabled=false;
            web.CoreWebView2.Settings.AreHostObjectsAllowed=false;
            web.CoreWebView2.NavigationStarting+=(_,e)=>{if(!AppleNavigation(e.Uri))e.Cancel=true;};
            web.CoreWebView2.NewWindowRequested+=OpenLoginPopup;
            web.CoreWebView2.NavigationCompleted+=(_,_)=>{pageLoadedAt=DateTime.UtcNow;};
            web.CoreWebView2.WebMessageReceived+=(_,e)=>{
                if(!Uri.TryCreate(e.Source,UriKind.Absolute,out var u)||u.GetLeftPart(UriPartial.Authority)!="https://music.apple.com")return;
                try{var message=JsonNode.Parse(e.WebMessageAsJson)!;string key=message["nonce"]!.GetValue<string>();
                    if(replies.TryGetValue(key,out var t))t.TrySetResult(message["result"]!.DeepClone());}catch{}
            };
            var pair=await Post("/pair",link.Fragment[1..],new JsonObject());
            session=pair["session"]!.GetValue<string>();ack=pair["ack"]!.GetValue<long>();
            web.CoreWebView2.Navigate("https://music.apple.com/");ready=true;timer.Start();
            RecordDiagnostic("initialize","");
        }
        catch{status.Text="播放组件初始化失败：请确认 WebView2 和 .NET 8 Desktop Runtime 已安装。";RecordDiagnostic("initialize","NATIVE_INITIALIZATION");}
    }
    private async void OpenLoginPopup(object? sender, CoreWebView2NewWindowRequestedEventArgs e)
    {
        e.Handled=true;
        if(!AppleNavigation(e.Uri)&&e.Uri!="about:blank")return;
        var deferral=e.GetDeferral();
        try
        {
            var popup=new Form {Text="Apple 官方账号授权 · 完成后返回 DangoMusic",Width=700,Height=800,StartPosition=FormStartPosition.CenterScreen};
            loginPopups.Add(popup);
            var view=new WebView2 {Dock=DockStyle.Fill};popup.Controls.Add(view);
            popup.FormClosed+=(_,_)=>{loginPopups.Remove(popup);view.Dispose();};
            view.CreationProperties=new CoreWebView2CreationProperties{ProfileName="Default",IsInPrivateModeEnabled=false};
            await view.EnsureCoreWebView2Async(environment);
            view.CoreWebView2.NavigationStarting+=(_,n)=>{if(!AppleNavigation(n.Uri)&&n.Uri!="about:blank")n.Cancel=true;};
            view.CoreWebView2.WindowCloseRequested+=(_,_)=>popup.Close();
            e.NewWindow=view.CoreWebView2;popup.Show(this);
        }catch{status.Text="无法打开登录窗口，请重启组件后重试。";}
        finally{deferral.Complete();}
    }
    private async Task<JsonNode> Post(string path,string key,JsonNode data)
    {
        using var req=new HttpRequestMessage(HttpMethod.Post,new Uri(link.GetLeftPart(UriPartial.Authority)+path));
        req.Headers.Add("Origin","dango-native://player");req.Headers.Authorization=new AuthenticationHeaderValue("Bearer",key);
        req.Content=new StringContent(data.ToJsonString(),Encoding.UTF8,"application/json");
        using var response=await http.SendAsync(req);response.EnsureSuccessStatusCode();
        return JsonNode.Parse(await response.Content.ReadAsStringAsync())!;
    }
    private async Task<JsonNode> Evaluate(JsonNode? command,TimeSpan? timeout=null)
    {
        if(web.Source?.GetLeftPart(UriPartial.Authority)!="https://music.apple.com")
            return new JsonObject { ["available"]=false,["error"]="Complete Apple login in the player window" };
        string nonce=Guid.NewGuid().ToString("N");var completion=new TaskCompletionSource<JsonNode>();replies[nonce]=completion;
        try
        {
            string js=adapter+"\nappleAdapter("+(command?.ToJsonString()??"null")+").then(result=>chrome.webview.postMessage({nonce:'"+nonce+"',result})).catch(()=>chrome.webview.postMessage({nonce:'"+nonce+"',result:{available:false,error:'Official player rejected control'}}));";
            await web.CoreWebView2.ExecuteScriptAsync(js);
            return await completion.Task.WaitAsync(timeout??TimeSpan.FromSeconds(7));
        }
        finally {replies.Remove(nonce);}
    }
    private async Task Poll()
    {
        try{if(System.Diagnostics.Process.GetProcessById(parentPid).HasExited){exitRequested=true;Close();return;}}catch{exitRequested=true;Close();return;}
        if(!ready||polling||closing)return;polling=true;
        try
        {
            JsonNode? completedLoad=null;
            if(pendingLoad?.IsCompleted==true) {
                try{completedLoad=await pendingLoad;}catch{completedLoad=new JsonObject{["error"]="Apple Music 媒体加载超时 [MEDIA_TIMEOUT]"};}
                pendingLoad=null;
                commandError=completedLoad["error"]?.GetValue<string>()??"";
            }
            var state=await Evaluate(null);
            if(completedLoad!=null)RecordDiagnostic("load",commandError,state);
            bool authorized=state["authorized"]?.GetValue<bool>()==true;
            if(authorized&&!regionChecked) {
                var account=await Evaluate(new JsonObject{["name"]="region"});
                string region=account["storefront"]?.GetValue<string>()??"";
                if(System.Text.RegularExpressions.Regex.IsMatch(region,"^[a-z]{2}$")) {
                    regionChecked=true;
                    if(account["aligned"]?.GetValue<bool>()==true) {
                        RecordDiagnostic("region-alignment","STOREFRONT_ALIGNED",state);
                    } else {
                    string current=web.Source?.AbsolutePath.Split('/',StringSplitOptions.RemoveEmptyEntries).FirstOrDefault()??"";
                    if(current!=region) {
                        // The public account storefront must match the page's SDK storefront.
                        // Keep the same Default profile and navigate normally; never patch SDK checks.
                        web.CoreWebView2.Navigate("https://music.apple.com/"+region+"/home");
                        state=new JsonObject{["available"]=false,["authorized"]=false,["authState"]="restoring"};
                        authorized=false;
                        RecordDiagnostic("region-alignment","STOREFRONT_RELOAD");
                    }
                    }
                }
            }
            if(authorized&&!wasAuthorized){BackgroundHost();loginRequested=true;foreach(var popup in loginPopups.ToArray())popup.Close();}
            string authState=state["authState"]?.GetValue<string>()??"restoring";
            if(!authorized&&authState=="login"&&!loginRequested&&(DateTime.UtcNow-pageLoadedAt).TotalSeconds>8){loginRequested=true;OfficialAuthSurface(await Evaluate(new JsonObject{["name"]="login"}));}
            wasAuthorized=authorized;
            if(pendingLoad!=null) {
                string stage=state["loadingStage"]?.GetValue<string>()??"";
                if(stage!=lastLoadingStage){lastLoadingStage=stage;RecordDiagnostic("load-wait:"+stage,"",state);}
                if(state["playing"]?.GetValue<bool>()==true&&state["id"]?.GetValue<string>()==pendingSongId) {
                    _=pendingLoad.ContinueWith(t=>{_ = t.Exception;},TaskContinuationOptions.OnlyOnFaulted);
                    pendingLoad=null;RecordDiagnostic("load","",state);
                }
            }
            if(pendingLoad!=null) {
                // Keep heartbeats/authentication alive during metadata and license loading,
                // but don't let the previous song's telemetry replace the game's loading state.
                state=new JsonObject{["available"]=false,["authorized"]=authorized,["authState"]=authState,["error"]=state["error"]?.DeepClone()};
            }
            var result=await Post("/sync",session,new JsonObject { ["ack"]=ack,["state"]=state,["commandError"]=commandError,["commandResult"]=commandResult?.DeepClone() });
            commandResult=null;
            commandError="";lastOk=DateTime.UtcNow;
            if(result["command"] is JsonNode command && command["id"]!.GetValue<long>()>ack)
            {
                string name=command["name"]!.GetValue<string>();
                if(name=="shutdown") {
                    try{await Evaluate(new JsonObject{["name"]="pause"});}catch{}
                    exitRequested=true;Close();return;
                }
                else if(name=="load") {
                    if(pendingLoad!=null)_=pendingLoad.ContinueWith(t=>{_ = t.Exception;},TaskContinuationOptions.OnlyOnFaulted);
                    pendingLoad=Evaluate(command,TimeSpan.FromSeconds(60));
                    pendingSongId=command["value"]?["id"]?.GetValue<string>()??"";
                    lastLoadingStage="";
                }
                else if(name=="show"){
                    // The game owns browsing and playback controls. Only official account
                    // authorization may open a separate window, never the full music site.
                    if(!authorized){loginRequested=true;OfficialAuthSurface(await Evaluate(new JsonObject{["name"]="login"}));}
                    foreach(var popup in loginPopups.ToArray()){popup.Show();popup.Activate();}
                }
                else if(name=="hide")BackgroundHost();
                else{
                    if(name=="stop"&&pendingLoad!=null) {
                        _=pendingLoad.ContinueWith(t=>{_ = t.Exception;},TaskContinuationOptions.OnlyOnFaulted);
                        pendingLoad=null;
                    }
                    JsonNode outcome;
                    try{outcome=await Evaluate(command);}catch{outcome=new JsonObject{["error"]="Apple Music operation timed out; check the login window"};}
                    if(name=="login")OfficialAuthSurface(outcome);
                    if(name=="query"){outcome["id"]=command["id"]!.GetValue<long>();commandResult=outcome;}
                    if(name!="query"&&outcome["error"] is JsonNode error)commandError=error.GetValue<string>();
                }
                ack=command["id"]!.GetValue<long>();
                RecordDiagnostic(name=="query"?"query:"+command["value"]?["op"]?.GetValue<string>():name,commandError,state);
            }
            status.Text=!string.IsNullOrEmpty(commandError)?commandError:authorized?"已恢复登录，可关闭或隐藏此窗口，回到游戏操作。":authState=="restoring"?"正在恢复登录和检查媒体库权限，请稍候…":authState=="unavailable"?"官方网页已打开，但媒体库接口尚未就绪。":"请在官方窗口完成授权；登录网页和媒体库权限会分别检查。";
        }
        catch
        {
            RecordDiagnostic("bridge","NATIVE_BRIDGE_CONNECTION");
            status.Text="与游戏连接中断；超过十秒将暂停并退出。";
            if((DateTime.UtcNow-lastOk).TotalSeconds>10){try{await Evaluate(new JsonObject{["name"]="pause"});}catch{}exitRequested=true;Close();}
        }
        finally {polling=false;}
    }
}
