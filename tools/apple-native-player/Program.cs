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
        if (args.Contains("--self-test"))
        {
            if (ValidLink("https://music.apple.com/#secret")) return 2;
            if (ValidLink("http://localhost:1234/#" + new string('a',64))) return 3;
            if (!ValidLink("http://127.0.0.1:1234/#" + new string('a',64))) return 4;
            try { return string.IsNullOrEmpty(CoreWebView2Environment.GetAvailableBrowserVersionString()) ? 5 : 0; }
            catch { return 6; }
        }
        try
        {
            Console.InputEncoding=Encoding.UTF8;
            // Pairing credentials arrive through a private parent/child pipe, never argv or disk.
            var bootstrap=JsonNode.Parse(Console.ReadLine() ?? "")!;
            string link=bootstrap["link"]!.GetValue<string>();
            string profile=bootstrap["profile"]!.GetValue<string>();
            if (!ValidLink(link)||!Path.IsPathFullyQualified(profile)) return 7;
            ApplicationConfiguration.Initialize();
            int parentPid=bootstrap["parentPid"]?.GetValue<int>()??0;
            if(parentPid<=0)return 8;
            Application.Run(new PlayerWindow(new Uri(link), profile,parentPid));
            return 0;
        }
        catch { return 1; } // No Apple URLs, credentials, script errors or account data in logs.
    }
    internal static bool ValidLink(string value) => Uri.TryCreate(value,UriKind.Absolute,out var u)
        && u.Scheme=="http" && u.Host=="127.0.0.1" && u.Port>0 && u.AbsolutePath=="/" && u.Query=="" && u.UserInfo==""
        && System.Text.RegularExpressions.Regex.IsMatch(u.Fragment,"^#[a-f0-9]{64}$");
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
    private CoreWebView2Environment? environment;
    public PlayerWindow(Uri link,string profile,int parentPid)
    {
        this.link=link;this.profile=profile;this.parentPid=parentPid;
        Text="DangoMusic · Apple Music 登录与播放";Width=1100;Height=780;
        var toolbar=new FlowLayoutPanel {Dock=DockStyle.Top,AutoSize=true,Padding=new Padding(6)};
        var hide=new Button {Text="隐藏窗口，回到游戏",AutoSize=true}; hide.Click+=(_,_)=>Hide();
        toolbar.Controls.Add(hide);toolbar.Controls.Add(status);Controls.Add(web);Controls.Add(toolbar);
        using var stream=typeof(PlayerWindow).Assembly.GetManifestResourceStream("adapter.js")!;
        adapter=new StreamReader(stream).ReadToEnd();
        Shown+=async(_,_)=>await Initialize();
        timer.Tick+=async(_,_)=>await Poll();
        timer.Start(); // Parent-exit cleanup must also work during failed initialization/login.
        FormClosing+=(_,_)=>{closing=true;timer.Stop();http.Dispose();web.Dispose();};
    }
    private static bool AppleNavigation(string url) => Uri.TryCreate(url,UriKind.Absolute,out var u) && u.Scheme=="https"
        && (u.Host=="apple.com"||u.Host.EndsWith(".apple.com",StringComparison.OrdinalIgnoreCase));
    private async Task Initialize()
    {
        try
        {
            Directory.CreateDirectory(profile);
            environment=await CoreWebView2Environment.CreateAsync(null,profile);
            await web.EnsureCoreWebView2Async(environment);
            web.CoreWebView2.Settings.AreDevToolsEnabled=false;
            web.CoreWebView2.Settings.AreHostObjectsAllowed=false;
            web.CoreWebView2.NavigationStarting+=(_,e)=>{if(!AppleNavigation(e.Uri))e.Cancel=true;};
            web.CoreWebView2.NewWindowRequested+=OpenLoginPopup;
            web.CoreWebView2.WebMessageReceived+=(_,e)=>{
                if(!Uri.TryCreate(e.Source,UriKind.Absolute,out var u)||u.GetLeftPart(UriPartial.Authority)!="https://music.apple.com")return;
                try{var message=JsonNode.Parse(e.WebMessageAsJson)!;string key=message["nonce"]!.GetValue<string>();
                    if(replies.TryGetValue(key,out var t))t.TrySetResult(message["result"]!.DeepClone());}catch{}
            };
            var pair=await Post("/pair",link.Fragment[1..],new JsonObject());
            session=pair["session"]!.GetValue<string>();ack=pair["ack"]!.GetValue<long>();
            web.CoreWebView2.Navigate("https://music.apple.com/");ready=true;timer.Start();
        }
        catch{status.Text="播放组件初始化失败：请确认 WebView2 和 .NET 8 Desktop Runtime 已安装。";}
    }
    private async void OpenLoginPopup(object? sender, CoreWebView2NewWindowRequestedEventArgs e)
    {
        e.Handled=true;
        if(!AppleNavigation(e.Uri))return;
        using var deferral=e.GetDeferral();
        try
        {
            var popup=new Form {Text="Apple 官方账号登录",Width=700,Height=800};
            var view=new WebView2 {Dock=DockStyle.Fill};popup.Controls.Add(view);
            await view.EnsureCoreWebView2Async(environment);
            view.CoreWebView2.NavigationStarting+=(_,n)=>{if(!AppleNavigation(n.Uri))n.Cancel=true;};
            e.NewWindow=view.CoreWebView2;popup.Show(this);
        }catch{status.Text="无法打开登录窗口，请重启组件后重试。";}
    }
    private async Task<JsonNode> Post(string path,string key,JsonNode data)
    {
        using var req=new HttpRequestMessage(HttpMethod.Post,new Uri(link.GetLeftPart(UriPartial.Authority)+path));
        req.Headers.Add("Origin","dango-native://player");req.Headers.Authorization=new AuthenticationHeaderValue("Bearer",key);
        req.Content=new StringContent(data.ToJsonString(),Encoding.UTF8,"application/json");
        using var response=await http.SendAsync(req);response.EnsureSuccessStatusCode();
        return JsonNode.Parse(await response.Content.ReadAsStringAsync())!;
    }
    private async Task<JsonNode> Evaluate(JsonNode? command)
    {
        if(web.Source?.GetLeftPart(UriPartial.Authority)!="https://music.apple.com")
            return new JsonObject { ["available"]=false,["error"]="Complete Apple login in the player window" };
        string nonce=Guid.NewGuid().ToString("N");var completion=new TaskCompletionSource<JsonNode>();replies[nonce]=completion;
        try
        {
            string js=adapter+"\nappleAdapter("+(command?.ToJsonString()??"null")+").then(result=>chrome.webview.postMessage({nonce:'"+nonce+"',result})).catch(()=>chrome.webview.postMessage({nonce:'"+nonce+"',result:{available:false,error:'Official player rejected control'}}));";
            await web.CoreWebView2.ExecuteScriptAsync(js);
            return await completion.Task.WaitAsync(TimeSpan.FromSeconds(5));
        }
        finally {replies.Remove(nonce);}
    }
    private async Task Poll()
    {
        try{if(System.Diagnostics.Process.GetProcessById(parentPid).HasExited){Close();return;}}catch{Close();return;}
        if(!ready||polling||closing)return;polling=true;
        try
        {
            var state=await Evaluate(null);
            var result=await Post("/sync",session,new JsonObject { ["ack"]=ack,["state"]=state,["commandError"]=commandError });
            commandError="";lastOk=DateTime.UtcNow;
            if(result["command"] is JsonNode command && command["id"]!.GetValue<long>()>ack)
            {
                string name=command["name"]!.GetValue<string>();
                if(name=="show"){Show();WindowState=FormWindowState.Normal;Activate();}
                else if(name=="hide")Hide();
                else{var outcome=await Evaluate(command);if(outcome["error"] is JsonNode error)commandError=error.GetValue<string>();}
                ack=command["id"]!.GetValue<long>();
            }
            status.Text=string.IsNullOrEmpty(commandError)?"组件已连接游戏；可隐藏此窗口。完整歌曲与后台播放请实测。":commandError;
        }
        catch
        {
            status.Text="与游戏连接中断；超过十秒将暂停并退出。";
            if((DateTime.UtcNow-lastOk).TotalSeconds>10){try{await Evaluate(new JsonObject{["name"]="pause"});}catch{}Close();}
        }
        finally {polling=false;}
    }
}
