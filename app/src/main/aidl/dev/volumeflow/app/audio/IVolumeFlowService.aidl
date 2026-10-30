package dev.volumeflow.app.audio;

interface IVolumeFlowService {
    void applyAppGain(String packageName, float gain);
    List<String> getActiveAppPackages();
    int getActiveSessionCount();
}
