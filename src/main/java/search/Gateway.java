package search;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.*;
import java.rmi.NotBoundException;
import java.util.concurrent.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;



/**
 * Enhanced Gateway class with comprehensive fault tolerance and health monitoring.
 */
public class Gateway extends UnicastRemoteObject implements GatewayInterface, AutoCloseable {

    //----------------------------------------ATTRIBUTES----------------------------------------

    /** Map tracking storage barrels and their current load */
    private Map<IndexStorageBarrelInterface, BarrelHealth> barrelsHealth;

    /** List of port ranges to attempt barrel connections */
    private static final int[] BARREL_PORTS = {8182, 8183};

    /** URL Queue service port */
    private static final int URL_QUEUE_PORT = 8184;

    /** Gateway service port */
    private static final int GATEWAY_PORT = 8185;

    /** Health check interval in seconds */
    private static final int HEALTH_CHECK_INTERVAL = 30;

    /** Maximum consecutive failures before removing a barrel */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** Interface for accessing the URL queue */
    private URLQueueInterface urlQueue;

    /** Cache for storing recent search results to improve response time */
    private Map<String, List<String>> searchCache;

    /** Random number generator for barrel selection */
    private final Random random = new Random();

    /** Scheduled executor for periodic health checks */
    private ScheduledExecutorService healthCheckExecutor;

    /** Formatter for logging timestamps */
    private static final DateTimeFormatter LOG_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Inner class to track barrel health status
     */
    private static class BarrelHealth {
        int currentLoad;
        int consecutiveFailures;
        long lastSuccessfulContactTime;

        BarrelHealth() {
            this.currentLoad = 0;
            this.consecutiveFailures = 0;
            this.lastSuccessfulContactTime = System.currentTimeMillis();
        }

        void recordSuccess() {
            consecutiveFailures = 0;
            lastSuccessfulContactTime = System.currentTimeMillis();
        }

        void recordFailure() {
            consecutiveFailures++;
        }

        boolean isHealthy() {
            return consecutiveFailures < MAX_CONSECUTIVE_FAILURES;
        }
    }

    //----------------------------------------LOGGING METHODS----------------------------------------

    /**
     * Logs an error message with timestamp.
     * @param message The error message to log
     */
    private static void logError(String message) {
        System.err.println(String.format("[ERROR] %s - %s",
                LocalDateTime.now().format(LOG_FORMATTER), message));
    }

    /**
     * Logs a warning message with timestamp.
     * @param message The warning message to log
     */
    private void logWarning(String message) {
        System.out.println(String.format("[WARN] %s - %s",
                LocalDateTime.now().format(LOG_FORMATTER), message));
    }

    /**
     * Static method for logging info messages.
     * @param message The message to log
     */
    public static void logInfo(String message) {
        System.out.println(String.format("[INFO] %s - %s",
                LocalDateTime.now().format(LOG_FORMATTER), message));
    }

    //----------------------------------------CONSTRUCTOR----------------------------------------

    /**
     * Constructs a new Gateway instance with enhanced health monitoring.
     *
     * @throws RemoteException If there is an error in remote communication
     */
    public Gateway() throws RemoteException {
        super();
        barrelsHealth = new ConcurrentHashMap<>();
        searchCache = new ConcurrentHashMap<>();

        // Initial connection to services
        connectToServices();

        // Start periodic health checks
        startPeriodicHealthChecks();
    }

    //----------------------------------------SERVICE CONNECTION METHODS----------------------------------------

    /**
     * Starts periodic health checks for all connected services.
     */
    private void startPeriodicHealthChecks() {
        healthCheckExecutor = Executors.newScheduledThreadPool(1);
        healthCheckExecutor.scheduleAtFixedRate(this::performHealthCheck,
                HEALTH_CHECK_INTERVAL,
                HEALTH_CHECK_INTERVAL,
                TimeUnit.SECONDS
        );
    }

    /**
     * Establishes connections to the distributed storage barrels and URL queue.
     */
    private synchronized void connectToServices() {
        // Clear existing connections
        barrelsHealth.clear();

        logInfo("Attempting to connect to distributed services...");

        try {
            // Connect to storage barrels on specified ports
            for (int port : BARREL_PORTS) {
                try {
                    Registry registry = LocateRegistry.getRegistry(port);
                    IndexStorageBarrelInterface barrel = (IndexStorageBarrelInterface) registry.lookup("index");

                    // Verify barrel connectivity with a test method call
                    barrel.ping();

                    // Add the barrel to the health tracking map
                    barrelsHealth.put(barrel, new BarrelHealth());
                    logInfo(String.format("Successfully connected to Storage Barrel on port %d", port));
                } catch (RemoteException | NotBoundException e) {
                    logWarning(String.format("No Storage Barrel service available on port %d: %s",
                            port, e.getMessage()));
                }
            }

            // Verify at least one barrel is connected
            if (barrelsHealth.isEmpty()) {
                logError("CRITICAL: No storage barrels could be connected!");
            }

            // Connect to the URL queue service
            try {
                Registry registryQueue = LocateRegistry.getRegistry(URL_QUEUE_PORT);
                urlQueue = (URLQueueInterface) registryQueue.lookup("URLQueueService");
                logInfo("Successfully connected to URL Queue Service");
            } catch (Exception e) {
                logError(String.format("Failed to connect to URL Queue: %s", e.getMessage()));
                urlQueue = null;
            }

            logInfo(String.format("Gateway connected to %d Storage Barrels and URL Queue", barrelsHealth.size()));
        } catch (Exception e) {
            logError(String.format("Unexpected error connecting to RMI services: %s", e.getMessage()));
        }
    }

    /**
     * Performs comprehensive health check on all connected barrels.
     */
    private synchronized void performHealthCheck() {
        logInfo("Performing periodic health check on distributed services...");

        // Check and remove unhealthy barrels
        List<IndexStorageBarrelInterface> barrelsToRemove = new ArrayList<>();

        for (Map.Entry<IndexStorageBarrelInterface, BarrelHealth> entry : barrelsHealth.entrySet()) {
            IndexStorageBarrelInterface barrel = entry.getKey();
            BarrelHealth health = entry.getValue();

            try {
                // Attempt to ping the barrel
                barrel.ping();
                health.recordSuccess();
                logInfo("Health check successful for a Storage Barrel");
            } catch (RemoteException e) {
                health.recordFailure();
                logWarning(String.format("Health check failed for a Storage Barrel. Failure count: %d",
                        health.consecutiveFailures));

                // Mark for removal if too many consecutive failures
                if (!health.isHealthy()) {
                    barrelsToRemove.add(barrel);
                    logError("A Storage Barrel has been marked for removal due to persistent failures");
                }
            }
        }

        // Remove unhealthy barrels
        barrelsToRemove.forEach(barrelsHealth::remove);

        // Attempt to reconnect if we've lost all barrels
        if (barrelsHealth.isEmpty()) {
            logError("All Storage Barrels lost. Attempting to reconnect...");
            connectToServices();
        }
    }

    /**
     * Selects the least loaded and healthy storage barrel.
     *
     * @return The least loaded healthy storage barrel, or null if none available
     */
    private IndexStorageBarrelInterface selectHealthyBarrel() {
        return barrelsHealth.entrySet()
                .stream()
                .filter(entry -> entry.getValue().isHealthy())
                .min(Comparator.comparingInt(entry -> entry.getValue().currentLoad))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    //----------------------------------------INTERFACE METHODS----------------------------------------

    /**
     * Searches for a word across the distributed index with enhanced fault tolerance.
     *
     * @param word The word to search for
     * @return List of URLs containing the searched word
     * @throws RemoteException If there is an error in remote communication
     */
    @Override
    public List<String> search(String word) throws RemoteException {
        // Check cache first
        if (searchCache.containsKey(word)) {
            logInfo(String.format("Cache hit for word: %s", word));
            return searchCache.get(word);
        }

        // Ensure we have barrels
        if (barrelsHealth.isEmpty()) {
            connectToServices();
            if (barrelsHealth.isEmpty()) {
                logError("No Storage Barrels available after reconnection!");
                return Collections.emptyList();
            }
        }

        // Select a healthy barrel
        IndexStorageBarrelInterface selectedBarrel = selectHealthyBarrel();
        if (selectedBarrel == null) {
            logError("No healthy Storage Barrels available!");
            return Collections.emptyList();
        }

        // Track barrel load
        BarrelHealth barrelHealth = barrelsHealth.get(selectedBarrel);
        barrelHealth.currentLoad++;

        try {
            // Perform search
            List<String> results = selectedBarrel.searchWord(word);

            // Cache results
            searchCache.put(word, results);

            return results;
        } catch (RemoteException e) {
            logError(String.format("Error searching barrel for word '%s'. Attempting recovery...", word));

            // Mark this barrel as failed
            barrelHealth.recordFailure();

            // If barrel is no longer healthy, remove it
            if (!barrelHealth.isHealthy()) {
                barrelsHealth.remove(selectedBarrel);
            }

            // Attempt to reconnect and retry
            connectToServices();

            // Recursive retry with newly connected barrels
            if (!barrelsHealth.isEmpty()) {
                return search(word);
            }

            return Collections.emptyList();
        } finally {
            // Decrease load
            barrelHealth.currentLoad--;
        }
    }

    /**
     * Adds a URL to the indexing queue with improved error handling.
     *
     * @param url The URL to be indexed
     * @throws RemoteException If there is an error in remote communication
     */
    @Override
    public void addUrl(String url) throws RemoteException {
        // Ensure URL queue is connected
        if (urlQueue == null) {
            try {
                Registry registryQueue = LocateRegistry.getRegistry(URL_QUEUE_PORT);
                urlQueue = (URLQueueInterface) registryQueue.lookup("URLQueueService");
            } catch (Exception e) {
                logError(String.format("Definitive failure reconnecting to URL Queue: %s", e.getMessage()));
                return;
            }
        }

        try {
            urlQueue.addUrl(url);
            logInfo(String.format("URL added to queue: %s", url));
        } catch (RemoteException e) {
            logError(String.format("Error adding URL to queue: %s. Attempting reconnection...", url));
            urlQueue = null;
            addUrl(url);  // Retry after nullifying
        }
    }

    //----------------------------------------RESOURCE MANAGEMENT----------------------------------------

    /**
     * Proper shutdown method to cleanly terminate health check executor.
     * Implements AutoCloseable interface for try-with-resources and explicit cleanup.
     */
    @Override
    public void close() {
        // Shutdown the health check executor
        if (healthCheckExecutor != null) {
            try {
                // Attempt a graceful shutdown
                healthCheckExecutor.shutdown();

                // Wait for existing tasks to terminate
                if (!healthCheckExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    // Force shutdown if tasks don't complete
                    healthCheckExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                // Restore interrupted status
                Thread.currentThread().interrupt();

                // Force shutdown
                healthCheckExecutor.shutdownNow();
            }
        }

        logInfo("Gateway resources cleaned up.");
    }





    //----------------------------------------MAIN METHOD----------------------------------------

    /**
     * Main method to start the Gateway service with improved logging.
     *
     * @param args Command-line arguments (not used)
     */
    public static void main(String[] args) {
        logInfo("Starting Gateway Service...");

        try (Gateway gateway = new Gateway()) {
            // Create RMI registry
            Registry registry = LocateRegistry.createRegistry(GATEWAY_PORT);

            // Register the Gateway service
            registry.rebind("GatewayService", gateway);

            logInfo(String.format("Gateway active on port %d and ready to receive requests", GATEWAY_PORT));

            // Keep the application running
            Thread.currentThread().join();
        } catch (RemoteException | InterruptedException e) {
            logError("Error starting Gateway service: " + e.getMessage());
            e.printStackTrace();
        }
    }


}