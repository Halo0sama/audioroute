package com.halo.audioroute;

import android.os.IBinder;

interface IAudioForceService {
    // usage: 0=communication 1=media 2=record; force: 0=none 1=speaker 2=headphones 3=bt_sco 4=bt_a2dp
    boolean setForceUse(int usage, int force) = 0;
    int forceUse(int usage) = 1;
    String lastResult() = 2;
    String mediaStrategyState() = 100;
    boolean setMediaDevice(int type, String address, IBinder owner) = 101;
    boolean restoreMediaDevice() = 102;
    boolean recoverMediaDevice(String journal) = 103;
    // Reserved transaction used by Shizuku to stop a UserService.
    void destroy() = 16777114;
}
