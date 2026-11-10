package jackpal.androidterm;

/** Native pseudo-terminal helpers (see pty.cpp). */
final class Pty {
    static {
        System.loadLibrary("codepress-pty");
    }

    private Pty() {}

    static native int createSubprocess(String cmd, String[] args, String[] env, String cwd, int masterFd) throws java.io.IOException;

    static native int waitFor(int pid);

    static native void sendSignal(int pid, int signal);

    static native void setWindowSize(int fd, int rows, int cols) throws java.io.IOException;

    static native void setUtf8Mode(int fd, boolean utf8) throws java.io.IOException;
}
