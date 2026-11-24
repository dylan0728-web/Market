
#include <jni.h>
#include <string>
#include <cstring>
#include <cerrno>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <net/if.h>
#include <unistd.h>
#include <cstdio>

extern "C"
JNIEXPORT jstring JNICALL
Java_tw_com_huangyi_apkmarket_MainActivity_readEthMacNative(
        JNIEnv *env,
        jobject /* this */) {

    int fd = socket(AF_INET, SOCK_DGRAM, 0);

    if (fd < 0) {
        return env->NewStringUTF(("ERROR:socket:" + std::to_string(errno)).c_str());
    }

    struct ifreq ifr{};
    std::strncpy(ifr.ifr_name, "eth0", IFNAMSIZ - 1);

    if (ioctl(fd, SIOCGIFHWADDR, &ifr) < 0) {
        int err = errno;
        close(fd);
        return env->NewStringUTF(("ERROR:ioctl:" + std::to_string(err)).c_str());
    }

    close(fd);

    unsigned char *mac =
        reinterpret_cast<unsigned char *>(ifr.ifr_hwaddr.sa_data);

    char result[18];
    std::snprintf(
        result, sizeof(result),
        "%02X:%02X:%02X:%02X:%02X:%02X",
        mac[0], mac[1], mac[2],
        mac[3], mac[4], mac[5]
    );

    return env->NewStringUTF(result);
}
