package search;

import search.Sockets.ReliableMulticast;

import java.io.IOException;
import java.net.*;
import java.rmi.*;
import java.rmi.server.*;
import java.rmi.registry.*;
import java.sql.*;
import java.util.*;
import java.io.File;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * IndexStorageBarrel implements a distributed inverted index storage system for a search engine.
 *
 * This class is responsible for storing and managing an inverted index for a search engine
 * using a SQLite database for persistent storage of indexed words and link relationships.
 * It implements multicast communication for receiving index updates and RMI for remote
 * method invocation.
 *
 * The database schema consists of:
 * 1. index_data table - stores words and their associated URLs
 * 2. links_graph table - stores the web page link relationships
 *
 * This class provides methods for adding items to the index, searching for words,
 * managing link relationships, and obtaining index statistics.
 *
 * @author João Antunes and David Cameijo
 */
public class IndexStorageBarrel extends UnicastRemoteObject implements IndexStorageBarrelInterface {

    //----------------------------------------ATTRIBUTES----------------------------------------

    /** Interface for communication with the URLQueue via RMI */
    private static URLQueueInterface urlQueueInterface;

    /** Connection to the SQLite database */
    private Connection connection;

    /** Multicast group address for distributed communication */
    private static final String GROUP_ADDRESS = "230.0.0.0";

    /** Port number for multicast communication */
    private static final int PORT = 4446;

    /** ReliableMulticast instance for group communication */
    private static ReliableMulticast multicast;

    /** Unique identifier for this barrel instance, used to create a unique database */
    private final String barrelId;

    //----------------------------------------CONSTRUCTORS----------------------------------------

    /**
     * Default constructor for IndexStorageBarrel.
     * Initializes the barrel with a randomly generated UUID as its identifier.
     *
     * @throws RemoteException If a communication-related exception occurs during remote object initialization
     */
    public IndexStorageBarrel() throws RemoteException {
        this(UUID.randomUUID().toString());
    }

    /**
     * Constructor with specified barrel identifier.
     * Initializes the barrel with the provided identifier, sets up the multicast
     * connection, and configures the database.
     *
     * @param barrelId Unique identifier for this barrel instance
     * @throws RemoteException If a communication-related exception occurs during remote object initialization
     */
    public IndexStorageBarrel(String barrelId) throws RemoteException {
        super();
        this.barrelId = barrelId;
        try {
            multicast = new ReliableMulticast(GROUP_ADDRESS, PORT);

            System.out.println("IndexStorageBarrel " + barrelId + " conectado ao grupo multicast.");

            setupDatabase();

        } catch (IOException e) {
            System.err.println("Erro ao configurar multicast: " + e.getMessage());
            e.printStackTrace();
        }
    }

    //----------------------------------------DATABASE METHODS----------------------------------------

    /**
     * Configures the SQLite database for this barrel.
     *
     * This method:
     * 1. Creates the Index_BD directory if it doesn't exist
     * 2. Establishes a connection to a SQLite database with a unique name based on barrelId
     * 3. Creates necessary tables if they don't exist
     * 4. Sets up database optimization parameters and indices
     *
     * The database schema includes:
     * - index_data table: stores words and their associated URLs
     * - links_graph table: stores the web page link relationships
     *
     * Performance optimizations include:
     * - Write-Ahead Logging (WAL) for improved concurrency
     * - Indices on frequently queried columns
     * - Memory-optimized cache settings
     */
    private void setupDatabase() {
        try {
            // Create "Index_BD" directory if it doesn't exist
            File directory = new File("../Index_BD");
            if (!directory.exists()) {
                directory.mkdir();
            }

            // Connect to SQLite database with unique name based on barrelId
            String dbPath = "jdbc:sqlite:../Index_BD/index_storage_" + barrelId + ".db";
            connection = DriverManager.getConnection(dbPath);
            System.out.println("Barrel " + barrelId + " ligado à base de dados: " + dbPath);

            // Create tables if they don't exist
            try (Statement stmt = connection.createStatement()) {
                // Configure database for performance
                stmt.execute("PRAGMA journal_mode=WAL;");           // Write-Ahead Logging for improved concurrency
                stmt.execute("PRAGMA synchronous=NORMAL;");         // Balance between durability and performance
                stmt.execute("PRAGMA cache_size=10000;");           // Allocate more memory for caching
                stmt.execute("PRAGMA temp_store=MEMORY;");          // Store temporary tables in memory

                // Create index_data table for inverted index storage
                stmt.execute("CREATE TABLE IF NOT EXISTS index_data (" +
                        "word TEXT PRIMARY KEY, " +                 // The indexed word
                        "urls TEXT)");                              // Semicolon-separated list of URLs containing the word

                // Add index to improve search performance
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_word ON index_data(word)");

                // Create links_graph table for storing link relationships between pages
                stmt.execute("CREATE TABLE IF NOT EXISTS links_graph (" +
                        "source_url TEXT, " +                       // URL of the page containing the link
                        "linked_url TEXT, " +                       // URL of the linked page
                        "PRIMARY KEY (source_url, linked_url))");   // Composite primary key to ensure uniqueness

                // Create indices to improve link query performance
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_source_url ON links_graph(source_url)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_linked_url ON links_graph(linked_url)");
            }

        } catch (SQLException e) {
            System.err.println("Erro ao configurar a base de dados para barrel " + barrelId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    //----------------------------------------METHODS----------------------------------------

    /**
     * Continuously listens for multicast messages and processes them accordingly.
     *
     * This method implements a non-terminating loop that:
     * 1. Receives messages from the multicast group
     * 2. Parses the message format
     * 3. Processes messages based on their format:
     *    - Word;URL format for adding to the inverted index
     *    - addLink;sourceUrl;linkedUrl format for adding link relationships
     * 4. Handles connection errors with reconnection logic
     *
     * The method includes error recovery mechanisms to reconnect to the
     * multicast group after a failure.
     */
    public void listen() {
        try {
            System.out.println("Barrel " + barrelId + " iniciando escuta de mensagens multicast...");
            while (true) {
                String message = multicast.receiveMessage();
                String[] parts = message.split(";");

                if (parts.length == 2) {
                    // Word;URL format for indexing
                    addToIndex(parts[0], parts[1]);
                } else if (parts.length == 3 && "addLink".equals(parts[0])) {
                    // addLink;sourceUrl;linkedUrl format for link relationships
                    addLink(parts[1], parts[2]);
                } else {
                    System.err.println("Invalid message format received: " + message);
                }
            }
        } catch (IOException | SQLException e) {
            System.err.println("Barrel " + barrelId + " erro na recepção de dados multicast: " + e.getMessage());
            e.printStackTrace();

            // Attempt to reconnect to the multicast group after a delay
            try {
                Thread.sleep(5000);
                multicast = new ReliableMulticast(GROUP_ADDRESS, PORT);
                System.out.println("Barrel " + barrelId + " reconectado ao grupo multicast.");
                listen(); // Restart listening
            } catch (Exception ex) {
                System.err.println("Barrel " + barrelId + " falha ao reconectar: " + ex.getMessage());
                ex.printStackTrace();
            }
        }
    }

    /**
     * Adds a word and its associated URL to the inverted index.
     *
     * This method implements the core functionality of the inverted index by:
     * 1. Checking if the word already exists in the database
     * 2. If it exists, appending the new URL to the existing list (if not already present)
     * 3. If it doesn't exist, creating a new entry with the word and URL
     *
     * The method uses SQL transactions to ensure data integrity during updates.
     *
     * @param word The word to be indexed
     * @param url The URL where the word was found
     * @throws RemoteException If a remote communication error occurs
     * @throws SQLException If a database error occurs
     */
    @Override
    public synchronized void addToIndex(String word, String url) throws RemoteException, SQLException {
        if (word == null || word.trim().isEmpty() || url == null || url.trim().isEmpty()) {
            System.err.println("Barrel " + barrelId + " tentativa de adicionar palavra ou URL vazio");
            return;
        }

        Connection conn = null;
        try {
            conn = connection;
            conn.setAutoCommit(false);  // Start transaction for atomicity

            String existingUrls = null;

            // Check if the word already exists in the database
            try (PreparedStatement stmt = conn.prepareStatement("SELECT urls FROM index_data WHERE word = ?")) {
                stmt.setString(1, word);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        existingUrls = rs.getString("urls");
                    }
                }
            }

            // Update or insert the word with associated URLs
            if (existingUrls != null) {
                // Check if the URL already exists for this word
                String[] urlArray = existingUrls.split(";");
                boolean urlExists = false;
                for (String existingUrl : urlArray) {
                    if (existingUrl.equals(url)) {
                        urlExists = true;
                        break;
                    }
                }

                if (!urlExists) {
                    // Append new URL to existing URLs
                    existingUrls += ";" + url;
                    try (PreparedStatement updateStmt = conn.prepareStatement("UPDATE index_data SET urls = ? WHERE word = ?")) {
                        updateStmt.setString(1, existingUrls);
                        updateStmt.setString(2, word);
                        updateStmt.executeUpdate();
                    }
                }
            } else {
                // Insert new word-URL pair
                try (PreparedStatement insertStmt = conn.prepareStatement("INSERT INTO index_data (word, urls) VALUES (?, ?)")) {
                    insertStmt.setString(1, word);
                    insertStmt.setString(2, url);
                    insertStmt.executeUpdate();
                }
            }
            conn.commit();  // Commit the transaction

        } catch (SQLException e) {
            System.err.println("Barrel " + barrelId + " erro ao adicionar ao índice: " + e.getMessage());
            try {
                if (conn != null) {
                    conn.rollback(); // Rollback transaction on error
                }
            } catch (SQLException re) {
                System.err.println("Barrel " + barrelId + " falha ao rollback da transação: " + re.getMessage());
            }
            throw e;
        }
    }

    /**
     * Searches for a word in the inverted index and returns all URLs containing it.
     *
     * This method queries the index_data table for the specified word and retrieves
     * all associated URLs.
     *
     * @param words The word to search for in the index
     * @return A list of URLs where the word appears
     * @throws RemoteException If a remote communication error occurs
     */
    @Override
    public List<String> searchWord(String words) throws RemoteException {
        // Split the input string into individual words
        String[] wordArray = words.trim().split("\\s+");
        List<Set<String>> listOfUrlSets = new ArrayList<>();

        // Process each word in the query
        for (String word : wordArray) {
            Set<String> urlSet = new HashSet<>();
            try (PreparedStatement stmt = connection.prepareStatement(
                    "SELECT urls FROM index_data WHERE word = ?")) {
                stmt.setString(1, word);
                ResultSet rs = stmt.executeQuery();
                // Loop through all rows for the given word
                while (rs.next()) {
                    String[] urls = rs.getString("urls").split(";");
                    urlSet.addAll(Arrays.asList(urls));
                }
            } catch (SQLException e) {
                System.err.println("Barrel " + barrelId + " error searching word: " + e.getMessage());
                e.printStackTrace();
            }
            // If no URLs were found for this word, exit early with an empty list
            if (urlSet.isEmpty()) {
                return new ArrayList<>();
            }
            listOfUrlSets.add(urlSet);
        }

        // If no word was provided, return an empty list
        if (listOfUrlSets.isEmpty()) {
            return new ArrayList<>();
        }

        // Compute the intersection of all URL sets
        Set<String> commonUrls = new HashSet<>(listOfUrlSets.get(0));
        for (int i = 1; i < listOfUrlSets.size(); i++) {
            commonUrls.retainAll(listOfUrlSets.get(i));
            // Early exit if no common URLs exist
            if (commonUrls.isEmpty()) {
                break;
            }
        }

        // Convert the set of common URLs to a list
        List<String> commonUrlsList = new ArrayList<>(commonUrls);

        // Return the sorted list by the number of times the URLs appear as linked_url
        return sortUrlsByLinkedCount(commonUrlsList);
    }



    public List<String> sortUrlsByLinkedCount(List<String> urls) {
        // A map to store each URL and its count from the links_graph table.
        Map<String, Integer> urlCountMap = new HashMap<>();

        // Prepare the SQL query to count occurrences of a given URL in the linked_url column.
        String sql = "SELECT COUNT(*) FROM links_graph WHERE linked_url = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            // For each URL, execute the count query.
            for (String url : urls) {
                stmt.setString(1, url);
                try (ResultSet rs = stmt.executeQuery()) {
                    int count = 0;
                    if (rs.next()) {
                        count = rs.getInt(1);
                    }
                    urlCountMap.put(url, count);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error counting linked_url occurrences: " + e.getMessage());
            e.printStackTrace();
        }

        // Sort the original list of URLs based on the counts, highest count first.
        List<String> sortedUrls = new ArrayList<>(urls);
        sortedUrls.sort((url1, url2) -> Integer.compare(urlCountMap.get(url2), urlCountMap.get(url1)));

        return sortedUrls;
    }

    public List<String> getInboundLinks(String pageUrl) {
        List<String> inboundLinks = new ArrayList<>();
        String sql = "SELECT source_url FROM links_graph WHERE linked_url = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, pageUrl);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                inboundLinks.add(rs.getString("source_url"));
            }
        } catch (SQLException e) {
            System.err.println("Erro ao consultar links para " + pageUrl + ": " + e.getMessage());
        }
        return inboundLinks;
    }

    /**
     * Adds a link relationship between two URLs to the links_graph table.
     *
     * This method records that a page at sourceUrl links to a page at linkedUrl.
     * It uses the INSERT OR IGNORE statement to avoid duplicate entries and
     * implements transaction management for data integrity.
     *
     * @param sourceUrl The URL of the page containing the link
     * @param linkedUrl The URL of the linked page
     * @throws RemoteException If a remote communication error occurs
     */
    @Override
    public synchronized void addLink(String sourceUrl, String linkedUrl) throws RemoteException {
        if (sourceUrl == null || sourceUrl.trim().isEmpty() ||
                linkedUrl == null || linkedUrl.trim().isEmpty()) {
            System.err.println("Barrel " + barrelId + " tentativa de adicionar link com URL vazio");
            return;
        }

        Connection conn = null;
        try {
            conn = connection;
            conn.setAutoCommit(false);  // Start transaction

            try (PreparedStatement stmt = conn.prepareStatement("INSERT OR IGNORE INTO links_graph (source_url, linked_url) VALUES (?, ?)")) {
                stmt.setString(1, sourceUrl);
                stmt.setString(2, linkedUrl);
                int rowsAffected = stmt.executeUpdate();

                conn.commit();  // Commit transaction

            }
        } catch (SQLException e) {
            System.err.println("Barrel " + barrelId + " erro ao adicionar link: " + e.getMessage());
            try {
                if (conn != null) {
                    conn.rollback(); // Rollback transaction on error
                }
            } catch (SQLException re) {
                System.err.println("Barrel " + barrelId + " falha ao rollback da transação: " + re.getMessage());
            }
            e.printStackTrace();
            throw new RemoteException("Falha ao adicionar link", e);
        }
    }

    /**
     * Checks if a URL is present in the inverted index.
     *
     * This method searches through the index_data table to determine if the
     * specified URL has been indexed for any word.
     *
     * @param url The URL to check for indexing status
     * @return true if the URL is present in the index, false otherwise
     * @throws RemoteException If a remote communication error occurs
     */
    @Override
    public boolean isUrlIndexed(String url) throws RemoteException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT 1 FROM index_data WHERE urls LIKE ? LIMIT 1")) {
            stmt.setString(1, "%" + url + "%");
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            System.err.println("Barrel " + barrelId + " erro ao verificar URL indexado: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Collects and returns statistics about the current state of the index.
     *
     * This method gathers various metrics including:
     * - Total number of indexed words
     * - Total number of link relationships
     * - Database size (in bytes and megabytes)
     * - Barrel identification and status information
     *
     * @return A map containing statistical information about the index
     * @throws RemoteException If a remote communication error occurs
     */
    public Map<String, Object> getStats() throws RemoteException {
        Map<String, Object> stats = new HashMap<>();

        try {
            // Count total indexed words
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM index_data")) {
                if (rs.next()) {
                    stats.put("total_words", rs.getInt(1));
                }
            }

            // Count total link relationships
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM links_graph")) {
                if (rs.next()) {
                    stats.put("total_links", rs.getInt(1));
                }
            }

            // Calculate database size
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("PRAGMA page_count")) {
                if (rs.next()) {
                    long pageCount = rs.getLong(1);
                    try (ResultSet rs2 = stmt.executeQuery("PRAGMA page_size")) {
                        if (rs2.next()) {
                            long pageSize = rs2.getLong(1);
                            stats.put("db_size_bytes", pageCount * pageSize);
                            stats.put("db_size_mb", (pageCount * pageSize) / (1024.0 * 1024.0));
                        }
                    }
                }
            }

            // Add barrel identification and status information
            stats.put("barrel_id", barrelId);
            stats.put("status", "active");

        } catch (SQLException e) {
            System.err.println("Barrel " + barrelId + " erro ao obter estatísticas: " + e.getMessage());
            e.printStackTrace();
            stats.put("status", "error");
            stats.put("error", e.getMessage());
        }

        return stats;
    }



    @Override
    public boolean ping() throws RemoteException {
        try {
            // Check if the database connection is valid
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            System.err.println("Ping failed: " + e.getMessage());
            return false;
        }
    }

    public String getBarrelId() throws RemoteException {
        try {
            return barrelId;  // Assuming barrelId is a field in the class
        } catch (Exception e) {
            System.err.println("Erro ao consultar Barrel ID");
            e.printStackTrace();
            return null;  // Ensure a value is returned in case of an error
        }
    }

//----------------------------------------MAIN----------------------------------------

    /**
     * Main method to initialize and run the IndexStorageBarrel server.
     *
     * This method:
     * 1. Validates command-line arguments
     * 2. Creates a unique barrel identifier
     * 3. Initializes the IndexStorageBarrel server
     * 4. Sets up the RMI registry and registers the service
     * 5. Connects to the URLQueue service
     * 6. Starts a multicast listener thread
     * 7. Adds a shutdown hook for clean resource termination
     *
     * @param args Command-line arguments (args[0]: port number, args[1]: optional barrel ID)
     */
    public static void main(String args[]) {
        try {
            if (args.length < 1) {
                System.err.println("Por favor, especifique a porta como argumento.");
                return;
            }

            int port = Integer.parseInt(args[0]); // RMI registry port

            // Create a unique barrel ID
            String barrelId = "barrel_" + System.currentTimeMillis() + "_" + Math.abs(new Random().nextInt(1000));

            // Optionally use the second argument as barrelId if provided
            if (args.length >= 2) {
                barrelId = args[1];
            }

            // Initialize the RMI server
            IndexStorageBarrel server = new IndexStorageBarrel(barrelId);

            // Create or connect to Registry on the specified port
            Registry registry;
            try {
                registry = LocateRegistry.createRegistry(port); // Create a new Registry
                System.out.println("Registry criado na porta: " + port);
            } catch (RemoteException e) {
                // If a Registry already exists, connect to it
                registry = LocateRegistry.getRegistry(port);
                System.out.println("Conectado ao Registry existente na porta: " + port);
            }

            // Register the service with the name "index"
            String serviceName = "index";
            registry.rebind(serviceName, server);
            System.out.println("IndexStorageBarrel " + barrelId + " registrado com o nome '" + serviceName + "' na porta: " + port);

            // Connect to the URLQueue server
            try {
                Registry registryQueue = LocateRegistry.getRegistry(8184);
                urlQueueInterface = (URLQueueInterface) registryQueue.lookup("URLQueueService");
                System.out.println("Barrel " + barrelId + " conectado à URLQueue.");
            } catch (Exception e) {
                System.err.println("Barrel " + barrelId + " não conseguiu conectar à URLQueue: " + e.getMessage());
                System.err.println("Certifique-se de que a URLQueue está em execução na porta 8184");
            }

            // Start multicast listener thread
            System.out.println("Barrel " + barrelId + " iniciando thread de escuta multicast...");
            Thread listenerThread = new Thread(server::listen);
            listenerThread.setName("MulticastListener-" + barrelId);
            listenerThread.start();
            System.out.println("Barrel " + barrelId + " thread de escuta multicast iniciada.");

            // Add shutdown hook for clean resource termination
            String finalBarrelId = barrelId;
            Registry finalRegistry = registry;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    System.out.println("Desligando barrel " + finalBarrelId + "...");
                    finalRegistry.unbind(serviceName);
                    multicast.leaveGroup();
                    if (server.connection != null && !server.connection.isClosed()) {
                        server.connection.close();
                    }
                    System.out.println("Barrel " + finalBarrelId + " encerrado com sucesso.");
                } catch (Exception e) {
                    System.err.println("Erro ao encerrar barrel " + finalBarrelId + ": " + e.getMessage());
                }
            }));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}