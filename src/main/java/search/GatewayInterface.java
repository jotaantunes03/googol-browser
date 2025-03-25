package search;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * The {@code GatewayInterface} defines a remote interface for communication between 
 * clients and the distributed search system. It provides methods for searching indexed words, 
 * adding new URLs for indexing, retrieving inbound links, and checking the system state.
 * <p>
 * This interface extends {@code Remote} to support Remote Method Invocation (RMI).
 */
public interface GatewayInterface extends Remote {

    /**
     * Searches for a word in the index and returns the URLs where it appears.
     *
     * @param word The word to be searched.
     * @return A list of URLs containing the word.
     * @throws RemoteException If a remote communication error occurs.
     */
    List<String> search(String word) throws RemoteException;

    /**
     * Adds a new URL to the queue to be indexed.
     *
     * @param url The URL to be indexed.
     * @throws RemoteException If a remote communication error occurs.
     */
    void addUrl(String url) throws RemoteException;

    /**
     * Retrieves a list of inbound links pointing to a specific page.
     * This method queries the system to find all URLs that link to the given page URL.
     *
     * @param pageUrl The URL for which inbound links should be retrieved.
     * @return A list of source URLs that link to the specified page.
     * @throws RemoteException If a remote communication error occurs.
     */
    List<String> checkInboundLinks(String pageUrl) throws RemoteException;

    /**
     * Retrieves the current state of the distributed search system.
     * This may include information about indexing progress, system load, or other status indicators.
     *
     * @return A string representing the system's current state.
     * @throws RemoteException If a remote communication error occurs.
     */
    String getSystemState() throws RemoteException;
}
