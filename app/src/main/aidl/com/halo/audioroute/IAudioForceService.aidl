package com.halo.audioroute;

interface IAudioForceService {
    // usage: 0=communication 1=media 2=record; force: 0=none 1=speaker 2=headphones 3=bt_sco 4=bt_a2dp
    boolean setForceUse(int usage, int force);
    int forceUse(int usage);
    String lastResult();
}
