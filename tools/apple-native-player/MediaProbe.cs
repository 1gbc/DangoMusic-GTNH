using System.Text.Json.Nodes;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

// Isolated, signed-out runtime check. Never opens the user's account profile.
internal static class MediaProbe
{
    internal static int Run()
    {
        ApplicationConfiguration.Initialize();
        using var form=new Form {Opacity=0,ShowInTaskbar=false,Width=400,Height=300};
        using var view=new WebView2 {Dock=DockStyle.Fill};form.Controls.Add(view);
        using var timeout=new System.Windows.Forms.Timer {Interval=25000};
        int code=5;
        void Finish(JsonObject result) {
            try {File.WriteAllText(Path.Combine(AppContext.BaseDirectory,"drm-capabilities.json"),result.ToJsonString());code=0;}catch{code=6;}
            timeout.Stop();form.Close();
        }
        timeout.Tick+=(_,_)=>Finish(new JsonObject{["error"]="PROBE_TIMEOUT"});
        form.Shown+=async(_,_)=>{
            form.Hide();timeout.Start();
            try {
                string profile=Path.Combine(Path.GetTempPath(),"DangoMusic-DRM-Probe-"+Guid.NewGuid().ToString("N"));
                var environment=await CoreWebView2Environment.CreateAsync(null,profile);
                await view.EnsureCoreWebView2Async(environment);
                view.CoreWebView2.Settings.AreDevToolsEnabled=false;
                view.CoreWebView2.Settings.AreHostObjectsAllowed=false;
                view.CoreWebView2.NavigationStarting+=(_,e)=>{if(!Uri.TryCreate(e.Uri,UriKind.Absolute,out var u)||u.Scheme!="https"||!(u.Host=="apple.com"||u.Host.EndsWith(".apple.com")))e.Cancel=true;};
                view.CoreWebView2.WebMessageReceived+=(_,e)=>{
                    if(!Uri.TryCreate(e.Source,UriKind.Absolute,out var u)||u.GetLeftPart(UriPartial.Authority)!="https://music.apple.com")return;
                    try {var r=JsonNode.Parse(e.WebMessageAsJson)!;
                        if(r["probe"]?.GetValue<string>()!="DangoDrmProbe")return;
                        Finish(new JsonObject{["secureContext"]=r["secureContext"]?.GetValue<bool>(),
                            ["widevine"]=r["widevine"]?.GetValue<bool>(),["playready"]=r["playready"]?.GetValue<bool>(),
                            ["playreadyLegacy"]=r["playreadyLegacy"]?.GetValue<bool>(),
                            ["widevineCbcs"]=r["widevineCbcs"]?.GetValue<bool>(),["playreadyCbcs"]=r["playreadyCbcs"]?.GetValue<bool>()});
                    }catch{Finish(new JsonObject{["error"]="PROBE_RESPONSE"});}
                };
                view.CoreWebView2.NavigationCompleted+=async(_,e)=>{
                    if(!e.IsSuccess)return;
                    await view.CoreWebView2.ExecuteScriptAsync("""
                      (async()=>{
                        async function supported(system,scheme){try{
                          await navigator.requestMediaKeySystemAccess(system,[{initDataTypes:['cenc'],
                            audioCapabilities:[{contentType:'audio/mp4; codecs="mp4a.40.2"',...(scheme?{encryptionScheme:scheme}:{})}]}]);return true;
                        }catch{return false;}}
                        const [widevine,playready,playreadyLegacy,widevineCbcs,playreadyCbcs]=await Promise.all([supported('com.widevine.alpha'),supported('com.microsoft.playready.recommendation'),supported('com.microsoft.playready'),supported('com.widevine.alpha','cbcs'),supported('com.microsoft.playready','cbcs')]);
                        chrome.webview.postMessage({probe:'DangoDrmProbe',secureContext:isSecureContext,widevine,playready,playreadyLegacy,widevineCbcs,playreadyCbcs});
                      })();
                      """);
                };
                view.CoreWebView2.Navigate("https://music.apple.com/");
            }catch{Finish(new JsonObject{["error"]="PROBE_INITIALIZATION"});}
        };
        Application.Run(form);return code;
    }
}
