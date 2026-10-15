package io.github.miaiplus;

import android.app.Application;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class ModuleApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService service) { HotReload.bind(service); }
            @Override public void onServiceDied(XposedService service) { HotReload.unbind(service); }
        });
    }
}
