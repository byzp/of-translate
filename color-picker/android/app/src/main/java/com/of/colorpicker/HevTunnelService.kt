package com.of.colorpicker

/**
 * JNI bridge to hev-socks5-tunnel.
 * Package/class name must match -DPKGNAME=com/of/colorpicker -DCLSNAME=HevTunnelService
 * set in Application.mk during ndk-build.
 */
class HevTunnelService {

    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        @JvmStatic
        external fun TProxyStartService(configPath: String, fd: Int): Boolean

        @JvmStatic
        external fun TProxyStopService(): Boolean

        @JvmStatic
        external fun TProxyIsRunning(): Boolean

        @JvmStatic
        external fun TProxyGetStats(): LongArray

        /**
         * Called from JNI (hev_jni_protect_fd) to protect a socket fd
         * so it bypasses VPN routing.
         */
        @JvmStatic
        fun protect(fd: Int): Boolean {
            return PacketVpnService.protectFd(fd)
        }
    }
}
