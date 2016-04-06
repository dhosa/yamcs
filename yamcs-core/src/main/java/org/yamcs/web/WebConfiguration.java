package org.yamcs.web;

import java.util.ArrayList;
import java.util.List;

import org.yamcs.YConfiguration;

public class WebConfiguration {

    private List<String> webRoots = new ArrayList<>();
    private boolean zeroCopyEnabled = true;
    private long webTimeout = 0;

    private static WebConfiguration INSTANCE;

    private WebConfiguration() {
        YConfiguration yconfig = YConfiguration.getConfiguration("yamcs");
        if (yconfig.isList("webRoot")) {
            @SuppressWarnings({ "unchecked", "rawtypes" })
            List<String> roots = (List) yconfig.getList("webRoot");
            for (String root : roots) {
                webRoots.add(root);
            }
        } else {
            webRoots.add(yconfig.getString("webRoot"));
        }

        if (yconfig.containsKey("zeroCopyEnabled")) {
            zeroCopyEnabled = yconfig.getBoolean("zeroCopyEnabled");
        }

        if (yconfig.containsKey("webTimeout")) {
            webTimeout = Math.max(0,  yconfig.getInt("webTimeout"));
        }
    }

    public static WebConfiguration getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new WebConfiguration();
        }
        return INSTANCE;
    }

    public List<String> getWebRoots() {
        return webRoots;
    }

    public boolean isZeroCopyEnabled() {
        return zeroCopyEnabled;
    }

    public long getWebTimeout() {
        return webTimeout;
    }
}
