package com.retro.launcher.lock;

interface IShizukuLockService {

    void destroy() = 16777114; // Destroy method defined by Shizuku server

    boolean lockScreen() = 1;
}
