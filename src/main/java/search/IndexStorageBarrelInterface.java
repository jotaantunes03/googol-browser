package search;

import java.rmi.*;
import java.sql.SQLException;
import java.util.*;

/**
 * The {@code IndexStorageBarrelInterface} defines a remote interface 
 * for managing an inverted index and link graph for a distributed search engine.
 * It extends {@code Remote} to support Remote Method Invocation (RMI).
 * 
 * Methods in this interface allow indexing words, searching for words, managing web links, 
 * checking barrel connectivity, and retrieving various indexing statistics.
 */
public interface IndexStorageBarrelInterface extends Remote {

    /**
     * Adds a word to the inverted index, associating it with a URL.
     * The URLs are stored as a semicolon-separated string in the database.
     *
     * @param word A palavra a ser indexada.
     * @param url O URL onde a palavra foi encontrada.
     * @throws RemoteException Caso ocorra um erro na operação remota.
     */
    void addToIndex(String word, String url) throws RemoteException, SQLException;

    /**
     * Performs a search in the inverted index and retrieves the URLs associated with a word.     *
     * @param word A palavra a ser pesquisada.
     * @return Lista de URLs que contêm a palavra pesquisada.
     * @throws RemoteException Caso ocorra um erro na operação remota.
     */
    List<String> searchWord(String word) throws RemoteException;

    /**
     * Adds a link between two web pages in the link graph.
     *
     * @param sourceUrl O URL da página de origem.
     * @param linkedUrl O URL da página de destino.
     * @throws RemoteException Caso ocorra um erro na operação remota.
     */
    void addLink(String sourceUrl, String linkedUrl) throws RemoteException;

    /**
     * Simple ping method to verify barrel connectivity.
     *
     * @return true if the barrel is active and responsive
     * @throws RemoteException if there's a communication error
     */
    boolean ping() throws RemoteException;

    /**
     * Check if a specific URL is already indexed
     *
     * @param url The URL to check
     * @return true if the URL is already in the index
     * @throws RemoteException if there's a communication error
     */
    boolean isUrlIndexed(String url) throws RemoteException;

    /**
     * Retrieves a list of inbound links pointing to a specified page.
     * This method queries the link graph to find all source URLs that link to the given page URL.
     *
     * @param pageUrl The URL for which inbound links should be retrieved.
     * @return A list of source URLs that link to the specified page.
     * @throws RemoteException If a remote communication error occurs.
     */
    List<String> getInboundLinks(String pageUrl) throws RemoteException;

    /**
     * Retrieves the unique identifier of the barrel.
     *
     * @return The barrel ID as a {@code String}.
     * @throws RemoteException If a remote communication error occurs.
     */
    String getBarrelId() throws RemoteException;
    
    /**
     * Get statistics about the current state of the index
     *
     * @return A map of statistical information
     * @throws RemoteException if there's a communication error
     */
    Map<String, Object> getStats() throws RemoteException;

}
