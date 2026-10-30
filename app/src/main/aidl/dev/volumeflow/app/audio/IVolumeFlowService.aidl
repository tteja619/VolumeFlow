package dev.volumeflow.app.audio;

import java.util.List;

interface IVolumeFlowService {
    void applyAppGain(String packageName, float gain);
    List<String> getActiveAppPackages();
    int getActiveSessionCount();
    String getEngineStatus();
    void destroy();
}
