package me.cerial.nova.cloudcombat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CloudCombatServer {
    private static final int DEFAULT_PORT = 47564;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DARK_GRAY = "\u001B[90m";
    private static final String GRAY = "\u001B[37m";
    private static final String RED = "\u001B[91m";
    private static final String YELLOW = "\u001B[93m";

    private final String host;
    private final int port;
    private final boolean loggingEnabled;
    private final LicenseSessionValidator licenseValidator = new LicenseSessionValidator();
    private final ExecutorService clients = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "nova-combat-cloud-client");
        thread.setDaemon(true);
        return thread;
    });
    private volatile ServerSocket serverSocket;
    private volatile Thread listenerThread;
    private volatile boolean running;

    public CloudCombatServer(int port) {
        this("0.0.0.0", port);
    }

    public CloudCombatServer(String host, int port) {
        this(host, port, true);
    }

    public CloudCombatServer(String host, int port, boolean loggingEnabled) {
        this.host = host == null || host.trim().isEmpty() ? "0.0.0.0" : host.trim();
        this.port = port;
        this.loggingEnabled = loggingEnabled;
    }

    public static void main(String[] args) throws IOException {
        int port = readPort(args);
        String host = readHost(args);
        CloudCombatServer server = new CloudCombatServer(host, port, readLoggingEnabled());
        server.listen();
    }

    public void startAsync() throws IOException {
        openSocket();
        Thread thread = new Thread(() -> {
            try {
                acceptLoop();
            } catch (IOException exception) {
                if (running && loggingEnabled) {
                    warn("Combat cloud listener stopped: " + exception.getMessage());
                }
            }
        }, "nova-combat-cloud-listener");
        thread.setDaemon(true);
        listenerThread = thread;
        thread.start();
    }

    public void listen() throws IOException {
        openSocket();
        try {
            acceptLoop();
        } finally {
            close();
        }
    }

    public void close() {
        running = false;
        Thread thread = listenerThread;
        listenerThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        clients.shutdownNow();
    }

    public boolean running() {
        return running;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    private synchronized void openSocket() throws IOException {
        if (running) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(host, port));
        serverSocket = socket;
        running = true;
        if (loggingEnabled) {
            System.out.println(prefix() + "Combat cloud listening on " + host + ":" + port + RESET);
            System.out.println(prefix() + "Using license database " + licenseValidator.databasePath() + RESET);
        }
    }

    private void acceptLoop() throws IOException {
        while (running && !Thread.currentThread().isInterrupted()) {
            Socket socket = serverSocket.accept();
            socket.setTcpNoDelay(true);
            clients.execute(new CloudClientSession(socket, new CombatCloudAnalyzer(), licenseValidator, loggingEnabled));
        }
    }

    private static String readHost(String[] args) {
        if (args != null && args.length > 1) {
            return args[1];
        }
        String property = System.getProperty("nova.combatcloud.host");
        if (property != null && !property.trim().isEmpty()) {
            return property.trim();
        }
        String environment = System.getenv("NOVA_COMBAT_CLOUD_HOST");
        if (environment != null && !environment.trim().isEmpty()) {
            return environment.trim();
        }
        return "0.0.0.0";
    }

    private static int readPort(String[] args) {
        if (args == null || args.length == 0) {
            Integer novaPort = Integer.getInteger("nova.combatcloud.port");
            if (novaPort != null) {
                return novaPort;
            }
            return Integer.getInteger("nova.combatcloud.port", DEFAULT_PORT);
        }
        try {
            return Integer.parseInt(args[0]);
        } catch (NumberFormatException ignored) {
            return DEFAULT_PORT;
        }
    }

    private static boolean readLoggingEnabled() {
        String property = System.getProperty("nova.combatcloud.logging");
        if (property != null && !property.trim().isEmpty()) {
            return Boolean.parseBoolean(property.trim());
        }
        String environment = System.getenv("NOVA_COMBAT_CLOUD_LOGGING");
        if (environment != null && !environment.trim().isEmpty()) {
            return Boolean.parseBoolean(environment.trim());
        }
        return true;
    }

    private static String prefix() {
        return DARK_GRAY + "[" + GRAY + TIME_FORMAT.format(LocalTime.now()) + DARK_GRAY + "] "
                + "[" + RED + BOLD + "Nova" + RESET + DARK_GRAY + "]" + GRAY + " ";
    }

    private static void warn(String message) {
        System.out.println(prefix() + YELLOW + BOLD + "WARNING" + GRAY + ": " + message + RESET);
    }
}
