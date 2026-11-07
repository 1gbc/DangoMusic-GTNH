package me.sorihiko.dangomusicgtnh.music;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Local transport only. Never receives Apple credentials or audio URLs. */
public final class AppleWebServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService worker=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"Dango-AppleBridge");t.setDaemon(true);return t;});
    private String pairing=random(),session,owner;
    private long born=System.nanoTime(),lastSync,sequence,lastAck;
    private boolean stopped;
    private JsonObject state;
    private final ArrayDeque<JsonObject> commands=new ArrayDeque<>();
    private volatile String error="";
    public AppleWebServer() throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);
        server.setExecutor(worker);server.createContext("/",this::handle);server.start();
    }
    private static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);StringBuilder b=new StringBuilder();for(byte x:bytes)b.append(String.format("%02x",x&255));return b.toString();}
    private static long elapsed(long t){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t);}
    public String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    public synchronized String pairingLink(){return origin()+"/#"+pairing;}
    public synchronized boolean connected(){return session!=null&&elapsed(lastSync)<10000;}
    public synchronized JsonObject snapshot(){return connected()&&!stopped&&state!=null?new JsonParser().parse(state.toString()).getAsJsonObject():null;}
    public String error(){return error;}
    public synchronized void reconnect(){session=null;owner=null;state=null;commands.clear();stopped=false;pairing=random();born=System.nanoTime();lastAck=sequence;error="";}
    public synchronized void command(String name,Double value){
        if(!Arrays.asList("play","pause","stop","next","previous","seek","volume").contains(name))throw new IllegalArgumentException("Unsupported control");
        if((name.equals("seek")||name.equals("volume"))&&(value==null||Double.isNaN(value)||Double.isInfinite(value)||value<0||value>(name.equals("volume")?1:86400)))throw new IllegalArgumentException("Invalid control value");
        if(!connected())return;
        if(name.equals("stop")){stopped=true;state=null;commands.clear();}
        if(commands.size()>=16){error="Apple Music controls are waiting for the browser";return;}
        if(Arrays.asList("play","next","previous").contains(name))stopped=false;
        JsonObject c=new JsonObject();c.addProperty("id",++sequence);c.addProperty("name",name);if(value!=null)c.addProperty("value",value);commands.add(c);
    }
    private void handle(HttpExchange x) throws IOException {
        try {
            String origin=x.getRequestHeaders().getFirst("Origin");
            if(!("127.0.0.1:"+server.getAddress().getPort()).equals(x.getRequestHeaders().getFirst("Host"))||origin==null||!origin.matches("chrome-extension://[a-p]{32}")){reply(x,403,"{}");return;}
            String path=x.getRequestURI().toString(),auth=x.getRequestHeaders().getFirst("Authorization");
            if(!x.getRequestMethod().equals("POST")||!(path.equals("/pair")||path.equals("/sync"))){reply(x,405,"{}");return;}
            // Read outside the state lock so a stalled browser cannot freeze game controls.
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buf=new byte[1024];int n;
            while((n=x.getRequestBody().read(buf))!=-1){if(bytes.size()+n>16384){reply(x,413,"{}");return;}bytes.write(buf,0,n);}
            synchronized(this){
                if(path.equals("/pair")){
                    if(session!=null||elapsed(born)>120000||!("Bearer "+pairing).equals(auth)){reply(x,403,"{}");return;}
                    session=random();owner=origin;pairing="";lastSync=System.nanoTime();JsonObject r=new JsonObject();r.addProperty("session",session);r.addProperty("ack",sequence);reply(x,200,r.toString());return;
                }
                if(!connected()||!origin.equals(owner)||!("Bearer "+session).equals(auth)){reply(x,403,"{}");return;}
                String type=x.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.startsWith("application/json")){reply(x,415,"{}");return;}
                JsonObject report=new JsonParser().parse(new String(bytes.toByteArray(),StandardCharsets.UTF_8)).getAsJsonObject();
                double a=report.get("ack").getAsDouble();if(a!=Math.floor(a)||a<lastAck||a>sequence){reply(x,400,"{}");return;}
                lastAck=(long)a;lastSync=System.nanoTime();while(!commands.isEmpty()&&commands.peek().get("id").getAsLong()<=lastAck)commands.remove();
                if(!stopped&&lastAck==sequence&&report.has("state")&&report.get("state").isJsonObject())state=sanitize(report.getAsJsonObject("state"));
                error=text(report,"commandError",300);
                if(error.isEmpty()&&state!=null)error=text(state,"error",300);
                JsonObject r=new JsonObject();r.add("command",commands.isEmpty()?JsonNull.INSTANCE:commands.peek());reply(x,200,r.toString());
            }
        }catch(Exception e){try{reply(x,400,"{}");}catch(IOException ignored){}}
        finally{x.close();}
    }
    private static JsonObject sanitize(JsonObject source){
        JsonObject r=new JsonObject();for(String k:Arrays.asList("title","artist","album","id","error"))r.addProperty(k,text(source,k,300));
        for(String k:Arrays.asList("positionMs","durationMs")){double v=source.has(k)?source.get(k).getAsDouble():0;r.addProperty(k,Double.isNaN(v)||Double.isInfinite(v)?0:Math.max(0,Math.min(v,86400000)));}
        for(String k:Arrays.asList("playing","available"))r.addProperty(k,source.has(k)&&source.get(k).getAsBoolean());
        String cover="";try{URI u=URI.create(text(source,"cover",2048));if("https".equals(u.getScheme())&&u.getHost()!=null&&(u.getHost().equals("mzstatic.com")||u.getHost().endsWith(".mzstatic.com"))&&u.getUserInfo()==null)cover=u.toString();}catch(Exception ignored){}
        r.addProperty("cover",cover);return r;
    }
    public static String text(JsonObject o,String k,int max){JsonElement e=o.get(k);if(e==null||!e.isJsonPrimitive())return "";String v=e.getAsString();return v.substring(0,Math.min(v.length(),max));}
    private static void reply(HttpExchange x,int code,String value)throws IOException{byte[] b=value.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(code,b.length);x.getResponseBody().write(b);}
    public synchronized void close(){server.stop(0);worker.shutdownNow();session=null;state=null;commands.clear();}
}
