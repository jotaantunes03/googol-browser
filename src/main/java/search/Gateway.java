package search;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.*;

/**
 * The Gateway class serves as the central coordinator for the distributed search engine.
 * It implements the GatewayInterface and extends UnicastRemoteObject to provide
 * remote method invocation capabilities.
 *
 * <p>The Gateway performs several critical functions:</p>
 * <ul>
 *   <li>Load balancing across multiple storage barrels</li>
 *   <li>Results caching to improve response times</li>
 *   <li>URL submission for indexing</li>
 *   <li>Search query distribution and aggregation</li>
 *   <li>Fault tolerance through barrel redundancy</li>
 * </ul>
 *
 * <p>The class uses RMI (Remote Method Invocation) for communication with
 * storage barrels and the URL queue system.</p>
 *
 * @author João Antunes and David Cameijo
 */
public class Gateway extends UnicastRemoteObject implements GatewayInterface {

    //----------------------------------------ATTRIBUTES----------------------------------------

    /** Map tracking storage barrels and their current load */
    private Map<IndexStorageBarrelInterface, Integer> barrelsLoad;

    /** Interface for accessing the URL queue */
    private URLQueueInterface urlQueue;

    /** Cache for storing recent search results to improve response time */
    private Map<String, List<String>> searchCache;

    /** Random number generator for barrel selection */
    private final Random random = new Random();

    //----------------------------------------CONSTRUCTOR----------------------------------------

    /**
     * Constructs a new Gateway instance, initializing data structures and
     * establishing connections to storage barrels and the URL queue.
     *
     * <p>The constructor initializes the barrels load map, search cache, and
     * connects to the distributed system components via RMI.</p>
     *
     * @throws RemoteException If there is an error in the remote communication
     */
    public Gateway() throws RemoteException {
        super();
        barrelsLoad = new HashMap<>();
        searchCache = new HashMap<>();
        connectToServices();
    }

    //----------------------------------------METHODS----------------------------------------

    /**
     * Establishes connections to the distributed storage barrels and URL queue.
     *
     * <p>This method attempts to connect to storage barrels on predefined ports
     * and to the URL queue service. It handles connection failures gracefully
     * by logging errors and continuing with available services.</p>
     */
    private void connectToServices() {
        try {
            // Connect to storage barrels on ports in the specified range
            for (int port = 8182; port <= 8183; port++) {
                try {
                    // Attempt to connect to the Registry at the current port
                    Registry registry = LocateRegistry.getRegistry(port);
                    System.out.println("Conectado ao Registry na porta: " + port);

                    // Look up the storage barrel service
                    IndexStorageBarrelInterface barrel = (IndexStorageBarrelInterface) registry.lookup("index");

                    // Add the barrel to the load tracking map with initial load of 0
                    barrelsLoad.put(barrel, 0);
                    System.out.println("Conectado a um Storage Barrel na porta " + port);
                } catch (Exception e) {
                    // Log connection failures but continue with other ports
                    System.out.println("Nenhum serviço disponível na porta: " + port);
                }
            }

            // Connect to the URL queue service
            Registry registryQueue = LocateRegistry.getRegistry(8184);
            urlQueue = (URLQueueInterface) registryQueue.lookup("URLQueueService");

            System.out.println("Gateway conectada a " + barrelsLoad.size() + " Storage Barrels e à URLQueue.");
        } catch (Exception e) {
            System.err.println("Erro ao conectar aos serviços RMI: " + e.getMessage());
        }
    }

    /**
     * Identifies the storage barrel with the lowest current load.
     *
     * <p>This method selects the storage barrel that is handling the fewest
     * requests, enabling load balancing across the distributed system.</p>
     *
     * @return The least loaded storage barrel, or null if none are available
     */
    private IndexStorageBarrelInterface getLeastLoadedBarrel() {
        return barrelsLoad.entrySet()
                .stream()
                .min(Comparator.comparingInt(Map.Entry::getValue))  // Find barrel with minimum load
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Selects a storage barrel randomly from the available barrels.
     *
     * <p>This method provides a simple form of load distribution by randomly
     * selecting a storage barrel for processing a request.</p>
     *
     * @return A randomly selected storage barrel
     */
    private IndexStorageBarrelInterface getRandomBarrel() {
        List<IndexStorageBarrelInterface> barrels = new ArrayList<>(barrelsLoad.keySet());
        return barrels.get(random.nextInt(barrels.size()));
    }

    /**
     * Searches for a word across the distributed index and returns matching URLs.
     *
     * <p>This method first checks the cache for recent results, then delegates
     * the search to an available storage barrel. It handles failures by retrying
     * with alternative barrels and updates the load tracking accordingly.</p>
     *
     * @param word The word to search for
     * @return List of URLs containing the searched word
     * @throws RemoteException If there is an error in remote communication
     */
    @Override
    public List<String> search(String word) throws RemoteException {
        // Check cache for existing results
        if (searchCache.containsKey(word)) {
            System.out.println("Cache hit para a palavra: " + word);
            return searchCache.get(word);
        }

        List<String> results = new ArrayList<>();

        // Verify that barrels are available
        if (barrelsLoad.isEmpty()) {
            System.err.println("Nenhum Storage Barrel disponível!");
            return results;
        }

        // Select a barrel randomly for this search
        IndexStorageBarrelInterface selectedBarrel = getRandomBarrel();

        if (selectedBarrel == null) {
            System.err.println("Nenhum Storage Barrel disponível!");
            return results;
        }

        // Increment the barrel's load count
        barrelsLoad.put(selectedBarrel, barrelsLoad.get(selectedBarrel) + 1);

        try {
            // Execute the search on the selected barrel
            results = selectedBarrel.searchWord(word);

            // Cache the results for future queries
            searchCache.put(word, results);
        } catch (RemoteException e) {
            System.err.println("Erro ao pesquisar num Storage Barrel. Tentando outro...");

            // Remove the failed barrel from the available set
            barrelsLoad.remove(selectedBarrel);

            if (!barrelsLoad.isEmpty()) {
                // Retry the search with another barrel
                return search(word);
            }
        } finally {
            // Decrease the load count after processing completes
            if (barrelsLoad.containsKey(selectedBarrel)) {
                barrelsLoad.put(selectedBarrel, barrelsLoad.get(selectedBarrel) - 1);
            }
        }

        return results;
    }

    /**
     * Adds a URL to the indexing queue for processing.
     *
     * <p>This method submits a URL to the URL queue service for subsequent
     * downloading and indexing by the downloader components.</p>
     *
     * @param url The URL to be indexed
     * @throws RemoteException If there is an error in remote communication
     */
    @Override
    public void addUrl(String url) throws RemoteException {
        if (urlQueue != null) {
            urlQueue.addUrl(url);
        } else {
            System.err.println("Erro: URLQueue não está disponível...");
        }
    }

    //----------------------------------------MAIN----------------------------------------

    /**
     * The main entry point that initializes and starts the Gateway service.
     *
     * <p>This method creates a Gateway instance, registers it with the RMI registry,
     * and makes it available for remote clients to access.</p>
     *
     * @param args Command-line arguments (not used)
     */
    public static void main(String[] args) {
        try {
            // Create the Gateway instance
            Gateway gateway = new Gateway();

            // Create RMI registry on the specified port
            Registry registry = LocateRegistry.createRegistry(8185);

            // Register the Gateway service
            registry.rebind("GatewayService", gateway);

            System.out.println("Gateway ativa e pronta para receber pedidos...");
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
}