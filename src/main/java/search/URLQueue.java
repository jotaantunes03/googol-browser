package search;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.sql.*;
import java.util.LinkedList;
import java.util.Properties;
import java.util.Queue;

/**
 * URLQueue implements a persistent queue system for URL management in a distributed search engine.
 *
 * This class provides a centralized service for storing and retrieving URLs that need to be
 * processed by web crawler components. The queue is implemented as a combination of an
 * in-memory queue (for efficiency) and a SQLite database (for persistence).
 *
 * The class ensures that:
 * 1. URLs are uniquely stored (no duplicates)
 * 2. URLs are persisted to disk to survive system restarts
 * 3. URLs are processed in a first-in-first-out (FIFO) manner
 * 4. The queue can be accessed remotely via Java RMI
 *
 * @author João Antunes, David Cameijo and Gabriel Pinto
 */
public class URLQueue extends UnicastRemoteObject implements URLQueueInterface {

    //----------------------------------------ATTRIBUTES----------------------------------------

    /** Connection to the SQLite database for persistent storage */
    private Connection connection;

    /** In-memory queue for high-performance URL operations */
    private Queue<String> queue;
    private static int URL_PORT = 8184;

    //----------------------------------------CONSTRUCTOR----------------------------------------

    /**
     * Constructs a new URLQueue instance.
     *
     * This constructor:
     * 1. Initializes the in-memory queue as a LinkedList
     * 2. Establishes a connection to the SQLite database
     * 3. Creates the URL storage table if it doesn't exist
     *
     * @throws RemoteException If a remote communication error occurs during initialization
     */
    public URLQueue() throws RemoteException {
        super();

        try (InputStream input = new FileInputStream("../config.properties")) {
            Properties prop = new Properties();
            prop.load(input);

            URL_PORT = Integer.parseInt(prop.getProperty("URL_QUEUE_PORT"));
        } catch (IOException ex) {
            System.out.println(ex.getMessage());
        }

        queue = new LinkedList<>();

        try {
            // Connect to SQLite database
            connection = DriverManager.getConnection("jdbc:sqlite:../urlqueue.db");
            Statement stmt = connection.createStatement();

            // Create table if it doesn't exist
            // The UNIQUE constraint prevents duplicate URLs
            stmt.execute("CREATE TABLE IF NOT EXISTS urls (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT UNIQUE)");

            System.out.println("Base de dados pronta a utilizar...");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    //----------------------------------------METHODS----------------------------------------

    /**
     * Adds a URL to the queue if it doesn't already exist.
     *
     * This method:
     * 1. Checks if the URL is already in the in-memory queue
     * 2. If not, adds it to both the in-memory queue and the database
     * 3. Uses SQL's INSERT OR IGNORE to handle duplicate URLs in the database
     *
     * The method is synchronized to ensure thread safety when multiple clients
     * are adding URLs concurrently.
     *
     * @param url The URL to add to the queue
     * @throws RemoteException If a remote communication error occurs
     */
    @Override
    public synchronized void addUrl(String url) throws RemoteException {
        if (!queue.contains(url)) {  // Avoid duplicate URLs
            queue.add(url);
            try (PreparedStatement stmt = connection.prepareStatement("INSERT OR IGNORE INTO urls (url) VALUES (?)")) {
                stmt.setString(1, url);
                stmt.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Retrieves and removes a URL from the queue.
     *
     * This method implements the following logic:
     * 1. First attempts to take a URL from the in-memory queue
     * 2. If the in-memory queue is empty, retrieves one URL from the database
     * 3. Removes the URL from both the in-memory queue and the database
     * 4. Uses a transaction to ensure database consistency
     *
     * The method is synchronized to ensure thread safety when multiple clients
     * are taking URLs concurrently.
     *
     * @return The next URL in the queue, or null if the queue is empty
     * @throws RemoteException If a remote communication error occurs
     */
    @Override
    public synchronized String takeUrl() throws RemoteException {
        String url = queue.poll();  // Remove from memory queue
        if (url != null) {
            // URL was removed from the queue, so remove it from the database too
            try (PreparedStatement deleteStmt = connection.prepareStatement("DELETE FROM urls WHERE url = ?")) {
                // Start a transaction
                connection.setAutoCommit(false);

                deleteStmt.setString(1, url);
                deleteStmt.executeUpdate();

                // Commit the transaction
                connection.commit();
            } catch (SQLException e) {
                // Rollback the transaction in case of error
                if (connection != null) {
                    try {
                        connection.rollback();
                    } catch (SQLException ex) {
                        ex.printStackTrace();
                    }
                }
                e.printStackTrace();
            }
        } else {
            // Try to get a URL from the database if the in-memory queue is empty
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT url FROM urls LIMIT 1")) {
                if (rs.next()) {
                    url = rs.getString("url");

                    try (PreparedStatement deleteStmt = connection.prepareStatement("DELETE FROM urls WHERE url = ?")) {
                        // Start a transaction
                        connection.setAutoCommit(false);

                        deleteStmt.setString(1, url);
                        deleteStmt.executeUpdate();

                        // Commit the transaction
                        connection.commit();
                    } catch (SQLException e) {
                        // Rollback the transaction in case of error
                        if (connection != null) {
                            try {
                                connection.rollback();
                            } catch (SQLException ex) {
                                ex.printStackTrace();
                            }
                        }
                        e.printStackTrace();
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return url;
    }

    /**
     * Checks if the queue is empty.
     *
     * This method only checks the in-memory queue, not the database.
     * For a complete check, it would need to also query the database
     * if the in-memory queue is empty.
     *
     * @return true if the in-memory queue is empty, false otherwise
     * @throws RemoteException If a remote communication error occurs
     */
    @Override
    public synchronized boolean isEmpty() throws RemoteException {
        return queue.isEmpty();
    }

    //----------------------------------------MAIN----------------------------------------

    /**
     * Main method to initialize and run the URLQueue service.
     *
     * This method:
     * 1. Creates a new URLQueue instance
     * 2. Sets up the RMI registry on port 8184
     * 3. Registers the URLQueue service in the registry with the name "URLQueueService"
     *
     * @param args Command-line arguments (not used)
     */
    public static void main(String args[]) {
        try {
            URLQueue urlQueue = new URLQueue();
            Registry registry = LocateRegistry.createRegistry(URL_PORT);
            registry.rebind("URLQueueService", urlQueue);
            System.out.println("URLQueueService ready...");
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
}