package monkeylord.XServer.api;

import android.os.Process;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Member;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import monkeylord.XServer.XServer;
import monkeylord.XServer.XposedEntry;
import monkeylord.XServer.utils.DexHelper;
import monkeylord.XServer.utils.NanoHTTPD;
import monkeylord.XServer.utils.NanoWSD;

public class wsTracer implements XServer.wsOperation {
    public static HashMap<String, XC_MethodHook.Unhook> unhooks = new HashMap<String, XC_MethodHook.Unhook>();

    @Override
    public NanoWSD.WebSocket handle(NanoHTTPD.IHTTPSession handshake) {
        return new ws(handshake);
    }

    public class ws extends NanoWSD.WebSocket {

        TracerHook myhook = new TracerHook(this);

        public ws(NanoHTTPD.IHTTPSession handshakeRequest) {
            super(handshakeRequest);
        }

        public void sendLog(String Msg) throws IOException {
            Map<String, Object> object = new HashMap<String, Object>();
            object.put("op", "msg");
            object.put("msg", Msg);
            send(new JSONObject(object).toString());
        }

        public void sendData(String dataname, String data) throws IOException {
            Map<String, Object> object = new HashMap<String, Object>();
            object.put("op", "data");
            object.put("data", data);
            object.put("dataname", dataname);
            send(new JSONObject(object).toString());
        }

        @Override
        protected void onOpen() {
            try {
                this.sendData("classes", new JSONArray(DexHelper.getClassesInDex(XposedEntry.classLoader)).toString());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        protected void onClose(NanoWSD.WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
            for (XC_MethodHook.Unhook unhook : unhooks.values()) {
                unhook.unhook();
            }
            XposedBridge.log(reason);
            XposedBridge.log(initiatedByRemote + "");
        }

        @Override
        protected void onMessage(NanoWSD.WebSocketFrame message) {
            try {
                JSONObject json = new JSONObject(message.getTextPayload());
                Request req = new Request(json);
                if (req.type.equals("hook")) {
                    myhook.tid = Integer.parseInt(req.tid);
                    StringBuilder sb = new StringBuilder();
                    sb.append("<details><summary>Hooked Classes</summary>");
                    for (String clzn : DexHelper.getClassesInDex(XposedEntry.classLoader)) {
                        if (clzn.contains(req.classn)) {
                            try {
                                if (Class.forName(clzn, false, XposedEntry.classLoader).isInterface())
                                    continue;
                                sb.append(clzn + "<br/>");
                                myhook.hook(clzn, req.method);
                            } catch (Exception e) {
                                this.sendLog("Exception:" + e.getLocalizedMessage() + "<br/>");
                            } finally {

                            }
                        }
                    }
                    sb.append("</details>");
                    this.sendLog(sb.toString());
                } else if (req.type.equals("unhookall")) {
                    for (XC_MethodHook.Unhook unhook : unhooks.values()) unhook.unhook();
                    unhooks.clear();
                }
            } catch (Exception e) {
                try {
                    this.sendLog(e.getLocalizedMessage());
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }

        }

        @Override
        protected void onPong(NanoWSD.WebSocketFrame pong) {
        }

        @Override
        protected void onException(IOException exception) {
            XposedBridge.log(exception.getLocalizedMessage());
            try {
                this.sendLog(exception.getLocalizedMessage());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public class TracerHook extends XC_MethodHook {
        public Member method;            //被Hook的方法
        public Object thisObject;        //方法被调用时的this对象
        public Object[] args;            //方法被调用时的参数
        public Object result = null;    //方法被调用后的返回结果
        public int tid = 0;
        ws myws;
        private HashMap<String, String> mids = new HashMap<>();

        TracerHook(ws ws) {
            myws = ws;
        }

        public void setPid(int pid) {
            this.tid = pid;
        }

        private void gatherInfo(MethodHookParam param) {
            method = param.method;
            thisObject = param.thisObject;
            args = param.args;
        }

        private String getMid(Member method) {
            return mids.get(method.getDeclaringClass() + method.getName());
        }

        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            super.beforeHookedMethod(param);
            if (tid > 0 && tid != Process.myTid()) return;
            gatherInfo(param);
            log("<details open>");
            if (getMid(method) != null)
                log("<summary>[" + Process.myTid() + "]<a href=\"/methodview?class=" + method.getDeclaringClass().getName() + "&method=" + getMid(method) + "\">" + method.getDeclaringClass().getName() + "." + MethodDescription(param).toString() + "</a></summary>");
            else
                log("<summary>[" + Process.myTid() + "]" + method.getDeclaringClass().getName() + "." + MethodDescription(param).toString() + "</summary>");
            log("<dl>");
            try {
                if (args != null) for (int i = 0; i < args.length; i++) {
                    log("<dt>Argument " + i + "</dt>");
                    log("<dd>" + translate(args[i]) + "</dd>");
                }

            } catch (Throwable e) {
                log("<p>" + e.getLocalizedMessage() + "</p>");
            } finally {
                log("</dl>");
            }
        }

        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            super.beforeHookedMethod(param);
            if (tid > 0 && tid != Process.myPid()) return;
            gatherInfo(param);
            result = param.getResult();
            //Write your code here.
            try {
                if (param.getThrowable() == null)
                    log("<dt>Return</dt><dd>" + translate(result) + "</dd>");
                else
                    log("<dt>Throw</dt><dd>" + translate(param.getThrowable()) + "</dd>");
            } catch (Throwable e) {
                log("<p>" + e.getLocalizedMessage() + "</p>");
            } finally {
                //log("</" + method.getDeclaringClass() + " method=" + MethodDescription(param).toString() +" pid="+Process.myPid()+ ">");
                log("</details>");
            }
        }

        private void log(String log) {
            //You can add your own logger here.
            //e.g filelogger like Xlog.log(log);
            try {
                myws.sendLog(log);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private String translate(Object obj) {
            //Write your translator here.
            if (obj == null) return "null";
            else return obj.toString();
        }

        private String MethodDescription(MethodHookParam param) {
            StringBuilder sb = new StringBuilder();
            sb.append(method.getName().toString());
            sb.append("(");
            if (args != null) for (Object arg : args) {
                if (arg == null) sb.append("UnknownType");
                else if (arg.getClass().isPrimitive()) sb.append(arg.getClass().getSimpleName());
                else sb.append(arg.getClass().getName());
                sb.append(",");
            }
            sb.append(")");
            return sb.toString();
        }

        public Unhook hook(Member method) {
            return XposedBridge.hookMethod(method, this);
        }

        public void hook(String clzn, String methodRegEx) throws ClassNotFoundException {
            Pattern pattern = Pattern.compile(methodRegEx);
            for (int i = 0; i < Class.forName(clzn, false, XposedEntry.classLoader).getDeclaredMethods().length; i++) {
                Member method = Class.forName(clzn, false, XposedEntry.classLoader).getDeclaredMethods()[i];
                mids.put(method.getDeclaringClass() + method.getName(), String.valueOf(i));
                if (pattern.matcher(method.getName()).matches() && !method.isSynthetic())
                    unhooks.put(clzn + "." + method.getName() + "@" + method.hashCode(), hook(method));
            }
        }
    }

    private class Request {
        String type;
        String classn;
        String method;
        String tid;

        Request(Map<String, String> parms) {
            type = parms.get("type");
            classn = parms.get("class");
            method = parms.get("method");
            tid = parms.get("tid");
        }

        Request(JSONObject json) throws JSONException {
            type = json.getString("type");
            classn = json.getString("class");
            method = json.getString("method");
            tid = json.getString("tid");
        }
    }
}
