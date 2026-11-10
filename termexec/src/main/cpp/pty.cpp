/*
 * Pseudo-terminal subprocess helper.
 * Derived from libtermexec / Android-Terminal-Emulator by Steven Luo (Apache License 2.0),
 * rewritten for the current NDK and extended with a working directory and a full environment.
 */
#include <jni.h>
#include <android/log.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <string>
#include <vector>

#define LOG_TAG "codepress-pty"

static void throwIO(JNIEnv *env, const char *what) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s: %s", what, strerror(errno));
    jclass ex = env->FindClass("java/io/IOException");
    std::string msg = std::string(what) + ": " + strerror(errno);
    env->ThrowNew(ex, msg.c_str());
}

static std::vector<std::string> toVector(JNIEnv *env, jobjectArray arr) {
    std::vector<std::string> out;
    jsize n = arr ? env->GetArrayLength(arr) : 0;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) env->GetObjectArrayElement(arr, i);
        const char *c = env->GetStringUTFChars(s, nullptr);
        out.emplace_back(c ? c : "");
        if (c) env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }
    return out;
}

static void closeExtraFds() {
    DIR *dir = opendir("/proc/self/fd");
    if (!dir) return;
    int dfd = dirfd(dir);
    while (struct dirent *e = readdir(dir)) {
        int fd = atoi(e->d_name);
        if (fd > STDERR_FILENO && fd != dfd) close(fd);
    }
    closedir(dir);
}

extern "C" {

JNIEXPORT jint JNICALL
Java_jackpal_androidterm_Pty_createSubprocess(JNIEnv *env, jclass, jstring jcmd, jobjectArray jargs,
                                              jobjectArray jenv, jstring jcwd, jint masterFd) {
    const char *c = env->GetStringUTFChars(jcmd, nullptr);
    std::string cmd = c ? c : "";
    if (c) env->ReleaseStringUTFChars(jcmd, c);
    std::string cwd;
    if (jcwd) {
        const char *w = env->GetStringUTFChars(jcwd, nullptr);
        cwd = w ? w : "";
        if (w) env->ReleaseStringUTFChars(jcwd, w);
    }
    std::vector<std::string> args = toVector(env, jargs);
    std::vector<std::string> envs = toVector(env, jenv);

    // Build argv / envp before fork(): nothing may allocate in the child.
    std::vector<char *> argv, envp;
    for (auto &s : args) argv.push_back(const_cast<char *>(s.c_str()));
    argv.push_back(nullptr);
    for (auto &s : envs) envp.push_back(const_cast<char *>(s.c_str()));
    envp.push_back(nullptr);

    fcntl(masterFd, F_SETFD, FD_CLOEXEC);
    if (unlockpt(masterFd)) { throwIO(env, "unlockpt"); return -1; }
    char devname[64];
    memset(devname, 0, sizeof(devname));
    if (ptsname_r(masterFd, devname, sizeof(devname))) { throwIO(env, "ptsname_r"); return -1; }

    pid_t pid = fork();
    if (pid < 0) { throwIO(env, "fork"); return -1; }
    if (pid == 0) {
        setsid();
        int pts = open(devname, O_RDWR);
        if (pts < 0) _exit(126);
        ioctl(pts, TIOCSCTTY, 0);
        dup2(pts, 0);
        dup2(pts, 1);
        dup2(pts, 2);
        closeExtraFds();
        if (!cwd.empty()) { if (chdir(cwd.c_str()) != 0) { /* stay in the default directory */ } }
        execve(cmd.c_str(), argv.data(), envp.data());
        _exit(127);
    }
    return (jint) pid;
}

JNIEXPORT jint JNICALL
Java_jackpal_androidterm_Pty_waitFor(JNIEnv *, jclass, jint pid) {
    int status = 0;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 0;
}

JNIEXPORT void JNICALL
Java_jackpal_androidterm_Pty_sendSignal(JNIEnv *, jclass, jint pid, jint sig) {
    kill(pid, sig);
}

JNIEXPORT void JNICALL
Java_jackpal_androidterm_Pty_setWindowSize(JNIEnv *env, jclass, jint fd, jint rows, jint cols) {
    struct winsize sz;
    memset(&sz, 0, sizeof(sz));
    sz.ws_row = (unsigned short) rows;
    sz.ws_col = (unsigned short) cols;
    if (ioctl(fd, TIOCSWINSZ, &sz) == -1) throwIO(env, "TIOCSWINSZ");
}

JNIEXPORT void JNICALL
Java_jackpal_androidterm_Pty_setUtf8Mode(JNIEnv *env, jclass, jint fd, jboolean utf8) {
    struct termios t;
    if (ioctl(fd, TCGETS, &t) != 0) { throwIO(env, "TCGETS"); return; }
    if (utf8) t.c_iflag |= IUTF8; else t.c_iflag &= ~IUTF8;
    if (ioctl(fd, TCSETS, &t) != 0) throwIO(env, "TCSETS");
}

}
