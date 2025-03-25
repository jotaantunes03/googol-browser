package search;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The {@code Gateway} class serves as the central access point for handling distributed 
 * search requests, managing communication between clients and storage barrels.
 * <p>
 * This implementation includes:
 * <ul>
 *     <li>Fault tolerance mechanisms for handling remote failures</li>
 *     <li>Load balancing between multiple storage barrels</li>
 *     <li>Health monitoring and automatic reconnection strategies</li>
 * </ul>
 * Implements {@link GatewayInterface} to provide core search and indexing functionalities.
 * </p>
 * <p>
 * Additionally, this class implements {@link AutoCloseable} to ensure proper resource 
 * management when shutting down.
 * </p>
 */
public class Gateway extends UnicastRemoteObject implements GatewayInterface, AutoCloseable {

    //----------------------------------------ATTRIBUTES----------------------------------------

    /** Map tracking storage barrels and their current load */
    private Map<IndexStorageBarrelInterface, BarrelHealth> barrelsHealth;

    /** List of port ranges to attempt barrel connections */
    private static int[] BARREL_PORTS = {8182, 8183};

    /** URL Queue service port */
    private static int URL_QUEUE_PORT = 8184;

    /** Gateway service port */
    private static int GATEWAY_PORT = 8185;

    private static String QUEUE_IP = "localhost";

    private static String[] BARREL_IP = {"localhost", "localhost"};


    /** Health check interval in seconds */
    private static final int HEALTH_CHECK_INTERVAL = 30;

    /** Maximum consecutive failures before removing a barrel */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** Interface for accessing the URL queue */
    private URLQueueInterface urlQueue;

    /** Cache for storing recent search results to improve response time */
    private Map<String, List<String>> searchCache;

    /** Map for tracking frequency of each search term */
    private Map<String, AtomicInteger> searchFrequency;

    /** Map for tracking response time metrics per barrel */
    private Map<IndexStorageBarrelInterface, BarrelMetrics> barrelMetrics;

    // Add this new field at the class level
    private Map<IndexStorageBarrelInterface, Map<String, Object>> closedBarrelStats = new ConcurrentHashMap<>();


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

    /**
     * Inner class to record response time metrics for each barrel.
     */
    private static class BarrelMetrics {
        long totalResponseTimeMs;
        int count;
        String barrelId;  // New field for barrel ID


        synchronized void recordResponse(long responseTimeMs) {
            totalResponseTimeMs += responseTimeMs;
            count++;
        }

        /**
         * Returns the average response time in tenths of a second.
         */
        synchronized double getAverageTenthsOfSecond() {
            if (count == 0) return 0.0;
            // Divide average ms by 100 to convert to tenths of a second
            return ((double) totalResponseTimeMs / count) / 100;
        }
        // Setter for barrelId
        void setBarrelId(String barrelId) {
            this.barrelId = barrelId;
        }

        // Getter for barrelId
        String getBarrelId() {
            return barrelId;
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


        try (InputStream input = new FileInputStream("../config.properties")) {
            Properties prop = new Properties();
            prop.load(input);

            // Ler e converter as portas para int[]
            String ports = prop.getProperty("BARREL_PORTS");
            if (ports != null && !ports.isEmpty()) BARREL_PORTS = Stream.of(ports.split(",")).mapToInt(Integer::parseInt).toArray();
            else System.out.println("Nenhuma porta especificada.");

            URL_QUEUE_PORT = Integer.parseInt(prop.getProperty("URL_QUEUE_PORT"));
            GATEWAY_PORT = Integer.parseInt(prop.getProperty("GATEWAY_PORT"));
            QUEUE_IP = prop.getProperty("QUEUE_IP");

            // Ler e converter os IPs para String[]
            String ips = prop.getProperty("BARREL_IP");
            if (ips != null && !ips.isEmpty()) BARREL_IP = ips.split(",");



        } catch (IOException ex) {
            System.out.println(ex.getMessage());
        }



        barrelsHealth = new ConcurrentHashMap<>();
        searchCache = new ConcurrentHashMap<>();
        searchFrequency = new ConcurrentHashMap<>();
        barrelMetrics = new ConcurrentHashMap<>();

        // Initial connection to services
        connectToServices();

        // Start periodic health checks
        startPeriodicHealthChecks();
    }

    //----------------------------------------SERVICE CONNECTION METHODS----------------------------------------

    /**
     * Starts periodic health checks for all connected services.
     * Health checks are scheduled to run at a fixed interval.
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
     * Establishes connections to the distributed storage barrels and URL queue service.
     * If a connection fails, logs an error and attempts to connect to remaining services.
     */
    private synchronized void connectToServices() {
        // Clear existing connections
        barrelsHealth.clear();

        logInfo("Attempting to connect to distributed services...");

        try {
            // Connect to storage barrels on specified ports
            for (int i = 0; i < BARREL_PORTS.length && i < BARREL_IP.length; i++) {

                int port = BARREL_PORTS[i];
                String barrel_ip = BARREL_IP[i];


                try {
                    Registry registry = LocateRegistry.getRegistry(barrel_ip, port);
                    IndexStorageBarrelInterface barrel = (IndexStorageBarrelInterface) registry.lookup("index");

                    // Verify barrel connectivity with a test method call
                    barrel.ping();
                    // Get barrel ID from the barrel (assuming barrelId can be fetched from the barrel object)
                    String barrelId = barrel.getBarrelId();  // Assuming this method exists


                    // Only create new metrics if this barrel is not already tracked
                    BarrelMetrics metrics = barrelMetrics.get(barrel);
                    if (metrics == null) {
                        metrics = new BarrelMetrics();
                        metrics.setBarrelId(barrelId);
                        barrelMetrics.put(barrel, metrics);
                    }

                    // Similarly, add the barrel to the health tracking map if not already present
                    barrelsHealth.putIfAbsent(barrel, new BarrelHealth());
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
                Registry registryQueue = LocateRegistry.getRegistry(QUEUE_IP, URL_QUEUE_PORT);
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
     * Performs a comprehensive health check on all connected storage barrels.
     * If a barrel repeatedly fails the check, it is marked for removal.
     * If all barrels are lost, attempts to reconnect.
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

        // Attempt to reconnect if all barrels are lost
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

        // Update search frequency
        searchFrequency.computeIfAbsent(word, k -> new AtomicInteger(0)).incrementAndGet();

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

        // Record the start time for response measurement
        long startTime = System.currentTimeMillis();

        try {
            // Perform search
            List<String> urls = selectedBarrel.searchWord(word);
            // Process URLs concurrently using parallelStream
            List<String> results = urls.parallelStream()
                    .map(url -> {
                        String title = getTitle(url);
                        return "URL" + url + "\n" + title + "\n";
                    })
                    .collect(Collectors.toList());

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
            long responseTime = System.currentTimeMillis() - startTime;
            // Update metrics for this barrel
            BarrelMetrics metrics = barrelMetrics.get(selectedBarrel);
            if (metrics != null) {
                metrics.recordResponse(responseTime);
            }
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
                Registry registryQueue = LocateRegistry.getRegistry(QUEUE_IP, URL_QUEUE_PORT);
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

    /**
     * Checks inbound links pointing to a specific page URL.
     * If no healthy storage barrels are available, attempts reconnection before retrying.
     *
     * @param pageUrl The URL to check inbound links for.
     * @return A list of URLs that link to the specified page.
     * @throws RemoteException If a remote communication error occurs.
     */
    public List<String> checkInboundLinks(String pageUrl) throws RemoteException {
        // Check cache first (if you have caching enabled for inbound links)

        // Ensure we have healthy storage barrels available
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
            // Perform the inbound links lookup on the selected barrel
            List<String> results = selectedBarrel.getInboundLinks(pageUrl);
            return results;
        } catch (RemoteException e) {
            logError(String.format("Error checking inbound links for '%s'. Attempting recovery...", pageUrl));

            // Mark this barrel as failed
            barrelHealth.recordFailure();

            // If the barrel is no longer healthy, remove it from the available barrels
            if (!barrelHealth.isHealthy()) {
                barrelsHealth.remove(selectedBarrel);
            }

            // Attempt to reconnect to services and retry
            connectToServices();

            // Recursive retry with newly connected barrels, if any are available
            if (!barrelsHealth.isEmpty()) {
                return checkInboundLinks(pageUrl);
            }

            return Collections.emptyList();
        } finally {
            // Decrease the current load on the selected barrel
            barrelHealth.currentLoad--;
        }
    }


    /**
     * Retrieves the title of a webpage by fetching and parsing its HTML.
     *
     * @param url The URL of the webpage.
     * @return The title of the page with the first paragrapher, or "Failed to fetch title" if retrieval fails.
     */
    public static String getTitle(String url) {
        try {
            Document doc = Jsoup.connect(url).get();
            return doc.title() + "\n" + doc.select("p").first().text();
        } catch (IOException e) {
            e.printStackTrace();
            return "Failed to fetch title";
        }
    }


    /**
     * Extracts a short citation from a webpage.
     * It attempts to fetch the meta description or the first paragraph of the page.
     *
     * @param url The URL of the webpage.
     * @return A short citation or "Failed to fetch citation" if retrieval fails.
     */
    public static String getShortCitation(String url) {
        try {
            Document doc = Jsoup.connect(url).get();
            return doc.select("p").first().text(); // Get the first paragraph text
        } catch (IOException | NullPointerException e) {
            e.printStackTrace();
            return "Failed to fetch citation";
        }
    }

    //----------------------------------------SYSTEM STATE REPORTING----------------------------------------

    /**
     * Returns the current system state including:
     *   - The 10 most common search terms.
     *   - A list of active barrels and their index sizes.
     *   - The average response time (in tenths of a second) per barrel.
     */
    public String getSystemState() throws RemoteException {
        StringBuilder stateReport = new StringBuilder();
        stateReport.append("---- System State Report ----\n");

        // 1. Top 10 most common searches
        stateReport.append("Top 10 Search Terms:\n");
        searchFrequency.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().get(), e1.getValue().get()))
                .limit(10)
                .forEach(entry -> stateReport.append(String.format("  %s: %d searches\n",
                        entry.getKey(), entry.getValue().get())));

        // 2. Active barrels and their detailed statistics (extracted from getStats())
        stateReport.append("\nBarrel Statistics:\n");
        connectToServices();
        for (IndexStorageBarrelInterface barrel : barrelsHealth.keySet()) {
            try {
                // Call getStats() on each barrel to extract its stats
                Map<String, Object> barrelStats = barrel.getStats();
                // Assuming barrelStats contains keys like "barrel_id", "total_words", "total_links", etc.
                stateReport.append(String.format("Barrel [%s]:\n", barrelStats.get("barrel_id")));
                barrelStats.forEach((key, value) -> {
                    // Optionally, filter out the barrel_id to avoid repetition
                    if (!"barrel_id".equals(key)) {
                        stateReport.append(String.format("  %s: %s\n", key, value));
                    }
                });
            } catch (RemoteException e) {
                //stateReport.append(String.format("  Barrel [%s]: Stats Unavailable (error: %s)\n",
                        //barrel.toString(), e.getMessage()));

            }
        }

        // 3. Average response time per barrel (in tenths of a second)
        stateReport.append("\nAverage Response Time per Barrel (tenths of a second):\n");
        for (Map.Entry<IndexStorageBarrelInterface, BarrelMetrics> entry : barrelMetrics.entrySet()) {
            BarrelMetrics metrics = entry.getValue();
            if (metrics != null) {
                double avgTenths = metrics.getAverageTenthsOfSecond();
                String barrelId = metrics.getBarrelId();
                stateReport.append(String.format("  Barrel [%s] -> %.2f\n", barrelId, avgTenths));
            } else {
                stateReport.append(String.format("  Barrel [%s] -> No data available\n", entry.getKey().toString()));
            }
        }

        stateReport.append("------------------------------\n");
        return stateReport.toString();
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