package search;

import java.rmi.*;
import java.sql.SQLException;
import java.util.*;

public interface IndexStorageBarrelInterface extends Remote {

    /**
     * Adiciona uma palavra ao índice invertido, associando-a a um URL.
     * Os URLs são armazenados como uma string separada por `;` na base de dados.
     *
     * @param word A palavra a ser indexada.
     * @param url O URL onde a palavra foi encontrada.
     * @throws RemoteException Caso ocorra um erro na operação remota.
     */
    void addToIndex(String word, String url) throws RemoteException, SQLException;

    /**
     * Realiza uma pesquisa no índice invertido e retorna os URLs associados a uma palavra.
     *
     * @param word A palavra a ser pesquisada.
     * @return Lista de URLs que contêm a palavra pesquisada.
     * @throws RemoteException Caso ocorra um erro na operação remota.
     */
    List<String> searchWord(String word) throws RemoteException;

    /**
     * Adiciona uma ligação entre duas páginas Web no grafo de ligações.
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

    public List<String> getInboundLinks(String pageUrl) throws RemoteException;

    String getBarrelId() throws RemoteException;
    /**
     * Get statistics about the current state of the index
     *
     * @return A map of statistical information
     * @throws RemoteException if there's a communication error
     */
    Map<String, Object> getStats() throws RemoteException;

}
