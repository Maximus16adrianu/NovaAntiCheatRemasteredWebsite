package kireiko.dev.millennium.ml.logic;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class Logger {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String RESET = "\u001B[m";
    private static final String DARK_GRAY = "\u001B[90m";
    private static final String GRAY = "\u001B[37m";
    private static final String RED = "\u001B[91m";
    private static final String YELLOW = "\u001B[93m";
    private static final String BOLD = "\u001B[1m";

    public static void info(String msg) {
        System.out.println(prefix() + msg + RESET);
    }

    public static void warn(String msg) {
        System.out.println(prefix() + YELLOW + BOLD + "WARNING" + GRAY + ": " + msg + RESET);
    }

    public static void error(String msg) {
        System.out.println(prefix() + RED + BOLD + "ERROR" + GRAY + ": " + msg + RESET);
    }

    private static String prefix() {
        return DARK_GRAY + "[" + GRAY + TIME_FORMAT.format(LocalTime.now()) + DARK_GRAY + "] "
                + "[" + RED + BOLD + "Nova" + RESET + DARK_GRAY + "]" + GRAY + " ";
    }
}
