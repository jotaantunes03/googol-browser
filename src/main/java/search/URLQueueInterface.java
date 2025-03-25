package search;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Remote interface for managing a URL queue in a distributed search system.
 * Provides methods for adding, retrieving, and checking the status of URLs in the queue.
 */
public interface URLQueueInterface extends Remote {

    /**
     * Adds a new URL to the queue.
     *
     * @param url The URL to be inserted into the queue.
     * @throws RemoteException If a remote communication error occurs.
     */
    void addUrl(String url) throws RemoteException;

    /**
     * Retrieves the next URL from the queue for processing.
     *
     * @return The next URL in the queue, or {@code null} if the queue is empty.
     * @throws RemoteException If a remote communication error occurs.
     */
    String takeUrl() throws RemoteException;

    /**
     * Checks whether the URL queue is empty.
     *
     * @return {@code true} if the queue is empty, {@code false} if it contains one or more URLs.
     * @throws RemoteException If a remote communication error occurs.
     */
    boolean isEmpty() throws RemoteException;
}