package jackpal.androidterm;

import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jackpal.androidterm.emulatorview.TermSession;
import jackpal.androidterm.emulatorview.UpdateCallback;

/**
 * A terminal session: a real shell process attached to a pty, drawn by the emulator view.
 * Interactive programs, job control (Ctrl+C), colours and cursor keys all work.
 */
public class PtySession extends TermSession {
    private final ParcelFileDescriptor master;
    private final int pid;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean exited;

    private final UpdateCallback utf8Notify = new UpdateCallback() {
        @Override
        public void onUpdate() {
            try {
                if (master.getFileDescriptor().valid()) Pty.setUtf8Mode(master.getFd(), getUTF8Mode());
            } catch (IOException ignored) {
            }
        }
    };

    /**
     * @param shell absolute path of the shell binary, e.g. /system/bin/sh
     * @param cwd   starting directory
     * @param extraEnv variables added to (or replacing) the app's environment
     */
    public PtySession(String shell, String cwd, Map<String, String> extraEnv) throws IOException {
        this(shell, java.util.Collections.singletonList(shell), cwd, extraEnv);
    }

    /** Starts an executable with an explicit argv vector (argv[0] must be the executable path). */
    public PtySession(String executable, List<String> arguments, String cwd, Map<String, String> extraEnv) throws IOException {
        super(false);
        if (arguments == null || arguments.isEmpty()) throw new IOException("Process argument list is empty");
        master = ParcelFileDescriptor.open(new File("/dev/ptmx"), ParcelFileDescriptor.MODE_READ_WRITE);

        Map<String, String> env = new TreeMap<>(System.getenv());
        env.putAll(extraEnv);
        List<String> envList = new ArrayList<>();
        for (Map.Entry<String, String> e : env.entrySet()) envList.add(e.getKey() + "=" + e.getValue());

        pid = Pty.createSubprocess(executable, arguments.toArray(new String[0]), envList.toArray(new String[0]), cwd, master.getFd());

        setTermOut(new ParcelFileDescriptor.AutoCloseOutputStream(master));
        setTermIn(new ParcelFileDescriptor.AutoCloseInputStream(master));
        setDefaultUTF8Mode(true);

        Thread watcher = new Thread(() -> {
            Pty.waitFor(pid);
            exited = true;
            main.post(() -> {
                if (isRunning()) onProcessExit();
            });
        }, "pty-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    @Override
    public void initializeEmulator(int columns, int rows) {
        super.initializeEmulator(columns, rows);
        utf8Notify.onUpdate();
        setUTF8ModeUpdateCallback(utf8Notify);
    }

    @Override
    public void updateSize(int columns, int rows) {
        try {
            if (master.getFileDescriptor().valid()) Pty.setWindowSize(master.getFd(), rows, columns);
        } catch (IOException ignored) {
        }
        super.updateSize(columns, rows);
    }

    @Override
    protected void onProcessExit() {
        try {
            byte[] msg = "\r\n[process finished - tap Restart to open a new shell]\r\n".getBytes("UTF-8");
            appendToEmulator(msg, 0, msg.length);
            notifyUpdate();
        } catch (java.io.UnsupportedEncodingException ignored) {
        }
    }

    public boolean hasExited() {
        return exited;
    }

    /** Sends SIGINT to the foreground process group (what Ctrl+C does). */
    public void interrupt() {
        Pty.sendSignal(-pid, 2);
    }

    @Override
    public void finish() {
        if (!exited) {
            Pty.sendSignal(-pid, 9);
            Pty.sendSignal(pid, 9);
        }
        try {
            master.close();
        } catch (IOException ignored) {
        }
        super.finish();
    }
}
