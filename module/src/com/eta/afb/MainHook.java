package com.eta.afb;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.error.HookFailedError;

import java.io.File;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class MainHook extends XposedModule {

    private static final String TAG = "AntiFraudBlock";
    private static final String PKG = "com.coloros.phonemanager";

    private static final String KILL_SWITCH = "/data/local/tmp/afb.disable";
    private static boolean sDisabled = false;

    private static final String[] BLOCK_PREFIX = {"anti_fraud", "ai_detect", "fraud"};
    private static final Set<String> BLOCK_EXACT = new HashSet<>();

    static {
        BLOCK_EXACT.add("anti_fraud_switches");
        BLOCK_EXACT.add("anti_fraud_events_count");
        BLOCK_EXACT.add("anti_fraud_master_switche");
        BLOCK_EXACT.add("anti_fraud_handle_event");
        BLOCK_EXACT.add("anti_fraud_false_report_event");
        BLOCK_EXACT.add("ai_detect_result_event");
        BLOCK_EXACT.add("ai_detect_voip_app_outside_lists");
    }

    public MainHook() {
        super();
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log("module loaded, api=" + getApiVersion() + " framework=" + getFrameworkName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (pkg == null || !pkg.startsWith(PKG)) return;

        sDisabled = new File(KILL_SWITCH).exists();
        log("attach " + pkg + (sDisabled ? " (kill switch ON, no-op)" : ""));

        ClassLoader cl = param.getDefaultClassLoader();

        // 1) 反诈埋点封装（全静默）
        hookStaticVoid(cl, "com.oplus.phonemanager.aivoicecalldetect.utils.AiDetectStatistics", null, false);

        // 2) DCS 云端上报（精确 + 兜底）
        hookDcs(cl);

        // 3) 最终出口兜底（兼容新版 track 和旧版 E）
        hookTrackSink(cl);

        log("kept local: insertFraudDetectSecurityEvent / showRiskNotification / FraudRiskDialogActivity");
    }

    private void hookStaticVoid(ClassLoader cl, String clsName, final Set<String> names, boolean dump) {
        try {
            Class<?> c = Class.forName(clsName, false, cl);
            if (dump) dumpMethods(clsName, c);
            int hit = 0;
            for (final Method m : c.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != void.class) continue;
                if (names != null && !names.contains(m.getName())) continue;
                hookMethod(m, "silence " + clsName + "#" + m.getName() + "/" + m.getParameterTypes().length, true);
                hit++;
            }
            log(clsName + " -> " + hit + " method(s) silenced");
        } catch (Throwable t) {
            log("hook class failed: " + clsName + " : " + t);
        }
    }

    private void hookDcs(ClassLoader cl) {
        final String cls = "com.oplus.phonemanager.aivoicecalldetect.utils.AiVoiceDetectDCSUtils";
        try {
            Class<?> c = Class.forName(cls, false, cl);
            // dumpMethods(cls, c); // 已经摸底过了，不需要 dump

            // 兼容新老版本的方法名
            Set<String> names = new HashSet<>();
            names.add("m"); // 旧版
            names.add("n"); // 旧版
            names.add("uploadDetectInfo"); // 新版
            names.add("uploadSwitches"); // 新版

            int hit = 0;
            for (final Method m : c.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != void.class) continue;
                if (!names.contains(m.getName())) continue;
                hookMethod(m, "silence " + cls + "#" + m.getName() + "/" + m.getParameterTypes().length, true);
                hit++;
            }

            if (hit == 0) {
                log(cls + ": not matched, falling back to all static void");
                for (final Method m : c.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getReturnType() != void.class) continue;
                    hookMethod(m, "silence-fallback " + cls + "#" + m.getName() + "/" + m.getParameterTypes().length, true);
                    hit++;
                }
            }
            log(cls + " -> " + hit + " method(s) silenced");
        } catch (Throwable t) {
            log("hook class failed: " + cls + " : " + t);
        }
    }

    private void hookTrackSink(ClassLoader cl) {
        final String cls = "com.oplus.nearx.track.TrackApi";
        try {
            Class<?> c = Class.forName(cls, false, cl);
            
            int hit = 0;
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                // 🌟 核心兼容：既抓新版 track，也抓旧版 E
                if (!name.contains("track") && !"E".equals(name)) continue;
                
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length >= 2 && ps[0] == String.class) {
                    hookTrackMethod(m);
                    hit++;
                }
            }
            log("TrackApi (track/E) -> " + hit + " sink hooked");
        } catch (Throwable t) {
            log("hook TrackApi failed: " + t);
        }
    }

    private void hookTrackMethod(final Method m) {
        try {
            hook(m).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (sDisabled) return chain.proceed();
                    if (chain.getArgs().isEmpty()) return chain.proceed();
                    
                    // 兼容新旧版参数位置：旧版 E(appId, event) 第二参是事件名；新版 track(event, ...) 第一参是事件名
                    for (int i = 0; i < Math.min(chain.getArgs().size(), 2); i++) {
                        Object arg = chain.getArg(i);
                        if (arg instanceof String && isBlockedEvent((String) arg)) {
                            log("blocked track event: " + arg);
                            return null; // 命中直接吞掉
                        }
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            log("hook TrackApi." + m.getName() + " error : " + t);
        }
    }

    private void hookMethod(Executable exe, final String label, final Boolean always) {
        try {
            hook(exe).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (sDisabled) return chain.proceed();
                    if (always == null) return chain.proceed();
                    if (always) {
                        log("hit " + label);
                        return null;
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            log("hook error " + label + " : " + t);
        }
    }

    private static boolean isBlockedEvent(String ev) {
        if (ev == null || ev.isEmpty()) return false;
        if (BLOCK_EXACT.contains(ev)) return true;
        String low = ev.toLowerCase(Locale.ROOT);
        for (String p : BLOCK_PREFIX) {
            if (low.startsWith(p)) return true;
        }
        return false;
    }

    private void dumpMethods(String tag, Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            log("  dump[" + tag + "]: " + Modifier.toString(m.getModifiers()) + " " + m.getReturnType().getName() + " " + m.getName() + "(" + paramsToString(m.getParameterTypes()) + ")");
        }
    }

    private static String paramsToString(Class<?>[] ps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(ps[i].getName());
        }
        return sb.toString();
    }

    private void log(String s) {
        try {
            Log.i(TAG, s);
        } catch (Throwable ignore) {}
        try {
            this.log(4, TAG, s);
        } catch (Throwable ignore) {}
    }
}