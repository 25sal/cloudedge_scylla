package de.hpi.bpt.scylla.logger;

/**
 * General class for logging occurences during simulation.
 * 
 * @author Tsun Yin Wong
 *
 */
public class DebugLogger {
	
	// -Dscylla.quiet=true switches debug output off, e.g. for long parameter sweeps
	public static boolean allowDebugLogging = !Boolean.getBoolean("scylla.quiet");

    /**
     * Logs general messages.
     * 
     * @param message
     *            the message
     */
    public static void log(String message) {
       if(allowDebugLogging)System.out.println("[DEBUG] "+message);
    }

    /**
     * Logs exceptional messages.
     * 
     * @param message
     *            the message
     */
    public static void error(String message) {
        System.err.println(message);
    }

}
